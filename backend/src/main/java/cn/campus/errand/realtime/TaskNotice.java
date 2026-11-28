package cn.campus.errand.realtime;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record TaskNotice(Message message, Set<Long> recipients) {
    public enum Type {
        TASK_PUBLISHED, TASK_CLAIMED, CLAIM_RESOLVED, TASK_CANCELLED, TASK_EXPIRED,
        PICKED_UP, DELIVERY_REQUESTED, DELIVERY_CODE_CHANGED, CONFIRMATION_UPDATED,
        TASK_COMPLETED, CANCELLATION_UPDATED, TASK_CLOSED
    }

    public record Message(String eventId, Type eventType, long taskId, Instant occurredAt) {}

    public TaskNotice {
        recipients = Set.copyOf(recipients);
    }

    public static TaskNotice create(Type type, long taskId, Set<Long> recipients) {
        return new TaskNotice(new Message(UUID.randomUUID().toString(), type, taskId, Instant.now()), recipients);
    }
}
