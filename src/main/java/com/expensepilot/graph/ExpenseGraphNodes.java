package com.expensepilot.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.expensepilot.agent.PlanAuditService;
import com.expensepilot.agent.PlanOutput;
import com.expensepilot.agent.Planner;
import com.expensepilot.approval.ApprovalService;
import com.expensepilot.outbox.OutboxService;
import com.expensepilot.tool.ToolCall;
import com.expensepilot.tool.ToolGateway;
import com.expensepilot.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.expensepilot.graph.ExpenseGraphKeys.*;

/**
 * ExpensePilot 的 Graph 节点实现。
 *
 * <p>这里每个方法只负责一个业务步骤。节点之间怎么走、哪些并行、哪里中断，
 * 全部由 ExpenseGraphConfig 的 StateGraph 边定义，不再存在手写 while/switch 工作流。</p>
 */
@Component
@RequiredArgsConstructor
public class ExpenseGraphNodes {

    private final Planner planner;
    private final PlanAuditService planAuditService;
    private final ApprovalService approvalService;
    private final ToolGateway toolGateway;
    private final OutboxService outboxService;
    private final TaskStateStore taskStateStore;
    private final ObjectMapper objectMapper;

    public Map<String, Object> planner(OverAllState state) {
        long taskId = taskId(state);
        taskStateStore.update(taskId, "PLANNING", "planner");

        String request = string(state, REQUEST_TEXT);
        PlanOutput output = planner.planOutput(request);

        // Structured Output 通过确定性校验后，先写可查询的审计快照，再让 Graph 继续执行。
        planAuditService.persist(taskId, output);

        Map<String, Object> planMap = objectMapper.convertValue(output, Map.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(PLAN, planMap);
        values.put(TRIP_START, output.tripScope().startDate() == null ? "" : output.tripScope().startDate().toString());
        values.put(TRIP_END, output.tripScope().endDate() == null ? "" : output.tripScope().endDate().toString());
        values.put(TRIP_CITY, output.tripScope().city() == null ? "" : output.tripScope().city());
        values.put(CLARIFICATION_QUESTIONS, output.clarificationQuestions());
        values.put(PLANNER_ROUTE, output.needsClarification() ? "CLARIFY" : "READY");
        values.put(TASK_STATUS, output.needsClarification() ? "WAITING_INPUT" : "RUNNING");
        return values;
    }

    public Map<String, Object> requestClarification(OverAllState state) {
        long taskId = taskId(state);
        taskStateStore.update(taskId, "WAITING_INPUT", "requestClarification");
        return Map.of(
                TASK_STATUS, "WAITING_INPUT",
                WAITING_REASON, state.value(CLARIFICATION_QUESTIONS).orElse(List.of())
        );
    }

    public Map<String, Object> resolveTripRange(OverAllState state) {
        String start = string(state, TRIP_START);
        String end = string(state, TRIP_END);
        String city = string(state, TRIP_CITY);

        if (start.isBlank() || end.isBlank() || city.isBlank()) {
            throw new IllegalStateException("出差日期或城市仍不完整，不能访问生产系统");
        }

        taskStateStore.update(taskId(state), "RUNNING", "resolveTripRange");
        return Map.of(TASK_STATUS, "RUNNING");
    }

    public Map<String, Object> searchEmail(OverAllState state) {
        ToolResult result = toolGateway.execute(new ToolCall(
                taskId(state),
                string(state, USER_ID),
                "search_email",
                Map.of(
                        "userId", string(state, USER_ID),
                        "startDate", string(state, TRIP_START),
                        "endDate", string(state, TRIP_END),
                        "city", string(state, TRIP_CITY),
                        "includeAttachments", true,
                        "keywords", List.of("发票", "invoice", "行程单")
                ),
                false,
                "SEARCH_EMAIL"
        ));
        return Map.of(EMAIL_RESULT, requireSuccess("search_email", result));
    }

    public Map<String, Object> searchDrive(OverAllState state) {
        ToolResult result = toolGateway.execute(new ToolCall(
                taskId(state),
                string(state, USER_ID),
                "search_drive",
                Map.of(
                        "userId", string(state, USER_ID),
                        "startDate", string(state, TRIP_START),
                        "endDate", string(state, TRIP_END),
                        "city", string(state, TRIP_CITY),
                        "fileTypes", List.of("pdf", "png", "jpg")
                ),
                false,
                "SEARCH_DRIVE"
        ));
        return Map.of(DRIVE_RESULT, requireSuccess("search_drive", result));
    }

    public Map<String, Object> queryTravel(OverAllState state) {
        ToolResult result = toolGateway.execute(new ToolCall(
                taskId(state),
                string(state, USER_ID),
                "query_travel",
                Map.of(
                        "userId", string(state, USER_ID),
                        "startDate", string(state, TRIP_START),
                        "endDate", string(state, TRIP_END),
                        "city", string(state, TRIP_CITY)
                ),
                false,
                "QUERY_TRAVEL"
        ));
        return Map.of(TRAVEL_RESULT, requireSuccess("query_travel", result));
    }

    /**
     * 并行分支汇聚点。真正的价值是让 Graph 等三个分支全部完成后才继续。
     */
    public Map<String, Object> materialJoin(OverAllState state) {
        requireMap(state, EMAIL_RESULT);
        requireMap(state, DRIVE_RESULT);
        requireMap(state, TRAVEL_RESULT);
        return Map.of();
    }

    public Map<String, Object> parseInvoices(OverAllState state) {
        ToolResult result = toolGateway.execute(new ToolCall(
                taskId(state),
                string(state, USER_ID),
                "parse_invoices",
                Map.of(
                        "userId", string(state, USER_ID),
                        "email", requireMap(state, EMAIL_RESULT),
                        "drive", requireMap(state, DRIVE_RESULT),
                        "travel", requireMap(state, TRAVEL_RESULT)
                ),
                false,
                "PARSE_INVOICES"
        ));
        return Map.of(INVOICE_RESULT, requireSuccess("parse_invoices", result));
    }

    public Map<String, Object> checkMaterial(OverAllState state) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("userId", string(state, USER_ID));
        args.put("invoices", requireMap(state, INVOICE_RESULT));
        args.put("travel", requireMap(state, TRAVEL_RESULT));
        args.put("supplementalMaterials", state.value(SUPPLEMENTAL_MATERIALS).orElse(List.of()));

        ToolResult result = toolGateway.execute(new ToolCall(
                taskId(state),
                string(state, USER_ID),
                "validate_materials",
                args,
                false,
                "VALIDATE_MATERIALS"
        ));

        Map<String, Object> data = requireSuccess("validate_materials", result);
        boolean complete = Boolean.TRUE.equals(data.get("complete"));

        return Map.of(
                MATERIAL_RESULT, data,
                MATERIAL_ROUTE, complete ? "READY" : "MISSING"
        );
    }

