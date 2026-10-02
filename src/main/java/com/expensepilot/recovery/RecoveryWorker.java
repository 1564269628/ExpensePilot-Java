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
                """, (rs, i) -> rs.getLong(1));

        for (Long taskId : taskIds) {
            try {
                TaskStateStore.TaskSnapshot task = taskStateStore.require(taskId);

                if ("CREATED".equals(task.status())) {
                    expenseAgentService.resume(taskId);
                    continue;
                }

                switch (recoveryPolicy.decide(task.retryCount())) {
                    case RESUME_CHECKPOINT ->
                            expenseAgentService.resume(taskId);
                    case REPLAN ->
                            expenseAgentService.replan(taskId);
                    case MANUAL_TAKEOVER ->
                            taskStateStore.markManualTakeover(
                                    taskId,
                                    "自动恢复已达到上限，最后异常: "
                                            + String.valueOf(task.lastError())
                            );
                }
            }
            catch (IllegalStateException ignoredRace) {
                // 扫描与执行之间可能被其他实例推进；以 MySQL 最新状态为准。
            }
        }
    }
}
