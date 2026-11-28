package cn.campus.errand.task;

import cn.campus.errand.realtime.TaskNotice;
import java.util.HashSet;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class TaskNotifications {
    private final TaskMapper tasks;
    private final ApplicationEventPublisher events;

    public TaskNotifications(TaskMapper tasks, ApplicationEventPublisher events) {
        this.tasks = tasks;
        this.events = events;
    }

    public void changed(long taskId, TaskNotice.Type type) {
        events.publishEvent(TaskNotice.create(type, taskId, new HashSet<>(tasks.findParticipants(taskId))));
    }

    public void claimResolved(long taskId, long studentId) {
        events.publishEvent(TaskNotice.create(TaskNotice.Type.CLAIM_RESOLVED, taskId, Set.of(studentId)));
    }
}
