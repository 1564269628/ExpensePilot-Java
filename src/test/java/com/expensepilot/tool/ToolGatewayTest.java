package com.expensepilot.tool;

import com.expensepilot.approval.ApprovalService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolGatewayTest {

    @Test
    void degradesEmailOnlyAfterTransportRetriesAreExhausted() {
        ExpenseMcpClient client =
                mock(ExpenseMcpClient.class);

        when(client.invoke(
                org.mockito.ArgumentMatchers.eq("search_email"),
                anyMap()
        )).thenThrow(new McpTransportException(
                "timeout",
                new RuntimeException("socket timeout")
        ));

        ToolGateway gateway = gateway(client, 2);

        ToolResult result = gateway.execute(
                emailCall()
        );

        assertTrue(result.success());
        assertEquals("DEGRADED", result.code());

        verify(client, times(2)).invoke(
                org.mockito.ArgumentMatchers.eq("search_email"),
                anyMap()
        );
    }

    @Test
    void neverDegradesContractFailureIntoMissingMaterial() {
        ExpenseMcpClient client =
                mock(ExpenseMcpClient.class);

        when(client.invoke(
                org.mockito.ArgumentMatchers.eq("search_email"),
                anyMap()
        )).thenThrow(new McpContractException(
                "bad envelope"
        ));

        ToolGateway gateway = gateway(client, 3);

        assertThrows(
                McpContractException.class,
                () -> gateway.execute(emailCall())
        );

        verify(client, times(1)).invoke(
                org.mockito.ArgumentMatchers.eq("search_email"),
                anyMap()
        );
    }

    private ToolGateway gateway(
            ExpenseMcpClient client,
            int retries) {

        return new ToolGateway(
                client,
                mock(SideEffectGuard.class),
                mock(ApprovalService.class),
                new ToolArgumentValidator(),
                ObservationRegistry.create(),
                retries,
                0
        );
    }

    private ToolCall emailCall() {
        return new ToolCall(
                1001L,
                "employee-1",
                "search_email",
                Map.of(
                        "userId", "employee-1",
                        "startDate", "2026-09-21",
                        "endDate", "2026-09-27",
                        "city", "上海",
                        "includeAttachments", true
                ),
                false,
                "SEARCH_EMAIL"
        );
    }
}
