package com.expensepilot.tool;

import com.expensepilot.approval.ApprovalService;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 生产 Tool Gateway：白名单、只读重试、安全降级、审批、副作用幂等和 Trace。
 */
@Component
public class ToolGateway {

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "search_email","search_drive","query_travel",
            "parse_invoices","validate_materials",
            "check_expense_policy","build_expense_report_draft",
            "submit_expense_report","send_notification"
    );

    private static final Set<String> APPROVAL_REQUIRED =
            Set.of("SUBMIT_REPORT");

    private static final Set<String> RETRYABLE_CODES = Set.of(
            "TIMEOUT",
            "UPSTREAM_UNAVAILABLE",
            "TEMPORARY_ERROR",
            "RATE_LIMITED"
    );

    /**
     * 邮箱/网盘不可用时可以安全退化为“没有自动找到材料”，随后材料检查会进入补件。
     * 差旅、政策等关键事实不能这样降级，否则可能产生错误报销判断。
     */
    private static final Set<String> DEGRADABLE_READ_TO_EMPTY =
            Set.of("search_email", "search_drive");

    private final ExpenseMcpClient mcpClient;
    private final SideEffectGuard sideEffectGuard;
    private final ApprovalService approvalService;
    private final ToolArgumentValidator argumentValidator;
    private final ObservationRegistry observationRegistry;
    private final int maxReadRetries;
    private final long retryBackoffMs;

    public ToolGateway(
            ExpenseMcpClient mcpClient,
            SideEffectGuard sideEffectGuard,
            ApprovalService approvalService,
            ToolArgumentValidator argumentValidator,
            ObservationRegistry observationRegistry,
            @Value("${expensepilot.tool.max-read-retries:3}") int maxReadRetries,
            @Value("${expensepilot.tool.read-retry-backoff-ms:200}") long retryBackoffMs) {
        this.mcpClient = mcpClient;
        this.sideEffectGuard = sideEffectGuard;
        this.approvalService = approvalService;
        this.argumentValidator = argumentValidator;
        this.observationRegistry = observationRegistry;
        this.maxReadRetries = Math.max(1, maxReadRetries);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
    }

    public ToolResult execute(ToolCall call) {
        validate(call);

        Observation observation =
                Observation.start("expensepilot.mcp.tool", observationRegistry);
        observation.lowCardinalityKeyValue("tool.name", call.toolName());
        observation.lowCardinalityKeyValue(
                "tool.side_effect",
                Boolean.toString(call.sideEffect())
        );

        try (Observation.Scope ignored = observation.openScope()) {
            if (!call.sideEffect()) {
                return executeReadOnly(call);
            }
            return executeSideEffect(call);
        }
        catch (RuntimeException ex) {
            observation.error(ex);
            throw ex;
        }
        finally {
            observation.stop();
        }
    }

    private ToolResult executeReadOnly(ToolCall call) {
        ToolResult lastResult = null;
        RuntimeException lastException = null;

        for (int attempt = 1; attempt <= maxReadRetries; attempt++) {
            try {
                ToolResult result =
                        mcpClient.invoke(call.toolName(), call.arguments());
                lastResult = result;

                if (result.success() || !RETRYABLE_CODES.contains(result.code())) {
                    return result;
                }
            }
            catch (RuntimeException ex) {
                lastException = ex;
            }

            if (attempt < maxReadRetries) {
                sleepBackoff(attempt);
            }
        }

        if (DEGRADABLE_READ_TO_EMPTY.contains(call.toolName())) {
            String reason = lastResult != null
                    ? lastResult.code() + ": " + lastResult.message()
                    : lastException == null
                        ? "unknown"
                        : lastException.getClass().getSimpleName()
                            + ": " + lastException.getMessage();

            return new ToolResult(
                    true,
                    "DEGRADED",
                    "上游暂不可用，降级为空材料并进入后续补件判断",
                    Map.of(
                            "degraded", true,
                            "source", call.toolName(),
                            "items", List.of(),
                            "reason", reason
                    ),
                    null
            );
        }

        if (lastException != null) {
            throw lastException;
        }

        return lastResult == null
                ? new ToolResult(
                        false,
                        "UPSTREAM_UNAVAILABLE",
                        "Tool 未返回结果",
                        Map.of(),
                        null
                )
                : lastResult;
    }

    private ToolResult executeSideEffect(ToolCall call) {
        if (APPROVAL_REQUIRED.contains(call.operationType())
                && !approvalService.isApproved(
                        call.taskId(),
                        call.operationType())) {
            approvalService.request(
                    call.taskId(),
                    call.operationType()
            );
            return new ToolResult(
                    false,
                    "APPROVAL_REQUIRED",
                    "副作用操作等待人工审批",
                    Map.of(),
                    null
            );
        }

        String idempotencyKey =
                call.taskId() + ":" + call.operationType();
        String requestId = UUID.nameUUIDFromBytes(
                idempotencyKey.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        ).toString();

        return sideEffectGuard.executeOnce(
                call,
                idempotencyKey,
                requestId,
                mcpClient
        );
    }

    private void validate(ToolCall call) {
        if (!ALLOWED_TOOLS.contains(call.toolName())) {
            throw new IllegalArgumentException(
                    "Tool 不在生产允许列表: " + call.toolName());
        }
        argumentValidator.validate(call);
    }

    private void sleepBackoff(int attempt) {
        try {
            long delay = Math.min(
                    2000L,
                    retryBackoffMs * (1L << Math.min(attempt - 1, 4))
            );
            Thread.sleep(delay);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Tool 重试等待被中断",
                    ex
            );
        }
    }
}
