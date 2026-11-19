package cn.campus.errand.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.task.expiration.enabled", havingValue = "true", matchIfMissing = true)
public class TaskExpirationScheduler {
    private static final Logger log = LoggerFactory.getLogger(TaskExpirationScheduler.class);
    private final TaskService tasks;

    public TaskExpirationScheduler(TaskService tasks) { this.tasks = tasks; }

    @Scheduled(fixedDelayString = "${app.task.expiration.fixed-delay-ms:30000}", initialDelay = 0)
    public void expireTasks() {
        int changed = 0;
        for (int batch = 0; batch < 10; batch++) {
            int count = tasks.expireBatch(200);
            changed += count;
            if (count < 200) break;
        }
        if (changed > 0) log.info("Expired {} waiting tasks", changed);
    }
}
