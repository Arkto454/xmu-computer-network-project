package cn.campus.errand.admin;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AdminTokenStoreTest {
    @Test
    void tokenExpiresExactlyAtDeadline() {
        var clock = new MutableClock();
        var store = new AdminTokenStore(Duration.ofHours(1), clock);
        var issued = store.issue(7);
        assertThat(store.resolve(issued.value())).isEqualTo(7L);
        clock.now = issued.expiresAt();
        assertThat(store.resolve(issued.value())).isNull();
    }

    @Test
    void independentAdminsHaveIndependentSessions() {
        var store = new AdminTokenStore(Duration.ofHours(1), Clock.systemUTC());
        var first = store.issue(1);
        store.issue(2);
        assertThat(store.resolve(first.value())).isEqualTo(1L);
    }

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-12T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
