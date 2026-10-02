package com.expensepilot.approval;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 副作用审批服务。
 *
 * <p>SUBMIT_REPORT 默认必须得到显式批准。LLM 只能提出“准备提交”，
 * 不能自己把审批状态改成 APPROVED。</p>
 */
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final JdbcTemplate jdbcTemplate;

    public void request(long taskId, String operationType) {
        jdbcTemplate.update("""
                insert into approval_record(task_id,operation_type,status)
                values (?,?,'PENDING')
                on duplicate key update status=if(status='APPROVED','APPROVED','PENDING')
                """, taskId, operationType);
    }

    public boolean isApproved(long taskId, String operationType) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from approval_record
                where task_id=? and operation_type=? and status='APPROVED'
                """, Integer.class, taskId, operationType);
        return count != null && count > 0;
    }

    @Transactional
    public void approve(long taskId, String operationType, String approver, String comment) {
        int affected = jdbcTemplate.update("""
                update approval_record
                set status='APPROVED',approver=?,comment_text=?,decided_at=now()
                where task_id=? and operation_type=?
                """, approver, comment, taskId, operationType);
        if (affected == 0) {
            jdbcTemplate.update("""
                    insert into approval_record(task_id,operation_type,status,approver,comment_text,decided_at)
                    values (?,?,'APPROVED',?,?,now())
                    """, taskId, operationType, approver, comment);
        }
    }

    public void reject(long taskId, String operationType, String approver, String comment) {
        jdbcTemplate.update("""
                update approval_record
                set status='REJECTED',approver=?,comment_text=?,decided_at=now()
                where task_id=? and operation_type=?
                """, approver, comment, taskId, operationType);
    }
}
