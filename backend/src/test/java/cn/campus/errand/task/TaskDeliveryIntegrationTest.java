package cn.campus.errand.task;

import cn.campus.errand.common.ApiException;
import cn.campus.errand.student.StudentAuthService;
import cn.campus.errand.student.StudentContracts;
import cn.campus.errand.student.WeChatGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static cn.campus.errand.task.TaskCancellationContracts.*;
import static cn.campus.errand.task.TaskDeliveryContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TaskDeliveryIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-23T08:00:00Z");
    private static final BigDecimal REWARD = new BigDecimal("3.50");

    @Autowired TaskService tasks;
    @Autowired TaskClaimService claims;
    @Autowired TaskCancellationService cancellations;
    @Autowired TaskDeliveryService delivery;
    @Autowired StudentAuthService students;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean WeChatGateway wechat;
    @MockitoBean(name = "taskClock") Clock clock;
    @MockitoSpyBean DeliveryQrRenderer renderer;

    private StudentContracts.LoginResult publisher;
    private StudentContracts.LoginResult runner;
    private StudentContracts.LoginResult outsider;
    private long taskId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM errand_task");
        jdbc.update("DELETE FROM student_user");
        publisher = login("publisher");
        runner = login("runner");
        outsider = login("outsider");
        taskId = pickedUpTask();
    }

    @Test
    void qrCanBeDecodedAndVerificationIsReadOnlyBeforeConfirmation() throws Exception {
        var response = mvc.perform(post(path("/delivery")).header("Authorization", bearer(runner)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.codeStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.expiresAt").value(START.plusSeconds(301).toString()))
                .andReturn().getResponse();
        CodeView code = json.treeToValue(json.readTree(response.getContentAsByteArray()).path("data"), CodeView.class);
        assertThat(delivery.currentCode(id(runner), taskId)).isEqualTo(code);
        assertThat(delivery.requestDelivery(id(runner), taskId)).isEqualTo(code);
        String payload = decode(code);

        scan(publisher, "/verify", payload).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("PENDING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.rewardAmount").value(3.5));
        assertThat(settlementCount()).isZero();

        scan(publisher, "/confirm", payload).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.data.completedAt").value(START.plusSeconds(1).toString()));
        mvc.perform(get(path("")).header("Authorization", bearer(runner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settlement.amount").value(3.5))
                .andExpect(jsonPath("$.data.completedAt").value(START.plusSeconds(1).toString()))
                .andExpect(jsonPath("$.data.privateInfo").doesNotExist());
        assertThat(settlementCount()).isEqualTo(1);
    }

    @Test
    void deliveryRequiresPickupAndOperationsAreRestrictedToTheirRoles() throws Exception {
        jdbc.update("UPDATE errand_task SET status='ACCEPTED' WHERE id=?", taskId);
        mvc.perform(post(path("/delivery")).header("Authorization", bearer(runner))).andExpect(status().isConflict());
        jdbc.update("UPDATE errand_task SET status='PICKED_UP' WHERE id=?", taskId);
        mvc.perform(post(path("/delivery"))).andExpect(status().isUnauthorized());
        mvc.perform(post(path("/delivery")).header("Authorization", bearer(publisher))).andExpect(status().isForbidden());
        mvc.perform(get(path("/delivery/code")).header("Authorization", bearer(outsider))).andExpect(status().isNotFound());
        String payload = decode(issue());
        scan(runner, "/confirm", payload).andExpect(status().isForbidden());
        scan(outsider, "/verify", payload).andExpect(status().isNotFound());
        String otherTaskPayload = decode(delivery.requestDelivery(id(runner), pickedUpTask()));
        scan(publisher, "/verify", otherTaskPayload).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DELIVERY_CODE_INVALID"));
        mvc.perform(post(path("/delivery/code/refresh")).header("Authorization", bearer(runner))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        assertThat(settlementCount()).isZero();
    }

    @Test
    void issuanceFailureLeavesTaskPickedUpAndAllowsRetry() {
        doThrow(new IllegalStateException("测试绘码失败")).doCallRealMethod().when(renderer).render(anyString());
        assertThatThrownBy(this::issue).isInstanceOf(IllegalStateException.class);
        assertThat(tasks.detail(id(runner), taskId).status()).isEqualTo(TaskStatus.PICKED_UP);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_delivery_code WHERE task_id=?", Integer.class, taskId))
                .isZero();
        assertThat(issue().codeStatus()).isEqualTo(CodeStatus.ACTIVE);
    }

    @Test
    void expiryAtFiveMinutesRequiresANewCodeEvenAfterSuccessfulVerification() throws Exception {
        CodeView code = issue();
        String original = decode(code);
        at(code.expiresAt().minusMillis(1));
        assertThat(delivery.verify(id(publisher), taskId, original).taskStatus()).isEqualTo(TaskStatus.PENDING_CONFIRMATION);
        at(code.expiresAt());
        assertCode("DELIVERY_CODE_EXPIRED", () -> delivery.confirm(id(publisher), taskId, original));
        assertThat(delivery.currentCode(id(runner), taskId).codeStatus()).isEqualTo(CodeStatus.EXPIRED);
        assertThat(delivery.requestDelivery(id(runner), taskId).codeVersion()).isEqualTo(code.codeVersion());
        CodeView fresh = delivery.refresh(id(runner), taskId, code.codeVersion());
        assertThat(delivery.confirm(id(publisher), taskId, decode(fresh)).taskStatus()).isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    void refreshAndRevokeOnlyAffectTheVersionTheRunnerSaw() throws Exception {
        CodeView first = issue();
        String firstPayload = decode(first);
        delivery.verify(id(publisher), taskId, firstPayload);
        CodeView second = delivery.refresh(id(runner), taskId, first.codeVersion());
        assertCode("DELIVERY_CODE_CHANGED", () -> delivery.revoke(id(runner), taskId, first.codeVersion()));
        assertCode("DELIVERY_CODE_EXPIRED", () -> delivery.confirm(id(publisher), taskId, firstPayload));
        assertThat(delivery.currentCode(id(runner), taskId)).isEqualTo(second);
        CodeView revoked = delivery.revoke(id(runner), taskId, second.codeVersion());
        assertThat(revoked.codeVersion()).isGreaterThan(second.codeVersion());
        assertThat(revoked.imageBase64()).isNull();
        assertThat(revoked.codeStatus()).isEqualTo(CodeStatus.EXPIRED);
        assertCode("DELIVERY_CODE_CHANGED", () -> delivery.refresh(id(runner), taskId, second.codeVersion()));
        String secondPayload = decode(second);
        assertCode("DELIVERY_CODE_EXPIRED", () -> delivery.confirm(id(publisher), taskId, secondPayload));
        assertThat(settlementCount()).isZero();
    }

    @Test
    void simultaneousRefreshAndRevokeCommitOnlyOneChange() throws Exception {
        CodeView original = issue();
        List<String> results = race(
                () -> outcome(() -> delivery.refresh(id(runner), taskId, original.codeVersion()).codeStatus()),
                () -> outcome(() -> delivery.revoke(id(runner), taskId, original.codeVersion()).codeStatus()));
        CodeView current = delivery.currentCode(id(runner), taskId);
        assertThat(current.codeVersion()).isEqualTo(original.codeVersion() + 1);
        assertThat(results).containsExactlyInAnyOrder(current.codeStatus().name(), "DELIVERY_CODE_CHANGED");
        String originalPayload = decode(original);
        assertCode("DELIVERY_CODE_EXPIRED", () -> delivery.confirm(id(publisher), taskId, originalPayload));
    }

    @Test
    void reviewerRejectionDoesNotRestoreRevokedCode() throws Exception {
        CodeView code = issue();
        String payload = decode(code);
        long reviewId = startReview();
        assertCode("DELIVERY_PAUSED", this::issue);
        assertCode("DELIVERY_PAUSED", () -> delivery.currentCode(id(runner), taskId));
        assertCode("DELIVERY_PAUSED", () -> delivery.refresh(id(runner), taskId, code.codeVersion()));
        assertCode("DELIVERY_PAUSED", () -> delivery.verify(id(publisher), taskId, payload));
        assertCode("DELIVERY_PAUSED", () -> delivery.confirm(id(publisher), taskId, payload));
        CodeView revoked = delivery.revoke(id(runner), taskId, code.codeVersion());
        cancellations.review(1, reviewId, new AdminDecision(AdminAction.REJECT, "继续交付"));
        assertThat(delivery.currentCode(id(runner), taskId)).isEqualTo(revoked);
        assertCode("DELIVERY_CODE_EXPIRED", () -> delivery.confirm(id(publisher), taskId, payload));
        assertThat(delivery.refresh(id(runner), taskId, revoked.codeVersion()).codeStatus()).isEqualTo(CodeStatus.ACTIVE);
    }

    @Test
    void rejectionKeepsOriginalExpiryAndApprovalClosesWithoutSettlement() throws Exception {
        CodeView code = issue();
        long firstReview = startReview();
        at(START.plusSeconds(60));
        cancellations.review(1, firstReview, new AdminDecision(AdminAction.REJECT, "继续交付"));
        assertThat(delivery.currentCode(id(runner), taskId)).isEqualTo(code);
        long secondReview = startReview();
        cancellations.review(1, secondReview, new AdminDecision(AdminAction.APPROVE, "物品已归还"));
        String payload = decode(code);
        assertCode("TASK_STATE_CONFLICT", () -> delivery.confirm(id(publisher), taskId, payload));
        assertThat(tasks.detail(id(runner), taskId).status()).isEqualTo(TaskStatus.CLOSED);
        assertThat(settlementCount()).isZero();
    }

    @Test
    void concurrentConfirmationsReturnSameReceiptAndClosePendingPeerRequest() throws Exception {
        long cancellationId = cancellations.create(id(runner), taskId, newRequest()).cancellationId();
        CodeView code = issue();
        String payload = decode(code);
        Callable<Completion> confirm = () -> delivery.confirm(id(publisher), taskId, payload);
        List<Completion> results = race(confirm, confirm);
        assertThat(results.get(0)).isEqualTo(results.get(1));
        at(code.expiresAt().plusSeconds(1));
        assertThat(confirm.call()).isEqualTo(results.get(0));
        assertThat(delivery.verify(id(publisher), taskId, payload).taskStatus()).isEqualTo(TaskStatus.COMPLETED);
        var cancellation = cancellations.latest(id(publisher), taskId);
        assertThat(cancellation.status()).isEqualTo(Status.TASK_FINISHED);
        assertThat(cancellation.cancellationId()).isEqualTo(cancellationId);
        assertThat(settlementCount()).isEqualTo(1);
    }

    @Test
    void concurrentReviewAndConfirmationPermitOneFinalPath() throws Exception {
        long cancelId = cancellations.create(id(runner), taskId, newRequest()).cancellationId();
        String payload = decode(issue());
        List<String> results = race(() -> outcome(() -> delivery.confirm(id(publisher), taskId, payload).taskStatus()),
                () -> outcome(() -> cancellations.studentAction(id(publisher), taskId, cancelId, StudentAction.AGREE).status()));
        assertThat(results).isIn(List.of("COMPLETED", "CANCELLATION_STATE_CONFLICT"),
                List.of("DELIVERY_PAUSED", "AWAITING_REVIEW"));
        assertThat(settlementCount()).isEqualTo(results.get(0).equals("COMPLETED") ? 1 : 0);
    }

    @Test
    void databaseFailureRollsBackCompletionAndKeepsCodeUsable() throws Exception {
        CodeView code = issue();
        String payload = decode(code);
        jdbc.update("INSERT INTO task_settlement (task_id, runner_id, amount, settled_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                taskId, id(runner), REWARD);
        assertThatThrownBy(() -> delivery.confirm(id(publisher), taskId, payload)).isInstanceOf(DuplicateKeyException.class);
        jdbc.update("DELETE FROM task_settlement WHERE task_id=?", taskId);
        assertThat(tasks.detail(id(publisher), taskId).status()).isEqualTo(TaskStatus.PENDING_CONFIRMATION);
        assertThat(delivery.currentCode(id(runner), taskId)).isEqualTo(code);
        assertThat(delivery.confirm(id(publisher), taskId, payload).settlement().amount()).isEqualByComparingTo(REWARD);
    }

    @Test
    void earningsArePagedAndSummedOnlyForTheRecipient() throws Exception {
        String firstPayload = decode(issue());
        delivery.confirm(id(publisher), taskId, firstPayload);
        long otherId = pickedUpTask();
        String secondPayload = decode(delivery.requestDelivery(id(runner), otherId));
        delivery.confirm(id(publisher), otherId, secondPayload);
        mvc.perform(get("/api/student/earnings").param("size", "1").header("Authorization", bearer(runner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.totalAmount").value(7))
                .andExpect(jsonPath("$.data.items[0].taskId").value(otherId))
                .andExpect(jsonPath("$.data.items.length()").value(1));
        assertThat(delivery.earnings(id(runner), 2, 1).items()).extracting(Earning::taskId).containsExactly(taskId);
        assertThat(delivery.earnings(id(publisher), 1, 20).totalAmount()).isEqualByComparingTo("0.00");
        assertThat(delivery.earnings(id(outsider), 1, 20).items()).isEmpty();
    }

    private long pickedUpTask() {
        at(START);
        long id = tasks.publish(id(publisher), new TaskContracts.CreateRequest("取快递", TaskType.EXPRESS,
                "快递站", "宿舍", START.plusSeconds(600), REWARD, null, "13800000000", null, null)).id();
        claims.submit(id(runner), id, new TaskContracts.ClaimRequest(UUID.randomUUID().toString(), "13900000000", null));
        at(START.plusSeconds(1));
        claims.confirm(id);
        cancellations.confirmPickup(id(runner), id);
        return id;
    }

    private long startReview() {
        long cancelId = cancellations.create(id(runner), taskId, newRequest()).cancellationId();
        cancellations.studentAction(id(publisher), taskId, cancelId, StudentAction.AGREE);
        return cancelId;
    }

    private CodeView issue() {
        return delivery.requestDelivery(id(runner), taskId);
    }

    private int settlementCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM task_settlement WHERE task_id=?", Integer.class, taskId);
    }

    private ResultActions scan(StudentContracts.LoginResult student, String action, String payload) throws Exception {
        return mvc.perform(post(path("/delivery" + action)).header("Authorization", bearer(student))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("payload", payload))));
    }

    private String path(String suffix) {
        return "/api/student/tasks/" + taskId + suffix;
    }

    private StudentContracts.LoginResult login(String code) {
        when(wechat.exchange(code)).thenReturn(new WeChatGateway.Identity("delivery-test-app", code));
        return students.login(new StudentContracts.LoginRequest(code));
    }

    private void at(Instant now) {
        when(clock.instant()).thenReturn(now);
    }

    private static long id(StudentContracts.LoginResult login) {
        return login.student().id();
    }

    private static String bearer(StudentContracts.LoginResult login) {
        return "Bearer " + login.accessToken();
    }

    private static CreateRequest newRequest() {
        return new CreateRequest(UUID.randomUUID().toString(), "测试申请");
    }

    private static String decode(CodeView code) throws Exception {
        byte[] png = Base64.getDecoder().decode(code.imageBase64());
        try (var input = new ByteArrayInputStream(png)) {
            var image = ImageIO.read(input);
            int width = image.getWidth();
            int height = image.getHeight();
            var pixels = new RGBLuminanceSource(width, height, image.getRGB(0, 0, width, height, null, 0, width));
            return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(pixels))).getText();
        }
    }

    private static void assertCode(String expected, ThrowingCallable operation) {
        assertThat(catchThrowableOfType(operation, ApiException.class)).extracting(ApiException::code).isEqualTo(expected);
    }

    private static String outcome(Callable<?> operation) throws Exception {
        try {
            return operation.call().toString();
        } catch (ApiException e) {
            return e.code();
        }
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
}
