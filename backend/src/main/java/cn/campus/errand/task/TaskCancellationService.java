package cn.campus.errand.task;

import cn.campus.errand.common.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static cn.campus.errand.task.TaskCancellationContracts.*;

@Service
public class TaskCancellationService {
    private final TaskMapper tasks;
    private final TaskClaimMapper assignments;
    private final TaskCancellationMapper cancellations;
    private final Clock clock;

    public TaskCancellationService(TaskMapper tasks, TaskClaimMapper assignments,
                                   TaskCancellationMapper cancellations, Clock taskClock) {
        this.tasks = tasks;
        this.assignments = assignments;
        this.cancellations = cancellations;
        this.clock = taskClock;
    }

    @Transactional
    public PickupResult confirmPickup(long studentId, long taskId) {
        TaskRecord task = lockTask(taskId);
        Long runnerId = assignments.findRunnerId(taskId);
        requireParticipant(task.publisherId, runnerId, studentId);
        if (!Objects.equals(runnerId, studentId)) throw forbidden("只有正式接单者可以确认取件");

        LocalDateTime pickedUpAt = cancellations.findPickedUpAt(taskId);
        if (pickedUpAt != null) return new PickupResult(taskId, task.status, instant(pickedUpAt));
        if (task.status != TaskStatus.ACCEPTED) throw taskConflict("当前任务状态不允许确认取件");
        LocalDateTime now = now();
        requireOne(cancellations.recordPickup(taskId, now));
        transition(task, TaskStatus.PICKED_UP, now);
        return new PickupResult(taskId, task.status, instant(now));
    }

    @Transactional
    public Detail create(long studentId, long taskId, CreateRequest input) {
        TaskRecord task = lockTask(taskId);
        requireParticipant(task.publisherId, assignments.findRunnerId(taskId), studentId);
        String requestId = input.requestId().toLowerCase(Locale.ROOT);
        TaskCancellationRecord existing = cancellations.findByRequest(studentId, requestId);
        if (existing != null) {
            if (existing.taskId != taskId) throw requestMismatch();
            return detail(existing, task.status);
        }
        if (!supportsCancellation(task.status)) throw taskConflict("当前任务状态不允许申请取消");

        TaskCancellationRecord request = cancellations.findLatest(taskId);
        if (request == null || !request.status.pending()) {
            request = new TaskCancellationRecord();
            request.taskId = taskId;
            request.requesterId = studentId;
            request.reason = input.reason() == null || input.reason().isBlank() ? null : input.reason().strip();
            request.status = Status.AWAITING_PEER;
            request.createdAt = now();
            requireOne(cancellations.insert(request));
        }
        try {
            // 同时发起的两个编号都关联这份申请，稍后重试不会另开一份。
            requireOne(cancellations.bindRequest(studentId, requestId, request.id));
        } catch (DuplicateKeyException e) {
            throw requestMismatch();
        }
        return detail(request, task.status);
    }

    @Transactional(readOnly = true)
    public Detail latest(long studentId, long taskId) {
        validateId(taskId);
        TaskRecord task = tasks.findById(taskId);
        if (task == null) throw notFound();
        requireParticipant(task.publisherId, assignments.findRunnerId(taskId), studentId);
        TaskCancellationRecord request = cancellations.findLatest(taskId);
        return request == null ? null : detail(request, task.status);
    }

    @Transactional
    public Detail studentAction(long studentId, long taskId, long cancellationId, StudentAction action) {
        validateId(cancellationId);
        TaskRecord task = lockTask(taskId);
        requireParticipant(task.publisherId, assignments.findRunnerId(taskId), studentId);
        TaskCancellationRecord request = getRequest(taskId, cancellationId);
        boolean initiator = studentId == request.requesterId;
        if ((action == StudentAction.WITHDRAW) != initiator) {
            throw forbidden("请由发起人撤回，或由另一方答复");
        }
        if (action == request.studentAction && Objects.equals(request.studentActionBy, studentId)) {
            return detail(request, task.status);
        }
        if (request.status != Status.AWAITING_PEER) throw requestConflict();
        if (!supportsCancellation(task.status)) throw taskConflict("任务已结束，不能处理该申请");

        LocalDateTime now = now();
        Status next = switch (action) {
            case WITHDRAW -> Status.WITHDRAWN;
            case DECLINE -> Status.DECLINED;
            case AGREE -> task.status == TaskStatus.ACCEPTED ? Status.CANCELLED : Status.AWAITING_REVIEW;
        };
        requireOne(cancellations.recordStudentAction(cancellationId, next, action, studentId, now));
        if (next == Status.CANCELLED) transition(task, TaskStatus.CANCELLED, now);
        return detail(getRequest(taskId, cancellationId), task.status);
    }

