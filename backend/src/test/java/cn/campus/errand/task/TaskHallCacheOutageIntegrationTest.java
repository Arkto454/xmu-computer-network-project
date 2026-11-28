package cn.campus.errand.task;

import cn.campus.errand.student.StudentAuthService;
import cn.campus.errand.student.StudentContracts;
import cn.campus.errand.student.WeChatGateway;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"app.task.cache.enabled=true", "spring.data.redis.host=127.0.0.1"})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskHallCacheOutageIntegrationTest {
    private static final int UNAVAILABLE_PORT = unusedPort();
    @Autowired TaskService tasks;
    @Autowired StudentAuthService students;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean WeChatGateway wechat;
    private long publisher;

    @DynamicPropertySource
    static void unavailableRedis(DynamicPropertyRegistry properties) {
        properties.add("spring.data.redis.port", () -> UNAVAILABLE_PORT);
    }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM errand_task");
        jdbc.update("DELETE FROM student_user");
        when(wechat.exchange("outage")).thenReturn(new WeChatGateway.Identity("redis-outage-test", "publisher"));
        publisher = students.login(new StudentContracts.LoginRequest("outage")).student().id();
    }

    @Test
    void unavailableRedisStillAllowsDatabaseQueriesAndPublishCommit() {
        long id = tasks.publish(publisher, new TaskContracts.CreateRequest("故障回退测试", TaskType.EXPRESS,
                "快递站", "宿舍", Instant.now().plusSeconds(600), new BigDecimal("3.50"), null,
                "13800000000", null, null)).id();
        assertThat(tasks.list(1, 20, null).items()).extracting(TaskContracts.Summary::id).containsExactly(id);
        assertThat(tasks.detail(publisher, id).status()).isEqualTo(TaskStatus.WAITING);
    }

    @Test
    void unavailableRedisDoesNotUndoCancellation() {
        long id = tasks.publish(publisher, new TaskContracts.CreateRequest("故障取消测试", TaskType.EXPRESS,
                "快递站", "宿舍", Instant.now().plusSeconds(600), new BigDecimal("3.50"), null,
                "13800000000", null, null)).id();
        assertThat(tasks.cancel(publisher, id).status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(tasks.list(1, 20, null).total()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM errand_task WHERE id=?", String.class, id)).isEqualTo("CANCELLED");
    }

    private static int unusedPort() {
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new IllegalStateException("无法为故障测试分配端口", failure);
        }
    }
}
