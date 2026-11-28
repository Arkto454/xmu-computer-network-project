package cn.campus.errand.task;

import cn.campus.errand.common.ApiException;
import cn.campus.errand.realtime.TaskNotice.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static cn.campus.errand.task.TaskDeliveryContracts.*;
import static cn.campus.errand.task.TaskDeliveryMapper.Code;
import static cn.campus.errand.task.TaskStatus.*;

@Service
public class TaskDeliveryService {
    private static final String PAYLOAD_PREFIX = "CED1.";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final long VALID_MINUTES = 5;

    private final TaskMapper tasks;
    private final TaskDeliveryMapper delivery;
    private final TaskCancellationMapper cancellations;
    private final DeliveryQrRenderer renderer;
    private final Clock clock;
    private final int windowMillis;
    private final TaskNotifications notifications;
    private final SecureRandom random = new SecureRandom();

    public TaskDeliveryService(TaskMapper tasks, TaskDeliveryMapper delivery, TaskCancellationMapper cancellations,
                               DeliveryQrRenderer renderer, Clock taskClock,
                               @Value("${app.task.delivery.window-ms:1000}") int windowMillis, TaskNotifications notifications) {
        if (windowMillis < 1) throw new IllegalArgumentException("交付确认窗口必须大于零");
        this.tasks = tasks;
        this.delivery = delivery;
        this.cancellations = cancellations;
        this.renderer = renderer;
        this.clock = taskClock;
        this.windowMillis = windowMillis;
        this.notifications = notifications;
    }

