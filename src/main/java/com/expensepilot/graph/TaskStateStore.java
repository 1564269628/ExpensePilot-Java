package com.expensepilot.graph;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 业务任务事实源。
 *
 * <p>Graph Checkpoint 保存“工作流如何继续”，expense_task 保存“业务当前是什么状态”。
 * 两者职责不同：恢复依赖 Graph Checkpoint，运营查询和并发 CAS 以业务表为准。</p>
 */
@Repository
@RequiredArgsConstructor
public class TaskStateStore {

    private final JdbcTemplate jdbcTemplate;

    public void update(long taskId, String status, String node) {
        jdbcTemplate.update("""
                update expense_task
                   set status=?, current_node=?, last_error=null, version=version+1
                 where id=?
                """, status, node, taskId);
    }

    public void markError(long taskId, String node, Throwable error) {
        jdbcTemplate.update("""
                update expense_task
                   set status='RETRYING', current_node=?, last_error=?, retry_count=retry_count+1,
                       version=version+1
                 where id=?
                """, node, safeMessage(error), taskId);
    }

    public String threadId(long taskId) {
        return jdbcTemplate.queryForObject(
                "select thread_id from expense_task where id=?",
                String.class,
                taskId
        );
    }

    public Optional<String> currentNode(long taskId) {
        return jdbcTemplate.query(
                "select current_node from expense_task where id=?",
                (rs, i) -> rs.getString(1),
                taskId
        ).stream().findFirst();
    }

    private String safeMessage(Throwable error) {
        String text = error.getClass().getSimpleName() + ": " + error.getMessage();
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }
}
