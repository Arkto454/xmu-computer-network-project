package cn.campus.errand.task;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;
import static cn.campus.errand.task.TaskCancellationContracts.*;

@Mapper
public interface TaskCancellationMapper {
    @Select("SELECT picked_up_at FROM task_pickup WHERE task_id=#{taskId}")
    LocalDateTime findPickedUpAt(long taskId);

    @Insert("INSERT INTO task_pickup (task_id, picked_up_at) VALUES (#{taskId}, #{now})")
    int recordPickup(@Param("taskId") long taskId, @Param("now") LocalDateTime now);

    @Select("""
            SELECT * FROM task_cancellation WHERE task_id=#{taskId} ORDER BY id DESC LIMIT 1
            """)
    TaskCancellationRecord findLatest(long taskId);

    @Select("SELECT * FROM task_cancellation WHERE id=#{id}")
    TaskCancellationRecord findById(long id);

    @Select("SELECT task_id FROM task_cancellation WHERE id=#{id}")
    Long findTaskId(long id);

    @Select("""
            SELECT c.* FROM task_cancellation_request_key k
            JOIN task_cancellation c ON c.id=k.cancellation_id
            WHERE k.student_id=#{studentId} AND k.request_id=#{requestId}
            """)
    TaskCancellationRecord findByRequest(@Param("studentId") long studentId, @Param("requestId") String requestId);

    @Insert("""
            INSERT INTO task_cancellation(task_id, requester_id, reason, status, created_at)
            VALUES (#{taskId}, #{requesterId}, #{reason}, #{status}, #{createdAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TaskCancellationRecord cancellation);

    @Insert("""
            INSERT INTO task_cancellation_request_key(student_id, request_id, cancellation_id)
            VALUES (#{studentId}, #{requestId}, #{cancellationId})
            """)
    int bindRequest(@Param("studentId") long studentId, @Param("requestId") String requestId,
                    @Param("cancellationId") long cancellationId);

    @Update("""
            UPDATE task_cancellation
            SET status=#{status}, student_action=#{action}, student_action_by=#{actor}, student_action_at=#{now}
            WHERE id=#{id} AND status='AWAITING_PEER'
            """)
    int recordStudentAction(@Param("id") long id, @Param("status") Status status,
                            @Param("action") StudentAction action, @Param("actor") long actor,
                            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE task_cancellation
            SET status=#{status}, reviewer_id=#{reviewerId}, review_reason=#{reason}, reviewed_at=#{now}
            WHERE id=#{id} AND status='AWAITING_REVIEW'
            """)
    int recordReview(@Param("id") long id, @Param("status") Status status, @Param("reviewerId") long reviewerId,
                     @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Update("UPDATE task_cancellation SET status='TASK_FINISHED' WHERE id=#{id} AND status='AWAITING_PEER'")
    int finishWaitingForPeer(long id);

    @Update("""
            UPDATE task_cancellation SET status='ADMIN_CLOSED'
            WHERE task_id=#{taskId} AND status IN ('AWAITING_PEER', 'AWAITING_REVIEW')
            """)
    int closePending(long taskId);

    @Select("SELECT * FROM task_cancellation WHERE task_id=#{taskId} ORDER BY id")
    List<TaskCancellationRecord> findHistory(long taskId);

    @Select("SELECT COUNT(*) FROM task_cancellation WHERE status=#{status}")
    long countByStatus(Status status);

    @Select("""
            SELECT c.*, t.status AS task_status FROM task_cancellation c
            JOIN errand_task t ON t.id=c.task_id WHERE c.status=#{status}
            ORDER BY c.id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<TaskCancellationRecord> findByStatus(@Param("status") Status status, @Param("offset") long offset,
                                              @Param("size") int size);
}
