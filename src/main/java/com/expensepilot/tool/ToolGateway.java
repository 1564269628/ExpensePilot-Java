package com.expensepilot.tool;

import com.expensepilot.approval.ApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * 所有生产工具调用的统一治理入口。
 */
@Component
@RequiredArgsConstructor
public class ToolGateway {

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "search_email",
            "search_drive",
            "query_travel",
            "parse_invoices",
            "validate_materials",
            "query_policy",
            "check_expense_policy",
            "build_expense_report_draft",
            "submit_expense_report",
            "query_expense_submission",
            "send_notification",
            "query_notification"
    );

    private static final Set<String> APPROVAL_REQUIRED = Set.of("SUBMIT_REPORT");

    private final ExpenseMcpClient mcpClient;
    private final SideEffectGuard sideEffectGuard;
    private final ApprovalService approvalService;

    public ToolResult execute(ToolCall call) {
        if (!ALLOWED_TOOLS.contains(call.toolName())) {
            throw new IllegalArgumentException("Tool 不在生产允许列表: " + call.toolName());
        }
        if (call.arguments() == null) {
            throw new IllegalArgumentException("Tool arguments 不能为空");
        }

        if (!call.sideEffect()) {
            return mcpClient.invoke(call.toolName(), call.arguments());
        }

        if (APPROVAL_REQUIRED.contains(call.operationType())
                && !approvalService.isApproved(call.taskId(), call.operationType())) {
            approvalService.request(call.taskId(), call.operationType());
            return new ToolResult(
                    false,
                    "APPROVAL_REQUIRED",
                    "副作用操作等待人工审批",
                    java.util.Map.of(),
                    null
            );
        }

        String idempotencyKey = call.taskId() + ":" + call.operationType();
        String requestId = UUID.nameUUIDFromBytes(idempotencyKey.getBytes()).toString();
        return sideEffectGuard.executeOnce(
                call,
                idempotencyKey,
                requestId,
                mcpClient
        );
    }
}
