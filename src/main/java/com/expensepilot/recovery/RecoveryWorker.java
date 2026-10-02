package com.expensepilot.recovery;

import com.expensepilot.service.ExpenseAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 恢复 Worker 不再自己解释节点状态，而是把 taskId 重新交给 CompiledGraph。
 * Graph 的 MysqlSaver 根据 threadId 自动加载最新 checkpoint。
 */
@Component
@RequiredArgsConstructor
public class RecoveryWorker {

    private final JdbcTemplate jdbcTemplate;
    private final ExpenseAgentService expenseAgentService;

    @Scheduled(fixedDelayString = "${expensepilot.recovery.scan-delay-ms:3000}")
    public void recover() {
        List<Long> taskIds = jdbcTemplate.query("""
                select id
                  from expense_task
                 where status in ('RETRYING','UNKNOWN')
                    or (status='RUNNING' and updated_at < date_sub(now(), interval 30 second))
                 order by updated_at
                 limit 50
                """, (rs, i) -> rs.getLong(1));

        for (Long taskId : taskIds) {
            expenseAgentService.resume(taskId);
        }
    }
}
