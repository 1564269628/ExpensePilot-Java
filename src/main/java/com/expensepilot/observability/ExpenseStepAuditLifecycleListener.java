package com.expensepilot.observability;

import com.alibaba.cloud.ai.graph.GraphLifecycleListener;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.expensepilot.domain.StepType;
import com.expensepilot.graph.TaskStateStore;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 将真实 Graph 节点生命周期同步到 agent_step 审计表。
 *
 * <p>Planner 生成的 stepKey 可以变化，但 PlanValidator 保证每个主路径 StepType
 * 在当前计划里最多一条，因此执行审计按 (task_id, step_type) 更新即可。</p>
 *
 * <p>审计写失败不会反向改变业务流程。业务事实与 Graph Checkpoint 仍由
 * expense_task / MysqlSaver 负责；这里记录的是可查询的执行证据。</p>
 */
@Component
@RequiredArgsConstructor
public class ExpenseStepAuditLifecycleListener
        implements GraphLifecycleListener {

    private static final Logger log =
            LoggerFactory.getLogger(
                    ExpenseStepAuditLifecycleListener.class
            );

    private static final Map<String, StepType> NODE_TO_STEP =
            Map.of(
                    "resolveTripRange", StepType.RESOLVE_TRIP_RANGE,
                    "emailSearch", StepType.SEARCH_EMAIL,
                    "driveSearch", StepType.SEARCH_DRIVE,
                    "travelQuery", StepType.QUERY_TRAVEL,
                    "parseInvoices", StepType.PARSE_INVOICE,
                    "materialCheck", StepType.CHECK_MATERIAL,
                    "policyCheck", StepType.CHECK_POLICY,
                    "generateReport", StepType.GENERATE_REPORT,
                    "submit", StepType.SUBMIT_REPORT
            );

    private final JdbcTemplate jdbcTemplate;
    private final TaskStateStore taskStateStore;

    @Override
    public void before(
            String nodeId,
            Map<String, Object> state,
            RunnableConfig config,
            Long curTime) {

        Optional<StepType> stepType =
                stepTypeForNode(nodeId);

        if (stepType.isEmpty()) {
            return;
        }

        Long taskId = taskId(state);
        if (taskId == null) {
            log.warn(
                    "Skip step audit start because taskId is missing, node={}",
                    nodeId
            );
            return;
        }

        safeUpdate(
                taskId,
                stepType.get(),
                "RUNNING",
                null,
                null
        );
    }

    @Override
    public void after(
            String nodeId,
            Map<String, Object> state,
            RunnableConfig config,
            Long curTime) {

        Optional<StepType> stepType =
                stepTypeForNode(nodeId);

        if (stepType.isEmpty()) {
            return;
        }

        Long taskId = taskId(state);
        if (taskId == null) {
            log.warn(
                    "Skip step audit completion because taskId is missing, node={}",
                    nodeId
            );
            return;
        }

        safeUpdate(
                taskId,
                stepType.get(),
                "SUCCEEDED",
                null,
                null
        );

        safeClearRetryCount(taskId);
    }

    @Override
    public void onError(
            String nodeId,
            Map<String, Object> state,
            Throwable ex,
            RunnableConfig config) {

        Optional<StepType> stepType =
                stepTypeForNode(nodeId);

        if (stepType.isEmpty()) {
            return;
        }

        Long taskId = taskId(state);
        if (taskId == null) {
            log.warn(
                    "Skip step audit failure because taskId is missing, node={}",
                    nodeId
            );
            return;
        }

        safeUpdate(
                taskId,
                stepType.get(),
                "FAILED",
                ex == null
                        ? "UNKNOWN"
                        : ex.getClass().getSimpleName(),
                safeMessage(ex)
        );
    }

    static Optional<StepType> stepTypeForNode(
            String nodeId) {
        return Optional.ofNullable(
                NODE_TO_STEP.get(nodeId)
        );
    }

    private void safeUpdate(
            long taskId,
            StepType stepType,
            String status,
            String errorCode,
            String errorMessage) {

        try {
            int affected;

            if ("RUNNING".equals(status)) {
                affected = jdbcTemplate.update("""
                        update agent_step
                           set status='RUNNING',
                               started_at=coalesce(started_at, now()),
                               finished_at=null,
                               error_code=null,
                               error_message=null
                         where task_id=?
                           and step_type=?
                        """,
                        taskId,
                        stepType.name()
                );
            }
            else if ("SUCCEEDED".equals(status)) {
                affected = jdbcTemplate.update("""
                        update agent_step
                           set status='SUCCEEDED',
                               finished_at=now(),
                               error_code=null,
                               error_message=null
                         where task_id=?
                           and step_type=?
                        """,
                        taskId,
                        stepType.name()
                );
            }
            else {
                affected = jdbcTemplate.update("""
                        update agent_step
                           set status='FAILED',
                               finished_at=now(),
                               error_code=?,
                               error_message=?
                         where task_id=?
                           and step_type=?
                        """,
                        errorCode,
                        errorMessage,
                        taskId,
                        stepType.name()
                );
            }

            if (affected == 0) {
                log.warn(
                        "No agent_step row matched audit update, taskId={}, stepType={}, status={}",
                        taskId,
                        stepType,
                        status
                );
            }
        }
        catch (RuntimeException auditFailure) {
            log.warn(
                    "Failed to update agent_step audit, taskId={}, stepType={}, status={}",
                    taskId,
                    stepType,
                    status,
                    auditFailure
            );
        }
    }

    private void safeClearRetryCount(long taskId) {
        try {
            taskStateStore.clearRetryCount(taskId);
        }
        catch (RuntimeException failure) {
            // 审计/恢复预算维护不能反向破坏 Graph 主业务执行。
            log.warn(
                    "Failed to clear retry_count after successful step, taskId={}",
                    taskId,
                    failure
            );
        }
    }

    private Long taskId(Map<String, Object> state) {
        Object value = state.get("taskId");

        if (value instanceof Number number) {
            return number.longValue();
        }

        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            }
            catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    private String safeMessage(Throwable ex) {
        if (ex == null) {
            return "unknown";
        }

        String message = ex.getMessage() == null
                ? ex.getClass().getSimpleName()
                : ex.getClass().getSimpleName()
                    + ": "
                    + ex.getMessage();

        return message.length() > 2000
                ? message.substring(0, 2000)
                : message;
    }
}
