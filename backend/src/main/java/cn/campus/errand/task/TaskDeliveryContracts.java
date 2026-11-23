package cn.campus.errand.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class TaskDeliveryContracts {
    private TaskDeliveryContracts() {}

    public enum CodeStatus { ACTIVE, EXPIRED }

    public record CodeVersion(@NotNull @PositiveOrZero Long codeVersion) {}
    public record ScannedCode(@NotBlank @Size(max = 100) String payload) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CodeView(long taskId, long codeVersion, CodeStatus codeStatus,
                           Instant expiresAt, String imageBase64) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Verification(long taskId, String title, BigDecimal rewardAmount,
                               TaskStatus taskStatus, Instant expiresAt) {}

    public record Settlement(BigDecimal amount, Instant settledAt) {}
    public record Completion(long taskId, TaskStatus taskStatus, Instant completedAt, Settlement settlement) {}
    public record Earning(long taskId, String title, BigDecimal amount, Instant settledAt) {}
    public record Earnings(List<Earning> items, BigDecimal totalAmount, long total, int page, int size) {}
}
