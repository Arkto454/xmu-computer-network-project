package cn.campus.errand.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.task.claim.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class TaskClaimScheduler {
    private static final Logger log = LoggerFactory.getLogger(TaskClaimScheduler.class);
    private final TaskClaimMapper mapper;
    private final TaskClaimService service;

    public TaskClaimScheduler(TaskClaimMapper mapper, TaskClaimService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.task.claim.fixed-delay-ms:250}", initialDelay = 0)
    public void confirmDue() {
        for (long taskId : mapper.findReadyTasks(service.currentTime(), 100)) {
            try {
                service.confirm(taskId);
            } catch (RuntimeException failure) {
                log.warn("Unable to confirm claims for task {} ({})", taskId, failure.getClass().getSimpleName());
            }
        }
    }
}
