package cn.campus.errand.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class TaskContracts {
    private TaskContracts() {}

    public record CreateRequest(
            @NotBlank @Size(max = 100) String title,
            @NotNull TaskType type,
            @NotBlank @Size(max = 200) String pickupLocation,
            @NotBlank @Size(max = 200) String deliveryLocation,
            @NotNull Instant deadlineAt,
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal rewardAmount,
            @Size(max = 1000) String description,
            @NotBlank @Pattern(regexp = "^\\+?[1-9][0-9]{6,14}$") String publisherPhone,
            @Size(max = 64) String publisherWechat,
            @Size(max = 1000) String privateInstructions) {}

    public record Created(long id, TaskStatus status) {}
    public record Summary(long id, String title, TaskType type, String pickupLocation, String deliveryLocation,
                          Instant deadlineAt, BigDecimal rewardAmount, TaskStatus status, Instant createdAt) {}
    public record Page(List<Summary> items, long total, int page, int size) {}
    public record PrivateInfo(String publisherPhone, String publisherWechat, String privateInstructions) {}
    public record Detail(long id, long publisherId, String title, TaskType type,
                         String pickupLocation, String deliveryLocation, Instant deadlineAt,
                         BigDecimal rewardAmount, String description, TaskStatus status,
                         Instant createdAt, boolean ownedByMe,
                         @JsonInclude(JsonInclude.Include.NON_NULL) PrivateInfo privateInfo) {}
}