    public Map<String, Object> requestSupplement(OverAllState state) {
        taskStateStore.update(taskId(state), "WAITING_MATERIAL", "requestSupplement");
        Map<String, Object> material = requireMap(state, MATERIAL_RESULT);
        return Map.of(
                TASK_STATUS, "WAITING_MATERIAL",
                WAITING_REASON, material.getOrDefault("missingItems", List.of())
        );
    }

    public Map<String, Object> checkPolicy(OverAllState state) {
        ToolResult result = toolGateway.execute(new ToolCall(
                taskId(state),
                string(state, USER_ID),
                "check_expense_policy",
                Map.of(
                        "userId", string(state, USER_ID),
                        "city", string(state, TRIP_CITY),
                        "invoices", requireMap(state, INVOICE_RESULT),
                        "travel", requireMap(state, TRAVEL_RESULT)
                ),
                false,
                "CHECK_POLICY"
        ));

        Map<String, Object> data = requireSuccess("check_expense_policy", result);
        boolean compliant = Boolean.TRUE.equals(data.get("compliant"));

        return Map.of(
                POLICY_RESULT, data,
                POLICY_ROUTE, compliant ? "PASS" : "CONFLICT"
        );
    }

    public Map<String, Object> humanApproval(OverAllState state) {
        long taskId = taskId(state);
        approvalService.request(taskId, "POLICY_EXCEPTION");
        taskStateStore.update(taskId, "WAITING_APPROVAL", "humanApproval");
        Map<String, Object> policy = requireMap(state, POLICY_RESULT);
        return Map.of(
                TASK_STATUS, "WAITING_APPROVAL",
                WAITING_REASON, policy.getOrDefault("violations", List.of())
        );
    }

