package com.expensepilot.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiMcpExpenseClientTest {

    @Test
    void decodesSpringAiMcpTextContentEnvelope() {
        SyncMcpToolCallbackProvider provider =
                mock(SyncMcpToolCallbackProvider.class);
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);

        when(definition.name()).thenReturn("search_email");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString())).thenReturn(
                mcpTextEnvelope(Map.of(
                        "success", true,
                        "code", "OK",
                        "message", "success",
                        "data", Map.of(
                                "messages",
                                List.of(Map.of("messageId", "m1"))
                        ),
                        "externalBusinessNo", null
                ))
        );
        when(provider.getToolCallbacks())
                .thenReturn(new ToolCallback[] { callback });

        SpringAiMcpExpenseClient client =
                new SpringAiMcpExpenseClient(
                        provider,
                        new ObjectMapper(),
                        new McpToolResultValidator()
                );

        ToolResult result = client.invoke(
                "search_email",
                Map.of("userId", "u1")
        );

        assertTrue(result.success());
        assertEquals("OK", result.code());
        assertTrue(result.data().containsKey("messages"));
    }

    @Test
    void mapsSideEffectToolToItsReconciliationTool() {
        SyncMcpToolCallbackProvider provider =
                mock(SyncMcpToolCallbackProvider.class);
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);

        when(definition.name())
                .thenReturn("query_expense_submission");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString())).thenReturn(
                mcpTextEnvelope(Map.of(
                        "success", true,
                        "code", "OK",
                        "message", "found",
                        "data", Map.of("status", "SUBMITTED"),
                        "externalBusinessNo", "EXP-001"
                ))
        );
        when(provider.getToolCallbacks())
                .thenReturn(new ToolCallback[] { callback });

        SpringAiMcpExpenseClient client =
                new SpringAiMcpExpenseClient(
                        provider,
                        new ObjectMapper(),
                        new McpToolResultValidator()
                );

        ToolResult result = client.queryByRequestId(
                "submit_expense_report",
                "request-1"
        );

        assertTrue(result.success());
        assertEquals("EXP-001", result.externalBusinessNo());
    }

    @Test
    void skipsNonJsonTextBeforeBusinessEnvelope() {
        SyncMcpToolCallbackProvider provider =
                mock(SyncMcpToolCallbackProvider.class);
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);

        when(definition.name()).thenReturn("search_email");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString())).thenReturn(
                mcpContentEnvelope(
                        "正在查询企业邮箱",
                        Map.of(
                                "success", true,
                                "code", "OK",
                                "message", "success",
                                "data", Map.of(),
                                "externalBusinessNo", null
                        )
                )
        );
        when(provider.getToolCallbacks())
                .thenReturn(new ToolCallback[] { callback });

        SpringAiMcpExpenseClient client =
                new SpringAiMcpExpenseClient(
                        provider,
                        new ObjectMapper(),
                        new McpToolResultValidator()
                );

        ToolResult result = client.invoke(
                "search_email",
                Map.of("userId", "u1")
        );

        assertTrue(result.success());
    }

    @Test
    void rejectsMalformedBusinessEnvelopeAsContractFailure() {
        SyncMcpToolCallbackProvider provider =
                mock(SyncMcpToolCallbackProvider.class);
        ToolCallback callback = mock(ToolCallback.class);
        ToolDefinition definition = mock(ToolDefinition.class);

        when(definition.name()).thenReturn("search_email");
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString())).thenReturn(
                mcpTextEnvelope(Map.of(
                        "success", "yes",
                        "code", "OK",
                        "message", "bad schema",
                        "data", Map.of()
                ))
        );
        when(provider.getToolCallbacks())
                .thenReturn(new ToolCallback[] { callback });

        SpringAiMcpExpenseClient client =
                new SpringAiMcpExpenseClient(
                        provider,
                        new ObjectMapper(),
                        new McpToolResultValidator()
                );

        assertThrows(
                McpContractException.class,
                () -> client.invoke(
                        "search_email",
                        Map.of("userId", "u1")
                )
        );
    }
    private String mcpTextEnvelope(
            Map<String, Object> businessPayload) {
        return mcpContentEnvelope(null, businessPayload);
    }

    private String mcpContentEnvelope(
            String leadingText,
            Map<String, Object> businessPayload) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> content = new java.util.ArrayList<>();

            if (leadingText != null) {
                content.add(Map.of(
                        "type", "text",
                        "text", leadingText
                ));
            }

            content.add(Map.of(
                    "type", "text",
                    "text", mapper.writeValueAsString(
                            businessPayload
                    )
            ));

            return mapper.writeValueAsString(content);
        }
        catch (Exception ex) {
            throw new IllegalStateException(
                    "构造测试 MCP envelope 失败",
                    ex
            );
        }
    }


}
