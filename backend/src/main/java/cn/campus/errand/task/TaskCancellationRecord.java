package cn.campus.errand.task;

import java.time.LocalDateTime;
import static cn.campus.errand.task.TaskCancellationContracts.*;

public class TaskCancellationRecord {
    public Long id;
    public long taskId;
    public long requesterId;
    public String reason;
    public Status status;
    public LocalDateTime createdAt;
    public StudentAction studentAction;
    public Long studentActionBy;
    public LocalDateTime studentActionAt;
    public Long reviewerId;
    public String reviewReason;
    public LocalDateTime reviewedAt;
    public TaskStatus taskStatus;
}