    public Map<String, Object> policyApprovalDecision(OverAllState state) {
        boolean approved = booleanValue(state, POLICY_APPROVED);
        return Map.of(POLICY_DECISION_ROUTE, approved ? "APPROVED" : "REJECTED");
    }

    public Map<String, Object> generateReport(OverAllState state) {
        ToolResult result = toolGateway.execute(new ToolCall(
                taskId(state),
                string(state, USER_ID),
                "build_expense_report_draft",
                Map.of(
                        "userId", string(state, USER_ID),
                        "requestText", string(state, REQUEST_TEXT),
                        "trip", requireMap(state, TRAVEL_RESULT),
                        "invoices", requireMap(state, INVOICE_RESULT),
                        "policy", requireMap(state, POLICY_RESULT)
                ),
                false,
                "BUILD_REPORT_DRAFT"
        ));

        return Map.of(REPORT_DRAFT, requireSuccess("build_expense_report_draft", result));
    }

    public Map<String, Object> submitApproval(OverAllState state) {
        long taskId = taskId(state);
        approvalService.request(taskId, "SUBMIT_REPORT");
        taskStateStore.update(taskId, "WAITING_APPROVAL", "submitApproval");
        return Map.of(
                TASK_STATUS, "WAITING_APPROVAL",
                WAITING_REASON, "报销单已生成，等待最终提交审批"
        );
    }

    public Map<String, Object> submitDecision(OverAllState state) {
        boolean approved = booleanValue(state, SUBMIT_APPROVED);
        return Map.of(SUBMIT_DECISION_ROUTE, approved ? "APPROVED" : "REJECTED");
    }

    public Map<String, Object> submit(OverAllState state) {
        taskStateStore.update(taskId(state), "RUNNING", "submit");

        ToolResult result = toolGateway.execute(new ToolCall(
                taskId(state),
                string(state, USER_ID),
                "submit_expense_report",
                Map.of(
                        "userId", string(state, USER_ID),
                        "reportDraft", requireMap(state, REPORT_DRAFT)
                ),
                true,
                "SUBMIT_REPORT"
        ));

        if (!result.success()) {
            throw new IllegalStateException(
                    "submit_expense_report 失败: " + result.code() + " " + result.message());
        }

        Map<String, Object> submitData = new LinkedHashMap<>(result.data());
        if (result.externalBusinessNo() != null) {
            submitData.put("externalBusinessNo", result.externalBusinessNo());
        }
        return Map.of(SUBMIT_RESULT, submitData);
    }

    public Map<String, Object> outbox(OverAllState state) {
        Map<String, Object> submit = requireMap(state, SUBMIT_RESULT);
        String businessNo = String.valueOf(submit.getOrDefault("externalBusinessNo", ""));
        String eventId = outboxService.markTaskSucceededAndAppendEvent(taskId(state), businessNo);

        return Map.of(
                TASK_STATUS, "SUCCEEDED",
                "eventId", eventId
        );
    }

    public Map<String, Object> rejected(OverAllState state) {
        taskStateStore.update(taskId(state), "REJECTED", "rejected");
        return Map.of(TASK_STATUS, "REJECTED");
    }

    private long taskId(OverAllState state) {
        Object value = state.value(TASK_ID)
                .orElseThrow(() -> new IllegalStateException("Graph state 缺少 taskId"));
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private String string(OverAllState state, String key) {
        return state.value(key).map(String::valueOf).orElse("");
    }

    private boolean booleanValue(OverAllState state, String key) {
        Object value = state.value(key).orElse(false);
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requireMap(OverAllState state, String key) {
        Object value = state.value(key)
                .orElseThrow(() -> new IllegalStateException("Graph state 缺少 " + key));
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Graph state " + key + " 不是 Map");
        }
        return (Map<String, Object>) map;
    }

    private Map<String, Object> requireSuccess(String toolName, ToolResult result) {
        if (!result.success()) {
            throw new IllegalStateException(
                    toolName + " 返回失败: " + result.code() + " " + result.message());
        }
        return result.data();
    }
}
