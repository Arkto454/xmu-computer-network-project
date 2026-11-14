package cn.campus.errand.task;

import cn.campus.errand.common.ApiException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.campus.errand.task.TaskContracts.*;

@Service
public class TaskService {
    private final TaskMapper mapper;
    private final Clock clock;

    public TaskService(TaskMapper mapper, Clock taskClock) {
        this.mapper = mapper;
        this.clock = taskClock;
    }

    @Transactional
    public Created publish(long publisherId, CreateRequest request) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant requestedDeadline = request.deadlineAt().truncatedTo(ChronoUnit.MICROS);
        if (!requestedDeadline.isAfter(now)) throw invalid("最晚送达时间必须晚于当前时间");
        LocalDateTime deadline;
        try { deadline = utc(requestedDeadline); }
        catch (DateTimeException e) { throw invalid("送达时间超出支持范围"); }
        if (deadline.getYear() > 9999) throw invalid("送达时间超出支持范围");
        var task = new TaskRecord();
        task.publisherId = publisherId;
        task.title = request.title().trim();
        task.type = request.type();
        task.pickupLocation = request.pickupLocation().trim();
        task.deliveryLocation = request.deliveryLocation().trim();
        task.deadlineAt = deadline;
        task.rewardAmount = request.rewardAmount();
        task.description = optionalText(request.description());
        task.publisherPhone = request.publisherPhone();
        task.publisherWechat = optionalText(request.publisherWechat());
        task.privateInstructions = optionalText(request.privateInstructions());
        task.status = TaskStatus.WAITING;
        task.createdAt = utc(now);
        task.updatedAt = task.createdAt;
        mapper.insert(task);
        return new Created(task.id, task.status);
    }

    @Transactional(readOnly = true)
    public Page list(int page, int size, TaskType type) {
        if (page < 1 || size < 1 || size > 100) throw invalid("page须大于等于1，size须在1至100之间");
        LocalDateTime now = utc(clock.instant());
        long total = mapper.countAvailable(type, now);
        long offset = ((long) page - 1) * size;
        List<Summary> items = offset >= total ? List.of() : mapper.findAvailable(type, now, offset, size)
                .stream().map(this::summary).toList();
        return new Page(items, total, page, size);
    }

    public Detail detail(long viewerId, long taskId) {
        if (taskId < 1) throw invalid("任务编号必须为正数");
        TaskRecord task = mapper.findById(taskId);
        if (task == null) throw notFound();
        TaskStatus status = effectiveStatus(task, clock.instant());
        boolean ownedByMe = task.publisherId == viewerId;
        if (ended(status) && !ownedByMe) throw notFound();
        PrivateInfo privateInfo = ownedByMe && !ended(status)
                ? new PrivateInfo(task.publisherPhone, task.publisherWechat, task.privateInstructions) : null;
        return new Detail(task.id, task.publisherId, task.title, task.type, task.pickupLocation,
                task.deliveryLocation, instant(task.deadlineAt), task.rewardAmount, task.description,
                status, instant(task.createdAt), ownedByMe, privateInfo);
    }

    private Summary summary(TaskRecord task) {
        return new Summary(task.id, task.title, task.type, task.pickupLocation, task.deliveryLocation,
                instant(task.deadlineAt), task.rewardAmount, task.status, instant(task.createdAt));
    }
    private TaskStatus effectiveStatus(TaskRecord task, Instant now) {
        return task.status == TaskStatus.WAITING && !instant(task.deadlineAt).isAfter(now) ? TaskStatus.EXPIRED : task.status;
    }
    private boolean ended(TaskStatus status) {
        return status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED
                || status == TaskStatus.EXPIRED || status == TaskStatus.CLOSED;
    }
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    private static Instant instant(LocalDateTime time) { return time.toInstant(ZoneOffset.UTC); }
    private static String optionalText(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message); }
    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在或不可查看"); }
}
