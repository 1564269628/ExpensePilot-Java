package com.expensepilot.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * 所有工具调用统一经过 Gateway：
 * 参数校验 -> 权限/允许列表 -> 副作用审批与幂等 -> 超时/审计 -> MCP。
 */
@Component
@RequiredArgsConstructor
public class ToolGateway {

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "search_email", "search_drive", "query_travel", "query_policy",
            "submit_expense_report", "send_notification"
    );

    private final ExpenseMcpClient mcpClient;
    private final SideEffectGuard sideEffectGuard;

    public ToolResult execute(ToolCall call) {
        if (!ALLOWED_TOOLS.contains(call.toolName())) {
            throw new IllegalArgumentException("Tool 不在允许列表: " + call.toolName());
        }
        if (call.arguments() == null) {
            throw new IllegalArgumentException("Tool arguments 不能为空");
        }

        if (!call.sideEffect()) {
            return mcpClient.invoke(call.toolName(), call.arguments());
        }

        String idempotencyKey = call.taskId() + ":" + call.operationType();
        String requestId = UUID.nameUUIDFromBytes(idempotencyKey.getBytes()).toString();
        return sideEffectGuard.executeOnce(call, idempotencyKey, requestId, mcpClient);
    }
}
