package cn.campus.errand.task;

import cn.campus.errand.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static cn.campus.errand.task.TaskContracts.*;
import static cn.campus.errand.realtime.TaskNotice.Type.*;

@Service
public class TaskClaimService {
    private final TaskMapper tasks;
    private final TaskClaimMapper claims;
    private final Clock clock;
    private final long windowMillis;
    private final TaskNotifications notifications;

    public TaskClaimService(TaskMapper tasks, TaskClaimMapper claims, Clock taskClock,
                            @Value("${app.task.claim.window-ms:1000}") int windowMillis, TaskNotifications notifications) {
        if (windowMillis < 1) throw new IllegalArgumentException("接单确认窗口必须大于零");
        this.tasks = tasks;
        this.claims = claims;
        this.clock = taskClock;
        this.windowMillis = windowMillis;
        this.notifications = notifications;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ClaimResult submit(long studentId, long taskId, ClaimRequest input) {
        if (taskId < 1) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "任务编号必须为正数");
        TaskRecord task = tasks.lockById(taskId);
        String requestId = input.requestId().toLowerCase(Locale.ROOT);
        String wechat = input.runnerWechat() == null ? "" : input.runnerWechat().strip();
        String digest = digest(requestId, input.runnerPhone(), wechat);
        TaskClaimRecord previous = claims.findRequest(studentId, requestId);
        if (previous != null) checkBinding(previous, taskId, digest);
        if (task == null) throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在或不可查看");

        LocalDateTime now = currentTime();
        confirmIfDueLocked(task, now);
        if (previous != null) return result(claims.findRequest(studentId, requestId));

        TaskClaimRecord request = new TaskClaimRecord();
        request.taskId = taskId;
        request.studentId = studentId;
        request.requestId = requestId;
        request.payloadDigest = digest;
        request.createdAt = now;
        request.outcome = task.publisherId == studentId ? ClaimOutcome.OWN_TASK : outcomeFor(task, studentId);
        if (request.outcome == ClaimOutcome.PENDING) {
            if (claims.countPending(taskId, studentId) > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "CLAIM_ALREADY_PENDING", "请用原请求编号确认这条任务");
            }
            LocalDateTime end = claims.findWindowEnd(taskId);
            if (end == null) {
                end = now.plus(windowMillis, ChronoUnit.MILLIS);
                if (task.deadlineAt.isBefore(end)) end = task.deadlineAt;
            }
            request.closesAt = end;
            request.runnerPhone = input.runnerPhone();
            request.runnerWechat = wechat.isEmpty() ? null : wechat;
        } else {
            request.resolvedAt = now;
        }
        try {
            claims.insert(request);
        } catch (DuplicateKeyException conflict) {
            // 同账号同编号同时请求不同任务时，唯一键仍能限制为一份绑定。
            TaskClaimRecord existing = claims.findRequest(studentId, requestId);
            if (existing == null) throw conflict;
            checkBinding(existing, taskId, digest);
            return result(existing);
        }
        if (request.outcome != ClaimOutcome.PENDING) notifications.claimResolved(taskId, studentId);
        return result(request);
    }

    @Transactional
    public void confirm(long taskId) {
        TaskRecord task = tasks.lockById(taskId);
        if (task != null) confirmIfDueLocked(task, currentTime());
    }

    /** 由已锁定任务行的事务调用。分配、状态和申请结果一同提交。 */
    void confirmIfDueLocked(TaskRecord task, LocalDateTime now) {
        if (task.status == TaskStatus.WAITING && !task.deadlineAt.isAfter(now)) {
            requireOne(tasks.expireIfDue(task.id, now));
            task.status = TaskStatus.EXPIRED;
            notifications.changed(task.id, TASK_EXPIRED);
        }
        LocalDateTime end = claims.findWindowEnd(task.id);
        if (end == null) return;
        if (task.status != TaskStatus.WAITING) {
            settleLocked(task.id, outcomeFor(task, null), now);
            return;
        }
        if (now.isBefore(end)) return;

        TaskClaimRecord winner = claims.findFirstEligible(task.id);
        if (winner == null) {
            settleLocked(task.id, ClaimOutcome.NOT_ELIGIBLE, now);
            return;
        }
        requireOne(tasks.acceptAvailable(task.id, now));
        requireOne(claims.assign(winner, now));
        requireOne(claims.markAccepted(winner.id, now));
        notifications.claimResolved(task.id, winner.studentId);
        settleLocked(task.id, ClaimOutcome.TASK_TAKEN, now);
        task.status = TaskStatus.ACCEPTED;
        notifications.changed(task.id, TASK_CLAIMED);
    }

    void settleLocked(long taskId, ClaimOutcome outcome, LocalDateTime now) {
        var students = claims.findPendingStudents(taskId);
        claims.settlePending(taskId, outcome, now);
        students.forEach(studentId -> notifications.claimResolved(taskId, studentId));
    }

    LocalDateTime currentTime() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    private ClaimOutcome outcomeFor(TaskRecord task, Long studentId) {
        return switch (task.status) {
            case WAITING -> ClaimOutcome.PENDING;
            case CANCELLED -> ClaimOutcome.TASK_CANCELLED;
            case EXPIRED -> ClaimOutcome.TASK_EXPIRED;
            case CLOSED -> ClaimOutcome.TASK_CLOSED;
            default -> {
                Long runnerId = studentId == null && task.status == TaskStatus.ACCEPTED
                        ? null : claims.findRunnerId(task.id);
                if (studentId != null && studentId.equals(runnerId)) yield ClaimOutcome.ALREADY_ACCEPTED;
                if (runnerId != null || task.status == TaskStatus.ACCEPTED) yield ClaimOutcome.TASK_TAKEN;
                yield ClaimOutcome.TASK_STATE_CONFLICT;
            }
        };
    }

    private static ClaimResult result(TaskClaimRecord request) {
        return new ClaimResult(request.taskId, request.requestId, request.outcome);
    }

    private static void checkBinding(TaskClaimRecord request, long taskId, String digest) {
        if (request.taskId != taskId || !request.payloadDigest.equals(digest)) {
            throw new ApiException(HttpStatus.CONFLICT, "CLAIM_REQUEST_MISMATCH", "请求编号已绑定其他任务或表单");
        }
    }

    private static String digest(String requestId, String phone, String wechat) {
        try {
            var value = (requestId + "\n" + phone + "\n" + wechat).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void requireOne(int changed) {
        if (changed != 1) throw new IllegalStateException("接单状态更新未生效");
    }
}
