package cn.campus.errand.task;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface TaskClaimMapper {
    @Select("""
            SELECT task_id, request_id, payload_digest, outcome
            FROM task_claim_request WHERE student_id=#{studentId} AND request_id=#{requestId}
            """)
    TaskClaimRecord findRequest(@Param("studentId") long studentId, @Param("requestId") String requestId);

    @Insert("""
            INSERT INTO task_claim_request
              (task_id, student_id, request_id, payload_digest, outcome, closes_at,
               runner_phone, runner_wechat, created_at, resolved_at)
            VALUES (#{taskId}, #{studentId}, #{requestId}, #{payloadDigest}, #{outcome}, #{closesAt},
                    #{runnerPhone}, #{runnerWechat}, #{createdAt}, #{resolvedAt})
            """)
    int insert(TaskClaimRecord request);

    @Select("""
            SELECT closes_at FROM task_claim_request
            WHERE task_id=#{taskId} AND outcome='PENDING' ORDER BY id LIMIT 1
            """)
    LocalDateTime findWindowEnd(long taskId);

    @Select("""
            SELECT COUNT(*) FROM task_claim_request
            WHERE task_id=#{taskId} AND student_id=#{studentId} AND outcome='PENDING'
            """)
    int countPending(@Param("taskId") long taskId, @Param("studentId") long studentId);

    @Select("""
            SELECT DISTINCT task_id FROM task_claim_request
            WHERE outcome='PENDING' AND closes_at <= #{now} ORDER BY task_id LIMIT #{limit}
            """)
    List<Long> findReadyTasks(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("""
            SELECT claim.id, claim.task_id, claim.student_id, claim.runner_phone, claim.runner_wechat
            FROM task_claim_request claim JOIN student_user student ON claim.student_id=student.id
            WHERE claim.task_id=#{taskId} AND claim.outcome='PENDING' AND student.enabled=TRUE
            ORDER BY claim.id LIMIT 1
            """)
    TaskClaimRecord findFirstEligible(long taskId);

    @Insert("""
            INSERT INTO task_assignment(task_id, runner_id, runner_phone, runner_wechat, accepted_at)
            VALUES (#{claim.taskId}, #{claim.studentId}, #{claim.runnerPhone}, #{claim.runnerWechat}, #{now})
            """)
    int assign(@Param("claim") TaskClaimRecord claim, @Param("now") LocalDateTime now);

    @Select("SELECT runner_id FROM task_assignment WHERE task_id=#{taskId}")
    Long findRunnerId(long taskId);

    @Update("""
            UPDATE task_claim_request SET outcome='ACCEPTED', resolved_at=#{now}, runner_phone=NULL, runner_wechat=NULL
            WHERE id=#{id} AND outcome='PENDING'
            """)
    int markAccepted(@Param("id") long id, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE task_claim_request SET outcome=#{outcome}, resolved_at=#{now}, runner_phone=NULL, runner_wechat=NULL
            WHERE task_id=#{taskId} AND outcome='PENDING'
            """)
    int settlePending(@Param("taskId") long taskId, @Param("outcome") ClaimOutcome outcome,
                      @Param("now") LocalDateTime now);
}
