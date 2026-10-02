package com.expensepilot.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 副作用幂等、并发认领与 UNKNOWN 对账。
 *
 * <p>唯一索引决定谁拥有第一次执行权；明确失败允许同 requestId 重新认领；
 * 网络异常进入 UNKNOWN，必须先向外部系统回查，禁止盲目重试。</p>
 */
@Component
@RequiredArgsConstructor
public class SideEffectGuard {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ToolResult executeOnce(
            ToolCall call,
            String idempotencyKey,
            String requestId,
            ExpenseMcpClient client) {

        Map<String, Object> arguments =
                new HashMap<>(call.arguments());
        arguments.put("requestId", requestId);

        boolean owner = tryCreateExecutionRecord(
                call,
                idempotencyKey,
                requestId,
                arguments
        );

        if (owner) {
            return invokeOwned(
                    call,
                    idempotencyKey,
                    requestId,
                    arguments,
                    client
            );
        }

        return resolveExisting(
                call,
                idempotencyKey,
                requestId,
                arguments,
                client
        );
    }

    private ToolResult invokeOwned(
            ToolCall call,
            String key,
            String requestId,
            Map<String, Object> arguments,
            ExpenseMcpClient client) {

        try {
            ToolResult result =
                    client.invoke(call.toolName(), arguments);

            jdbcTemplate.update("""
                    update tool_execution_record
                       set status=?,
                           external_business_no=?,
                           response_json=cast(? as json),
                           last_error=?
                     where idempotency_key=?
                    """,
                    result.success() ? "SUCCEEDED" : "FAILED",
                    result.externalBusinessNo(),
                    toJson(result),
                    result.success() ? null : result.message(),
                    key
            );
            return result;
        }
        catch (RuntimeException transportFailure) {
            jdbcTemplate.update("""
                    update tool_execution_record
                       set status='UNKNOWN', last_error=?
                     where idempotency_key=?
                    """,
                    safeMessage(transportFailure),
                    key
            );

            return reconcileUnknown(
                    call.toolName(),
                    key,
                    requestId,
                    client
            );
        }
    }

    private ToolResult resolveExisting(
            ToolCall call,
            String key,
            String requestId,
            Map<String, Object> arguments,
            ExpenseMcpClient client) {

        Map<String, Object> record = jdbcTemplate.queryForMap("""
                select status,external_business_no
                  from tool_execution_record
                 where idempotency_key=?
                """, key);

        String status = String.valueOf(record.get("status"));

        if ("SUCCEEDED".equals(status)) {
            return new ToolResult(
                    true,
                    "IDEMPOTENT_HIT",
                    "命中已完成幂等记录，不重复调用外部系统",
                    Map.of(),
                    (String) record.get("external_business_no")
            );
        }

        if ("UNKNOWN".equals(status)) {
            return reconcileUnknown(
                    call.toolName(),
                    key,
                    requestId,
                    client
            );
        }

        if ("FAILED".equals(status)) {
            int claimed = jdbcTemplate.update("""
                    update tool_execution_record
                       set status='RUNNING', last_error=null
                     where idempotency_key=? and status='FAILED'
                    """, key);

            if (claimed == 1) {
                return invokeOwned(
                        call,
                        key,
                        requestId,
                        arguments,
                        client
                );
            }
        }

        return new ToolResult(
                false,
                "IN_PROGRESS",
                "相同副作用请求已被其他执行者认领",
                Map.of(),
                null
        );
    }

    private ToolResult reconcileUnknown(
            String toolName,
            String key,
            String requestId,
            ExpenseMcpClient client) {
        try {
            ToolResult queried =
                    client.queryByRequestId(toolName, requestId);

            if (queried.success()) {
                jdbcTemplate.update("""
                        update tool_execution_record
                           set status='SUCCEEDED',
                               external_business_no=?,
                               response_json=cast(? as json),
                               last_error=null
                         where idempotency_key=?
                        """,
                        queried.externalBusinessNo(),
                        toJson(queried),
                        key
                );
                return queried;
            }

            // UNKNOWN 状态下即使暂时查询不到，也不直接重做副作用。
            return new ToolResult(
                    false,
                    "UNKNOWN",
                    "外部最终状态尚未确认，等待下一次恢复继续对账",
                    Map.of(),
                    null
            );
        }
        catch (RuntimeException queryFailure) {
            return new ToolResult(
                    false,
                    "UNKNOWN",
                    "外部结果查询失败，保持 UNKNOWN: "
                            + safeMessage(queryFailure),
                    Map.of(),
                    null
            );
        }
    }

    private boolean tryCreateExecutionRecord(
            ToolCall call,
            String key,
            String requestId,
            Map<String, Object> arguments) {
        try {
            jdbcTemplate.update("""
                    insert into tool_execution_record(
                        task_id,tool_name,operation_type,
                        idempotency_key,request_id,status,request_json
                    )
                    values (?,?,?,?,?,'RUNNING',cast(? as json))
                    """,
                    call.taskId(),
                    call.toolName(),
                    call.operationType(),
                    key,
                    requestId,
                    toJson(arguments)
            );
            return true;
        }
        catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Tool 审计 JSON 序列化失败",
                    ex
            );
        }
    }

    private String safeMessage(Throwable ex) {
        String message = ex.getMessage() == null
                ? ex.getClass().getSimpleName()
                : ex.getClass().getSimpleName() + ": " + ex.getMessage();
        return message.length() > 2000
                ? message.substring(0, 2000)
                : message;
    }
}
