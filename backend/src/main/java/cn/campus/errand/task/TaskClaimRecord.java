package cn.campus.errand.task;

import java.time.LocalDateTime;

/** 待确认期间的申请，结清后仅保留用于重试核对的摘要和结果。 */
public class TaskClaimRecord {
    public long id;
    public long taskId;
    public long studentId;
    public String requestId;
    public String payloadDigest;
    public ClaimOutcome outcome;
    public LocalDateTime closesAt;
    public String runnerPhone;
    public String runnerWechat;
    public LocalDateTime createdAt;
    public LocalDateTime resolvedAt;
}
