package com.expensepilot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 真实 Spring AI MCP 客户端实现。
 *
 * <p>底层连接由 spring-ai-starter-mcp-client-webflux 管理。
 * 本类不模拟任何邮箱、网盘、差旅或报销数据，而是调用远端生产 MCP Server。</p>
 *
 * <p>远端 MCP Tool 统一约定返回 JSON：</p>
 * <pre>
 * {
 *   "success": true,
 *   "code": "OK",
 *   "message": "success",
 *   "data": {...},
 *   "externalBusinessNo": "EXP-..."
 * }
 * </pre>
 */
@Component
@Primary
@RequiredArgsConstructor
public class SpringAiMcpExpenseClient implements ExpenseMcpClient {

    private final SyncMcpToolCallbackProvider toolCallbackProvider;
    private final ObjectMapper objectMapper;

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        ToolCallback callback = findTool(toolName);
        try {
            String input = objectMapper.writeValueAsString(arguments);
            String rawResult = callback.call(input);
            return decodeToolResult(toolName, rawResult);
        }
        catch (Exception ex) {
            throw new IllegalStateException("生产 MCP 调用失败: " + toolName, ex);
        }
    }

    @Override
    public ToolResult queryByRequestId(String toolName, String requestId) {
        String queryTool = switch (toolName) {
            case "submit_expense_report" -> "query_expense_submission";
            case "send_notification" -> "query_notification";
            default -> throw new IllegalArgumentException(
                    "该工具没有定义副作用结果回查协议: " + toolName);
        };

        return invoke(queryTool, Map.of("requestId", requestId));
    }

    public boolean hasTool(String toolName) {
        return Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .anyMatch(tool -> tool.getToolDefinition().name().equals(toolName));
    }

    private ToolCallback findTool(String toolName) {
        return Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .filter(tool -> tool.getToolDefinition().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "生产 MCP Server 未注册所需工具: " + toolName));
    }

    private ToolResult decodeToolResult(String toolName, String rawResult) throws Exception {
        JsonNode root = objectMapper.readTree(rawResult);
        JsonNode payload = extractPayload(root);

        if (!payload.isObject()) {
            throw new IllegalStateException(
                    "MCP Tool 返回值不是约定 JSON 对象: " + toolName + ", raw=" + rawResult);
        }

        boolean success = payload.path("success").asBoolean(false);
        String code = payload.path("code").asText(success ? "OK" : "ERROR");
        String message = payload.path("message").asText("");
        String businessNo = payload.path("externalBusinessNo").isMissingNode()
                || payload.path("externalBusinessNo").isNull()
                ? null
                : payload.path("externalBusinessNo").asText();

        Map<String, Object> data = new LinkedHashMap<>();
        JsonNode dataNode = payload.path("data");
        if (dataNode.isObject()) {
            data = objectMapper.convertValue(dataNode, LinkedHashMap.class);
        }

        return new ToolResult(success, code, message, data, businessNo);
    }

    /**
     * Spring AI MCP ToolCallback 返回的通常是 MCP content 数组。
     * 生产 Server 将业务 JSON 放在 TextContent.text 中，因此这里先剥 MCP envelope。
     */
    private JsonNode extractPayload(JsonNode root) throws Exception {
        if (root.isObject() && root.has("success")) {
            return root;
        }

        if (root.isArray()) {
            for (JsonNode content : root) {
                JsonNode text = content.get("text");
                if (text != null && text.isTextual()) {
                    return objectMapper.readTree(text.asText());
                }
            }
        }

        throw new IllegalStateException("无法从 MCP content 中提取业务 JSON");
    }
}
