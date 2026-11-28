package cn.campus.errand.task;

import cn.campus.errand.realtime.TaskNotice;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import static cn.campus.errand.task.TaskContracts.Page;

@Component
public class RedisHallCache {
    private static final Logger log = LoggerFactory.getLogger(RedisHallCache.class);
    private static final Set<TaskNotice.Type> INVALIDATING_EVENTS = Set.of(
            TaskNotice.Type.TASK_PUBLISHED, TaskNotice.Type.TASK_CLAIMED, TaskNotice.Type.TASK_CANCELLED,
            TaskNotice.Type.TASK_EXPIRED, TaskNotice.Type.TASK_CLOSED);
    private static final DefaultRedisScript<String> GENERATION = new DefaultRedisScript<>("""
            local generation = redis.call('GET', KEYS[1])
            if not generation then
                generation = ARGV[1]
                redis.call('SET', KEYS[1], generation)
            end
            return generation
            """, String.class);
    private static final DefaultRedisScript<Long> STORE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end
            redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock clock;
    private final boolean enabled;
    private final Duration ttl;
    private final String namespace;

    public RedisHallCache(StringRedisTemplate redis, ObjectMapper json, Clock taskClock,
                          @Value("${app.task.cache.enabled:true}") boolean enabled,
                          @Value("${app.task.cache.ttl:10s}") Duration ttl,
                          @Value("${app.task.cache.namespace:campus-errand:hall:v1}") String namespace) {
        if (ttl.isNegative() || ttl.isZero() || ttl.toMillis() < 1) {
            throw new IllegalArgumentException("大厅缓存有效期必须至少为1毫秒");
        }
        if (namespace.isBlank()) throw new IllegalArgumentException("大厅缓存命名空间不能为空");
        this.redis = redis;
        this.json = json;
        this.clock = taskClock;
        this.enabled = enabled;
        this.ttl = ttl;
        this.namespace = namespace;
    }

    public Lookup lookup(int page, int size, TaskType type) {
        if (!enabled) return new Lookup(null, null);
        String generation = null;
        try {
            generation = redis.execute(GENERATION, List.of(versionKey()), UUID.randomUUID().toString());
            if (generation == null) return new Lookup(null, null);
            String value = redis.opsForValue().get(pageKey(generation, page, size, type));
            if (value == null) return new Lookup(generation, null);
            Entry entry = json.readValue(value, Entry.class);
            if (entry != null && entry.page() != null && entry.validUntil() != null
                    && entry.validUntil().isAfter(clock.instant())
                    && entry.page().page() == page && entry.page().size() == size
                    && entry.page().items() != null && entry.page().total() >= 0) {
                return new Lookup(generation, entry.page());
            }
        } catch (IOException | RuntimeException failure) {
            log.warn("Hall cache read unavailable; querying database");
        }
        return new Lookup(generation, null);
    }

    public void store(String generation, TaskType type, TaskHallQuery.Snapshot snapshot) {
        if (!enabled || generation == null) return;
        Instant validUntil = snapshot.queriedAt().plus(ttl);
        if (snapshot.nextDeadline() != null && snapshot.nextDeadline().isBefore(validUntil)) {
            validUntil = snapshot.nextDeadline();
        }
        long remainingMillis = Duration.between(clock.instant(), validUntil).toMillis();
        if (remainingMillis < 1) return;
        Page page = snapshot.page();
        try {
            String value = json.writeValueAsString(new Entry(page, validUntil));
            redis.execute(STORE, List.of(versionKey(), pageKey(generation, page.page(), page.size(), type)),
                    generation, value, Long.toString(remainingMillis));
        } catch (IOException | RuntimeException failure) {
            log.warn("Hall cache write unavailable; returning database result");
        }
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener
    public void afterCommit(TaskNotice notice) {
        if (!enabled || !INVALIDATING_EVENTS.contains(notice.message().eventType())) return;
        try {
            redis.opsForValue().set(versionKey(), UUID.randomUUID().toString());
        } catch (RuntimeException failure) {
            log.warn("Hall cache invalidation failed for task {}; entries expire by TTL", notice.message().taskId());
        }
    }

    private String versionKey() { return namespace + ":generation"; }
    private String pageKey(String generation, int page, int size, TaskType type) {
        return namespace + ":" + generation + ":" + (type == null ? "ALL" : type.name()) + ":" + page + ":" + size;
    }

    public record Lookup(String generation, Page page) {}
    public record Entry(Page page, Instant validUntil) {}
}
