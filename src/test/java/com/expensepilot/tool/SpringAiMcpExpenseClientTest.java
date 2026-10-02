package com.expensepilot.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

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
        when(callback.call(anyString())).thenReturn("""
                [
                  {
                    "type": "text",
                    "text": "{\"success\":true,\"code\":\"OK\",\"message\":\"success\",\"data\":{\"messages\":[{\"messageId\":\"m1\"}]},\"externalBusinessNo\":null}"
                  }
                ]
                """);
        when(provider.getToolCallbacks())
                .thenReturn(new ToolCallback[] { callback });

        SpringAiMcpExpenseClient client =
                new SpringAiMcpExpenseClient(
                        provider,
                        new ObjectMapper()
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
        when(callback.call(anyString())).thenReturn("""
                [
                  {
                    "type": "text",
                    "text": "{\"success\":true,\"code\":\"OK\",\"message\":\"found\",\"data\":{\"status\":\"SUBMITTED\"},\"externalBusinessNo\":\"EXP-001\"}"
                  }
                ]
                """);
        when(provider.getToolCallbacks())
                .thenReturn(new ToolCallback[] { callback });

        SpringAiMcpExpenseClient client =
                new SpringAiMcpExpenseClient(
                        provider,
                        new ObjectMapper()
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
        when(callback.call(anyString())).thenReturn("""
                [
                  {
                    "type": "text",
                    "text": "正在查询企业邮箱"
                  },
                  {
                    "type": "text",
                    "text": "{\"success\":true,\"code\":\"OK\",\"message\":\"success\",\"data\":{},\"externalBusinessNo\":null}"
                  }
                ]
                """);
        when(provider.getToolCallbacks())
                .thenReturn(new ToolCallback[] { callback });

        SpringAiMcpExpenseClient client =
                new SpringAiMcpExpenseClient(
                        provider,
                        new ObjectMapper()
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
        when(callback.call(anyString())).thenReturn("""
                [
                  {
                    "type": "text",
                    "text": "{\"success\":\"yes\",\"code\":\"OK\",\"message\":\"bad schema\",\"data\":{}}"
                  }
                ]
                """);
        when(provider.getToolCallbacks())
                .thenReturn(new ToolCallback[] { callback });

        SpringAiMcpExpenseClient client =
                new SpringAiMcpExpenseClient(
                        provider,
                        new ObjectMapper()
                );

        assertThrows(
                McpContractException.class,
                () -> client.invoke(
                        "search_email",
                        Map.of("userId", "u1")
                )
        );
    }

}
