package cn.campus.errand.task;

public enum ClaimOutcome {
    PENDING("正在确认接单"),
    ACCEPTED("接单成功"),
    ALREADY_ACCEPTED("你已接到这条任务"),
    OWN_TASK("不能接自己发布的任务"),
    TASK_TAKEN("任务已被接走"),
    TASK_CANCELLED("发布者已取消任务"),
    TASK_EXPIRED("任务已过期"),
    TASK_STATE_CONFLICT("当前任务状态不允许接单"),
    NOT_ELIGIBLE("当前账号不能接单");

    private final String message;

    ClaimOutcome(String message) { this.message = message; }
    public String message() { return message; }
}
