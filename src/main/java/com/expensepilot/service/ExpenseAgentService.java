package com.expensepilot.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.expensepilot.coordination.TaskLeaseService;
import com.expensepilot.graph.TaskStateStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import static com.expensepilot.graph.ExpenseGraphKeys.*;

/**
 * Graph Runtime 应用入口。
 *
 * <p>这里不编排业务节点，只负责创建、恢复、人工输入和多实例执行租约。</p>
 */
@Service
public class ExpenseAgentService {

    private static final Set<String> AUTO_RECOVERABLE = Set.of(
            "CREATED",
            "RUNNING",
            "RETRYING",
            "UNKNOWN"
    );

    private final JdbcTemplate jdbcTemplate;
    private final CompiledGraph expenseCompiledGraph;
    private final TaskStateStore taskStateStore;
    private final TaskLeaseService taskLeaseService;
    private final Executor graphExecutor;

    public ExpenseAgentService(
            JdbcTemplate jdbcTemplate,
            CompiledGraph expenseCompiledGraph,
            TaskStateStore taskStateStore,
            TaskLeaseService taskLeaseService,
            @Qualifier("graphExecutor") Executor graphExecutor) {
        this.jdbcTemplate = jdbcTemplate;
        this.expenseCompiledGraph = expenseCompiledGraph;
        this.taskStateStore = taskStateStore;
        this.taskLeaseService = taskLeaseService;
        this.graphExecutor = graphExecutor;
    }

    public long start(String userId, String requestText) {
        long taskId = positiveId();
        String threadId = "expense-" + taskId;

        jdbcTemplate.update("""
                insert into expense_task
                (id,user_id,request_text,status,thread_id,retry_count,version)
                values (?,?,?,'CREATED',?,0,0)
                """, taskId, userId, requestText, threadId);

        graphExecutor.execute(() -> execute(
                taskId,
                initialState(taskId, userId, requestText),
                config(threadId)
        ));

        return taskId;
    }

    /**
     * 仅用于机器故障恢复。
     *
     * <p>WAITING_INPUT / WAITING_MATERIAL / WAITING_APPROVAL 绝不能通过这个接口
     * “强行继续”，否则可能把缺失的人类决策当成默认值。它们必须走专用 Human API。</p>
     */
    public void resume(long taskId) {
        TaskStateStore.TaskSnapshot task = taskStateStore.require(taskId);

        if (!AUTO_RECOVERABLE.contains(task.status())) {
            throw new IllegalStateException(
                    "当前状态不允许自动恢复: " + task.status()
                            + "，必须走对应的补件/澄清/审批接口");
        }

        graphExecutor.execute(() -> {
            if ("CREATED".equals(task.status())) {
                // 解决“业务任务已 INSERT，但 JVM 在首次 graph.stream 前崩溃”的窗口。
                execute(
                        taskId,
                        initialState(taskId, task.userId(), task.requestText()),
                        config(task.threadId())
                );
            }
            else {
                execute(taskId, null, config(task.threadId()));
            }
        });
    }

    public void resumeClarification(
            long taskId,
            Map<String, Object> statePatch) {
        resumeHuman(
                taskId,
                "WAITING_INPUT",
                "requestClarification",
                statePatch
        );
    }

    public void resumeSupplement(
            long taskId,
            Map<String, Object> statePatch) {
        resumeHuman(
                taskId,
                "WAITING_MATERIAL",
                "requestSupplement",
                statePatch
        );
    }

    public void resumeApproval(
            long taskId,
            String operationType,
            Map<String, Object> statePatch) {

        String expectedNode = switch (operationType) {
            case "POLICY_EXCEPTION" -> "humanApproval";
            case "SUBMIT_REPORT" -> "submitApproval";
            default -> throw new IllegalArgumentException(
                    "未知审批类型: " + operationType);
        };

        resumeHuman(
                taskId,
                "WAITING_APPROVAL",
                expectedNode,
                statePatch
        );
    }

    private void resumeHuman(
            long taskId,
            String expectedStatus,
            String expectedNode,
            Map<String, Object> statePatch) {

        TaskStateStore.TaskSnapshot task = taskStateStore.require(taskId);

        if (!expectedStatus.equals(task.status())
                || !expectedNode.equals(task.currentNode())) {
            throw new IllegalStateException(
                    "任务不在预期 Human-in-the-loop 中断点: status="
                            + task.status() + ", node=" + task.currentNode());
        }

        RunnableConfig config = config(task.threadId());

        graphExecutor.execute(() -> {
            if (!taskLeaseService.tryAcquire(taskId)) {
                return;
            }

            try {
                RunnableConfig updated = expenseCompiledGraph.updateState(
                        config,
                        statePatch,
                        null
                );
                expenseCompiledGraph.stream(null, updated).blockLast();
            }
            catch (Throwable ex) {
                taskStateStore.markError(taskId, "graph-human-resume", ex);
            }
            finally {
                taskLeaseService.release(taskId);
            }
        });
    }

    private void execute(
            long taskId,
            Map<String, Object> input,
            RunnableConfig config) {

        if (!taskLeaseService.tryAcquire(taskId)) {
            return;
        }

        try {
            expenseCompiledGraph.stream(input, config).blockLast();
        }
        catch (Throwable ex) {
            taskStateStore.markError(taskId, "graph-runtime", ex);
        }
        finally {
            taskLeaseService.release(taskId);
        }
    }

    private Map<String, Object> initialState(
            long taskId,
            String userId,
            String requestText) {
        return Map.of(
                TASK_ID, taskId,
                USER_ID, userId,
                REQUEST_TEXT, requestText,
                TASK_STATUS, "CREATED"
        );
    }

    private RunnableConfig config(long taskId) {
        return config(taskStateStore.threadId(taskId));
    }

    private RunnableConfig config(String threadId) {
        return RunnableConfig.builder()
                .threadId(threadId)
                .build();
    }

    private long positiveId() {
        long value = UUID.randomUUID().getMostSignificantBits();
        return value == Long.MIN_VALUE ? 0L : Math.abs(value);
    }
}
