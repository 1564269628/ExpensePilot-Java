package com.expensepilot.tool;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;

/**
 * 本地演示版 MCP。返回值模拟企业邮箱、网盘、差旅和报销系统。
 * 后续只需替换本实现，上层 ToolGateway/Agent/Checkpoint 不需要改。
 */
@Component
public class MockExpenseMcpClient implements ExpenseMcpClient {

    private final Map<String, ToolResult> externalRequests = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        return switch (toolName) {
            case "search_email" -> ToolResult.ok(Map.of("invoices", List.of("MU5123-einvoice.pdf")));
            case "search_drive" -> ToolResult.ok(Map.of("files", List.of("hotel-20260925.pdf", "taxi-01.pdf")));
            case "query_travel" -> ToolResult.ok(Map.of(
                    "city", "上海",
                    "startDate", LocalDate.now().minusDays(7).toString(),
                    "endDate", LocalDate.now().minusDays(5).toString(),
                    "orders", List.of("flight-MU5123", "hotel-8891")
            ));
            case "query_policy" -> ToolResult.ok(Map.of(
                    "hotelLimit", 600,
                    "taxiAllowed", true,
                    "currency", "CNY"
            ));
            case "submit_expense_report" -> submit(arguments);
            case "send_notification" -> ToolResult.ok(Map.of("sent", true));
            default -> new ToolResult(false, "TOOL_NOT_FOUND", "未知 MCP Tool: " + toolName, Map.of(), null);
        };
    }

    private ToolResult submit(Map<String, Object> arguments) {
        String requestId = String.valueOf(arguments.get("requestId"));
        String businessNo = "EXP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        ToolResult result = new ToolResult(true, "OK", "已提交", Map.of("status", "SUBMITTED"), businessNo);
        externalRequests.put(requestId, result);
        return result;
    }

    @Override
    public ToolResult queryByRequestId(String toolName, String requestId) {
        return externalRequests.getOrDefault(requestId,
                new ToolResult(false, "NOT_FOUND", "外部系统未查询到该请求", Map.of(), null));
    }
}
