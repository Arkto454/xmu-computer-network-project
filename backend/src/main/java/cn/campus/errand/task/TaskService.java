package cn.campus.errand.task;

import cn.campus.errand.common.ApiException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.campus.errand.task.TaskContracts.*;

@Service
public class TaskService {
    private final TaskMapper mapper;
    private final Clock clock;
    private final TaskClaimService claims;

    public TaskService(TaskMapper mapper, Clock taskClock, TaskClaimService claims) {
        this.mapper = mapper;
        this.clock = taskClock;
        this.claims = claims;
    }

    @Transactional
    public Created publish(long publisherId, CreateRequest request) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant requestedDeadline = request.deadlineAt().truncatedTo(ChronoUnit.MICROS);
        if (!requestedDeadline.isAfter(now)) throw invalid("最晚送达时间必须晚于当前时间");
        LocalDateTime deadline;
        try { deadline = utc(requestedDeadline); }
        catch (DateTimeException e) { throw invalid("送达时间超出支持范围"); }
        if (deadline.getYear() > 9999) throw invalid("送达时间超出支持范围");
        var task = new TaskRecord();
        task.publisherId = publisherId;
        task.title = request.title().trim();
        task.type = request.type();
        task.pickupLocation = request.pickupLocation().trim();
        task.deliveryLocation = request.deliveryLocation().trim();
        task.deadlineAt = deadline;
        task.rewardAmount = request.rewardAmount();
        task.description = optionalText(request.description());
        task.publisherPhone = request.publisherPhone();
        task.publisherWechat = optionalText(request.publisherWechat());
        task.privateInstructions = optionalText(request.privateInstructions());
        task.status = TaskStatus.WAITING;
        task.createdAt = utc(now);
        task.updatedAt = task.createdAt;
        mapper.insert(task);
        return new Created(task.id, task.status);
    }

    @Transactional(readOnly = true)
    public Page list(int page, int size, TaskType type) {
        validatePage(page, size);
        LocalDateTime now = utc(clock.instant());
        long total = mapper.countAvailable(type, now);
        long offset = ((long) page - 1) * size;
        List<Summary> items = offset >= total ? List.of() : mapper.findAvailable(type, now, offset, size)
                .stream().map(this::summary).toList();
        return new Page(items, total, page, size);
    }

    @Transactional(readOnly = true)
    public Page listMine(long publisherId, int page, int size, TaskStatus status) {
        validatePage(page, size);
        LocalDateTime now = utc(clock.instant().truncatedTo(ChronoUnit.MICROS));
        long total = mapper.countOwned(publisherId, status, now);
        long offset = ((long) page - 1) * size;
        List<Summary> items = offset >= total ? List.of() : mapper.findOwned(publisherId, status, now, offset, size)
                .stream().map(this::summary).toList();
        return new Page(items, total, page, size);
    }

    @Transactional(noRollbackFor = {TaskExpiredException.class, AlreadyAcceptedException.class})
    public StateResult cancel(long publisherId, long taskId) {
        if (taskId < 1) throw invalid("任务编号必须为正数");
        TaskRecord task = mapper.lockOwned(taskId, publisherId);
        if (task == null) throw notFound();
        if (task.status == TaskStatus.CANCELLED) return new StateResult(task.id, TaskStatus.CANCELLED);
        if (task.status == TaskStatus.EXPIRED) throw new TaskExpiredException();
        if (task.status == TaskStatus.ACCEPTED) throw new AlreadyAcceptedException();
        if (task.status != TaskStatus.WAITING) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_STATE_CONFLICT", "当前任务状态不允许取消");
        }
        // 在取得行锁后取时，不能使用等待锁之前的旧时间作取消依据。
        LocalDateTime now = utc(clock.instant().truncatedTo(ChronoUnit.MICROS));
        claims.confirmIfDueLocked(task, now);
        if (task.status == TaskStatus.EXPIRED) throw new TaskExpiredException();
        if (task.status == TaskStatus.ACCEPTED) throw new AlreadyAcceptedException();
        requireSingleChange(mapper.cancelAvailable(taskId, publisherId, now));
        claims.settleLocked(taskId, ClaimOutcome.TASK_CANCELLED, now);
        return new StateResult(taskId, TaskStatus.CANCELLED);
    }

    @Transactional(readOnly = true)
    public Page listAccepted(long studentId, int page, int size, TaskStatus status) {
        validatePage(page, size);
        long total = mapper.countAccepted(studentId, status);
        long offset = ((long) page - 1) * size;
        List<Summary> items = offset >= total ? List.of() : mapper.findAccepted(studentId, status, offset, size)
                .stream().map(this::summary).toList();
        return new Page(items, total, page, size);
    }

    @Transactional
    public int expireBatch(int size) {
        if (size < 1 || size > 200) throw new IllegalArgumentException("过期批次须在1至200之间");
        LocalDateTime now = utc(clock.instant().truncatedTo(ChronoUnit.MICROS));
        int changed = 0;
        for (long taskId : mapper.findDueIds(now, size)) {
            if (mapper.expireIfDue(taskId, now) == 1) {
                claims.settleLocked(taskId, ClaimOutcome.TASK_EXPIRED, now);
                changed++;
            }
        }
        return changed;
    }

    public Detail detail(long viewerId, long taskId) {
        if (taskId < 1) throw invalid("任务编号必须为正数");
        TaskRecord task = mapper.findDetailById(taskId);
        if (task == null) throw notFound();
        TaskStatus status = effectiveStatus(task, clock.instant());
        boolean ownedByMe = task.publisherId == viewerId;
        boolean acceptedByMe = task.runnerId != null && task.runnerId == viewerId;
        boolean related = ownedByMe || acceptedByMe;
        if (ended(status) && !related) throw notFound();
        PrivateInfo privateInfo = related && !ended(status)
                ? new PrivateInfo(task.publisherPhone, task.publisherWechat, task.privateInstructions,
                                  task.runnerPhone, task.runnerWechat) : null;
        Instant pickedUpAt = related && task.pickedUpAt != null ? instant(task.pickedUpAt) : null;
        var cancellation = related && task.cancellationId != null
                ? new TaskCancellationContracts.Summary(task.cancellationId, task.cancellationStatus,
                        task.cancellationRequesterId, task.cancellationStatus == TaskCancellationContracts.Status.AWAITING_REVIEW)
                : null;
        var settlement = status == TaskStatus.COMPLETED && related && task.settledAt != null
                ? new TaskDeliveryContracts.Settlement(task.settlementAmount, instant(task.settledAt)) : null;
        Instant completedAt = settlement == null ? null : settlement.settledAt();
        return new Detail(task.id, task.publisherId, task.title, task.type, task.pickupLocation,
                task.deliveryLocation, instant(task.deadlineAt), task.rewardAmount, task.description,
                status, instant(task.createdAt), ownedByMe, acceptedByMe, pickedUpAt, cancellation, completedAt,
                settlement, privateInfo);
    }

    private Summary summary(TaskRecord task) {
        return new Summary(task.id, task.title, task.type, task.pickupLocation, task.deliveryLocation,
                instant(task.deadlineAt), task.rewardAmount, task.status, instant(task.createdAt));
    }
    private TaskStatus effectiveStatus(TaskRecord task, Instant now) {
        return task.status == TaskStatus.WAITING && !instant(task.deadlineAt).isAfter(now) ? TaskStatus.EXPIRED : task.status;
    }
    private boolean ended(TaskStatus status) {
        return status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED
                || status == TaskStatus.EXPIRED || status == TaskStatus.CLOSED;
    }
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime time) { return time.toInstant(ZoneOffset.UTC); }
    private static String optionalText(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw invalid("page须大于等于1，size须在1至100之间");
    }
    private void requireSingleChange(int changed) {
        if (changed != 1) throw new IllegalStateException("任务状态更新未生效");
    }
    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在或不可查看"); }

    // 截止窗口后的取消请求可能先确定了接单结果，返回冲突时仍需提交该结果。
    private static final class AlreadyAcceptedException extends ApiException {
        AlreadyAcceptedException() {
            super(HttpStatus.CONFLICT, "TASK_STATE_CONFLICT", "任务已接单，无法直接取消");
        }
    }
}
