package cn.campus.errand.realtime;

import cn.campus.errand.common.ApiException;
import cn.campus.errand.student.StudentAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import static cn.campus.errand.realtime.StudentSocketHandshake.*;

@Component
public class StudentTaskSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(StudentTaskSocketHandler.class);
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final StudentAuthService auth;
    private final ObjectMapper json;

    public StudentTaskSocketHandler(StudentAuthService auth, ObjectMapper json) {
        this.auth = auth;
        this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) {
        raw.setTextMessageSizeLimit(1024);
        var session = new ConcurrentWebSocketSessionDecorator(raw, 1000, 65536);
        if (!authenticated(session)) return;
        sessions.put(session.getId(), session);
        send(session, new TextMessage("{\"type\":\"READY\"}"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage message) throws IOException {
        WebSocketSession session = sessions.get(raw.getId());
        if (session == null || !authenticated(session)) return;
        String type;
        try {
            type = json.readTree(message.getPayload()).path("type").asText();
        } catch (IOException | RuntimeException malformed) {
            close(session, CloseStatus.BAD_DATA);
            return;
        }
        if ("PING".equals(type)) send(session, new TextMessage("{\"type\":\"PONG\"}"));
        else close(session, CloseStatus.POLICY_VIOLATION);
    }

    public void deliver(TaskNotice notice) {
        TextMessage message;
        try {
            message = new TextMessage(json.writeValueAsString(notice.message()));
        } catch (IOException failure) {
            return;
        }
        for (WebSocketSession session : sessions.values()) {
            if (notice.recipients().contains(session.getAttributes().get(STUDENT_ID)) && authenticated(session)) {
                send(session, message);
            }
        }
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void revalidateConnections() {
        for (WebSocketSession session : sessions.values()) authenticated(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        close(session, CloseStatus.SERVER_ERROR);
    }

    private boolean authenticated(WebSocketSession session) {
        if (!session.isOpen()) {
            sessions.remove(session.getId());
            return false;
        }
        try {
            long actual = auth.authenticate((String) session.getAttributes().get(AUTHORIZATION)).id();
            if (!Long.valueOf(actual).equals(session.getAttributes().get(STUDENT_ID))) {
                close(session, new CloseStatus(4401, "Authentication required"));
                return false;
            }
            return true;
        } catch (ApiException expired) {
            close(session, new CloseStatus(4401, "Authentication required"));
        } catch (RuntimeException unavailable) {
            close(session, CloseStatus.SERVER_ERROR);
        }
        return false;
    }

    private void send(WebSocketSession session, TextMessage message) {
        try {
            session.sendMessage(message);
        } catch (IOException | RuntimeException failure) {
            log.warn("Unable to send task notification to connection {}", session.getId());
            close(session, CloseStatus.SERVER_ERROR);
        }
    }

    private void close(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        try {
            session.close(status);
        } catch (IOException | RuntimeException ignored) {
            // 失效连接已移除，后续提醒不再投递到该连接。
        }
    }
}
