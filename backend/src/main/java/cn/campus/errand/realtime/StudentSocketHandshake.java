package cn.campus.errand.realtime;

import cn.campus.errand.common.ApiException;
import cn.campus.errand.student.StudentAuthService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class StudentSocketHandshake implements HandshakeInterceptor {
    static final String AUTHORIZATION = "studentSocketAuthorization";
    static final String STUDENT_ID = "studentSocketId";
    private final StudentAuthService auth;

    public StudentSocketHandshake(StudentAuthService auth) { this.auth = auth; }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        String authorization = request.getHeaders().getFirst("Authorization");
        try {
            long id = auth.authenticate(authorization).id();
            attributes.put(AUTHORIZATION, authorization);
            attributes.put(STUDENT_ID, id);
            return true;
        } catch (ApiException invalid) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        } catch (RuntimeException unavailable) {
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception exception) {}
}
