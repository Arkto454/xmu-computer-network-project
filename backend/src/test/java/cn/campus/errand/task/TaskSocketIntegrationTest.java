package cn.campus.errand.task;

import cn.campus.errand.admin.AdminTokenStore;
import cn.campus.errand.realtime.StudentTaskSocketHandler;
import cn.campus.errand.student.StudentAuthService;
import cn.campus.errand.student.StudentContracts;
import cn.campus.errand.student.StudentTokenStore;
import cn.campus.errand.student.WeChatGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import static cn.campus.errand.task.TaskCancellationContracts.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TaskSocketIntegrationTest {
    private static final Instant START = Instant.parse("2026-11-28T08:00:00Z");
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired TaskService tasks;
    @Autowired TaskClaimService claims;
    @Autowired TaskCancellationService cancellations;
    @Autowired TaskDeliveryService delivery;
    @Autowired AdminTaskService adminTasks;
    @Autowired StudentAuthService students;
    @Autowired StudentTokenStore tokens;
    @Autowired AdminTokenStore adminTokens;
    @Autowired StudentTaskSocketHandler handler;
    @MockitoBean WeChatGateway wechat;
    @MockitoBean(name = "taskClock") Clock clock;
    private final List<Probe> connections = new ArrayList<>();
    private StudentContracts.LoginResult publisher;
    private StudentContracts.LoginResult runner;
    private StudentContracts.LoginResult outsider;

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM errand_task");
        jdbc.update("DELETE FROM student_user");
        when(clock.instant()).thenReturn(START);
        publisher = login("socket-publisher");
        runner = login("socket-runner");
        outsider = login("socket-outsider");
    }

    @AfterEach
    void disconnect() {
        for (Probe probe : connections) {
            if (probe.socket != null) probe.socket.abort();
        }
    }

    @Test
    void handshakeRejectsMissingInvalidAdminAndDisabledCredentials() {
        assertUnauthorized(null);
        assertUnauthorized("Bearer invalid");
        assertUnauthorized("Bearer " + adminTokens.issue(1).value());
        jdbc.update("UPDATE student_user SET enabled=FALSE WHERE id=?", id(runner));
        assertUnauthorized(bearer(runner));
    }

    @Test
    void eventsWaitForCommitAndRollbackDoesNotProduceMessages() throws Exception {
        Probe probe = connect(publisher);
        var transaction = new TransactionTemplate(transactions);
        long taskId = transaction.execute(status -> {
            long created = publish();
            assertThat(probe.poll(150)).isNull();
            return created;
        });
        JsonNode event = probe.event("TASK_PUBLISHED");
        assertThat(event.path("taskId").asLong()).isEqualTo(taskId);
        assertThat(event.propertyStream().map(Map.Entry::getKey).toList())
                .containsExactlyInAnyOrder("eventId", "eventType", "taskId", "occurredAt");
        transaction.executeWithoutResult(status -> {
            publish();
            status.setRollbackOnly();
        });
        assertThat(probe.poll(250)).isNull();
        assertThat(tasks.listMine(id(publisher), 1, 20, null).total()).isEqualTo(1);
    }

    @Test
    void multiDeviceAndClaimResultsAreRoutedToTheirOwnStudents() throws Exception {
        Probe ownerA = connect(publisher);
        Probe ownerB = connect(publisher);
        Probe runnerProbe = connect(runner);
        Probe loser = connect(outsider);
        var unrelated = login("socket-unrelated");
        Probe unrelatedProbe = connect(unrelated);
        long taskId = publish();
        ownerA.event("TASK_PUBLISHED");
        ownerB.event("TASK_PUBLISHED");
        claims.submit(id(runner), taskId, claim());
        claims.submit(id(outsider), taskId, claim());
        when(clock.instant()).thenReturn(START.plusSeconds(1));
        claims.confirm(taskId);
        JsonNode ownerEvent = ownerA.event("TASK_CLAIMED");
        assertThat(ownerB.event("TASK_CLAIMED").path("eventId")).isEqualTo(ownerEvent.path("eventId"));
        runnerProbe.event("CLAIM_RESOLVED");
        loser.event("CLAIM_RESOLVED");
        assertThat(unrelatedProbe.poll(200)).isNull();
        cancellations.confirmPickup(id(runner), taskId);
        ownerA.event("PICKED_UP");
        ownerB.event("PICKED_UP");
        runnerProbe.event("PICKED_UP");
        assertThat(loser.poll(200)).isNull();
    }

    @Test
    void deliveryConfirmationAndAdminCloseNotifyBothParticipants() throws Exception {
        long taskId = pickedUpTask();
        Probe owner = connect(publisher);
        Probe taker = connect(runner);
        var code = delivery.requestDelivery(id(runner), taskId);
        owner.event("DELIVERY_REQUESTED");
        taker.event("DELIVERY_REQUESTED");
        var request = delivery.confirm(id(publisher), taskId, TaskDeliveryIntegrationTest.decode(code));
        owner.event("CONFIRMATION_UPDATED");
        taker.event("CONFIRMATION_UPDATED");
        adminTasks.close(1, taskId, new AdminTaskContracts.CloseRequest("停止交付"));
        owner.event("TASK_CLOSED");
        taker.event("TASK_CLOSED");
        when(clock.instant()).thenReturn(request.closesAt());
        delivery.finalizeConfirmation(taskId);
        assertThat(tasks.detail(id(runner), taskId).closure().reason()).isEqualTo("停止交付");
    }

    @Test
    void completionSendsOneEventAndRetriesDoNotSendItAgain() throws Exception {
        long taskId = pickedUpTask();
        var code = delivery.requestDelivery(id(runner), taskId);
        String payload = TaskDeliveryIntegrationTest.decode(code);
        Probe owner = connect(publisher);
        Probe taker = connect(runner);
        var pending = delivery.confirm(id(publisher), taskId, payload);
        owner.event("CONFIRMATION_UPDATED");
        taker.event("CONFIRMATION_UPDATED");
        when(clock.instant()).thenReturn(pending.closesAt());
        delivery.finalizeConfirmation(taskId);
        JsonNode done = owner.event("TASK_COMPLETED");
        assertThat(taker.event("TASK_COMPLETED").path("eventId")).isEqualTo(done.path("eventId"));
        delivery.confirm(id(publisher), taskId, payload);
        delivery.finalizeConfirmation(taskId);
        assertThat(owner.poll(200)).isNull();
        assertThat(delivery.earnings(id(runner), 1, 20).total()).isEqualTo(1);
    }

    @Test
    void cancellationAndReviewAreSentWithoutReasonOrContactDetails() throws Exception {
        long taskId = pickedUpTask();
        Probe owner = connect(publisher);
        Probe taker = connect(runner);
        long cancellationId = cancellations.create(id(runner), taskId,
                new CreateRequest(UUID.randomUUID().toString(), "MOCK-PRIVATE-REASON")).cancellationId();
        owner.event("CANCELLATION_UPDATED");
        taker.event("CANCELLATION_UPDATED");
        cancellations.studentAction(id(publisher), taskId, cancellationId, StudentAction.AGREE);
        owner.event("CANCELLATION_UPDATED");
        taker.event("CANCELLATION_UPDATED");
        cancellations.review(1, cancellationId, new AdminDecision(AdminAction.APPROVE, "MOCK-PRIVATE-REVIEW"));
        assertThat(owner.event("TASK_CLOSED").toString()).doesNotContain("PRIVATE", "phone", "reason");
        taker.event("TASK_CLOSED");
    }

    @Test
    void invalidatedCredentialsCloseOldConnectionsAndCanReconnect() throws Exception {
        Probe old = connect(runner);
        var replacement = tokens.issue(id(runner));
        handler.revalidateConnections();
        assertThat(old.closed.get(5, TimeUnit.SECONDS)).isEqualTo(4401);
        assertUnauthorized(bearer(runner));
        Probe fresh = connect("Bearer " + replacement.value());
        fresh.socket.sendText("{\"type\":\"PING\"}", true).join();
        assertThat(fresh.next().path("type").asText()).isEqualTo("PONG");
    }

    @Test
    void accountDisabledAfterConnectionIsDisconnected() throws Exception {
        Probe old = connect(runner);
        jdbc.update("UPDATE student_user SET enabled=FALSE WHERE id=?", id(runner));
        handler.revalidateConnections();
        assertThat(old.closed.get(5, TimeUnit.SECONDS)).isEqualTo(4401);
    }

    @Test
    void revokedTokenIsCheckedBeforeSendingAndClientsCannotSubscribeToOthers() throws Exception {
        Probe old = connect(publisher);
        tokens.issue(id(publisher));
        publish();
        assertThat(old.closed.get(5, TimeUnit.SECONDS)).isEqualTo(4401);
        assertThat(old.poll(100)).isNull();
        Probe attacker = connect(outsider);
        attacker.socket.sendText("{\"type\":\"SUBSCRIBE\",\"taskId\":1}", true).join();
        assertThat(attacker.closed.get(5, TimeUnit.SECONDS)).isEqualTo(1008);
    }

    @Test
    void offlineUserRecoversLatestStateThroughExistingHttpQuery() throws Exception {
        long taskId = pickedUpTask();
        Probe old = connect(runner);
        old.socket.sendClose(WebSocket.NORMAL_CLOSURE, "offline").join();
        old.closed.get(5, TimeUnit.SECONDS);
        adminTasks.close(1, taskId, new AdminTaskContracts.CloseRequest("离线时关闭"));
        Probe fresh = connect(runner);
        assertThat(fresh.socket.isInputClosed()).isFalse();
        var http = HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/student/tasks/" + taskId))
                .header("Authorization", bearer(runner)).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(http.statusCode()).isEqualTo(200);
        assertThat(json.readTree(http.body()).path("data").path("status").asText()).isEqualTo("CLOSED");
    }

    @Test
    void failureOnOneConnectionCannotFailPublicationOrAnotherConnection() throws Exception {
        Probe healthy = connect(publisher);
        WebSocketSession broken = mock(WebSocketSession.class);
        when(broken.getId()).thenReturn("broken-fixture");
        when(broken.isOpen()).thenReturn(true);
        when(broken.getAttributes()).thenReturn(Map.of("studentSocketAuthorization", bearer(publisher),
                "studentSocketId", id(publisher)));
        doNothing().doThrow(new IOException("fixture transport failure")).when(broken).sendMessage(any());
        handler.afterConnectionEstablished(broken);
        long taskId = publish();
        assertThat(healthy.event("TASK_PUBLISHED").path("taskId").asLong()).isEqualTo(taskId);
        verify(broken, timeout(3000)).close(CloseStatus.SERVER_ERROR);
        assertThat(tasks.detail(id(publisher), taskId).status()).isEqualTo(TaskStatus.WAITING);
    }

    private long pickedUpTask() {
        long taskId = publish();
        claims.submit(id(runner), taskId, claim());
        when(clock.instant()).thenReturn(START.plusSeconds(1));
        claims.confirm(taskId);
        cancellations.confirmPickup(id(runner), taskId);
        return taskId;
    }

    private long publish() {
        return tasks.publish(id(publisher), new TaskContracts.CreateRequest("取快递", TaskType.EXPRESS, "快递站", "宿舍",
                START.plusSeconds(600), new BigDecimal("3.50"), null, "13800000000", null, "MOCK-PRIVATE")).id();
    }

    private StudentContracts.LoginResult login(String code) {
        when(wechat.exchange(code)).thenReturn(new WeChatGateway.Identity("socket-test-app", code));
        return students.login(new StudentContracts.LoginRequest(code));
    }

    private Probe connect(StudentContracts.LoginResult student) throws Exception { return connect(bearer(student)); }
    private Probe connect(String authorization) throws Exception {
        var probe = new Probe();
        connections.add(probe);
        probe.socket = builder(authorization).buildAsync(uri(), probe).get(5, TimeUnit.SECONDS);
        probe.ready.get(5, TimeUnit.SECONDS);
        return probe;
    }

    private void assertUnauthorized(String authorization) {
        var failure = catchThrowable(() -> builder(authorization).buildAsync(uri(), new Probe()).join());
        assertThat(failure).hasCauseInstanceOf(WebSocketHandshakeException.class);
        assertThat(((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode()).isEqualTo(401);
    }

    private WebSocket.Builder builder(String authorization) {
        var builder = HttpClient.newHttpClient().newWebSocketBuilder();
        if (authorization != null) builder.header("Authorization", authorization);
        return builder;
    }

    private URI uri() { return URI.create("ws://127.0.0.1:" + port + "/ws/student/tasks"); }
    private static long id(StudentContracts.LoginResult student) { return student.student().id(); }
    private static String bearer(StudentContracts.LoginResult student) { return "Bearer " + student.accessToken(); }
    private static TaskContracts.ClaimRequest claim() {
        return new TaskContracts.ClaimRequest(UUID.randomUUID().toString(), "13900000000", null);
    }

    private class Probe implements WebSocket.Listener {
        private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();
        private WebSocket socket;

        @Override
        public void onOpen(WebSocket socket) { socket.request(1); }
        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence part, boolean last) {
            text.append(part);
            if (last) {
                try {
                    JsonNode message = json.readTree(text.toString());
                    if ("READY".equals(message.path("type").asText())) ready.complete(null);
                    else messages.add(message);
                } catch (IOException failure) { ready.completeExceptionally(failure); }
                text.setLength(0);
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override
        public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
            closed.complete(status);
            return CompletableFuture.completedFuture(null);
        }
        @Override
        public void onError(WebSocket socket, Throwable failure) { ready.completeExceptionally(failure); }

        JsonNode next() throws InterruptedException {
            JsonNode next = messages.poll(5, TimeUnit.SECONDS);
            assertThat(next).as("WebSocket message").isNotNull();
            return next;
        }

        JsonNode event(String type) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                JsonNode event = next();
                if (type.equals(event.path("eventType").asText())) return event;
            }
            throw new AssertionError("No WebSocket event " + type);
        }

        JsonNode poll(long millis) {
            try { return messages.poll(millis, TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
    }
}
