package cn.campus.errand.common;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** 一个实例对应一种身份域；学生和管理员必须使用不同实例。 */
public class InMemoryTokenStore {
    public record IssuedToken(String value, Instant expiresAt) {}
    private record Session(long subjectId, Instant expiresAt) {}
    private final Map<String, Session> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final Duration ttl;

    protected InMemoryTokenStore(Duration ttl) { this(ttl, Clock.systemUTC()); }

    protected InMemoryTokenStore(Duration ttl, Clock clock) {
        if (ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("Token TTL must be positive");
        this.ttl = ttl;
        this.clock = clock;
    }

    public synchronized IssuedToken issue(long subjectId) {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(e -> e.getValue().subjectId() == subjectId || !e.getValue().expiresAt().isAfter(now));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = now.plus(ttl);
        sessions.put(token, new Session(subjectId, expiresAt));
        return new IssuedToken(token, expiresAt);
    }

    public synchronized Long resolve(String token) {
        Session session = sessions.get(token);
        if (session == null) return null;
        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(token);
            return null;
        }
        return session.subjectId();
    }
}
