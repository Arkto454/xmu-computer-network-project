package cn.campus.errand.task;

import cn.campus.errand.common.ApiException;
import cn.campus.errand.student.StudentAuthService;
import cn.campus.errand.student.StudentContracts;
import cn.campus.errand.student.WeChatGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static cn.campus.errand.task.TaskContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskClaimIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-19T03:00:00Z");
    private static final String SAMPLE_PHONE = "13900000000";

    @Autowired TaskService tasks;
    @Autowired TaskClaimService claims;
    @Autowired TaskClaimMapper mapper;
    @Autowired StudentAuthService students;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean WeChatGateway wechat;
    @MockitoBean(name = "taskClock") Clock clock;

    private StudentContracts.LoginResult publisher;
    private StudentContracts.LoginResult first;
    private StudentContracts.LoginResult second;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM errand_task");
        jdbc.update("DELETE FROM student_user");
        at(0);
        publisher = login("publisher");
        first = login("first");
        second = login("second");
    }

    @Test
    void firstRegistrationWinsAndReplaysKeepTheirOriginalResults() throws Exception {
        long taskId = task(60_000);
        ClaimRequest firstRequest = request(SAMPLE_PHONE);
        ClaimRequest secondRequest = request("13800000000");
        postClaim(first, taskId, firstRequest).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("CLAIM_PENDING"));
        at(900);
        postClaim(second, taskId, secondRequest).andExpect(status().isAccepted());
        postClaim(first, taskId, firstRequest).andExpect(status().isAccepted());
        at(999);
        runScheduler();
        assertThat(assignmentCount(taskId)).isZero();
        at(1000);
        runScheduler();
        postClaim(first, taskId, firstRequest).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("ACCEPTED"));
        postClaim(second, taskId, secondRequest).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_TAKEN"));
        postClaim(first, taskId, request("13800000000")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("ALREADY_ACCEPTED"));
        assertThat(mapper.findRunnerId(taskId)).isEqualTo(id(first));
        assertThat(jdbc.queryForObject("SELECT runner_phone FROM task_assignment WHERE task_id=?", String.class, taskId))
                .isEqualTo(SAMPLE_PHONE);
        assertPendingContactsCleared(taskId);
        jdbc.update("UPDATE errand_task SET status='PICKED_UP' WHERE id=?", taskId);
        postClaim(first, taskId, firstRequest).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("ACCEPTED"));
    }

    @Test
    void cancellationDuringWindowSettlesAllApplicants() throws Exception {
        long taskId = task(60_000);
        ClaimRequest a = request(SAMPLE_PHONE);
        ClaimRequest b = request("13800000000");
        postClaim(first, taskId, a).andExpect(status().isAccepted());
        postClaim(second, taskId, b).andExpect(status().isAccepted());
        at(600);
        mvc.perform(post("/api/student/tasks/{id}/cancel", taskId).header("Authorization", bearer(publisher)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CANCELLED"));
        at(1000);
        runScheduler();
        postClaim(first, taskId, a).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        postClaim(second, taskId, b).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_CANCELLED"));
        assertThat(assignmentCount(taskId)).isZero();
        assertPendingContactsCleared(taskId);
    }

    @Test
    void cancellationAtWindowEndConfirmsTheWinnerBeforeReturningConflict() throws Exception {
        long taskId = task(60_000);
        ClaimRequest a = request(SAMPLE_PHONE);
        claims.submit(id(first), taskId, a);
        at(1000);
        mvc.perform(post("/api/student/tasks/{id}/cancel", taskId).header("Authorization", bearer(publisher)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_STATE_CONFLICT"));
        assertThat(mapper.findRunnerId(taskId)).isEqualTo(id(first));
        assertThat(claims.submit(id(first), taskId, a).outcome()).isEqualTo(ClaimOutcome.ACCEPTED);
        mvc.perform(post("/api/student/tasks/{id}/cancel", taskId).header("Authorization", bearer(publisher)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("任务已接单，无法直接取消"));
    }

    @Test
    void deadlineInsideWindowPreventsAssignmentEvenWhenExpirationScanIsDelayed() throws Exception {
        long taskId = task(800);
        ClaimRequest a = request(SAMPLE_PHONE);
        postClaim(first, taskId, a).andExpect(status().isAccepted());
        at(800);
        postClaim(first, taskId, a).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_EXPIRED"));
        assertThat(tasks.detail(id(publisher), taskId).status()).isEqualTo(TaskStatus.EXPIRED);
        assertThat(assignmentCount(taskId)).isZero();
        assertPendingContactsCleared(taskId);
    }

    @Test
    void expirationScanSettlesRequestsAndClearsPendingContactData() {
        long taskId = task(400);
        ClaimRequest a = request(SAMPLE_PHONE);
        claims.submit(id(first), taskId, a);
        at(400);
        assertThat(tasks.expireBatch(200)).isEqualTo(1);
        assertThat(mapper.findRequest(id(first), a.requestId()).outcome).isEqualTo(ClaimOutcome.TASK_EXPIRED);
        assertPendingContactsCleared(taskId);
    }

    @Test
    void requestIdBindsOriginalTaskAndContactDetails() throws Exception {
        long taskId = task(60_000);
        long otherTaskId = task(60_000);
        ClaimRequest a = request(SAMPLE_PHONE);
        postClaim(first, taskId, a).andExpect(status().isAccepted());
        postClaim(first, taskId, new ClaimRequest(a.requestId(), "13800000000", null)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLAIM_REQUEST_MISMATCH"));
        postClaim(first, otherTaskId, a).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLAIM_REQUEST_MISMATCH"));
        postClaim(first, taskId, request("13800000000")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLAIM_ALREADY_PENDING"));
        at(1000);
        postClaim(first, taskId, a).andExpect(status().isOk());
        postClaim(first, taskId, new ClaimRequest(a.requestId(), "13800000000", a.runnerWechat()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLAIM_REQUEST_MISMATCH"));
    }

    @Test
    void claimRejectsAnonymousInvalidAndSelfClaimRequests() throws Exception {
        long taskId = task(60_000);
        mvc.perform(post("/api/student/tasks/{id}/claim", taskId).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(request(SAMPLE_PHONE))))
                .andExpect(status().isUnauthorized());
        postClaim(first, taskId, new ClaimRequest("bad", SAMPLE_PHONE, null)).andExpect(status().isBadRequest());
        postClaim(first, taskId, new ClaimRequest(UUID.randomUUID().toString(), "x", null)).andExpect(status().isBadRequest());
        postClaim(publisher, taskId, request(SAMPLE_PHONE)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OWN_TASK"));
        assertThat(assignmentCount(taskId)).isZero();
    }

    @Test
    void onlyTaskParticipantsSeePrivateDetailsAndBothRetainHistoryAccess() throws Exception {
        long taskId = task(60_000);
        claims.submit(id(first), taskId, request(SAMPLE_PHONE));
        mvc.perform(get("/api/student/tasks/{id}", taskId).header("Authorization", bearer(first)))
                .andExpect(jsonPath("$.data.privateInfo").doesNotExist());
        at(1000);
        runScheduler();
        for (var participant : List.of(first, publisher)) {
            mvc.perform(get("/api/student/tasks/{id}", taskId).header("Authorization", bearer(participant)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.privateInfo.runnerPhone").value(SAMPLE_PHONE))
                    .andExpect(jsonPath("$.data.privateInfo.publisherPhone").value("13800000000"))
                    .andExpect(jsonPath("$.data.privateInfo.privateInstructions").value("MOCK-HANDOFF"));
        }
        mvc.perform(get("/api/student/tasks/{id}", taskId).header("Authorization", bearer(second)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.privateInfo").doesNotExist());
        mvc.perform(get("/api/student/tasks/accepted").param("status", "ACCEPTED")
                        .header("Authorization", bearer(first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(taskId))
                .andExpect(jsonPath("$.data.items[0].runnerPhone").doesNotExist());
        mvc.perform(get("/api/student/tasks/accepted").header("Authorization", bearer(second)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        jdbc.update("UPDATE errand_task SET status='COMPLETED' WHERE id=?", taskId);
        for (var participant : List.of(first, publisher)) {
            mvc.perform(get("/api/student/tasks/{id}", taskId).header("Authorization", bearer(participant)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.privateInfo").doesNotExist());
        }
        mvc.perform(get("/api/student/tasks/{id}", taskId).header("Authorization", bearer(second)))
                .andExpect(status().isNotFound());
    }

    @Test
    void disabledFirstApplicantDoesNotWin() {
        long taskId = task(60_000);
        claims.submit(id(first), taskId, request(SAMPLE_PHONE));
        claims.submit(id(second), taskId, request("13800000000"));
        jdbc.update("UPDATE student_user SET enabled=FALSE WHERE id=?", id(first));
        at(1000);
        runScheduler();
        assertThat(mapper.findRunnerId(taskId)).isEqualTo(id(second));
    }

    @Test
    void sameIdConcurrentlyAcrossDifferentTasksCanBindOnlyOneTask() throws Exception {
        long taskA = task(60_000);
        long taskB = task(60_000);
        ClaimRequest a = request(SAMPLE_PHONE);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        List<Future<String>> submissions = new ArrayList<>();
        try {
            for (long taskId : List.of(taskA, taskB)) {
                submissions.add(executor.submit(() -> {
                    start.await();
                    try {
                        return claims.submit(id(first), taskId, a).outcome().name();
                    } catch (ApiException e) {
                        return e.code();
                    }
                }));
            }
            start.countDown();
            assertThat(List.of(submissions.get(0).get(10, TimeUnit.SECONDS),
                               submissions.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("PENDING", "CLAIM_REQUEST_MISMATCH");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentApplicationsAndConfirmationsKeepOneAssignment() throws Exception {
        long taskId = task(60_000);
        ClaimRequest a = request(SAMPLE_PHONE);
        ClaimRequest b = request("13800000000");
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(3);
        try {
            var attempts = List.of(
                    executor.submit(() -> { start.await(); return claims.submit(id(first), taskId, a); }),
                    executor.submit(() -> { start.await(); return claims.submit(id(first), taskId, a); }),
                    executor.submit(() -> { start.await(); return claims.submit(id(second), taskId, b); }));
            start.countDown();
            for (var attempt : attempts) assertThat(attempt.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(ClaimOutcome.PENDING);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_claim_request WHERE task_id=?", Integer.class, taskId))
                    .isEqualTo(2);
            at(1000);
            var firstConfirmation = executor.submit(() -> claims.confirm(taskId));
            var secondConfirmation = executor.submit(() -> claims.confirm(taskId));
            firstConfirmation.get(10, TimeUnit.SECONDS);
            secondConfirmation.get(10, TimeUnit.SECONDS);
            assertThat(assignmentCount(taskId)).isEqualTo(1);
            assertThat(List.of(claims.submit(id(first), taskId, a).outcome(), claims.submit(id(second), taskId, b).outcome()))
                    .containsExactlyInAnyOrder(ClaimOutcome.ACCEPTED, ClaimOutcome.TASK_TAKEN);
        } finally {
            executor.shutdownNow();
        }
    }

    private void at(long millis) {
        when(clock.instant()).thenReturn(START.plusMillis(millis));
    }

    private StudentContracts.LoginResult login(String code) {
        when(wechat.exchange(code)).thenReturn(new WeChatGateway.Identity("claim-test-app", code));
        return students.login(new StudentContracts.LoginRequest(code));
    }

    private long task(long deadlineMillis) {
        return tasks.publish(id(publisher), new CreateRequest("取快递", TaskType.EXPRESS, "快递站", "宿舍",
                START.plusMillis(deadlineMillis), new BigDecimal("3.50"), null,
                "13800000000", null, "MOCK-HANDOFF")).id();
    }

    private ClaimRequest request(String phone) {
        return new ClaimRequest(UUID.randomUUID().toString(), phone, "demo_runner");
    }

    private static long id(StudentContracts.LoginResult login) { return login.student().id(); }

    private static String bearer(StudentContracts.LoginResult login) { return "Bearer " + login.accessToken(); }

    private ResultActions postClaim(StudentContracts.LoginResult login, long taskId, ClaimRequest request) throws Exception {
        return mvc.perform(post("/api/student/tasks/{id}/claim", taskId).header("Authorization", bearer(login))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)));
    }

    private void runScheduler() {
        new TaskClaimScheduler(mapper, claims).confirmDue();
    }

    private int assignmentCount(long taskId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM task_assignment WHERE task_id=?", Integer.class, taskId);
    }

    private void assertPendingContactsCleared(long taskId) {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_claim_request
                WHERE task_id=? AND (runner_phone IS NOT NULL OR runner_wechat IS NOT NULL)
                """, Integer.class, taskId)).isZero();
    }
}
