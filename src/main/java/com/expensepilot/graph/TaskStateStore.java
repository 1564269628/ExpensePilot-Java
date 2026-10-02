package com.expensepilot.graph;

import com.expensepilot.cache.TaskContextCache;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * MySQL 业务状态事实源。
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
                   set status=?, current_node=?, last_error=null, version=version+1
                 where id=? and version=?
                """, status, node, taskId, snapshot.version());

        if (affected != 1) {
            throw new IllegalStateException(
                    "任务状态发生并发更新，CAS 失败: taskId=" + taskId
                            + ", expectedVersion=" + snapshot.version());
        }
        taskContextCache.evict(taskId);
        return snapshot.version() + 1;
    }

    /**
     * 任一主路径节点成功都代表任务取得真实进展，连续失败计数应重新开始。
     *
     * <p>这里不改 version，因为并行查询节点可能同时成功；retry_count 只是恢复预算，
     * 不是业务状态 CAS 的一部分。</p>
     */
    public void clearRetryCount(long taskId) {
        int affected = jdbcTemplate.update("""
                update expense_task
                   set retry_count=0,
                       last_error=null
                 where id=?
                   and retry_count<>0
                """,
                taskId
        );

        if (affected > 0) {
            taskContextCache.evict(taskId);
        }
    }

    public void markError(long taskId, String node, Throwable error) {
        jdbcTemplate.update("""
                update expense_task
                   set status='RETRYING',
                       current_node=?,
                       last_error=?,
                       retry_count=retry_count+1
                 where id=?
                   and status not in (
                       'WAITING_INPUT','WAITING_MATERIAL','WAITING_APPROVAL',
                       'SUCCEEDED','REJECTED','MANUAL_TAKEOVER'
                   )
                """, node, safeMessage(error), taskId);
        taskContextCache.evict(taskId);
    }

    /**
     * 连续失败后创建新的 Graph thread，从 Planner 重新生成计划。
     * retry_count 不清零，保证恢复阶梯最终能够升级到人工接管。
     */
    @Transactional
    public TaskSnapshot resetForReplan(long taskId, String newThreadId) {
        TaskSnapshot snapshot = require(taskId);
        int affected = jdbcTemplate.update("""
                update expense_task
                   set status='CREATED',
                       current_node='planner',
                       thread_id=?,
                       last_error=null,
                       version=version+1
                 where id=? and version=?
                   and status in ('RETRYING','UNKNOWN')
                """, newThreadId, taskId, snapshot.version());

        if (affected != 1) {
            throw new IllegalStateException(
                    "Replan CAS 失败，任务状态可能已变化: " + taskId);
        }

        // Replan 可能改变材料/政策/报销草稿，旧审批不能继续授权新计划。
        jdbcTemplate.update(
                "delete from approval_record where task_id=?",
                taskId
        );

        taskContextCache.evict(taskId);
        return require(taskId);
    }

    public void markManualTakeover(long taskId, String reason) {
        TaskSnapshot snapshot = require(taskId);
        int affected = jdbcTemplate.update("""
                update expense_task
                   set status='MANUAL_TAKEOVER',
                       current_node='manualTakeover',
                       last_error=?,
                       version=version+1
                 where id=? and version=?
                   and status in ('RETRYING','UNKNOWN','RUNNING')
                """, reason, taskId, snapshot.version());

        if (affected == 1) {
            taskContextCache.evict(taskId);
        }
    }

    public TaskSnapshot require(long taskId) {
        return jdbcTemplate.query("""
                select id,user_id,request_text,status,current_node,thread_id,
                       retry_count,version,last_error
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
                        rs.getInt("retry_count"),
                        rs.getInt("version"),
                        rs.getString("last_error")
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
            int retryCount,
            int version,
            String lastError
    ) {}
}
