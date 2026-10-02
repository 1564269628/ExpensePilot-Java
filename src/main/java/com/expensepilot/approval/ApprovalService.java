package com.expensepilot.approval;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人工审批事实服务。
 *
 * <p>Graph 负责“停在哪里等人”，approval_record 保存唯一的人类决定。
 * 审批记录必须先进入 PENDING，最终 APPROVED/REJECTED 只能 CAS 一次。</p>
 */
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final JdbcTemplate jdbcTemplate;

    public void request(long taskId, String operationType) {
        jdbcTemplate.update("""
                insert into approval_record(task_id,operation_type,status)
                values (?,?,'PENDING')
                on duplicate key update status=status
                """,
                taskId,
                operationType
        );
    }

    public boolean isApproved(long taskId, String operationType) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from approval_record
                 where task_id=?
                   and operation_type=?
                   and status='APPROVED'
                """,
                Integer.class,
                taskId,
                operationType
        );
        return count != null && count > 0;
    }

    @Transactional
    public void approve(
            long taskId,
            String operationType,
            String approver,
            String comment) {
        decideOnce(
                taskId,
                operationType,
                "APPROVED",
                approver,
                comment
        );
    }

    @Transactional
    public void reject(
            long taskId,
            String operationType,
            String approver,
            String comment) {
        decideOnce(
                taskId,
                operationType,
                "REJECTED",
                approver,
                comment
        );
    }

    /**
     * 只有 PENDING 能进入最终状态。
     *
     * <p>两个审批人并发操作时，数据库条件更新只允许一个 affectedRows=1；
     * 后到的请求直接返回冲突，不能覆盖前一个人的决定。</p>
     */
    private void decideOnce(
            long taskId,
            String operationType,
            String decision,
            String approver,
            String comment) {

        int affected = jdbcTemplate.update("""
                update approval_record
                   set status=?,
                       approver=?,
                       comment_text=?,
                       decided_at=now()
                 where task_id=?
                   and operation_type=?
                   and status='PENDING'
                """,
                decision,
                approver,
                comment,
                taskId,
                operationType
        );

        if (affected == 1) {
            return;
        }

        String existing = jdbcTemplate.query("""
                select status
                  from approval_record
                 where task_id=? and operation_type=?
                """,
                (rs, i) -> rs.getString("status"),
                taskId,
                operationType
        ).stream().findFirst().orElse("MISSING");

        throw new IllegalStateException(
                "审批已被处理或审批记录不存在: operationType="
                        + operationType
                        + ", currentStatus="
                        + existing
        );
    }
}
