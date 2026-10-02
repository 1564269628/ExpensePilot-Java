package com.expensepilot.recovery;

import com.expensepilot.graph.TaskStateStore;
import com.expensepilot.service.ExpenseAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Graph 故障恢复扫描器。
 *
 * <p>除了机器异常，还会恢复“审批事实已落库但 Graph 恢复投递因线程池饱和/进程崩溃
 * 没有执行”的 WAITING_APPROVAL 任务。</p>
 */
@Component
@RequiredArgsConstructor
public class RecoveryWorker {

    private final JdbcTemplate jdbcTemplate;
    private final ExpenseAgentService expenseAgentService;
    private final TaskStateStore taskStateStore;
    private final RecoveryPolicy recoveryPolicy;

    @Scheduled(fixedDelayString = "${expensepilot.recovery.scan-delay-ms:3000}")
    public void recover() {
        recoverMachineFailures();
        recoverDecidedApprovals();
    }

    private void recoverMachineFailures() {
        List<Long> taskIds = jdbcTemplate.query("""
                select id
                  from expense_task
                 where (status='CREATED'
                        and updated_at < date_sub(now(), interval 5 second))
                    or status in ('RETRYING','UNKNOWN')
                    or (status='RUNNING'
                        and updated_at < date_sub(now(), interval 30 second))
                 order by updated_at
                 limit 50
                """,
                (rs, i) -> rs.getLong(1)
        );

        for (Long taskId : taskIds) {
            try {
                TaskStateStore.TaskSnapshot task =
                        taskStateStore.require(taskId);

                if ("CREATED".equals(task.status())) {
                    expenseAgentService.resume(taskId);
                    continue;
                }

                switch (recoveryPolicy.decide(
                        task.retryCount())) {
                    case RESUME_CHECKPOINT ->
                            expenseAgentService.resume(taskId);

                    case REPLAN ->
                            expenseAgentService.replan(taskId);

                    case MANUAL_TAKEOVER ->
                            taskStateStore.markManualTakeover(
                                    taskId,
                                    "自动恢复已达到上限，最后异常: "
                                            + String.valueOf(
                                                task.lastError())
                            );
                }
            }
            catch (IllegalStateException ignoredRace) {
                // 扫描与执行之间可能被其他实例推进。
            }
        }
    }

    private void recoverDecidedApprovals() {
        List<ApprovalResume> approvals =
                jdbcTemplate.query("""
                        select t.id as task_id,
                               a.operation_type,
                               a.status,
                               a.approver
                          from expense_task t
                          join approval_record a
                            on a.task_id=t.id
                         where t.status='WAITING_APPROVAL'
                           and a.status in ('APPROVED','REJECTED')
                           and (
                                (t.current_node='humanApproval'
                                 and a.operation_type='POLICY_EXCEPTION')
                                or
                                (t.current_node='submitApproval'
                                 and a.operation_type='SUBMIT_REPORT')
                           )
                         order by t.updated_at
                         limit 50
                        """,
                        (rs, i) -> new ApprovalResume(
                                rs.getLong("task_id"),
                                rs.getString("operation_type"),
                                rs.getString("status"),
                                rs.getString("approver")
                        )
                );

        for (ApprovalResume approval : approvals) {
            try {
                expenseAgentService.resumeApproval(
                        approval.taskId(),
                        approval.operationType(),
                        "APPROVED".equals(
                                approval.status()),
                        approval.approver()
                );
            }
            catch (IllegalStateException ignoredRace) {
                // 另一个实例可能已经推进 Graph。
            }
        }
    }

    private record ApprovalResume(
            long taskId,
            String operationType,
            String status,
            String approver
    ) {}
}
