package com.expensepilot.agent;

import com.expensepilot.checkpoint.CheckpointRepository;
import com.expensepilot.coordination.TaskLeaseService;
import com.expensepilot.domain.TaskStatus;
import com.expensepilot.outbox.OutboxService;
import com.expensepilot.service.ParallelMaterialService;
import com.expensepilot.tool.*;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 主执行循环。
 *
 * <p>它体现 Planner + Executor 的“可靠执行侧”：每个关键阶段都更新事实状态并保存 Checkpoint；
 * 如果异常发生，恢复 Worker 从最新 Checkpoint 继续，而不是重头跑。</p>
 */
@Component
@RequiredArgsConstructor
public class ExpenseWorkflowRuntime {

    private final ParallelMaterialService parallelMaterialService;
    private final ToolGateway toolGateway;
    private final CheckpointRepository checkpointRepository;
    private final TaskLeaseService taskLeaseService;
    private final OutboxService outboxService;
    private final ExpenseAgentGraph graph;
    private final JdbcTemplate jdbcTemplate;
    private final ObservationRegistry observationRegistry;

    public void run(AgentState state) {
        if (!taskLeaseService.tryAcquire(state.getTaskId())) return;

        Observation observation = Observation.start("expensepilot.agent.workflow", observationRegistry);
        try (Observation.Scope ignored = observation.openScope()) {
            updateTask(state.getTaskId(), "RUNNING", "collectMaterials");
            state.setTaskStatus(TaskStatus.RUNNING);
            checkpointRepository.save(state, "before-collect");

            Map<String, Object> range = Map.of("relativeRange", "last-week");
            Map<String, ToolResult> materials =
                    parallelMaterialService.collect(state.getTaskId(), state.getUserId(), range);
            materials.forEach((k, v) -> state.mergeMaterial(k, v.data()));
            state.markCompleted("email").markCompleted("drive").markCompleted("travel");
            checkpointRepository.save(state, "collectMaterials");

            inspectMaterial(state);
            checkpointRepository.save(state, "materialCheck");
            String next = graph.routeAfterMaterialCheck(state);
            if ("requestSupplement".equals(next)) {
                state.setTaskStatus(TaskStatus.WAITING_MATERIAL);
                updateTask(state.getTaskId(), "WAITING_MATERIAL", "requestSupplement");
                checkpointRepository.save(state, "requestSupplement");
                return;
            }

            checkPolicy(state);
            checkpointRepository.save(state, "policyCheck");
            next = graph.routeAfterPolicyCheck(state);
            if ("humanApproval".equals(next)) {
                state.setTaskStatus(TaskStatus.WAITING_APPROVAL);
                updateTask(state.getTaskId(), "WAITING_APPROVAL", "humanApproval");
                checkpointRepository.save(state, "humanApproval");
                return;
            }

            submit(state);
        } catch (Exception ex) {
            state.getErrors().add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
            state.setTaskStatus(TaskStatus.RETRYING);
            updateTask(state.getTaskId(), "RETRYING", state.getCurrentNode());
            checkpointRepository.save(state, "exception");
            observation.error(ex);
        } finally {
            observation.stop();
            taskLeaseService.release(state.getTaskId());
        }
    }

    public void resume(long taskId) {
        AgentState state = checkpointRepository.loadLatest(taskId)
                .orElseThrow(() -> new IllegalArgumentException("没有可恢复的 Checkpoint: " + taskId));
        run(state);
    }

    private void inspectMaterial(AgentState state) {
        state.setCurrentNode("materialCheck");
        // 演示逻辑：只要邮箱和网盘都有材料就认为基础材料齐全。
        boolean missing = !state.getMaterials().containsKey("email") || !state.getMaterials().containsKey("drive");
        state.getContext().put("missingMaterial", missing);
        state.markCompleted("material-check");
    }

    private void checkPolicy(AgentState state) {
        state.setCurrentNode("policyCheck");
        ToolResult policy = toolGateway.execute(new ToolCall(
                state.getTaskId(), state.getUserId(), "query_policy", Map.of(), false, "QUERY_POLICY"));
        state.getToolResults().put("policy", policy.data());
        // 真实实现由解析出的酒店/交通金额与政策命中结果决定。
        state.getContext().putIfAbsent("policyConflict", false);
        state.markCompleted("policy-check");
    }

    private void submit(AgentState state) {
        state.setCurrentNode("submit");
        Map<String, Object> report = new HashMap<>();
        report.put("materials", state.getMaterials());
        report.put("policy", state.getToolResults().get("policy"));

        ToolResult result = toolGateway.execute(new ToolCall(
                state.getTaskId(),
                state.getUserId(),
                "submit_expense_report",
                report,
                true,
                "SUBMIT_REPORT"
        ));
        if (!result.success() && "APPROVAL_REQUIRED".equals(result.code())) {
            // 审批是正常的业务暂停点，不应进入异常重试。
            state.setTaskStatus(TaskStatus.WAITING_APPROVAL);
            state.setCurrentNode("humanApproval");
            updateTask(state.getTaskId(), "WAITING_APPROVAL", "humanApproval");
            checkpointRepository.save(state, "humanApproval");
            return;
        }
        if (!result.success()) {
            throw new IllegalStateException("报销提交失败: " + result.message());
        }

        state.markCompleted("submit-report");
        state.getContext().put("businessNo", result.externalBusinessNo());
        checkpointRepository.save(state, "submitted");

        // “任务成功”和“待发送事件”同一事务写入。
        outboxService.markTaskSucceededAndAppendEvent(state.getTaskId(), result.externalBusinessNo());
        state.setTaskStatus(TaskStatus.SUCCEEDED);
        state.setCurrentNode("done");
        checkpointRepository.save(state, "done");
    }

    private void updateTask(long taskId, String status, String node) {
        jdbcTemplate.update(
                "update expense_task set status=?,current_node=?,version=version+1 where id=?",
                status, node, taskId
        );
    }
}
