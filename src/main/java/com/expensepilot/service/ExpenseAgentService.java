package com.expensepilot.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.expensepilot.coordination.TaskLeaseService;
import com.expensepilot.graph.TaskStateStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    /**
     * 创建任务使用 API Idempotency-Key 做请求级幂等。
     *
     * <p>只保存 key 的 SHA-256，不把客户端原始 token 写入数据库。
     * 同一用户重复提交同一个 key 返回原 taskId；同 key 换请求内容则拒绝。</p>
     */
    public long start(
            String userId,
            String requestText,
            String clientRequestId) {

        String requestHash =
                clientRequestHash(clientRequestId);

        // 极小概率的随机 BIGINT 主键碰撞可以重新生成；
        // 真正的 API 重试则会命中 user_id + client_request_hash 唯一索引。
        for (int attempt = 0; attempt < 3; attempt++) {
            long taskId = positiveId();
            String threadId = "expense-" + taskId;

            try {
                jdbcTemplate.update("""
                        insert into expense_task(
                            id,
                            user_id,
                            client_request_hash,
                            request_text,
                            status,
                            thread_id,
                            retry_count,
                            version
                        )
                        values (
                            ?,?,?,
                            ?,
                            'CREATED',
                            ?,
                            0,
                            0
                        )
                        """,
                        taskId,
                        userId,
                        requestHash,
                        requestText,
                        threadId
                );

                scheduleRecoverable(() -> execute(
                        taskId,
                        initialState(
                                taskId,
                                userId,
                                requestText
                        ),
                        config(threadId)
                ));

                return taskId;
            }
            catch (DuplicateKeyException duplicate) {
                ExistingCreate existing =
                        findExistingCreate(
                                userId,
                                requestHash
                        );

                if (existing != null) {
                    if (!existing.requestText()
                            .equals(requestText)) {
                        throw new IllegalStateException(
                                "同一个 Idempotency-Key 不能复用于不同 requestText");
                    }

                    return existing.taskId();
                }

                // 没查到 requestHash，说明更可能是随机 taskId 主键碰撞，继续生成。
            }
        }

        throw new IllegalStateException(
                "任务 ID 连续冲突，无法创建报销任务");
    }

    private ExistingCreate findExistingCreate(
            String userId,
            String requestHash) {

        return jdbcTemplate.query("""
                select id, request_text
                  from expense_task
                 where user_id=?
                   and client_request_hash=?
                """,
                (rs, i) -> new ExistingCreate(
                        rs.getLong("id"),
                        rs.getString("request_text")
                ),
                userId,
                requestHash
        ).stream().findFirst().orElse(null);
    }

    private String clientRequestHash(
            String clientRequestId) {

        if (clientRequestId == null) {
            throw new IllegalArgumentException(
                    "Idempotency-Key 不能为空");
        }

        String normalized = clientRequestId.trim();
        if (normalized.length() < 8
                || normalized.length() > 256) {
            throw new IllegalArgumentException(
                    "Idempotency-Key 长度必须在 8~256 之间");
        }

        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(
                            normalized.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    )
            );
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "JVM 不支持 SHA-256",
                    impossible
            );
        }
    }

    private record ExistingCreate(
            long taskId,
            String requestText
    ) {}

    public void resume(long taskId) {
        TaskStateStore.TaskSnapshot task = taskStateStore.require(taskId);
        if (!AUTO_RECOVERABLE.contains(task.status())) {
            throw new IllegalStateException(
                    "当前状态不允许自动恢复: " + task.status()
                            + "，必须走对应的补件/澄清/审批接口");
        }

        scheduleRecoverable(() -> {
            if ("CREATED".equals(task.status())) {
                execute(
                        taskId,
                        initialState(taskId, task.userId(), task.requestText()),
                        config(task.threadId())
                );
            }
            else {
                // Spring AI Alibaba Graph 1.1.2.2 明确要求 resume metadata；
                // 否则只会复用持久化 state，却从 START 重新执行。
                execute(
                        taskId,
                        null,
                        config(task.threadId()).withResume()
                );
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

        scheduleRecoverable(() -> execute(
                taskId,
                initialState(taskId, task.userId(), task.requestText()),
                config(task.threadId())
        ));
    }

    public void resumeClarification(
            long taskId,
            Map<String, Object> patch) {

        resumeHuman(
                taskId,
                "WAITING_INPUT",
                "requestClarification",
                patch,
                false
        );
    }

    public void resumeSupplement(
            long taskId,
            Map<String, Object> patch) {

        resumeHuman(
                taskId,
                "WAITING_MATERIAL",
                "requestSupplement",
                patch,
                false
        );
    }

    /**
     * 审批结果已经持久化在 approval_record，所以恢复投递本身可以安全重试。
     * graphExecutor 饱和时不丢决定，由 RecoveryWorker 继续扫描。
     */
    public void resumeApproval(
            long taskId,
            String operationType,
            boolean approved,
            String approver) {

        Map<String, Object> patch =
                approvalPatch(
                        operationType,
                        approved,
                        approver
                );

        resumeHuman(
                taskId,
                "WAITING_APPROVAL",
                approvalNode(operationType),
                patch,
                true
        );
    }

    private void resumeHuman(
            long taskId,
            String expectedStatus,
            String expectedNode,
            Map<String, Object> statePatch,
            boolean inputPersisted) {

        TaskStateStore.TaskSnapshot task = taskStateStore.require(taskId);
        if (!expectedStatus.equals(task.status())
                || !expectedNode.equals(task.currentNode())) {

            // 审批决定是持久化事实：相同请求重试时 Graph 可能已经被第一次请求推进。
            if (inputPersisted) {
                return;
            }

            throw new IllegalStateException(
                    "任务不在预期 Human-in-the-loop 中断点: status="
                            + task.status()
                            + ", node="
                            + task.currentNode());
        }

        Runnable resumeAction = () -> {
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

                // updateState 只写入 checkpoint state；真正从 checkpoint.nextNode
                // 继续仍需要 withResume()，否则会从 START 重新进入 Planner。
                RunnableConfig resumeConfig =
                        updated.withResume();

                expenseCompiledGraph.stream(
                        null,
                        resumeConfig
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
        };

        if (inputPersisted) {
            // 审批决定已在 MySQL，可由 RecoveryWorker 重新发现。
            scheduleRecoverable(resumeAction);
        }
        else {
            // 澄清/补件 patch 尚未持久化，不能静默吞掉线程池拒绝。
            // 让 TaskRejectedException 冒泡为 503，客户端明确重试。
            graphExecutor.execute(resumeAction);
        }
    }

    private Map<String, Object> approvalPatch(
            String operationType,
            boolean approved,
            String approver) {

        Map<String, Object> patch =
                new java.util.LinkedHashMap<>();

        patch.put("lastApprover", approver);

        switch (operationType) {
            case "POLICY_EXCEPTION" ->
                    patch.put(
                            "policyApproved",
                            approved
                    );
            case "SUBMIT_REPORT" ->
                    patch.put(
                            "submitApproved",
                            approved
                    );
            default -> throw new IllegalArgumentException(
                    "未知审批类型: " + operationType);
        }

        return patch;
    }

    /**
     * 创建/机器恢复/Replan/已持久化审批都有 MySQL 状态可重新发现。
     * 如果 graphExecutor 饱和，保持当前可恢复状态，由 RecoveryWorker 后续再次调度。
     */
    private void scheduleRecoverable(Runnable runnable) {
        try {
            graphExecutor.execute(runnable);
        }
        catch (TaskRejectedException saturated) {
            // 故意不把任务标 FAILED：
            // CREATED/RETRYING/UNKNOWN 会继续被 RecoveryWorker 扫描。
        }
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
