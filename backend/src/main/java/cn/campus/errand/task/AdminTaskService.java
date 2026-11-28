package cn.campus.errand.task;

import cn.campus.errand.common.ApiException;
import cn.campus.errand.realtime.TaskNotice.Type;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static cn.campus.errand.task.AdminTaskContracts.*;

@Service
public class AdminTaskService {
    private final AdminTaskMapper admin;
    private final TaskMapper tasks;
    private final TaskDeliveryMapper codes;
    private final TaskDeliveryService delivery;
    private final TaskCancellationMapper cancellations;
    private final TaskClaimService claims;
    private final Clock clock;
    private final TaskNotifications notifications;

    public AdminTaskService(AdminTaskMapper admin, TaskMapper tasks, TaskDeliveryMapper codes,
                            TaskDeliveryService delivery, TaskCancellationMapper cancellations,
                            TaskClaimService claims, Clock taskClock, TaskNotifications notifications) {
        this.admin = admin;
        this.tasks = tasks;
        this.codes = codes;
        this.delivery = delivery;
        this.cancellations = cancellations;
        this.claims = claims;
        this.clock = taskClock;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(Long taskId, TaskStatus status, int page, int size) {
        if (taskId != null) validateId(taskId);
        if (page < 1 || size < 1 || size > 100) throw invalid("页码须为正数，每页最多100条");
        LocalDateTime now = now();
        long total = admin.count(taskId, status, now);
        long offset = ((long) page - 1) * size;
        List<TaskContracts.Summary> rows = offset >= total ? List.of() : admin.list(taskId, status, now, offset, size)
                .stream().map(this::summary).toList();
        return new Page(rows, total, page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail detail(long taskId) {
        validateId(taskId);
        TaskRecord task = tasks.findDetailById(taskId);
        if (task == null) throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在");
        task.status = effectiveStatus(task);
        var history = cancellations.findHistory(taskId);
        var events = new ArrayList<Event>();
        event(events, "PUBLISHED", task.publisherId, task.createdAt, null);
        event(events, "ACCEPTED", task.runnerId, task.acceptedAt, null);
        event(events, "PICKED_UP", task.runnerId, task.pickedUpAt, null);
        event(events, "DELIVERY_REQUESTED", task.runnerId, task.deliveryStartedAt, null);
        event(events, "COMPLETED", task.publisherId, task.settledAt, null);
        event(events, "CLOSED", task.closedBy, task.closedAt, task.closeReason);
        for (var request : history) {
            event(events, "CANCELLATION_REQUESTED", request.requesterId, request.createdAt, request.reason);
            event(events, "CANCELLATION_" + request.studentAction, request.studentActionBy, request.studentActionAt, null);
            event(events, "CANCELLATION_" + request.status, request.reviewerId, request.reviewedAt, request.reviewReason);
        }
        events.sort(Comparator.comparing(Event::occurredAt));
        var closure = task.closedAt == null ? null
                : new Closure(task.closedBy, task.closeReason, task.closedFrom, instant(task.closedAt));
        var settlement = task.settledAt == null ? null
                : new TaskDeliveryContracts.Settlement(task.settlementAmount, instant(task.settledAt));
        return new Detail(summary(task), task.publisherId, task.runnerId, task.description,
                new TaskContracts.PrivateInfo(task.publisherPhone, task.publisherWechat, task.privateInstructions,
                        task.runnerPhone, task.runnerWechat), closure, settlement, events,
                history.stream().map(row -> TaskCancellationService.detail(row, task.status)).toList());
    }

    // 截止后补结算或补过期后，冲突响应也须保留已提交的最终结果。
    @Transactional(noRollbackFor = CloseConflict.class)
    public Detail close(long adminId, long taskId, CloseRequest input) {
        validateId(taskId);
        if (tasks.lockById(taskId) == null) throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在");
        TaskRecord task = tasks.findDetailById(taskId);
        LocalDateTime now = now();
        delivery.finalizeDueLocked(task, now);
        if (task.status == TaskStatus.CLOSED && task.closedAt != null) return detail(taskId);
        if (task.status == TaskStatus.WAITING && !task.deadlineAt.isAfter(now)) {
            tasks.expireIfDue(taskId, now);
            claims.settleLocked(taskId, ClaimOutcome.TASK_EXPIRED, now);
            notifications.changed(taskId, Type.TASK_EXPIRED);
            throw new CloseConflict();
        }
        if (task.status != TaskStatus.WAITING && task.status != TaskStatus.ACCEPTED
                && task.status != TaskStatus.PICKED_UP && task.status != TaskStatus.PENDING_CONFIRMATION) {
            throw new CloseConflict();
        }
        if (tasks.transition(taskId, task.status, TaskStatus.CLOSED, now) != 1
                || admin.close(taskId, adminId, input.reason().strip(), task.status, now) != 1) {
            throw new IllegalStateException("管理员关单未生效");
        }
        codes.clearImage(taskId);
        codes.resolveConfirmation(taskId, TaskDeliveryContracts.ConfirmationOutcome.TASK_CLOSED);
        cancellations.closePending(taskId);
        claims.settleLocked(taskId, ClaimOutcome.TASK_CLOSED, now);
        notifications.changed(taskId, Type.TASK_CLOSED);
        return detail(taskId);
    }

    private TaskContracts.Summary summary(TaskRecord task) {
        return new TaskContracts.Summary(task.id, task.title, task.type, task.pickupLocation, task.deliveryLocation,
                instant(task.deadlineAt), task.rewardAmount, task.status, instant(task.createdAt));
    }

    private TaskStatus effectiveStatus(TaskRecord task) {
        return task.status == TaskStatus.WAITING && !task.deadlineAt.isAfter(now()) ? TaskStatus.EXPIRED : task.status;
    }

    private static void event(List<Event> events, String action, Long actor, LocalDateTime at, String reason) {
        if (at != null) events.add(new Event(action, actor, instant(at), reason));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime time) { return time.toInstant(ZoneOffset.UTC); }
    private static void validateId(long id) { if (id < 1) throw invalid("任务编号须为正数"); }
    private static ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }

    private static final class CloseConflict extends ApiException {
        CloseConflict() { super(HttpStatus.CONFLICT, "TASK_STATE_CONFLICT", "任务已经结束，请刷新详情"); }
    }
}
