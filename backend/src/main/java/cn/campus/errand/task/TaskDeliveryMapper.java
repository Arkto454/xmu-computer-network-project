package cn.campus.errand.task;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TaskDeliveryMapper {
    @Select("SELECT * FROM task_delivery_code WHERE task_id=#{taskId}")
    Code findCode(long taskId);

    @Insert("""
            INSERT INTO task_delivery_code(task_id, code_version, payload_digest, image_png, expires_at)
            VALUES (#{taskId}, #{codeVersion}, #{payloadDigest}, #{imagePng}, #{expiresAt})
            """)
    int insertCode(Code code);

    @Update("""
            UPDATE task_delivery_code SET code_version=#{codeVersion}, payload_digest=#{payloadDigest},
                   image_png=#{imagePng}, expires_at=#{expiresAt}
            WHERE task_id=#{taskId}
            """)
    int replaceCode(Code code);

    @Update("""
            UPDATE task_delivery_code SET code_version=code_version+1, image_png=NULL
            WHERE task_id=#{taskId} AND image_png IS NOT NULL
            """)
    int clearImage(long taskId);

    @Insert("""
            INSERT INTO task_settlement(task_id, runner_id, amount, settled_at)
            VALUES (#{taskId}, #{runnerId}, #{amount}, #{settledAt})
            """)
    int settle(@Param("taskId") long taskId, @Param("runnerId") long runnerId,
               @Param("amount") BigDecimal amount, @Param("settledAt") LocalDateTime settledAt);

    @Select("SELECT COUNT(*) AS total, COALESCE(SUM(amount), 0.00) AS total_amount FROM task_settlement WHERE runner_id=#{runnerId}")
    Totals totals(long runnerId);

    @Select("""
            SELECT s.task_id, t.title, s.amount, s.settled_at
            FROM task_settlement s JOIN errand_task t ON t.id=s.task_id
            WHERE s.runner_id=#{runnerId} ORDER BY s.settled_at DESC, s.task_id DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<EarningRow> findEarnings(@Param("runnerId") long runnerId, @Param("offset") long offset,
                                  @Param("size") int size);

    class Code {
        public long taskId;
        public long codeVersion;
        public String payloadDigest;
        public byte[] imagePng;
        public LocalDateTime expiresAt;
    }

    class Totals {
        public long total;
        public BigDecimal totalAmount;
    }

    class EarningRow {
        public long taskId;
        public String title;
        public BigDecimal amount;
        public LocalDateTime settledAt;
    }
}
