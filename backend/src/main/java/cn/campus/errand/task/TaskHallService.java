package cn.campus.errand.task;

import cn.campus.errand.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static cn.campus.errand.task.TaskContracts.Page;

@Service
public class TaskHallService {
    private final TaskHallQuery query;
    private final RedisHallCache cache;

    public TaskHallService(TaskHallQuery query, RedisHallCache cache) {
        this.query = query;
        this.cache = cache;
    }

    public Page list(int page, int size, TaskType type) {
        if (page < 1 || size < 1 || size > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "page须大于等于1，size须在1至100之间");
        }
        // 事务内查询可能包含未提交修改或旧快照，直接读取数据库。
        if (TransactionSynchronizationManager.isActualTransactionActive()) return query.load(page, size, type).page();
        var lookup = cache.lookup(page, size, type);
        if (lookup.page() != null) return lookup.page();
        var snapshot = query.load(page, size, type);
        cache.store(lookup.generation(), type, snapshot);
        return snapshot.page();
    }
}
