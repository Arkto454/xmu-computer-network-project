package cn.campus.errand.task;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** MyBatis 内部持久化对象，禁止直接用作接口响应。日期字段按 UTC 保存。 */
public class TaskRecord {
    public Long id;
    public Long publisherId;
    public String title;
    public TaskType type;
    public String pickupLocation;
    public String deliveryLocation;
    public LocalDateTime deadlineAt;
    public BigDecimal rewardAmount;
    public String description;
    public String publisherPhone;
    public String publisherWechat;
    public String privateInstructions;
    public TaskStatus status;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
