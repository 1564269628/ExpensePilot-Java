package com.expensepilot.recovery;

import com.expensepilot.agent.ExpenseWorkflowRuntime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 重启/异常恢复扫描器。
 *
 * <p>事实源是 MySQL。Worker 定期找到 RETRYING / UNKNOWN / 长时间 RUNNING 的任务，
 * 然后交给 Runtime 从最新 Checkpoint 恢复。Redisson Lease 保证多个实例不会同时恢复同一任务。</p>
 */
@Component
@RequiredArgsConstructor
public class RecoveryWorker {

    private final JdbcTemplate jdbcTemplate;
    private final ExpenseWorkflowRuntime runtime;

    @Scheduled(fixedDelayString = "${expensepilot.recovery.scan-delay-ms:3000}")
    public void recover() {
        List<Long> taskIds = jdbcTemplate.query("""
                select id from expense_task
                where status in ('RETRYING','UNKNOWN')
                   or (status='RUNNING' and updated_at < date_sub(now(), interval 30 second))
                order by updated_at
                limit 50
                """, (rs, i) -> rs.getLong(1));

        for (Long taskId : taskIds) {
            try {
                runtime.resume(taskId);
            } catch (Exception ignored) {
                // 单个任务恢复失败不能阻断其他任务；详细异常由 Trace + task.last_error 记录。
            }
        }
    }
}
