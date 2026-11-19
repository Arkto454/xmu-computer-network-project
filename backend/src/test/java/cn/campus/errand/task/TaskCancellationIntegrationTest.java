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
import org.springframework.test.web.servlet.ResultActions;
import static cn.campus.errand.task.TaskCancellationContracts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskCancellationIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-19T03:00:00Z");
    private static final String TEST_PASSWORD = "LocalFixtureOnly18!";

    @Autowired TaskService tasks;
    @Autowired TaskClaimService claims;
    @Autowired TaskCancellationService cancellations;
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
    private long taskId;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM errand_task");
        jdbc.update("DELETE FROM student_user");
        at(0);
        publisher = login("publisher-18");
        runner = login("runner-18");
        outsider = login("outsider-18");
        taskId = tasks.publish(id(publisher), new TaskContracts.CreateRequest("取快递", TaskType.EXPRESS,
                "快递站", "宿舍", START.plusSeconds(60), new BigDecimal("3.50"), null,
                "13800000000", null, "MOCK-HANDOFF")).id();
        claims.submit(id(runner), taskId, new TaskContracts.ClaimRequest(UUID.randomUUID().toString(), "13900000000", null));
        at(1000);
        claims.confirm(taskId);
    }

    @Test
    void onlyAssignedRunnerCanConfirmPickupAndRetriesKeepOriginalTime() throws Exception {
        pickup(publisher).andExpect(status().isForbidden());
        pickup(outsider).andExpect(status().isNotFound());
        pickup(runner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("PICKED_UP"))
                .andExpect(jsonPath("$.data.pickedUpAt").value(START.plusSeconds(1).toString()));
        at(2200);
        pickup(runner).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pickedUpAt").value(START.plusSeconds(1).toString()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_pickup WHERE task_id=?", Integer.class, taskId))
                .isEqualTo(1);
    }

    @Test
    void agreementBeforePickupCancelsAndLaterPickupCannotChangeState() throws Exception {
        mvc.perform(post("/api/student/tasks/{id}/cancellations", taskId).header("Authorization", bearer(runner))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(newRequest())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("AWAITING_PEER"));
        long requestId = cancellations.latest(id(runner), taskId).cancellationId();
        studentAction(runner, requestId, "AGREE").andExpect(status().isForbidden());
        studentAction(outsider, requestId, "AGREE").andExpect(status().isNotFound());
        assertThat(tasks.detail(id(publisher), taskId).status()).isEqualTo(TaskStatus.ACCEPTED);
        studentAction(publisher, requestId, "AGREE").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.taskStatus").value("CANCELLED"));
        pickup(runner).andExpect(status().isConflict());
        studentAction(publisher, requestId, "AGREE").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mvc.perform(get("/api/student/tasks/{id}", taskId).header("Authorization", bearer(runner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.privateInfo").doesNotExist())
                .andExpect(jsonPath("$.data.cancellation.status").value("CANCELLED"));
    }

    @Test
    void pickupCanProceedWhileWaitingForPeerThenAgreementQueuesReview() throws Exception {
        Detail request = cancellations.create(id(publisher), taskId, newRequest());
        detailFor(runner).andExpect(jsonPath("$.data.cancellation.deliveryPaused").value(false));
        pickup(runner).andExpect(status().isOk());
        studentAction(runner, request.cancellationId(), "AGREE").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AWAITING_REVIEW"))
                .andExpect(jsonPath("$.data.taskStatus").value("PICKED_UP"))
                .andExpect(jsonPath("$.data.deliveryPaused").value(true));
        studentAction(publisher, request.cancellationId(), "WITHDRAW").andExpect(status().isConflict());
        detailFor(publisher).andExpect(jsonPath("$.data.cancellation.deliveryPaused").value(true));
        detailFor(outsider).andExpect(jsonPath("$.data.cancellation").doesNotExist())
                .andExpect(jsonPath("$.data.pickedUpAt").doesNotExist());
        mvc.perform(get("/api/student/tasks/{id}/cancellations/latest", taskId)
                        .header("Authorization", bearer(outsider)))
                .andExpect(status().isNotFound());
    }

    @Test
    void simultaneousApplicationsRequireExplicitConsentAndBothIdsRemainBound() throws Exception {
        var first = newRequest();
        var second = newRequest();
        var results = race(() -> cancellations.create(id(publisher), taskId, first),
                           () -> cancellations.create(id(runner), taskId, second));
        Detail request = results.get(0);
        assertThat(results).extracting(Detail::cancellationId).containsOnly(request.cancellationId());
        assertThat(results).extracting(Detail::status).containsOnly(Status.AWAITING_PEER);
        long peerId = request.requesterId() == id(publisher) ? id(runner) : id(publisher);
        cancellations.studentAction(peerId, taskId, request.cancellationId(), StudentAction.DECLINE);
        assertThat(cancellations.create(id(publisher), taskId, first).status()).isEqualTo(Status.DECLINED);
        assertThat(cancellations.create(id(runner), taskId, second).status()).isEqualTo(Status.DECLINED);
        Detail newAttempt = cancellations.create(id(publisher), taskId, newRequest());
        assertThat(newAttempt.cancellationId()).isNotEqualTo(request.cancellationId());
    }

    @Test
    void withdrawalAndDeclineLeaveTaskAndRejectOldConflictingActions() {
        Detail request = cancellations.create(id(runner), taskId, newRequest());
        assertThat(cancellations.studentAction(id(runner), taskId, request.cancellationId(), StudentAction.WITHDRAW)
                .status()).isEqualTo(Status.WITHDRAWN);
        Detail newAttempt = cancellations.create(id(publisher), taskId, newRequest());
        assertThat(cancellations.studentAction(id(runner), taskId, newAttempt.cancellationId(), StudentAction.DECLINE)
                .taskStatus()).isEqualTo(TaskStatus.ACCEPTED);
        assertThat(cancellations.latest(id(publisher), taskId).status()).isEqualTo(Status.DECLINED);
        assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> cancellations.studentAction(id(publisher), taskId, request.cancellationId(), StudentAction.AGREE),
                ApiException.class).code()).isEqualTo("CANCELLATION_STATE_CONFLICT");
    }

    @Test
    void adminApprovalClosesRetainsPickupAndCanBeReplayed() throws Exception {
        long requestId = queueReview();
        String admin = adminBearer();
        mvc.perform(get("/api/admin/task-cancellations")).andExpect(status().isUnauthorized());
        review(bearer(runner), requestId, "APPROVE", "物品已退回").andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/task-cancellations").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].cancellationId").value(requestId))
                .andExpect(jsonPath("$.data.items[0].studentAction").value("AGREE"));
        review(admin, requestId, "APPROVE", " ").andExpect(status().isBadRequest());
        review(admin, requestId, "APPROVE", "物品已退回").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("CLOSED"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.reviewerId").isNumber());
        review(admin, requestId, "APPROVE", "不应覆盖原理由").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reviewReason").value("物品已退回"));
        review(admin, requestId, "REJECT", "状态已经确定").andExpect(status().isConflict());
        detailFor(runner).andExpect(jsonPath("$.data.privateInfo").doesNotExist())
                .andExpect(jsonPath("$.data.pickedUpAt").value(START.plusSeconds(1).toString()));
        mvc.perform(get("/api/admin/task-cancellations").header("Authorization", admin))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void adminRejectionResumesTheExactPreviousDeliveryStage() throws Exception {
        // 交付模块尚未开放，通过预置待确认状态验证审核能保留原进度。
        cancellations.confirmPickup(id(runner), taskId);
        jdbc.update("UPDATE errand_task SET status='PENDING_CONFIRMATION' WHERE id=?", taskId);
        long requestId = createAndAgree();
        detailFor(publisher).andExpect(jsonPath("$.data.cancellation.deliveryPaused").value(true));
        review(adminBearer(), requestId, "REJECT", "双方继续交付").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.taskStatus").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.deliveryPaused").value(false));
        detailFor(runner).andExpect(jsonPath("$.data.cancellation.deliveryPaused").value(false));
    }

    @Test
    void concurrentPickupAndPeerAgreementLeaveOnlyOneValidPath() throws Exception {
        long cancellationId = cancellations.create(id(publisher), taskId, newRequest()).cancellationId();
        var results = race(() -> {
            try {
                return cancellations.confirmPickup(id(runner), taskId).taskStatus().name();
            } catch (ApiException e) {
                return e.code();
            }
        }, () -> cancellations.studentAction(id(runner), taskId, cancellationId, StudentAction.AGREE).status().name());
        if (results.get(0).equals("PICKED_UP")) {
            assertThat(results.get(1)).isEqualTo("AWAITING_REVIEW");
            assertThat(tasks.detail(id(publisher), taskId).status()).isEqualTo(TaskStatus.PICKED_UP);
        } else {
            assertThat(results).containsExactly("TASK_STATE_CONFLICT", "CANCELLED");
            assertThat(tasks.detail(id(publisher), taskId).status()).isEqualTo(TaskStatus.CANCELLED);
        }
    }

    private long queueReview() {
        cancellations.confirmPickup(id(runner), taskId);
        return createAndAgree();
    }

    private long createAndAgree() {
        long requestId = cancellations.create(id(publisher), taskId, newRequest()).cancellationId();
        cancellations.studentAction(id(runner), taskId, requestId, StudentAction.AGREE);
        return requestId;
    }

    private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var a = executor.submit(() -> { start.await(); return first.call(); });
            var b = executor.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private void at(long millis) {
        when(clock.instant()).thenReturn(START.plusMillis(millis));
    }

    private StudentContracts.LoginResult login(String code) {
        when(wechat.exchange(code)).thenReturn(new WeChatGateway.Identity("cancellation-test-app", code));
        return students.login(new StudentContracts.LoginRequest(code));
    }

    private String adminBearer() throws Exception {
        String username = "review_" + UUID.randomUUID().toString().replace("-", "");
        admins.insert(username, encoder.encode(TEST_PASSWORD), "测试客服");
        var result = mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", username, "password", TEST_PASSWORD))))
                .andExpect(status().isOk()).andReturn().getResponse();
        return "Bearer " + json.readTree(result.getContentAsByteArray()).path("data").path("accessToken").asText();
    }

    private ResultActions pickup(StudentContracts.LoginResult student) throws Exception {
        return mvc.perform(post("/api/student/tasks/{id}/pickup", taskId).header("Authorization", bearer(student)));
    }

    private ResultActions detailFor(StudentContracts.LoginResult student) throws Exception {
        return mvc.perform(get("/api/student/tasks/{id}", taskId).header("Authorization", bearer(student)))
                .andExpect(status().isOk());
    }

    private ResultActions studentAction(StudentContracts.LoginResult student, long requestId, String action) throws Exception {
        return mvc.perform(post("/api/student/tasks/{id}/cancellations/{requestId}/actions", taskId, requestId)
                .header("Authorization", bearer(student)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("action", action))));
    }

    private ResultActions review(String adminBearer, long requestId, String decision, String reason) throws Exception {
        return mvc.perform(post("/api/admin/task-cancellations/{requestId}/decision", requestId)
                .header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("decision", decision, "reason", reason))));
    }

    private static CreateRequest newRequest() {
        return new CreateRequest(UUID.randomUUID().toString(), "测试申请");
    }

    private static long id(StudentContracts.LoginResult login) { return login.student().id(); }

    private static String bearer(StudentContracts.LoginResult login) { return "Bearer " + login.accessToken(); }
}
