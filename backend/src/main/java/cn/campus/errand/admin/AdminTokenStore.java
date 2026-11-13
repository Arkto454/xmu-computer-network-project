package cn.campus.errand.admin;

import cn.campus.errand.common.InMemoryTokenStore;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AdminTokenStore extends InMemoryTokenStore {

    @Autowired
    public AdminTokenStore(@Value("${app.admin.token-ttl}") Duration ttl) {
        super(ttl);
    }

    AdminTokenStore(Duration ttl, Clock clock) {
        super(ttl, clock);
    }
}
