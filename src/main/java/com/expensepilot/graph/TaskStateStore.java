package com.expensepilot.graph;

import com.expensepilot.cache.TaskContextCache;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * MySQL 业务状态事实源。
 *
 * <p>Graph Checkpoint 决定“从哪里继续”，expense_task 决定“业务事实是什么”。
 * 状态迁移使用 version CAS；成功后主动失效 Redis 热点视图。</p>
 */
@Repository
@RequiredArgsConstructor
public class TaskStateStore {

    private final JdbcTemplate jdbcTemplate;
    private final TaskContextCache taskContextCache;

    public int update(long taskId, String status, String node) {
        TaskSnapshot snapshot = require(taskId);

        int affected = jdbcTemplate.update("""
                update expense_task
                   set status=?,
                       current_node=?,
                       last_error=null,
                       version=version+1
                 where id=? and version=?
                """,
                status,
                node,
                taskId,
                snapshot.version()
        );

        if (affected != 1) {
            throw new IllegalStateException(
                    "任务状态发生并发更新，CAS 失败: taskId=" + taskId
                            + ", expectedVersion=" + snapshot.version());
        }

        taskContextCache.evict(taskId);
        return snapshot.version() + 1;
    }

    /**
     * 异常标记不推进 version，避免 DB version 与最近 Graph Checkpoint 无谓错位。
     */
    public void markError(long taskId, String node, Throwable error) {
        jdbcTemplate.update("""
                update expense_task
                   set status='RETRYING',
                       current_node=?,
                       last_error=?,
                       retry_count=retry_count+1
                 where id=?
                   and status not in (
                       'WAITING_INPUT',
                       'WAITING_MATERIAL',
                       'WAITING_APPROVAL',
                       'SUCCEEDED',
                       'REJECTED'
                   )
                """,
                node,
                safeMessage(error),
                taskId
        );
        taskContextCache.evict(taskId);
    }

    public TaskSnapshot require(long taskId) {
        return jdbcTemplate.query("""
                select id,user_id,request_text,status,current_node,thread_id,version
                  from expense_task
                 where id=?
                """,
                (rs, i) -> new TaskSnapshot(
                        rs.getLong("id"),
                        rs.getString("user_id"),
                        rs.getString("request_text"),
                        rs.getString("status"),
                        rs.getString("current_node"),
                        rs.getString("thread_id"),
                        rs.getInt("version")
                ),
                taskId
        ).stream().findFirst().orElseThrow(
                () -> new IllegalArgumentException("任务不存在: " + taskId)
        );
    }

    public String threadId(long taskId) {
        return require(taskId).threadId();
    }

    public Optional<String> currentNode(long taskId) {
        return Optional.ofNullable(require(taskId).currentNode());
    }

    private String safeMessage(Throwable error) {
        String text = error.getClass().getSimpleName() + ": " + error.getMessage();
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }

    public record TaskSnapshot(
            long id,
            String userId,
            String requestText,
            String status,
            String currentNode,
            String threadId,
            int version
    ) {}
}
