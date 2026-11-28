package cn.campus.errand.task;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AdminTaskMapper {
    String FILTER = """
            WHERE 1=1
            <if test="taskId != null">AND id=#{taskId}</if>
            <if test="status != null">
              AND (CASE WHEN status='WAITING' AND deadline_at &lt;= #{now} THEN 'EXPIRED' ELSE status END)=#{status}
            </if>
            """;

    @Select("<script>SELECT COUNT(*) FROM errand_task " + FILTER + "</script>")
    long count(@Param("taskId") Long taskId, @Param("status") TaskStatus status, @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT id, title, task_type AS type, pickup_location, delivery_location, deadline_at,
                   reward_amount, created_at,
                   CASE WHEN status='WAITING' AND deadline_at &lt;= #{now} THEN 'EXPIRED' ELSE status END AS status
            FROM errand_task
            """ + FILTER + " ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<TaskRecord> list(@Param("taskId") Long taskId, @Param("status") TaskStatus status,
                          @Param("now") LocalDateTime now, @Param("offset") long offset, @Param("size") int size);

    @Insert("""
            INSERT INTO task_closure(task_id, admin_id, reason, previous_status, closed_at)
            VALUES (#{taskId}, #{adminId}, #{reason}, #{previous}, #{now})
            """)
    int close(@Param("taskId") long taskId, @Param("adminId") long adminId, @Param("reason") String reason,
              @Param("previous") TaskStatus previous, @Param("now") LocalDateTime now);
}
