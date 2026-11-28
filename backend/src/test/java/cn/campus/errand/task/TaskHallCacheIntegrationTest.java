package cn.campus.errand.task;

import cn.campus.errand.realtime.TaskNotice;
import cn.campus.errand.student.StudentAuthService;
import cn.campus.errand.student.StudentContracts;
import cn.campus.errand.student.WeChatGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.task.cache.enabled=true", "spring.data.redis.host=127.0.0.1"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(named = "TEST_REDIS_PORT", matches = "[0-9]+")
class TaskHallCacheIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-28T08:00:00Z");
    private static final String NAMESPACE = "campus:test:hall:" + UUID.randomUUID();
    @Autowired TaskService tasks;
    @Autowired TaskClaimService claims;
    @Autowired AdminTaskService adminTasks;
    @Autowired RedisHallCache cache;
    @Autowired StringRedisTemplate redis;
    @Autowired StudentAuthService students;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean WeChatGateway wechat;
    @MockitoBean(name = "taskClock") Clock clock;
    @MockitoSpyBean TaskHallQuery query;
    private StudentContracts.LoginResult publisher;
    private StudentContracts.LoginResult runner;
    private long taskId;

    @DynamicPropertySource
    static void redisSettings(DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.port", () -> System.getenv("TEST_REDIS_PORT"));
        properties.add("app.task.cache.namespace", () -> NAMESPACE);
    }

    @BeforeEach
    void setup() {
        Set<String> keys = redis.keys(NAMESPACE + ":*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        jdbc.update("DELETE FROM errand_task");
        jdbc.update("DELETE FROM student_user");
        when(clock.instant()).thenReturn(START);
        publisher = login("hall-publisher");
        runner = login("hall-runner");
        taskId = publish(TaskType.EXPRESS, START.plusSeconds(600));
    }

    @Test
    void repeatedQueriesUseRedisAndRetainAuthenticationAndPublicFields() throws Exception {
        var first = tasks.list(1, 20, null);
        assertThat(tasks.list(1, 20, null)).isEqualTo(first);
        verify(query, times(1)).load(1, 20, null);
        var cached = cache.lookup(1, 20, null);
        assertThat(cached.page()).isEqualTo(first);
        String stored = redis.opsForValue().get(pageKey(cached.generation(), "ALL", 1, 20));
        assertThat(stored).doesNotContain("publisherPhone", "publisherWechat", "privateInstructions",
                "runnerPhone", "MOCK-PRIVATE", "13800000000");
        mvc.perform(get("/api/student/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/student/tasks").header("Authorization", "Bearer " + runner.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void typePageAndPageSizeHaveIndependentKeys() {
        publish(TaskType.FOOD, START.plusSeconds(600));
        assertThat(tasks.list(1, 1, null).items()).hasSize(1);
        assertThat(tasks.list(2, 1, null).items()).extracting(TaskContracts.Summary::id).containsExactly(taskId);
        assertThat(tasks.list(1, 20, TaskType.EXPRESS).items()).extracting(TaskContracts.Summary::type)
                .containsOnly(TaskType.EXPRESS);
        assertThat(tasks.list(1, 20, TaskType.FOOD).items()).extracting(TaskContracts.Summary::type)
                .containsOnly(TaskType.FOOD);
        assertThat(redis.keys(NAMESPACE + ":*")).hasSize(5);
    }

    @ParameterizedTest
    @EnumSource(value = TaskNotice.Type.class,
            names = {"TASK_PUBLISHED", "TASK_CLAIMED", "TASK_CANCELLED", "TASK_EXPIRED", "TASK_CLOSED"})
    void committedChangesInvalidateHallPages(TaskNotice.Type operation) {
        tasks.list(1, 20, null);
        String oldVersion = redis.opsForValue().get(NAMESPACE + ":generation");
        long expectedTotal = 0;
        switch (operation) {
            case TASK_PUBLISHED -> {
                publish(TaskType.FOOD, START.plusSeconds(600));
                expectedTotal = 2;
            }
            case TASK_CLAIMED -> {
                claims.submit(runner.student().id(), taskId, new TaskContracts.ClaimRequest(
                        UUID.randomUUID().toString(), "13900000000", null));
                when(clock.instant()).thenReturn(START.plusSeconds(1));
                claims.confirm(taskId);
            }
            case TASK_CANCELLED -> tasks.cancel(publisher.student().id(), taskId);
            case TASK_EXPIRED -> {
                when(clock.instant()).thenReturn(START.plusSeconds(600));
                tasks.expireBatch(100);
            }
            case TASK_CLOSED -> adminTasks.close(1, taskId, new AdminTaskContracts.CloseRequest("测试关单"));
            default -> throw new AssertionError(operation);
        }
        assertThat(redis.opsForValue().get(NAMESPACE + ":generation")).isNotEqualTo(oldVersion);
        assertThat(tasks.list(1, 20, null).total()).isEqualTo(expectedTotal);
    }

    @Test
    void rollbackAndTransactionLocalQueriesDoNotChangeSharedCache() {
        tasks.list(1, 20, null);
        String generation = cache.lookup(1, 20, null).generation();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            publish(TaskType.FOOD, START.plusSeconds(600));
            assertThat(tasks.list(1, 20, null).total()).isEqualTo(2);
            assertThat(cache.lookup(1, 20, null).page().total()).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertThat(cache.lookup(1, 20, null).generation()).isEqualTo(generation);
        assertThat(tasks.list(1, 20, null).total()).isEqualTo(1);
    }

    @Test
    void invalidationPreventsSlowOldQueryFromRefillingCurrentGeneration() {
        var original = cache.lookup(1, 20, null);
        var snapshot = query.load(1, 20, null);
        publish(TaskType.FOOD, START.plusSeconds(600));
        cache.store(original.generation(), null, snapshot);
        var current = cache.lookup(1, 20, null);
        assertThat(current.generation()).isNotEqualTo(original.generation());
        assertThat(current.page()).isNull();
        assertThat(tasks.list(1, 20, null).total()).isEqualTo(2);
    }

    @Test
    void offPageDeadlineAlsoExpiresTotalAndPagination() {
        jdbc.update("UPDATE errand_task SET deadline_at=? WHERE id=?",
                java.time.LocalDateTime.ofInstant(START.plusSeconds(1), java.time.ZoneOffset.UTC), taskId);
        long latest = publish(TaskType.EXPRESS, START.plusSeconds(600));
        var first = tasks.list(1, 1, TaskType.EXPRESS);
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.items()).extracting(TaskContracts.Summary::id).containsExactly(latest);
        String generation = cache.lookup(1, 1, TaskType.EXPRESS).generation();
        Long ttl = redis.getExpire(pageKey(generation, "EXPRESS", 1, 1), java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttl).isPositive().isLessThanOrEqualTo(1000);
        when(clock.instant()).thenReturn(START.plusSeconds(1));
        assertThat(tasks.list(1, 1, TaskType.EXPRESS).total()).isEqualTo(1);
    }

    @Test
    void absoluteExpiryWorksEvenWhileRedisKeyStillExists() {
        tasks.list(1, 20, null);
        String generation = cache.lookup(1, 20, null).generation();
        when(clock.instant()).thenReturn(START.plusSeconds(10));
        assertThat(redis.hasKey(pageKey(generation, "ALL", 1, 20))).isTrue();
        assertThat(cache.lookup(1, 20, null).page()).isNull();
        assertThat(tasks.list(1, 20, null).total()).isEqualTo(1);
        verify(query, times(2)).load(1, 20, null);
    }

    @Test
    void slowResultsCannotExtendTheirOriginalLifetime() {
        String generation = cache.lookup(1, 20, null).generation();
        var snapshot = query.load(1, 20, null);
        when(clock.instant()).thenReturn(START.plusSeconds(11));
        cache.store(generation, null, snapshot);
        assertThat(cache.lookup(1, 20, null).page()).isNull();
        assertThat(redis.hasKey(pageKey(generation, "ALL", 1, 20))).isFalse();
    }

    @Test
    void corruptJsonFallsBackToDatabaseAndIsReplaced() throws Exception {
        tasks.list(1, 20, null);
        String generation = cache.lookup(1, 20, null).generation();
        String key = pageKey(generation, "ALL", 1, 20);
        redis.opsForValue().set(key, "{broken JSON");
        assertThat(tasks.list(1, 20, null).total()).isEqualTo(1);
        assertThat(json.readTree(redis.opsForValue().get(key)).path("page").path("total").asInt()).isEqualTo(1);
    }

    @Test
    void losingGenerationNeverReusesOldPageKeysOrAcceptsOldBackfill() {
        tasks.list(1, 20, null);
        String original = cache.lookup(1, 20, null).generation();
        var snapshot = query.load(1, 20, null);
        redis.delete(NAMESPACE + ":generation");
        String replacement = cache.lookup(1, 20, null).generation();
        assertThat(replacement).isNotEqualTo(original);
        cache.store(original, null, snapshot);
        assertThat(cache.lookup(1, 20, null).page()).isNull();
    }

    private long publish(TaskType type, Instant deadline) {
        return tasks.publish(publisher.student().id(), new TaskContracts.CreateRequest("缓存测试", type, "快递站", "宿舍",
                deadline, new BigDecimal("3.50"), null, "13800000000", "MOCK-WECHAT", "MOCK-PRIVATE")).id();
    }

    private StudentContracts.LoginResult login(String code) {
        when(wechat.exchange(code)).thenReturn(new WeChatGateway.Identity("hall-cache-test", code));
        return students.login(new StudentContracts.LoginRequest(code));
    }

    private static String pageKey(String generation, String type, int page, int size) {
        return NAMESPACE + ":" + generation + ":" + type + ":" + page + ":" + size;
    }
}