    @Transactional
    public CodeView requestDelivery(long studentId, long taskId) {
        TaskRecord task = lockTask(taskId);
        requireRole(task, studentId, true);
        if (task.status != PICKED_UP && task.status != PENDING_CONFIRMATION) throw stateConflict();
        requireUnpaused(task);
        LocalDateTime now = now();
        Code current = delivery.findCode(taskId);
        // 重试申请只查询原结果；新码由接单者显式生成。
        if (task.status == PENDING_CONFIRMATION) return view(taskId, current, now);
        Code issued = issue(taskId, current, now);
        requireOne(tasks.transition(taskId, PICKED_UP, PENDING_CONFIRMATION, now));
        requireOne(delivery.recordDeliveryStart(taskId, now));
        notifications.changed(taskId, Type.DELIVERY_REQUESTED);
        return view(taskId, issued, now);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CodeView currentCode(long studentId, long taskId) {
        TaskRecord task = findTask(taskId);
        requireRole(task, studentId, true);
        requirePending(task);
        requireUnpaused(task);
        return view(taskId, delivery.findCode(taskId), now());
    }

    @Transactional
    public CodeView refresh(long studentId, long taskId, long codeVersion) {
        TaskRecord task = lockTask(taskId);
        requireRole(task, studentId, true);
        requirePending(task);
        requireUnpaused(task);
        Code current = delivery.findCode(taskId);
        requireVersion(current, codeVersion);
        LocalDateTime now = now();
        Code issued = issue(taskId, current, now);
        notifications.changed(taskId, Type.DELIVERY_CODE_CHANGED);
        return view(taskId, issued, now);
    }

    @Transactional
    public CodeView revoke(long studentId, long taskId, long codeVersion) {
        TaskRecord task = lockTask(taskId);
        requireRole(task, studentId, true);
        requirePending(task);
        Code current = delivery.findCode(taskId);
        requireVersion(current, codeVersion);
        // 即使等待客服审核，接单者也有权停用已发出的码。
        if (current != null && current.imagePng != null) {
            requireOne(delivery.clearImage(taskId));
            current.codeVersion++;
            current.imagePng = null;
            notifications.changed(taskId, Type.DELIVERY_CODE_CHANGED);
        }
        return view(taskId, current, now());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Verification verify(long studentId, long taskId, String payload) {
        TaskRecord task = findTask(taskId);
        requireRole(task, studentId, false);
        Code code = delivery.findCode(taskId);
        checkConfirmation(task, code, payload, now());
        return new Verification(taskId, task.title, task.rewardAmount, task.status,
                task.status == COMPLETED ? null : instant(code.expiresAt));
    }

    @Transactional
    public Completion confirm(long studentId, long taskId, String payload) {
        TaskRecord task = lockTask(taskId);
        requireRole(task, studentId, false);
        Code code = delivery.findCode(taskId);
        LocalDateTime now = now();
        checkConfirmation(task, code, payload, now);
        if (task.status == COMPLETED) return completion(taskId, task.settlementAmount, task.settledAt);
        var previous = delivery.findConfirmation(taskId);
        if (previous != null && previous.payloadDigest.equals(code.payloadDigest)) {
            finalizeDueLocked(task, now);
            return confirmationResult(tasks.findDetailById(taskId), delivery.findConfirmation(taskId));
        }
        if (previous != null && previous.outcome == ConfirmationOutcome.PENDING) {
            requireOne(delivery.resolveConfirmation(taskId, ConfirmationOutcome.CODE_EXPIRED));
        }
        var request = new TaskDeliveryMapper.Confirmation();
        request.taskId = taskId;
        request.payloadDigest = code.payloadDigest;
        request.codeVersion = code.codeVersion;
        request.closesAt = now.plus(windowMillis, ChronoUnit.MILLIS);
        request.outcome = ConfirmationOutcome.PENDING;
        requireOne(previous == null ? delivery.insertConfirmation(request) : delivery.replaceConfirmation(request));
        notifications.changed(taskId, Type.CONFIRMATION_UPDATED);
        return confirmationResult(task, request);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Completion confirmation(long studentId, long taskId) {
        TaskRecord task = findTask(taskId);
        if (task.publisherId != studentId && !Objects.equals(task.runnerId, studentId)) throw notFound();
        var request = delivery.findConfirmation(taskId);
        return request == null ? null : confirmationResult(task, request);
    }

    @Transactional
    public void finalizeConfirmation(long taskId) {
        TaskRecord task = lockTask(taskId);
        finalizeDueLocked(task, now());
    }

    /** 调用方须已取得任务行锁，截止后先结清窗口再处理关单。 */
    void finalizeDueLocked(TaskRecord task, LocalDateTime now) {
        var request = delivery.findConfirmation(task.id);
        if (request == null || request.outcome != ConfirmationOutcome.PENDING || now.isBefore(request.closesAt)) return;
        ConfirmationOutcome failure = finalizationFailure(task, request, delivery.findCode(task.id), now);
        if (failure != null) {
            requireOne(delivery.resolveConfirmation(task.id, failure));
            notifications.changed(task.id, Type.CONFIRMATION_UPDATED);
            return;
        }
        long taskId = task.id;
        requireOne(tasks.transition(taskId, PENDING_CONFIRMATION, COMPLETED, now));
        requireOne(delivery.clearImage(taskId));
        requireOne(delivery.settle(taskId, task.runnerId, task.rewardAmount, now));
        if (task.cancellationStatus == TaskCancellationContracts.Status.AWAITING_PEER) {
            requireOne(cancellations.finishWaitingForPeer(task.cancellationId));
        }
        requireOne(delivery.resolveConfirmation(taskId, ConfirmationOutcome.COMPLETED));
        task.status = COMPLETED;
        task.settledAt = now;
        task.settlementAmount = task.rewardAmount;
        notifications.changed(taskId, Type.TASK_COMPLETED);
    }

    private static ConfirmationOutcome finalizationFailure(TaskRecord task,
            TaskDeliveryMapper.Confirmation request, Code code, LocalDateTime now) {
        if (task.status == CLOSED) return ConfirmationOutcome.TASK_CLOSED;
        if (task.cancellationStatus == TaskCancellationContracts.Status.AWAITING_REVIEW) {
            return ConfirmationOutcome.DELIVERY_PAUSED;
        }
        if (task.status != PENDING_CONFIRMATION || !active(code, now)
                || code.codeVersion != request.codeVersion || !code.payloadDigest.equals(request.payloadDigest)) {
            return ConfirmationOutcome.CODE_EXPIRED;
        }
        return null;
    }

    private static Completion confirmationResult(TaskRecord task, TaskDeliveryMapper.Confirmation request) {
        if (request.outcome == ConfirmationOutcome.COMPLETED) {
            return completion(task.id, task.settlementAmount, task.settledAt);
        }
        return new Completion(task.id, task.status, null, null, request.outcome, instant(request.closesAt));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Earnings earnings(long studentId, int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "page须大于等于1，size须在1至100之间");
        }
        var totals = delivery.totals(studentId);
        long offset = ((long) page - 1) * size;
        List<Earning> items = offset >= totals.total ? List.of() : delivery.findEarnings(studentId, offset, size)
                .stream().map(row -> new Earning(row.taskId, row.title, row.amount, instant(row.settledAt))).toList();
        return new Earnings(items, totals.totalAmount, totals.total, page, size);
    }

    private static void checkConfirmation(TaskRecord task, Code code, String payload, LocalDateTime now) {
        if (task.status != COMPLETED) {
            requirePending(task);
            requireUnpaused(task);
        }
        String prefix = prefix(task.id);
        if (!payload.startsWith(prefix) || !TOKEN.matcher(payload.substring(prefix.length())).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERY_CODE_INVALID", "请扫描当前任务的交付码");
        }
        if (code == null || !MessageDigest.isEqual(code.payloadDigest.getBytes(StandardCharsets.US_ASCII),
                digest(payload).getBytes(StandardCharsets.US_ASCII))) throw expired();
        if (task.status == COMPLETED) {
            if (task.settledAt == null) throw stateConflict();
        } else if (!active(code, now)) {
            throw expired();
        }
    }

    private Code issue(long taskId, Code current, LocalDateTime now) {
        byte[] token = new byte[32];
        random.nextBytes(token);
        String payload = prefix(taskId) + Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        var code = new Code();
        code.taskId = taskId;
        code.codeVersion = version(current) + 1;
        code.payloadDigest = digest(payload);
        code.imagePng = renderer.render(payload);
        code.expiresAt = now.plusMinutes(VALID_MINUTES);
        requireOne(current == null ? delivery.insertCode(code) : delivery.replaceCode(code));
        return code;
    }

    private TaskRecord lockTask(long taskId) {
        validateTaskId(taskId);
        if (tasks.lockById(taskId) == null) throw notFound();
        return tasks.findDetailById(taskId);
    }

    private TaskRecord findTask(long taskId) {
        validateTaskId(taskId);
        TaskRecord task = tasks.findDetailById(taskId);
        if (task == null) throw notFound();
        return task;
    }

    private static void validateTaskId(long taskId) {
        if (taskId < 1) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "任务编号必须为正数");
    }

    private static void requireRole(TaskRecord task, long studentId, boolean runnerOnly) {
        boolean runner = Objects.equals(task.runnerId, studentId);
        boolean publisher = task.publisherId == studentId;
        if (!runner && !publisher) throw notFound();
        if (runnerOnly ? !runner : !publisher) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TASK_ACTOR_FORBIDDEN",
                    runnerOnly ? "仅接单者可管理交付码" : "仅发布者可核对并确认交付");
        }
    }

    private static void requirePending(TaskRecord task) {
        if (task.status != PENDING_CONFIRMATION) throw stateConflict();
    }

    private static void requireUnpaused(TaskRecord task) {
        if (task.cancellationStatus == TaskCancellationContracts.Status.AWAITING_REVIEW) {
            throw new ApiException(HttpStatus.CONFLICT, "DELIVERY_PAUSED", "取消审核中，交付已暂停");
        }
    }

    private static void requireVersion(Code code, long version) {
        if (version(code) != version) {
            throw new ApiException(HttpStatus.CONFLICT, "DELIVERY_CODE_CHANGED", "交付码已更新，请重新获取");
        }
    }

    private static void requireOne(int changed) {
        if (changed != 1) throw new IllegalStateException("任务交付更新未生效");
    }

    private static boolean active(Code code, LocalDateTime now) {
        return code != null && code.imagePng != null && code.expiresAt.isAfter(now);
    }

    private static long version(Code code) {
        return code == null ? 0 : code.codeVersion;
    }

    private static CodeView view(long taskId, Code code, LocalDateTime now) {
        if (!active(code, now)) return new CodeView(taskId, version(code), CodeStatus.EXPIRED, null, null);
        return new CodeView(taskId, code.codeVersion, CodeStatus.ACTIVE,
                instant(code.expiresAt), Base64.getEncoder().encodeToString(code.imagePng));
    }

    private static Completion completion(long taskId, BigDecimal amount, LocalDateTime at) {
        Instant time = instant(at);
        return new Completion(taskId, COMPLETED, time, new Settlement(amount, time), ConfirmationOutcome.COMPLETED, null);
    }

    private static String prefix(long taskId) { return PAYLOAD_PREFIX + taskId + "."; }

    private static String digest(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private static Instant instant(LocalDateTime time) { return time.toInstant(ZoneOffset.UTC); }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在或不可查看");
    }

    private static ApiException stateConflict() {
        return new ApiException(HttpStatus.CONFLICT, "TASK_STATE_CONFLICT", "当前任务状态不允许交付");
    }

    private static ApiException expired() {
        return new ApiException(HttpStatus.CONFLICT, "DELIVERY_CODE_EXPIRED", "交付码已过期，请重新扫码");
    }
}
