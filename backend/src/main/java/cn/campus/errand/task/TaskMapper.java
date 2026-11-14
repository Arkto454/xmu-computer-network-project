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
}
