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
 * <p>业务状态更新与 outbox_event 必须在同一个本地事务中提交，
 * 从根源上避免“DB 已成功，但 RocketMQ 消息还没发程序就崩了”的丢消息窗口。</p>
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public String markTaskSucceededAndAppendEvent(long taskId, String businessNo) {
        jdbcTemplate.update("update expense_task set status='SUCCEEDED', current_node='done', version=version+1 where id=?",
                taskId);

        String eventId = UUID.randomUUID().toString();
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "taskId", taskId,
                    "businessNo", businessNo
            ));
            jdbcTemplate.update("""
                    insert into outbox_event(event_id,aggregate_id,event_type,payload_json,status,retry_count,next_retry_at)
                    values (?,?, 'EXPENSE_SUBMITTED', CAST(? AS JSON), 'PENDING', 0, now())
                    """, eventId, String.valueOf(taskId), payload);
            return eventId;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
