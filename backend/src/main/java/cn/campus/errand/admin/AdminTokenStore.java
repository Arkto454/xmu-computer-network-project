package cn.campus.errand.admin;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AdminTokenStore {
    public record IssuedToken(String value, Instant expiresAt) {}
    private record Session(long adminId, Instant expiresAt) {}
    private final Map<String, Session> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Duration ttl;

    @Autowired
    public AdminTokenStore(@Value("${app.admin.token-ttl}") Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    AdminTokenStore(Duration ttl, Clock clock) {
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Token TTL must be positive");
        this.ttl = ttl;
        this.clock = clock;
    }

    public synchronized IssuedToken issue(long adminId) {
        Instant now = clock.instant();
        // 每个管理员只保留一个有效凭据，同时清理已过期记录。
        sessions.entrySet().removeIf(e -> e.getValue().adminId() == adminId || !e.getValue().expiresAt().isAfter(now));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plus(ttl);
        sessions.put(token, new Session(adminId, expiresAt));
        return new IssuedToken(token, expiresAt);
    }

    public synchronized Long resolve(String token) {
        Session session = sessions.get(token);
        if (session == null) return null;
        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(token);
            return null;
        }
        return session.adminId();
    }
}
