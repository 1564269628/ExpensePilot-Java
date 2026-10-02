package com.expensepilot.approval;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人工审批事实服务。
 *
 * <p>Graph 只负责“停在哪里等人”；真正的人类决定必须落 approval_record，
 * 这样恢复、审计和副作用网关都读取同一个审批事实。</p>
 */
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final JdbcTemplate jdbcTemplate;

    public void request(long taskId, String operationType) {
        jdbcTemplate.update("""
                insert into approval_record(task_id,operation_type,status)
                values (?,?,'PENDING')
                on duplicate key update
                    status=if(status='APPROVED','APPROVED',
                              if(status='REJECTED','REJECTED','PENDING'))
                """, taskId, operationType);
    }

    public boolean isApproved(long taskId, String operationType) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from approval_record
                 where task_id=?
                   and operation_type=?
                   and status='APPROVED'
                """, Integer.class, taskId, operationType);
        return count != null && count > 0;
    }

    @Transactional
    public void approve(
            long taskId,
            String operationType,
            String approver,
            String comment) {
        upsertDecision(
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
        upsertDecision(
                taskId,
                operationType,
                "REJECTED",
                approver,
                comment
        );
    }

    /**
     * 人工决定必须“有记录可查”。
     *
     * <p>政策冲突节点可能直接进入人工审批，并不一定提前调用 request()；
     * 因此 approve/reject 都必须具备 upsert 能力，不能只 update 已存在记录。</p>
     */
    private void upsertDecision(
            long taskId,
            String operationType,
            String decision,
            String approver,
            String comment) {

        jdbcTemplate.update("""
                insert into approval_record(
                    task_id,
                    operation_type,
                    status,
                    approver,
                    comment_text,
                    decided_at
                )
                values (?,?,?,?,?,now())
                on duplicate key update
                    status=values(status),
                    approver=values(approver),
                    comment_text=values(comment_text),
                    decided_at=now()
                """,
                taskId,
                operationType,
                decision,
                approver,
                comment
        );
    }
}
