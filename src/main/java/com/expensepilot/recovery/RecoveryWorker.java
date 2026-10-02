package com.expensepilot.recovery;

import com.expensepilot.service.ExpenseAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Graph 故障恢复扫描器。
 *
 * <p>CREATED 也需要扫描，覆盖“数据库已创建任务但还没来得及首次调用 Graph 就崩溃”的窗口。
 * WAITING_* 不扫描，因为那是正常的人类等待状态，不是机器故障。</p>
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
                expenseAgentService.resume(taskId);
            }
            catch (IllegalStateException ignoredRace) {
                // 扫描到执行之间状态可能已变化；下一轮以 MySQL 最新事实为准。
            }
        }
    }
}
