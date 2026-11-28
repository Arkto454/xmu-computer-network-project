package cn.campus.errand.task;

import cn.campus.errand.admin.AdminMapper;
import cn.campus.errand.common.ApiException;
import cn.campus.errand.student.StudentAuthService;
import cn.campus.errand.student.StudentContracts;
import cn.campus.errand.student.WeChatGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static cn.campus.errand.task.TaskDeliveryContracts.ConfirmationOutcome.*;
import static cn.campus.errand.task.TaskCancellationContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminTaskIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-28T08:00:00Z");
    @Autowired TaskService tasks;
    @Autowired TaskClaimService claims;
    @Autowired TaskDeliveryService delivery;
    @Autowired TaskDeliveryMapper codes;
    @Autowired TaskCancellationService cancellations;
    @Autowired AdminTaskService adminTasks;
    @Autowired StudentAuthService students;
    @Autowired AdminMapper admins;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean WeChatGateway wechat;
    @MockitoBean(name = "taskClock") Clock clock;
    private StudentContracts.LoginResult publisher;
    private StudentContracts.LoginResult runner;
    private StudentContracts.LoginResult outsider;
    private String adminToken;
    private long adminId;

    @BeforeEach
    void setup() throws Exception {
        jdbc.update("DELETE FROM errand_task");
        jdbc.update("DELETE FROM student_user");
        at(START);
        publisher = login("admin-task-publisher");
        runner = login("admin-task-runner");
        outsider = login("admin-task-outsider");
        String username = "admin_task_" + UUID.randomUUID().toString().replace("-", "");
        admins.insert(username, encoder.encode("FixtureOnly19!"), "测试管理员");
        var response = mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", username, "password", "FixtureOnly19!"))))
                .andExpect(status().isOk()).andReturn().getResponse();
        var result = json.readTree(response.getContentAsByteArray()).path("data");
        adminToken = "Bearer " + result.path("accessToken").asText();
        adminId = result.path("admin").path("id").asLong();
    }

    @Test
    void adminQueriesRequireAdminIdentityAndValidateInputs() throws Exception {
        long taskId = publish();
        mvc.perform(get("/api/admin/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/tasks").header("Authorization", bearer(runner))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/tasks/0").header("Authorization", adminToken)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/tasks/9999999").header("Authorization", adminToken)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/tasks").param("size", "101").header("Authorization", adminToken))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/tasks/{id}/close", taskId).header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}")).andExpect(status().isBadRequest());
        assertThat(tasks.detail(id(publisher), taskId).status()).isEqualTo(TaskStatus.WAITING);
    }

    @Test
    void listSupportsIdStatePaginationAndEffectiveExpiryWithoutPrivateFields() throws Exception {
        long first = publish();
        at(START.plusSeconds(1));
        long second = publish();
        mvc.perform(get("/api/admin/tasks").param("size", "1").header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(second))
                .andExpect(jsonPath("$.data.items[0].publisherPhone").doesNotExist());
        assertThat(adminTasks.list(first, TaskStatus.WAITING, 1, 20).total()).isEqualTo(1);
        at(START.plusSeconds(601));
        assertThat(adminTasks.list(null, TaskStatus.EXPIRED, 1, 20).total()).isEqualTo(2);
        assertThat(adminTasks.list(null, TaskStatus.WAITING, 1, 20).items()).isEmpty();
        assertThat(adminTasks.list(null, null, Integer.MAX_VALUE, 100).items()).isEmpty();
    }

    @Test
    void detailContainsActualTimelineAndRelatedClosureWithoutExposingCode() throws Exception {
        long taskId = pickedUpTask();
        delivery.requestDelivery(id(runner), taskId);
        var before = adminTasks.detail(taskId);
        assertThat(before.publisherId()).isEqualTo(id(publisher));
        assertThat(before.runnerId()).isEqualTo(id(runner));
        assertThat(before.timeline()).extracting(AdminTaskContracts.Event::action)
                .containsExactly("PUBLISHED", "ACCEPTED", "PICKED_UP", "DELIVERY_REQUESTED");
        adminTasks.close(adminId, taskId, new AdminTaskContracts.CloseRequest("核实后终止"));
        mvc.perform(get("/api/student/tasks/{id}", taskId).header("Authorization", bearer(runner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.closure.reason").value("核实后终止"))
                .andExpect(jsonPath("$.data.privateInfo").doesNotExist());
        mvc.perform(get("/api/student/tasks/{id}", taskId).header("Authorization", bearer(outsider)))
                .andExpect(status().isNotFound());
        String detail = mvc.perform(get("/api/admin/tasks/{id}", taskId).header("Authorization", adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("imageBase64", "payloadDigest", "wechatOpenid");
    }

    @Test
    void closingDuringWindowWinsAndRetriesPreserveOriginalClosure() throws Exception {
        long taskId = pickedUpTask();
        String payload = TaskDeliveryIntegrationTest.decode(delivery.requestDelivery(id(runner), taskId));
        var pending = delivery.confirm(id(publisher), taskId, payload);
        assertThat(pending.outcome()).isEqualTo(PENDING);
        assertThat(delivery.confirm(id(publisher), taskId, payload).closesAt()).isEqualTo(pending.closesAt());
        at(pending.closesAt().minusMillis(1));
        var closed = adminTasks.close(adminId, taskId, new AdminTaskContracts.CloseRequest("停止交付"));
        at(pending.closesAt().plusSeconds(1));
        delivery.finalizeConfirmation(taskId);
        assertThat(delivery.confirmation(id(publisher), taskId).outcome()).isEqualTo(TASK_CLOSED);
        assertThat(adminTasks.close(adminId + 1, taskId, new AdminTaskContracts.CloseRequest("不覆盖")).closure())
                .isEqualTo(closed.closure());
        assertThat(settlements(taskId)).isZero();
        assertThat(codes.findCode(taskId).imagePng).isNull();
    }

    @Test
    void closingAtDeadlineFinalizesFirstEvenWhenSchedulerHasNotRun() throws Exception {
        long taskId = pickedUpTask();
        String payload = TaskDeliveryIntegrationTest.decode(delivery.requestDelivery(id(runner), taskId));
        var pending = delivery.confirm(id(publisher), taskId, payload);
        at(pending.closesAt());
        mvc.perform(post("/api/admin/tasks/{id}/close", taskId).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"窗口后关闭\"}"))
                .andExpect(status().isConflict());
        assertThat(tasks.detail(id(publisher), taskId).status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(settlements(taskId)).isEqualTo(1);
        assertThat(adminTasks.detail(taskId).closure()).isNull();
    }

    @Test
    void closingPendingCancellationEndsItAndPreservesStudentAnswers() {
        long taskId = pickedUpTask();
        long requestId = cancellations.create(id(runner), taskId, request()).cancellationId();
        cancellations.studentAction(id(publisher), taskId, requestId, StudentAction.AGREE);
        adminTasks.close(adminId, taskId, new AdminTaskContracts.CloseRequest("直接关闭"));
        var history = adminTasks.detail(taskId).cancellations();
        assertThat(history.get(0).status()).isEqualTo(Status.ADMIN_CLOSED);
        assertThat(history.get(0).studentAction()).isEqualTo(StudentAction.AGREE);
        assertThat(cancellations.adminList(Status.AWAITING_REVIEW, 1, 20).total()).isZero();
        assertThat(cancellations.adminList(Status.ADMIN_CLOSED, 1, 20).total()).isEqualTo(1);
    }

    @Test
    void closingUnassignedTaskSettlesPendingClaimsWithoutCreatingAssignment() {
        long taskId = publish();
        var request = new TaskContracts.ClaimRequest(UUID.randomUUID().toString(), "13900000000", null);
        assertThat(claims.submit(id(runner), taskId, request).outcome()).isEqualTo(ClaimOutcome.PENDING);
        adminTasks.close(adminId, taskId, new AdminTaskContracts.CloseRequest("虚假任务"));
        at(START.plusSeconds(2));
        claims.confirm(taskId);
        assertThat(claims.submit(id(runner), taskId, request).outcome()).isEqualTo(ClaimOutcome.TASK_CLOSED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_assignment WHERE task_id=?", Integer.class, taskId)).isZero();
    }

    @Test
    void closingAwaitingPeerApplicationKeepsApplicationHistory() {
        long taskId = acceptedTask();
        cancellations.create(id(runner), taskId, request());
        adminTasks.close(adminId, taskId, new AdminTaskContracts.CloseRequest("双方终止"));
        assertThat(cancellations.latest(id(publisher), taskId).status()).isEqualTo(Status.ADMIN_CLOSED);
        assertThat(adminTasks.detail(taskId).cancellations()).hasSize(1);
    }

    @Test
    void alreadyExpiredWaitingTaskRemainsExpiredWhenCloseIsRequested() {
        long taskId = publish();
        at(START.plusSeconds(600));
        assertThatThrownBy(() -> adminTasks.close(adminId, taskId, new AdminTaskContracts.CloseRequest("关单")))
                .isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM errand_task WHERE id=?", String.class, taskId))
                .isEqualTo("EXPIRED");
    }

    @Test
    void schedulerRecoversPersistedRequestsAndRepeatedScansSettleOnce() throws Exception {
        long taskId = pickedUpTask();
        String payload = TaskDeliveryIntegrationTest.decode(delivery.requestDelivery(id(runner), taskId));
        var pending = delivery.confirm(id(publisher), taskId, payload);
        var restartedScheduler = new TaskDeliveryScheduler(codes, delivery);
        restartedScheduler.confirmDue();
        assertThat(settlements(taskId)).isZero();
        at(pending.closesAt());
        restartedScheduler.confirmDue();
        restartedScheduler.confirmDue();
        assertThat(settlements(taskId)).isEqualTo(1);
        assertThat(delivery.confirmation(id(runner), taskId).outcome()).isEqualTo(COMPLETED);
    }

    @Test
    void expiryDuringWindowLeavesRecoverableFailure() throws Exception {
        long taskId = pickedUpTask();
        var code = delivery.requestDelivery(id(runner), taskId);
        at(code.expiresAt().minusMillis(500));
        var pending = delivery.confirm(id(publisher), taskId, TaskDeliveryIntegrationTest.decode(code));
        at(pending.closesAt());
        delivery.finalizeConfirmation(taskId);
        assertThat(delivery.confirmation(id(runner), taskId).outcome()).isEqualTo(CODE_EXPIRED);
        assertThat(settlements(taskId)).isZero();
        assertThat(tasks.detail(id(publisher), taskId).status()).isEqualTo(TaskStatus.PENDING_CONFIRMATION);
        var fresh = delivery.refresh(id(runner), taskId, code.codeVersion());
        var next = delivery.confirm(id(publisher), taskId, TaskDeliveryIntegrationTest.decode(fresh));
        at(next.closesAt());
        delivery.finalizeConfirmation(taskId);
        assertThat(settlements(taskId)).isEqualTo(1);
    }

    @Test
    void revokedCodeDuringWindowCannotSettle() throws Exception {
        long taskId = pickedUpTask();
        var code = delivery.requestDelivery(id(runner), taskId);
        var pending = delivery.confirm(id(publisher), taskId, TaskDeliveryIntegrationTest.decode(code));
        delivery.revoke(id(runner), taskId, code.codeVersion());
        at(pending.closesAt());
        delivery.finalizeConfirmation(taskId);
        assertThat(delivery.confirmation(id(publisher), taskId).outcome()).isEqualTo(CODE_EXPIRED);
        assertThat(settlements(taskId)).isZero();
    }

    @Test
    void simultaneousCloseAndPickupAlwaysLeaveClosedTask() throws Exception {
        long taskId = acceptedTask();
        var results = race(() -> {
            try { cancellations.confirmPickup(id(runner), taskId); } catch (ApiException expected) {
                assertThat(expected.code()).isEqualTo("TASK_STATE_CONFLICT");
            }
            return "pickup-handled";
        }, () -> adminTasks.close(adminId, taskId, new AdminTaskContracts.CloseRequest("核实关单")).task().status().name());
        assertThat(results).contains("CLOSED");
        assertThat(tasks.detail(id(runner), taskId).status()).isEqualTo(TaskStatus.CLOSED);
    }

    private long publish() {
        return tasks.publish(id(publisher), new TaskContracts.CreateRequest("取快递", TaskType.EXPRESS, "快递站", "宿舍",
                START.plusSeconds(600), new BigDecimal("3.50"), null, "13800000000", null, "MOCK-PRIVATE")).id();
    }

    private long acceptedTask() {
        long taskId = publish();
        claims.submit(id(runner), taskId, new TaskContracts.ClaimRequest(UUID.randomUUID().toString(), "13900000000", null));
        at(START.plusSeconds(1));
        claims.confirm(taskId);
        return taskId;
    }

    private long pickedUpTask() {
        long taskId = acceptedTask();
        cancellations.confirmPickup(id(runner), taskId);
        return taskId;
    }

    private int settlements(long taskId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM task_settlement WHERE task_id=?", Integer.class, taskId);
    }

    private void at(Instant now) { when(clock.instant()).thenReturn(now); }
    private StudentContracts.LoginResult login(String code) {
        when(wechat.exchange(code)).thenReturn(new WeChatGateway.Identity("admin-task-test", code));
        return students.login(new StudentContracts.LoginRequest(code));
    }
    private static long id(StudentContracts.LoginResult login) { return login.student().id(); }
    private static String bearer(StudentContracts.LoginResult login) { return "Bearer " + login.accessToken(); }
    private static CreateRequest request() { return new CreateRequest(UUID.randomUUID().toString(), "申请取消"); }
    private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { start.await(); return first.call(); });
            var b = executor.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
}
