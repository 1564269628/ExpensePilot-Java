package com.expensepilot.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * 副作用操作的核心保护。
 *
 * <p>数据库唯一索引是最终防线。即使 JVM 重启或同一 Task 被重复调度，
 * 相同 idempotencyKey 也只能成功创建一条执行记录。</p>
 */
@Component
@RequiredArgsConstructor
public class SideEffectGuard {

    private final JdbcTemplate jdbcTemplate;

    @Transactional
    public ToolResult executeOnce(
            ToolCall call,
            String idempotencyKey,
            String requestId,
            ExpenseMcpClient client) {

        var old = jdbcTemplate.query(
                "select status, external_business_no, response_json from tool_execution_record where idempotency_key=?",
                (rs, i) -> Map.of(
                        "status", rs.getString("status"),
                        "businessNo", rs.getString("external_business_no") == null ? "" : rs.getString("external_business_no")
                ),
                idempotencyKey
        );
        if (!old.isEmpty() && "SUCCEEDED".equals(old.getFirst().get("status"))) {
            return new ToolResult(true, "IDEMPOTENT_HIT", "命中幂等记录，不重复执行",
                    Map.of(), String.valueOf(old.getFirst().get("businessNo")));
        }

        jdbcTemplate.update("""
                insert into tool_execution_record
                (task_id,tool_name,operation_type,idempotency_key,request_id,status,request_json)
                values (?,?,?,?,?,'RUNNING',JSON_OBJECT())
                on duplicate key update updated_at=now()
                """, call.taskId(), call.toolName(), call.operationType(), idempotencyKey, requestId);

        Map<String, Object> args = new HashMap<>(call.arguments());
        args.put("requestId", requestId);
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
                idempotencyKey
        );
        return result;
    }
}
