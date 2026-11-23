package cn.campus.errand.task;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TaskMapper {
    String COLUMNS = "id, publisher_id, title, task_type AS type, pickup_location, delivery_location, "
            + "deadline_at, reward_amount, description, publisher_phone, publisher_wechat, "
            + "private_instructions, status, created_at, updated_at";

    @Insert("""
            INSERT INTO errand_task
            (publisher_id, title, task_type, pickup_location, delivery_location, deadline_at,
             reward_amount, description, publisher_phone, publisher_wechat, private_instructions,
             status, created_at, updated_at)
            VALUES (#{publisherId}, #{title}, #{type}, #{pickupLocation}, #{deliveryLocation}, #{deadlineAt},
                    #{rewardAmount}, #{description}, #{publisherPhone}, #{publisherWechat}, #{privateInstructions},
                    #{status}, #{createdAt}, #{updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TaskRecord task);

    @Select("SELECT " + COLUMNS + " FROM errand_task WHERE id=#{id}")
    TaskRecord findById(long id);

    @Select("""
            <script>
            SELECT COUNT(*) FROM errand_task WHERE status='WAITING' AND deadline_at &gt; #{now}
            <if test="type != null">AND task_type=#{type}</if>
            </script>
            """)
    long countAvailable(@Param("type") TaskType type, @Param("now") LocalDateTime now);

    // 大厅查询不读取私密列，后续缓存也只能使用 Summary。
    @Select("""
            <script>
            SELECT id, title, task_type AS type, pickup_location, delivery_location,
                   deadline_at, reward_amount, status, created_at
            FROM errand_task WHERE status='WAITING' AND deadline_at &gt; #{now}
            <if test="type != null">AND task_type=#{type}</if>
            ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<TaskRecord> findAvailable(@Param("type") TaskType type, @Param("now") LocalDateTime now,
                                   @Param("offset") long offset, @Param("size") int size);

    String OWNED_WHERE = """
            WHERE publisher_id=#{publisherId}
            <if test="status != null">
              AND (CASE WHEN status='WAITING' AND deadline_at &lt;= #{now}
                        THEN 'EXPIRED' ELSE status END)=#{status}
            </if>
            """;

    @Select("<script>SELECT COUNT(*) FROM errand_task " + OWNED_WHERE + "</script>")
    long countOwned(@Param("publisherId") long publisherId, @Param("status") TaskStatus status,
                    @Param("now") LocalDateTime now);

    // 本人列表仍只读取公开摘要，过期展示与筛选使用同一个 now。
    @Select("""
            <script>
            SELECT id, title, task_type AS type, pickup_location, delivery_location, deadline_at,
                   reward_amount, created_at,
                   CASE WHEN status='WAITING' AND deadline_at &lt;= #{now}
                        THEN 'EXPIRED' ELSE status END AS status
            FROM errand_task
            """ + OWNED_WHERE + " ORDER BY created_at DESC, id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<TaskRecord> findOwned(@Param("publisherId") long publisherId, @Param("status") TaskStatus status,
                               @Param("now") LocalDateTime now, @Param("offset") long offset,
                               @Param("size") int size);

    @Select("""
            SELECT id, publisher_id, deadline_at, status FROM errand_task
            WHERE id=#{id} AND publisher_id=#{publisherId} FOR UPDATE
            """)
    TaskRecord lockOwned(@Param("id") long id, @Param("publisherId") long publisherId);

    @Update("""
            UPDATE errand_task SET status='CANCELLED', updated_at=#{now}
            WHERE id=#{id} AND publisher_id=#{publisherId} AND status='WAITING' AND deadline_at > #{now}
            """)
    int cancelAvailable(@Param("id") long id, @Param("publisherId") long publisherId,
                         @Param("now") LocalDateTime now);

    @Select("""
            SELECT id FROM errand_task WHERE status='WAITING' AND deadline_at <= #{now}
            ORDER BY deadline_at, id LIMIT #{size}
            """)
    List<Long> findDueIds(@Param("now") LocalDateTime now, @Param("size") int size);

    @Update("""
            UPDATE errand_task SET status='EXPIRED', updated_at=#{now}
            WHERE id=#{id} AND status='WAITING' AND deadline_at <= #{now}
            """)
    int expireIfDue(@Param("id") long id, @Param("now") LocalDateTime now);

    @Select("SELECT id, publisher_id, deadline_at, status FROM errand_task WHERE id=#{id} FOR UPDATE")
    TaskRecord lockById(long id);

    @Update("""
            UPDATE errand_task SET status='ACCEPTED', updated_at=#{now}
            WHERE id=#{id} AND status='WAITING' AND deadline_at > #{now}
            """)
    int acceptAvailable(@Param("id") long id, @Param("now") LocalDateTime now);

    @Select("""
            SELECT t.*, t.task_type AS type, a.runner_id, a.runner_phone, a.runner_wechat, p.picked_up_at,
                   c.id AS cancellation_id, c.status AS cancellation_status, c.requester_id AS cancellation_requester_id,
                   s.amount AS settlement_amount, s.settled_at
            FROM errand_task t
            LEFT JOIN task_assignment a ON t.id=a.task_id
            LEFT JOIN task_pickup p ON p.task_id=t.id
            LEFT JOIN task_cancellation c ON c.id=(SELECT MAX(id) FROM task_cancellation WHERE task_id=t.id)
            LEFT JOIN task_settlement s ON s.task_id=t.id
            WHERE t.id=#{id}
            """)
    TaskRecord findDetailById(long id);

    @Update("""
            UPDATE errand_task SET status=#{next}, updated_at=#{now} WHERE id=#{id} AND status=#{expected}
            """)
    int transition(@Param("id") long id, @Param("expected") TaskStatus expected, @Param("next") TaskStatus next,
                   @Param("now") LocalDateTime now);

    String ACCEPTED_FROM = """
            FROM task_assignment a JOIN errand_task t ON a.task_id=t.id
            WHERE a.runner_id=#{runnerId}
            <if test="status != null">AND t.status=#{status}</if>
            """;

    @Select("<script>SELECT COUNT(*) " + ACCEPTED_FROM + "</script>")
    long countAccepted(@Param("runnerId") long runnerId, @Param("status") TaskStatus status);

    @Select("""
            <script>
            SELECT t.id, t.title, t.task_type AS type, t.pickup_location, t.delivery_location,
                   t.deadline_at, t.reward_amount, t.status, t.created_at
            """ + ACCEPTED_FROM + " ORDER BY a.accepted_at DESC, t.id DESC LIMIT #{size} OFFSET #{offset}</script>")
    List<TaskRecord> findAccepted(@Param("runnerId") long runnerId, @Param("status") TaskStatus status,
                                  @Param("offset") long offset, @Param("size") int size);
}
