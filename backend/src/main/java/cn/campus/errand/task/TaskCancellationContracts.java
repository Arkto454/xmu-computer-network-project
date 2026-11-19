package cn.campus.errand.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class TaskCancellationContracts {
    private TaskCancellationContracts() {}

    public enum Status {
        AWAITING_PEER, AWAITING_REVIEW, WITHDRAWN, DECLINED, CANCELLED, APPROVED, REJECTED, TASK_FINISHED;

        public boolean pending() {
            return this == AWAITING_PEER || this == AWAITING_REVIEW;
        }
    }

    public enum StudentAction { AGREE, DECLINE, WITHDRAW }
    public enum AdminAction { APPROVE, REJECT }

    public record CreateRequest(
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$") String requestId,
            @Size(max = 500) String reason) {}
    public record StudentDecision(@NotNull StudentAction action) {}
    public record AdminDecision(@NotNull AdminAction decision, @NotBlank @Size(max = 500) String reason) {}

    public record PickupResult(long taskId, TaskStatus taskStatus, Instant pickedUpAt) {}
    public record Summary(long cancellationId, Status status, long requesterId, boolean deliveryPaused) {}
    public record Detail(long cancellationId, long taskId, TaskStatus taskStatus, Status status,
                         long requesterId, String reason, Instant createdAt,
                         StudentAction studentAction, Long studentActionBy, Instant studentActionAt,
                         Long reviewerId, String reviewReason, Instant reviewedAt, boolean deliveryPaused) {}
    public record Page(List<Detail> items, long total, int page, int size) {}
}
