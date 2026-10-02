package com.expensepilot.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Transactional Outbox。
 *
 * <p>报销任务变为 SUCCEEDED 与 EXPENSE_SUBMITTED 事件插入同一个 MySQL 本地事务。
 * 这样不会出现“报销已经成功，但通知事件根本没记录下来”的中间状态。</p>
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public String markTaskSucceededAndAppendEvent(
            long taskId,
            String businessNo) {

        int affected = jdbcTemplate.update("""
                update expense_task
                   set status='SUCCEEDED',
                       current_node='outbox',
                       last_error=null,
                       version=version+1
                 where id=?
                """, taskId);

        if (affected != 1) {
            throw new IllegalStateException("任务不存在，不能写 Outbox: " + taskId);
        }

        String eventId = UUID.randomUUID().toString();

        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "taskId", taskId,
                    "businessNo", businessNo == null ? "" : businessNo
            ));

            jdbcTemplate.update("""
                    insert into outbox_event(
                        event_id,
                        aggregate_id,
                        event_type,
                        payload_json,
                        status,
                        retry_count,
                        next_retry_at
                    )
                    values (
                        ?,?,
                        'EXPENSE_SUBMITTED',
                        CAST(? AS JSON),
                        'PENDING',
                        0,
                        now()
                    )
                    """,
                    eventId,
                    String.valueOf(taskId),
                    payload
            );

            return eventId;
        }
        catch (JsonProcessingException ex) {
            // 当前方法有 @Transactional，序列化/插入任何一步失败都会回滚任务状态。
            throw new IllegalStateException("Outbox payload 序列化失败", ex);
        }
    }
}
