package cn.campus.errand.task;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaskConfig {
    @Bean
    Clock taskClock() { return Clock.systemUTC(); }
}
