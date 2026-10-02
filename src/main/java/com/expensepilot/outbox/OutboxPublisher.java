package com.expensepilot.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Transactional Outbox 发布器。
 *
 * <p>本地事务只负责把业务状态和事件一起写 MySQL；本发布器负责把 PENDING
 * 事件至少一次投递到 RocketMQ。发送成功后才改 SENT，因此进程在发送前崩溃
 * 不会永久丢消息。多个实例偶尔重复投递也没关系，消费侧用原始 eventId 幂等。</p>
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final JdbcTemplate jdbcTemplate;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 2000)
    public void publishPending() {
        var events = jdbcTemplate.query("""
                select event_id,event_type,payload_json,retry_count
                  from outbox_event
                 where status='PENDING'
                   and (next_retry_at is null or next_retry_at<=now())
                 order by id
                 limit 100
                """, (rs, i) -> new Event(
                rs.getString("event_id"),
                rs.getString("event_type"),
                rs.getString("payload_json"),
                rs.getInt("retry_count")
        ));

        for (Event event : events) {
            try {
                JsonNode payload = objectMapper.readTree(event.payloadJson());
                String envelope = objectMapper.writeValueAsString(
                        new OutboxMessage(event.id(), event.type(), payload));

                rocketMQTemplate.syncSend(
                        "expense-events:" + event.type(),
                        envelope
                );

                jdbcTemplate.update("""
                        update outbox_event
                           set status='SENT', sent_at=now()
                         where event_id=? and status='PENDING'
                        """, event.id());
            }
            catch (Exception ex) {
                int retry = event.retryCount() + 1;
                int delaySeconds = Math.min(60, 1 << Math.min(retry, 6));

                jdbcTemplate.update("""
                        update outbox_event
                           set retry_count=?,
                               next_retry_at=date_add(now(), interval ? second)
                         where event_id=? and status='PENDING'
                        """, retry, delaySeconds, event.id());
            }
        }
    }

    private record Event(
            String id,
            String type,
            String payloadJson,
            int retryCount
    ) {}
}
