package com.expensepilot.service;

import com.expensepilot.agent.*;
import com.expensepilot.domain.TaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * API 与 Graph Runtime 之间的应用服务。
 * create 只快速落任务并异步启动，避免 HTTP 线程陪着 Agent 跑完整个长流程。
 */
@Service
@RequiredArgsConstructor
public class ExpenseAgentService {

    private final JdbcTemplate jdbcTemplate;
    private final Planner planner;
    private final PlanValidator planValidator;
    private final ExpenseWorkflowRuntime workflowRuntime;

    public long start(String userId, String requestText) {
        long taskId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        String threadId = "expense-" + taskId;
        jdbcTemplate.update("""
                insert into expense_task
                (id,user_id,request_text,status,thread_id,retry_count,version)
                values (?,?,?,'CREATED',?,0,0)
                """, taskId, userId, requestText, threadId);

        List<PlannedTask> plan = planner.plan(requestText);
        planValidator.validate(plan);

        AgentState state = AgentState.builder()
                .taskId(taskId)
                .userId(userId)
                .requestText(requestText)
                .taskStatus(TaskStatus.PLANNING)
                .plan(plan)
                .build();

        CompletableFuture.runAsync(() -> workflowRuntime.run(state));
        return taskId;
    }

    public void resume(long taskId) {
        CompletableFuture.runAsync(() -> workflowRuntime.resume(taskId));
    }
}
