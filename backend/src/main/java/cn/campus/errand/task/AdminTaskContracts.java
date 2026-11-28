package cn.campus.errand.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AdminTaskContracts {
    private AdminTaskContracts() {}

    public record CloseRequest(@NotBlank @Size(max = 500) String reason) {}
    public record Closure(long adminId, String reason, TaskStatus previousStatus, Instant closedAt) {}
    public record Page(List<TaskContracts.Summary> items, long total, int page, int size) {}
    public record Event(String action, Long actorId, Instant occurredAt, String reason) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Detail(TaskContracts.Summary task, long publisherId, Long runnerId, String description,
                         TaskContracts.PrivateInfo privateInfo, Closure closure,
                         TaskDeliveryContracts.Settlement settlement, List<Event> timeline,
                         List<TaskCancellationContracts.Detail> cancellations) {}
}
