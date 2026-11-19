package cn.campus.errand.task;

import cn.campus.errand.admin.AdminTokenStore;
import cn.campus.errand.common.ApiException;
import cn.campus.errand.student.StudentAuthService;
import cn.campus.errand.student.StudentContracts;
import cn.campus.errand.student.WeChatGateway;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 固定时间与模拟微信；HTTP、事务、行锁和数据库操作实际执行。自动扫描在 test 配置中关闭。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskManagementIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-14T04:00:00Z");
    private static final String PHONE = "13800000000";
    private static final String PRIVATE = "B-TEST-PRIVATE-CODE";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TaskMapper mapper;
    @Autowired TaskService tasks;
    @Autowired StudentAuthService students;
    @Autowired AdminTokenStore adminTokens;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean WeChatGateway wechat;
    @MockitoBean(name = "taskClock") Clock clock;
    StudentContracts.LoginResult publisher;
    StudentContracts.LoginResult viewer;

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM errand_task");
        jdbc.update("DELETE FROM student_user");
        when(clock.instant()).thenReturn(NOW);
        when(wechat.exchange("b-publisher")).thenReturn(new WeChatGateway.Identity("b-test-app", "publisher"));
        when(wechat.exchange("b-viewer")).thenReturn(new WeChatGateway.Identity("b-test-app", "viewer"));
        publisher = students.login(new StudentContracts.LoginRequest("b-publisher"));
        viewer = students.login(new StudentContracts.LoginRequest("b-viewer"));
    }

    private long seed(long owner) {
        return tasks.publish(owner, new TaskContracts.CreateRequest("B测试任务", TaskType.EXPRESS,
                "快递站公共区域", "宿舍楼门口", NOW.plusSeconds(3600), new BigDecimal("3.50"),
                "公开说明", PHONE, "b_demo_wechat", PRIVATE)).id();
    }
    private long seed() { return seed(publisher.student().id()); }
    private String bearer(StudentContracts.LoginResult login) { return "Bearer " + login.accessToken(); }

    @Test
    void mineUsesActualOwnerAndReturnsOnlyPublicSummaries() throws Exception {
        seed(); seed(); seed(viewer.student().id());
        mvc.perform(get("/api/student/tasks/mine").param("publisherId", viewer.student().id() + "")
                        .header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(1)).andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(content().string(not(containsString(PHONE))))
                .andExpect(content().string(not(containsString(PRIVATE))))
                .andExpect(content().string(not(containsString("b_demo_wechat"))));
        mvc.perform(get("/api/student/tasks/mine").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void minePaginationIsStableAndHandlesEmptyOrVeryLargePages() throws Exception {
        long first = seed();
        when(clock.instant()).thenReturn(NOW.plusSeconds(1));
        long second = seed(); long third = seed();
        mvc.perform(get("/api/student/tasks/mine").param("size", "2").header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items[0].id").value(third))
                .andExpect(jsonPath("$.data.items[1].id").value(second));
        mvc.perform(get("/api/student/tasks/mine").param("page", "2").param("size", "2")
                        .header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(first));
        mvc.perform(get("/api/student/tasks/mine").param("page", "2147483647").param("size", "100")
                        .header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(0));
        mvc.perform(get("/api/student/tasks/mine").param("status", "CANCELLED")
                        .header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void mineFiltersEffectiveExpirationBeforeScannerPersistsIt() throws Exception {
        long expired = seed();
        when(clock.instant()).thenReturn(NOW.plusSeconds(3599));
        long waiting = seed(); // 相同截止时间，下面单独延长此任务。
        jdbc.update("UPDATE errand_task SET deadline_at=? WHERE id=?",
                java.time.LocalDateTime.ofInstant(NOW.plusSeconds(3601), java.time.ZoneOffset.UTC), waiting);
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        mvc.perform(get("/api/student/tasks/mine").param("status", "EXPIRED")
                        .header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(expired))
                .andExpect(jsonPath("$.data.items[0].status").value("EXPIRED"));
        mvc.perform(get("/api/student/tasks/mine").param("status", "WAITING")
                        .header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(waiting));
        assertThat(mapper.findById(expired).status).isEqualTo(TaskStatus.WAITING);
    }

    @ParameterizedTest
    @EnumSource(TaskStatus.class)
    void mineCanFilterEveryDeclaredStoredState(TaskStatus state) throws Exception {
        long id = seed();
        jdbc.update("UPDATE errand_task SET status=? WHERE id=?", state.name(), id);
        mvc.perform(get("/api/student/tasks/mine").param("status", state.name())
                        .header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value(state.name()));
    }

    @Test
    void invalidMineParametersAndCancelIdsAreClientErrors() throws Exception {
        for (String[] query : new String[][]{{"page","0"},{"page","abc"},{"size","0"},{"size","101"},{"status","UNKNOWN"}}) {
            mvc.perform(get("/api/student/tasks/mine").param(query[0], query[1])
                            .header("Authorization", bearer(publisher)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        for (String id : new String[]{"0", "-1", "not-a-number"}) {
            mvc.perform(post("/api/student/tasks/{id}/cancel", id).header("Authorization", bearer(publisher)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void newEndpointsRequireStudentAndRejectAdminCredentials() throws Exception {
        String admin = "Bearer " + adminTokens.issue(publisher.student().id()).value();
        for (String token : new String[]{"", admin}) {
            mvc.perform(get("/api/student/tasks/mine").header("Authorization", token)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/student/tasks/1/cancel").header("Authorization", token)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void disabledStudentCannotUseMineOrCancel() throws Exception {
        long id = seed();
        jdbc.update("UPDATE student_user SET enabled=FALSE WHERE id=?", publisher.student().id());
        mvc.perform(get("/api/student/tasks/mine").header("Authorization", bearer(publisher)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/student/tasks/{id}/cancel", id).header("Authorization", bearer(publisher)))
                .andExpect(status().isUnauthorized());
        assertThat(mapper.findById(id).status).isEqualTo(TaskStatus.WAITING);
    }

    @Test
    void cancellationPersistsRemovesHallEntryAndHidesPrivateHistory() throws Exception {
        long id = seed();
        var original = mapper.findById(id);
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        mvc.perform(post("/api/student/tasks/{id}/cancel", id).header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(content().string(not(containsString(PHONE))));
        var cancelled = mapper.findById(id);
        assertThat(cancelled.status).isEqualTo(TaskStatus.CANCELLED);
        assertThat(cancelled.createdAt).isEqualTo(original.createdAt);
        assertThat(cancelled.updatedAt).isAfter(original.updatedAt);
        mvc.perform(get("/api/student/tasks").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/api/student/tasks/mine").param("status", "CANCELLED").header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/student/tasks/{id}", id).header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.privateInfo").doesNotExist());
    }

    @Test
    void repeatedCancellationKeepsOriginalResultAndTimestampAfterDeadline() throws Exception {
        long id = seed();
        tasks.cancel(publisher.student().id(), id);
        var firstUpdate = mapper.findById(id).updatedAt;
        when(clock.instant()).thenReturn(NOW.plusSeconds(7200));
        mvc.perform(post("/api/student/tasks/{id}/cancel", id).header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        assertThat(tasks.expireBatch(200)).isZero();
        assertThat(mapper.findById(id).updatedAt).isEqualTo(firstUpdate);
    }

    @Test
    void foreignOrMissingCancellationReturnsNotFoundWithoutMutating() throws Exception {
        long id = seed();
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        mvc.perform(post("/api/student/tasks/{id}/cancel", id).param("publisherId", publisher.student().id() + "")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
        mvc.perform(post("/api/student/tasks/9223372036854775807/cancel").header("Authorization", bearer(publisher)))
                .andExpect(status().isNotFound());
        assertThat(mapper.findById(id).status).isEqualTo(TaskStatus.WAITING);
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"WAITING", "CANCELLED", "EXPIRED"}, mode = EnumSource.Mode.EXCLUDE)
    void cannotCancelAnAlreadyAssignedOrClosedTask(TaskStatus state) throws Exception {
        long id = seed();
        jdbc.update("UPDATE errand_task SET status=? WHERE id=?", state.name(), id);
        mvc.perform(post("/api/student/tasks/{id}/cancel", id).header("Authorization", bearer(publisher)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_STATE_CONFLICT"));
        assertThat(mapper.findById(id).status).isEqualTo(state);
    }

    @Test
    void exactlyDueCancellationCommitsExpirationDespiteConflictResponse() throws Exception {
        long id = seed();
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        mvc.perform(post("/api/student/tasks/{id}/cancel", id).header("Authorization", bearer(publisher)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_EXPIRED"));
        assertThat(mapper.findById(id).status).isEqualTo(TaskStatus.EXPIRED);
        var firstUpdate = mapper.findById(id).updatedAt;
        when(clock.instant()).thenReturn(NOW.plusSeconds(7200));
        mvc.perform(post("/api/student/tasks/{id}/cancel", id).header("Authorization", bearer(publisher)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_EXPIRED"));
        assertThat(mapper.findById(id).updatedAt).isEqualTo(firstUpdate);
    }

    @Test
    void cancellationOneMicrosecondBeforeDeadlineCanSucceed() {
        long id = seed();
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600).minusNanos(1000));
        assertThat(tasks.cancel(publisher.student().id(), id).status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void scannerOnlyExpiresDueWaitingRecordsAndDoesNotRewriteOtherStates() {
        long due = seed(); long future = seed();
        jdbc.update("UPDATE errand_task SET deadline_at=? WHERE id=?",
                java.time.LocalDateTime.ofInstant(NOW.plusSeconds(7200), java.time.ZoneOffset.UTC), future);
        var preserved = new ArrayList<Long>();
        for (TaskStatus state : TaskStatus.values()) {
            if (state == TaskStatus.WAITING) continue;
            long id = seed(); preserved.add(id);
            jdbc.update("UPDATE errand_task SET status=? WHERE id=?", state.name(), id);
        }
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        assertThat(tasks.expireBatch(200)).isEqualTo(1);
        assertThat(mapper.findById(due).status).isEqualTo(TaskStatus.EXPIRED);
        assertThat(mapper.findById(future).status).isEqualTo(TaskStatus.WAITING);
        for (long id : preserved) assertThat(mapper.findById(id).updatedAt).isEqualTo(java.time.LocalDateTime.ofInstant(NOW, java.time.ZoneOffset.UTC));
        assertThat(tasks.expireBatch(200)).isZero();
    }

    @Test
    void expirationCanDrainBacklogInBoundedBatches() {
        for (int i = 0; i < 5; i++) seed();
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        assertThat(tasks.expireBatch(2)).isEqualTo(2);
        assertThat(tasks.expireBatch(2)).isEqualTo(2);
        assertThat(tasks.expireBatch(2)).isEqualTo(1);
        assertThat(tasks.expireBatch(2)).isZero();
    }

    @Test
    void schedulerRecoversOverdueBacklogBeyondOneBatch() {
        for (int i = 0; i < 201; i++) seed();
        when(clock.instant()).thenReturn(NOW.plusSeconds(7200));
        new TaskExpirationScheduler(tasks).expireTasks();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM errand_task WHERE status='EXPIRED'", Integer.class)).isEqualTo(201);
        assertThat(tasks.expireBatch(200)).isZero();
    }

    @Test
    void concurrentCancellationReturnsOneStableCancelledResult() throws Exception {
        long id = seed();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        try {
            List<Future<TaskContracts.StateResult>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(executor.submit(() -> {
                start.await(); return tasks.cancel(publisher.student().id(), id);
            }));
            start.countDown();
            for (var result : results) assertThat(result.get(10, TimeUnit.SECONDS).status()).isEqualTo(TaskStatus.CANCELLED);
            assertThat(mapper.findById(id).status).isEqualTo(TaskStatus.CANCELLED);
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @RepeatedTest(5)
    void cancellationAndScannerAtDeadlineKeepOneExpiredState() throws Exception {
        long id = seed();
        when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var cancel = executor.submit(() -> {
                start.await();
                try { tasks.cancel(publisher.student().id(), id); return "unexpected-success"; }
                catch (ApiException e) { assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT); return e.code(); }
            });
            var expire = executor.submit(() -> { start.await(); return tasks.expireBatch(200); });
            start.countDown();
            assertThat(cancel.get(10, TimeUnit.SECONDS)).isEqualTo("TASK_EXPIRED");
            assertThat(expire.get(10, TimeUnit.SECONDS)).isBetween(0, 1);
            assertThat(mapper.findById(id).status).isEqualTo(TaskStatus.EXPIRED);
        } finally { start.countDown(); executor.shutdownNow(); }
    }

    @Test
    void cancellationSamplesTimeAfterWaitingForRowLock() throws Exception {
        long id = seed();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<Future<String>> results = new ArrayList<>();
        var entered = new CountDownLatch(1);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                jdbc.queryForObject("SELECT id FROM errand_task WHERE id=? FOR UPDATE", Long.class, id);
                Future<String> result = executor.submit(() -> {
                    entered.countDown();
                    try { tasks.cancel(publisher.student().id(), id); return "unexpected-success"; }
                    catch (ApiException e) { return e.code(); }
                });
                results.add(result);
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> result.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            });
            assertThat(results.get(0).get(10, TimeUnit.SECONDS)).isEqualTo("TASK_EXPIRED");
            assertThat(mapper.findById(id).status).isEqualTo(TaskStatus.EXPIRED);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void scannerCannotOverwriteCancellationCommittedAfterItsDueSelection() throws Exception {
        long id = seed();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        List<Future<Integer>> results = new ArrayList<>();
        var entered = new CountDownLatch(1);
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                tasks.cancel(publisher.student().id(), id); // 仍持有外层事务的行锁，尚未提交。
                when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
                Future<Integer> result = executor.submit(() -> {
                    entered.countDown(); return tasks.expireBatch(200);
                });
                results.add(result);
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> result.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            });
            assertThat(results.get(0).get(10, TimeUnit.SECONDS)).isZero();
            assertThat(mapper.findById(id).status).isEqualTo(TaskStatus.CANCELLED);
        } finally { executor.shutdownNow(); }
    }
}
