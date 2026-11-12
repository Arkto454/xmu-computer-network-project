package cn.campus.errand.common;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    public HealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/api/health")
    ApiResponse<Map<String, String>> health() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return ApiResponse.success(Map.of("application", "UP", "database", "UP"));
    }
}
