package com.expensepilot.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
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
 * 传输失败与返回契约失败使用不同异常类型，避免把服务端 Schema 错误误判为
 * “没找到材料”后静默降级。</p>
 */
@Component
@Primary
@RequiredArgsConstructor
public class SpringAiMcpExpenseClient implements ExpenseMcpClient {

    private final SyncMcpToolCallbackProvider toolCallbackProvider;
    private final ObjectMapper objectMapper;

    @Override
    public ToolResult invoke(
            String toolName,
            Map<String, Object> arguments) {

        ToolCallback callback = findTool(toolName);

        final String input;
        try {
            input = objectMapper.writeValueAsString(arguments);
        }
        catch (JsonProcessingException ex) {
            throw new McpContractException(
                    "MCP 调用参数无法序列化: " + toolName,
                    ex
            );
        }

        final String rawResult;
        try {
            rawResult = callback.call(input);
        }
        catch (RuntimeException ex) {
            throw new McpTransportException(
                    "生产 MCP 调用失败: " + toolName,
                    ex
            );
        }

        return decodeToolResult(
                toolName,
                rawResult
        );
    }

    @Override
    public ToolResult queryByRequestId(
            String toolName,
            String requestId) {

        String queryTool = switch (toolName) {
            case "submit_expense_report" ->
                    "query_expense_submission";
            case "send_notification" ->
                    "query_notification";
            default -> throw new IllegalArgumentException(
                    "该工具没有定义副作用结果回查协议: "
                            + toolName);
        };

        return invoke(
                queryTool,
                Map.of("requestId", requestId)
        );
    }

    public boolean hasTool(String toolName) {
        return Arrays.stream(
                        toolCallbackProvider.getToolCallbacks()
                )
                .anyMatch(tool ->
                        tool.getToolDefinition()
                                .name()
                                .equals(toolName));
    }

    private ToolCallback findTool(String toolName) {
        return Arrays.stream(
                        toolCallbackProvider.getToolCallbacks()
                )
                .filter(tool ->
                        tool.getToolDefinition()
                                .name()
                                .equals(toolName))
                .findFirst()
                .orElseThrow(() ->
                        new McpContractException(
                                "生产 MCP Server 未注册所需工具: "
                                        + toolName));
    }

    private ToolResult decodeToolResult(
            String toolName,
            String rawResult) {

        final JsonNode root;
        try {
            root = objectMapper.readTree(rawResult);
        }
        catch (JsonProcessingException ex) {
            throw new McpContractException(
                    "MCP Tool 返回的 content 不是合法 JSON: "
                            + toolName,
                    ex
            );
        }

        JsonNode payload = extractPayload(
                toolName,
                root
        );

        JsonNode successNode = payload.get("success");
        JsonNode codeNode = payload.get("code");
        JsonNode messageNode = payload.get("message");
        JsonNode dataNode = payload.get("data");

        if (successNode == null
                || !successNode.isBoolean()) {
            throw contractError(
                    toolName,
                    "success 必须是 boolean"
            );
        }
        if (codeNode == null
                || !codeNode.isTextual()
                || codeNode.asText().isBlank()) {
            throw contractError(
                    toolName,
                    "code 必须是非空字符串"
            );
        }
        if (messageNode == null
                || !messageNode.isTextual()) {
            throw contractError(
                    toolName,
                    "message 必须是字符串"
            );
        }
        if (dataNode == null
                || !dataNode.isObject()) {
            throw contractError(
                    toolName,
                    "data 必须是对象"
            );
        }

        boolean success = successNode.asBoolean();
        String code = codeNode.asText();
        String message = messageNode.asText();

        String businessNo =
                payload.path("externalBusinessNo")
                        .isMissingNode()
                        || payload.path("externalBusinessNo")
                        .isNull()
                        ? null
                        : payload.path("externalBusinessNo")
                                .asText();

        Map<String, Object> data =
                objectMapper.convertValue(
                        dataNode,
                        LinkedHashMap.class
                );

        return new ToolResult(
                success,
                code,
                message,
                data,
                businessNo
        );
    }

    /**
     * Spring AI 1.1.2 的 SyncMcpToolCallback 会把 CallToolResult.content()
     * 序列化成 JSON 数组。生产业务 JSON 放在 TextContent.text 中。
     *
     * <p>某些 Server 可能同时返回解释性文本和业务 JSON，所以这里会跳过非 JSON /
     * 非业务 Envelope 的文本块，直到找到带 success 字段的对象。</p>
     */
    private JsonNode extractPayload(
            String toolName,
            JsonNode root) {

        if (isBusinessEnvelope(root)) {
            return root;
        }

        if (root != null && root.isArray()) {
            for (JsonNode content : root) {
                JsonNode text = content.get("text");

                if (text == null || !text.isTextual()) {
                    continue;
                }

                try {
                    JsonNode candidate =
                            objectMapper.readTree(
                                    text.asText()
                            );

                    if (isBusinessEnvelope(candidate)) {
                        return candidate;
                    }
                }
                catch (JsonProcessingException ignored) {
                    // 允许 MCP content 中包含普通解释文本；继续找真正的业务 JSON。
                }
            }
        }

        throw contractError(
                toolName,
                "无法从 MCP content 中提取业务 JSON Envelope"
        );
    }

    private boolean isBusinessEnvelope(JsonNode node) {
        return node != null
                && node.isObject()
                && node.has("success");
    }

    private McpContractException contractError(
            String toolName,
            String detail) {
        return new McpContractException(
                "MCP Tool 契约错误: "
                        + toolName
                        + " - "
                        + detail
        );
    }
}
