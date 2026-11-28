package cn.campus.errand.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.task.delivery.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class TaskDeliveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(TaskDeliveryScheduler.class);
    private final TaskDeliveryMapper mapper;
    private final TaskDeliveryService service;

    public TaskDeliveryScheduler(TaskDeliveryMapper mapper, TaskDeliveryService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.task.delivery.fixed-delay-ms:250}", initialDelay = 0)
    public void confirmDue() {
        for (long taskId : mapper.findDueConfirmations(service.now())) {
            try {
                service.finalizeConfirmation(taskId);
            } catch (RuntimeException failure) {
                log.warn("Unable to finalize delivery for task {} ({})", taskId, failure.getClass().getSimpleName());
            }
        }
    }
}
