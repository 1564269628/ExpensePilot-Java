package com.expensepilot.outbox;

import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Outbox 发布器。
 *
 * <p>采用至少一次投递：只有 MQ send 成功后才标 SENT；失败保持 PENDING，
 * 下轮继续扫描。重复消息由消费端 eventId 幂等解决。</p>
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final JdbcTemplate jdbcTemplate;
    private final RocketMQTemplate rocketMQTemplate;

    @Scheduled(fixedDelay = 2000)
    public void publishPending() {
        var events = jdbcTemplate.query("""
                select event_id,event_type,payload_json,retry_count
                from outbox_event
                where status='PENDING' and (next_retry_at is null or next_retry_at<=now())
                order by id limit 100
                """, (rs, i) -> new Event(
                rs.getString("event_id"),
                rs.getString("event_type"),
                rs.getString("payload_json"),
                rs.getInt("retry_count")
        ));

        for (Event event : events) {
            try {
                rocketMQTemplate.syncSend("expense-events:" + event.type(), event.payload());
                jdbcTemplate.update("update outbox_event set status='SENT',sent_at=now() where event_id=?",
                        event.id());
            } catch (Exception ex) {
                int retry = event.retryCount() + 1;
                // 简化的指数退避：2^retry 秒，上限 60 秒。
                int delay = Math.min(60, 1 << Math.min(retry, 6));
                jdbcTemplate.update("""
                        update outbox_event
                        set retry_count=?, next_retry_at=date_add(now(), interval ? second)
                        where event_id=?
                        """, retry, delay, event.id());
            }
        }
    }

    private record Event(String id, String type, String payload, int retryCount) {}
}