    @Transactional(readOnly = true)
    public Page adminList(Status status, int page, int size) {
        if (status != Status.AWAITING_REVIEW && status != Status.APPROVED && status != Status.REJECTED) {
            throw invalid("审核列表支持待审核、已通过或已驳回");
        }
        if (page < 1 || size < 1 || size > 100) throw invalid("页码须为正数，每页最多 100 条");
        long total = cancellations.countByStatus(status);
        long offset = ((long) page - 1) * size;
        List<Detail> items = offset >= total ? List.of() : cancellations.findByStatus(status, offset, size)
                .stream().map(row -> detail(row, row.taskStatus)).toList();
        return new Page(items, total, page, size);
    }

    // 先定位 taskId，再锁任务；读已审核状态时需要读取锁后的最新提交。
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Detail review(long adminId, long cancellationId, AdminDecision input) {
        validateId(cancellationId);
        Long taskId = cancellations.findTaskId(cancellationId);
        if (taskId == null) throw notFound();
        TaskRecord task = lockTask(taskId);
        TaskCancellationRecord request = getRequest(taskId, cancellationId);
        Status next = input.decision() == AdminAction.APPROVE ? Status.APPROVED : Status.REJECTED;
        if (request.status == next) return detail(request, task.status);
        if (request.status != Status.AWAITING_REVIEW) throw requestConflict();
        if (task.status != TaskStatus.PICKED_UP && task.status != TaskStatus.PENDING_CONFIRMATION) {
            throw taskConflict("当前任务状态不允许审核取消");
        }

        LocalDateTime now = now();
        requireOne(cancellations.recordReview(cancellationId, next, adminId, input.reason().strip(), now));
        if (next == Status.APPROVED) transition(task, TaskStatus.CLOSED, now);
        return detail(getRequest(taskId, cancellationId), task.status);
    }

    private TaskRecord lockTask(long taskId) {
        validateId(taskId);
        TaskRecord task = tasks.lockById(taskId);
        if (task == null) throw notFound();
        return task;
    }

    private TaskCancellationRecord getRequest(long taskId, long cancellationId) {
        TaskCancellationRecord request = cancellations.findById(cancellationId);
        if (request == null || request.taskId != taskId) throw notFound();
        return request;
    }

    private void transition(TaskRecord task, TaskStatus next, LocalDateTime now) {
        requireOne(tasks.transition(task.id, task.status, next, now));
        task.status = next;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    private static Detail detail(TaskCancellationRecord request, TaskStatus taskStatus) {
        return new Detail(request.id, request.taskId, taskStatus, request.status, request.requesterId,
                request.reason, instant(request.createdAt), request.studentAction, request.studentActionBy,
                instant(request.studentActionAt), request.reviewerId, request.reviewReason, instant(request.reviewedAt),
                request.status == Status.AWAITING_REVIEW);
    }

    private static boolean supportsCancellation(TaskStatus status) {
        return status == TaskStatus.ACCEPTED || status == TaskStatus.PICKED_UP || status == TaskStatus.PENDING_CONFIRMATION;
    }

    private static void requireParticipant(long publisherId, Long runnerId, long studentId) {
        if (publisherId != studentId && !Objects.equals(runnerId, studentId)) throw notFound();
    }

    private static void validateId(long id) {
        if (id < 1) throw invalid("编号必须为正数");
    }

    private static void requireOne(int updated) {
        if (updated != 1) throw new IllegalStateException("任务或取消申请的更新未生效");
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务或取消申请不存在或不可查看");
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    private static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "TASK_ACTOR_FORBIDDEN", message);
    }

    private static ApiException taskConflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "TASK_STATE_CONFLICT", message);
    }

    private static ApiException requestMismatch() {
        return new ApiException(HttpStatus.CONFLICT, "CANCELLATION_REQUEST_MISMATCH", "申请编号已关联其他任务");
    }

    private static ApiException requestConflict() {
        return new ApiException(HttpStatus.CONFLICT, "CANCELLATION_STATE_CONFLICT", "申请状态已变更，请刷新详情");
    }
}
