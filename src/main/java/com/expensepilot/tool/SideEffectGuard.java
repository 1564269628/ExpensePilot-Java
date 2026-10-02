package com.expensepilot.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 副作用幂等与 UNKNOWN 对账。
 *
 * <p>关键点：不能使用“insert on duplicate key update”后继续调用外部系统，
 * 因为两个并发请求都可能越过这一层。这里让第一个请求成功 INSERT 获得执行权，
 * 后来的请求只读取已有执行记录。</p>
 */
@Component
@RequiredArgsConstructor
public class SideEffectGuard {

    private final JdbcTemplate jdbcTemplate;

    public ToolResult executeOnce(
            ToolCall call,
            String idempotencyKey,
            String requestId,
            ExpenseMcpClient client) {

        boolean owner = tryCreateExecutionRecord(call, idempotencyKey, requestId);
        if (!owner) {
            return resolveExisting(call.toolName(), idempotencyKey, requestId, client);
        }

        Map<String, Object> args = new HashMap<>(call.arguments());
        args.put("requestId", requestId);

        try {
            ToolResult result = client.invoke(call.toolName(), args);
            jdbcTemplate.update("""
                    update tool_execution_record
                    set status=?, external_business_no=?, response_json=JSON_OBJECT('code', ?), last_error=?
                    where idempotency_key=?
                    """,
                    result.success() ? "SUCCEEDED" : "FAILED",
                    result.externalBusinessNo(),
                    result.code(),
                    result.success() ? null : result.message(),
                    idempotencyKey);
            return result;
        } catch (RuntimeException timeoutOrNetworkFailure) {
            // 对副作用来说“没收到响应”不能直接判失败，更不能盲目重试。
            jdbcTemplate.update("""
                    update tool_execution_record set status='UNKNOWN',last_error=?
                    where idempotency_key=?
                    """, timeoutOrNetworkFailure.getMessage(), idempotencyKey);

            ToolResult reconciled = client.queryByRequestId(call.toolName(), requestId);
            if (reconciled.success()) {
                jdbcTemplate.update("""
                        update tool_execution_record
                        set status='SUCCEEDED',external_business_no=?,last_error=null
                        where idempotency_key=?
                        """, reconciled.externalBusinessNo(), idempotencyKey);
                return reconciled;
            }
            return new ToolResult(false, "UNKNOWN",
                    "外部系统最终状态暂时无法确认，等待恢复任务继续对账", Map.of(), null);
        }
    }

    private boolean tryCreateExecutionRecord(ToolCall call, String key, String requestId) {
        try {
            jdbcTemplate.update("""
                    insert into tool_execution_record
                    (task_id,tool_name,operation_type,idempotency_key,request_id,status,request_json)
                    values (?,?,?,?,?,'RUNNING',JSON_OBJECT())
                    """, call.taskId(), call.toolName(), call.operationType(), key, requestId);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    private ToolResult resolveExisting(
            String toolName, String key, String requestId, ExpenseMcpClient client) {
        Map<String, Object> record = jdbcTemplate.queryForMap("""
                select status,external_business_no from tool_execution_record
                where idempotency_key=?
                """, key);

        String status = String.valueOf(record.get("status"));
        if ("SUCCEEDED".equals(status)) {
            return new ToolResult(true, "IDEMPOTENT_HIT",
                    "命中已完成幂等记录，不重复调用外部系统",
                    Map.of(), (String) record.get("external_business_no"));
        }

        if ("UNKNOWN".equals(status)) {
            ToolResult queried = client.queryByRequestId(toolName, requestId);
            if (queried.success()) {
                jdbcTemplate.update("""
                        update tool_execution_record
                        set status='SUCCEEDED',external_business_no=?,last_error=null
                        where idempotency_key=?
                        """, queried.externalBusinessNo(), key);
                return queried;
            }
        }

        return new ToolResult(false, "IN_PROGRESS",
                "相同副作用请求已存在，由原执行者或恢复任务负责完成", Map.of(), null);
    }
}
