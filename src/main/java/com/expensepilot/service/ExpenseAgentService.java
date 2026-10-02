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
 * Graph Runtime 应用入口，只负责创建/恢复/人工输入/重新规划，不手写业务工作流。
 */
@Service
public class ExpenseAgentService {

    private static final Set<String> AUTO_RECOVERABLE = Set.of(
            "CREATED","RUNNING","RETRYING","UNKNOWN"
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

    public void resume(long taskId) {
        TaskStateStore.TaskSnapshot task = taskStateStore.require(taskId);
        if (!AUTO_RECOVERABLE.contains(task.status())) {
            throw new IllegalStateException(
                    "当前状态不允许自动恢复: " + task.status()
                            + "，必须走对应的补件/澄清/审批接口");
        }

        graphExecutor.execute(() -> {
            if ("CREATED".equals(task.status())) {
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

    /**
     * 多次 Checkpoint 续跑仍失败时，从新的 Graph thread 重新经过 Planner。
     *
     * <p>读操作允许重做；提交/通知等副作用即使再次走到，也会被 SideEffectGuard
     * 的稳定业务幂等键挡住，因此 Replan 不等于重复报销。</p>
     */
    public void replan(long taskId) {
        TaskStateStore.TaskSnapshot before = taskStateStore.require(taskId);
        if (!Set.of("RETRYING","UNKNOWN").contains(before.status())) {
            throw new IllegalStateException(
                    "只有异常任务允许 Replan: " + before.status());
        }

        String newThreadId = "expense-" + taskId
                + "-replan-" + before.retryCount()
                + "-" + UUID.randomUUID().toString().substring(0, 8);

        TaskStateStore.TaskSnapshot task =
                taskStateStore.resetForReplan(taskId, newThreadId);

        graphExecutor.execute(() -> execute(
                taskId,
                initialState(taskId, task.userId(), task.requestText()),
                config(task.threadId())
        ));
    }

    public void assertApprovalReady(long taskId, String operationType) {
        TaskStateStore.TaskSnapshot task = taskStateStore.require(taskId);
        String expectedNode = approvalNode(operationType);
        if (!"WAITING_APPROVAL".equals(task.status())
                || !expectedNode.equals(task.currentNode())) {
            throw new IllegalStateException(
                    "任务当前不允许该审批: operationType=" + operationType
                            + ", status=" + task.status()
                            + ", node=" + task.currentNode());
        }
    }

    public void resumeClarification(long taskId, Map<String, Object> patch) {
        resumeHuman(taskId, "WAITING_INPUT", "requestClarification", patch);
    }

    public void resumeSupplement(long taskId, Map<String, Object> patch) {
        resumeHuman(taskId, "WAITING_MATERIAL", "requestSupplement", patch);
    }

    public void resumeApproval(
            long taskId,
            String operationType,
            Map<String, Object> patch) {
        resumeHuman(
                taskId,
                "WAITING_APPROVAL",
                approvalNode(operationType),
                patch
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

        graphExecutor.execute(() -> {
            if (!taskLeaseService.tryAcquire(taskId)) {
                return;
            }

            try {
                // 队列等待期间任务可能已被另一个人工操作推进。
                // 拿到分布式锁后必须再次读取 MySQL 事实，不能使用排队前的旧快照。
                TaskStateStore.TaskSnapshot latest =
                        taskStateStore.require(taskId);

                if (!expectedStatus.equals(latest.status())
                        || !expectedNode.equals(latest.currentNode())) {
                    return;
                }

                RunnableConfig latestConfig =
                        config(latest.threadId());

                RunnableConfig updated = expenseCompiledGraph.updateState(
                        latestConfig,
                        statePatch,
                        null
                );

                expenseCompiledGraph.stream(
                        null,
                        updated
                ).blockLast();
            }
            catch (Throwable ex) {
                taskStateStore.markError(
                        taskId,
                        "graph-human-resume",
                        ex
                );
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

    private String approvalNode(String operationType) {
        return switch (operationType) {
            case "POLICY_EXCEPTION" -> "humanApproval";
            case "SUBMIT_REPORT" -> "submitApproval";
            default -> throw new IllegalArgumentException(
                    "未知审批类型: " + operationType);
        };
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

    private RunnableConfig config(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    private long positiveId() {
        long value = UUID.randomUUID().getMostSignificantBits();
        return value == Long.MIN_VALUE ? 0L : Math.abs(value);
    }
}
