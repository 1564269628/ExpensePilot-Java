package com.expensepilot.approval;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 人工审批事实服务。
 *
 * <p>Graph 进入审批中断点前先创建 PENDING。最终 APPROVED/REJECTED
 * 使用条件更新，只允许一个审批决定获胜。</p>
 *
 * <p>相同审批人重试相同决定是幂等的，适配 HTTP 响应丢失后的客户端重试；
 * 不同决定或不同审批人不能覆盖已经落库的最终事实。</p>
 */
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final JdbcTemplate jdbcTemplate;

    public void request(long taskId, String operationType) {
        jdbcTemplate.update("""
                insert into approval_record(
                    task_id,
                    operation_type,
                    status
                )
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

        ExistingDecision existing = jdbcTemplate.query("""
                select status,approver
                  from approval_record
                 where task_id=?
                   and operation_type=?
                """,
                (rs, i) -> new ExistingDecision(
                        rs.getString("status"),
                        rs.getString("approver")
                ),
                taskId,
                operationType
        ).stream().findFirst().orElse(null);

        // 客户端可能没有收到第一次 200，又用同一个身份重试相同决定。
        if (existing != null
                && decision.equals(existing.status())
                && Objects.equals(
                        approver,
                        existing.approver()
                )) {
            return;
        }

        throw new IllegalStateException(
                "审批已被处理或审批记录不存在: operationType="
                        + operationType
                        + ", currentStatus="
                        + (existing == null
                            ? "MISSING"
                            : existing.status())
        );
    }

    private record ExistingDecision(
            String status,
            String approver
    ) {}
}
