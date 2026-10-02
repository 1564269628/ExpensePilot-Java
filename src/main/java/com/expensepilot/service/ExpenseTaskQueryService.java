package com.expensepilot.service;

import com.expensepilot.cache.TaskContextCache;
import com.expensepilot.cache.TaskStatusView;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 热点读路径：Redis -> MySQL -> 回填 Redis。
 */
@Service
@RequiredArgsConstructor
public class ExpenseTaskQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final TaskContextCache cache;

    public TaskStatusView get(long taskId) {
        return cache.get(taskId).orElseGet(() -> {
            TaskStatusView view = jdbcTemplate.query("""
                    select t.id,
                           t.user_id,
                           t.status,
                           t.current_node,
                           t.retry_count,
                           t.version,
                           t.last_error,
                           p.goal,
                           p.plan_summary,
                           t.created_at,
                           t.updated_at
                      from expense_task t
                      left join agent_plan p on p.task_id=t.id
                     where t.id=?
                    """,
                    (rs, i) -> new TaskStatusView(
                            rs.getLong("id"),
                            rs.getString("user_id"),
                            rs.getString("status"),
                            rs.getString("current_node"),
                            rs.getInt("retry_count"),
                            rs.getInt("version"),
                            rs.getString("last_error"),
                            rs.getString("goal"),
                            rs.getString("plan_summary"),
                            rs.getTimestamp("created_at").toLocalDateTime(),
                            rs.getTimestamp("updated_at").toLocalDateTime()
                    ),
                    taskId
            ).stream().findFirst().orElseThrow(
                    () -> new IllegalArgumentException("任务不存在: " + taskId)
            );

            cache.put(view);
            return view;
        });
    }
}
