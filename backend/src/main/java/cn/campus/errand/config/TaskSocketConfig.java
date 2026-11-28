package cn.campus.errand.config;

import cn.campus.errand.realtime.StudentSocketHandshake;
import cn.campus.errand.realtime.StudentTaskSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class TaskSocketConfig implements WebSocketConfigurer {
    private final StudentTaskSocketHandler handler;
    private final StudentSocketHandshake handshake;
    private final String[] allowedOrigins;

    public TaskSocketConfig(StudentTaskSocketHandler handler, StudentSocketHandshake handshake,
                            @Value("${app.websocket.allowed-origins:}") String[] allowedOrigins) {
        this.handler = handler;
        this.handshake = handshake;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/student/tasks").addInterceptors(handshake).setAllowedOrigins(allowedOrigins);
    }
}
