package cn.campus.errand.task;

import cn.campus.errand.common.ApiException;
import org.springframework.http.HttpStatus;

/** 过期状态已经确认；取消事务保留该状态，再反馈 HTTP 409。 */
final class TaskExpiredException extends ApiException {
    TaskExpiredException() {
        super(HttpStatus.CONFLICT, "TASK_EXPIRED", "任务已过期，不能取消");
    }
}
