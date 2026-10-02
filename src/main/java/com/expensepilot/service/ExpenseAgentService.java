package com.expensepilot.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.expensepilot.coordination.TaskLeaseService;
import com.expensepilot.graph.TaskStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static com.expensepilot.graph.ExpenseGraphKeys.*;

/**
 * Graph Runtime 的应用入口。
 *
 * <p>这里不再编排业务步骤，只负责：
 * 1. 创建业务任务；
 * 2. 给 CompiledGraph 提供初始 State / threadId；
 * 3. 人工输入后 updateState 并恢复；
 * 4. 多实例租约和异常状态处理。</p>
 */
@Service
@RequiredArgsConstructor
public class ExpenseAgentService {

    private final JdbcTemplate jdbcTemplate;
    private final CompiledGraph expenseCompiledGraph;
    private final TaskStateStore taskStateStore;
    private final TaskLeaseService taskLeaseService;

    @Qualifier("graphExecutor")
    private final Executor graphExecutor;

    public long start(String userId, String requestText) {
        long taskId = positiveId();
        String threadId = "expense-" + taskId;

        jdbcTemplate.update("""
                insert into expense_task
                (id,user_id,request_text,status,thread_id,retry_count,version)
                values (?,?,?,'CREATED',?,0,0)
                """, taskId, userId, requestText, threadId);

        Map<String, Object> initialState = Map.of(
                TASK_ID, taskId,
                USER_ID, userId,
                REQUEST_TEXT, requestText,
                TASK_STATUS, "CREATED"
        );

        RunnableConfig config = RunnableConfig.builder()
                .threadId(threadId)
                .build();

        graphExecutor.execute(() -> execute(taskId, initialState, config));
        return taskId;
    }

    /**
     * 恢复 crash/retry 场景：input=null，Graph 从 MySQL Saver 最新 checkpoint 继续。
     */
    public void resume(long taskId) {
        RunnableConfig config = config(taskId);
        graphExecutor.execute(() -> execute(taskId, null, config));
    }

    /**
     * Human-in-the-loop 恢复：先把人工输入写进 checkpoint state，再从中断点继续。
     */
    public void resume(long taskId, Map<String, Object> statePatch) {
        RunnableConfig config = config(taskId);
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
                taskStateStore.markError(taskId, "graph-resume", ex);
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

    private RunnableConfig config(long taskId) {
        return RunnableConfig.builder()
                .threadId(taskStateStore.threadId(taskId))
                .build();
    }

    private long positiveId() {
        long value = UUID.randomUUID().getMostSignificantBits();
        return value == Long.MIN_VALUE ? 0L : Math.abs(value);
    }
}
