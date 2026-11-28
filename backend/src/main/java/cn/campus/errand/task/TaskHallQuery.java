package cn.campus.errand.task;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static cn.campus.errand.task.TaskContracts.*;

@Service
public class TaskHallQuery {
    private final TaskMapper tasks;
    private final Clock clock;

    public TaskHallQuery(TaskMapper tasks, Clock taskClock) {
        this.tasks = tasks;
        this.clock = taskClock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot load(int page, int size, TaskType type) {
        Instant queriedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDateTime now = LocalDateTime.ofInstant(queriedAt, ZoneOffset.UTC);
        var stats = tasks.availableStats(type, now);
        long offset = ((long) page - 1) * size;
        List<Summary> items = offset >= stats.total ? List.of() : tasks.findAvailable(type, now, offset, size)
                .stream().map(TaskService::summary).toList();
        Instant nextDeadline = stats.nextDeadline == null ? null : stats.nextDeadline.toInstant(ZoneOffset.UTC);
        return new Snapshot(new Page(items, stats.total, page, size), queriedAt, nextDeadline);
    }

    public record Snapshot(Page page, Instant queriedAt, Instant nextDeadline) {}
}
