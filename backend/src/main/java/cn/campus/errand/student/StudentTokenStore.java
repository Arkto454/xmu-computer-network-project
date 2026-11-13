package cn.campus.errand.student;

import cn.campus.errand.common.InMemoryTokenStore;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StudentTokenStore extends InMemoryTokenStore {
    public StudentTokenStore(@Value("${app.student.token-ttl}") Duration ttl) { super(ttl); }
}
