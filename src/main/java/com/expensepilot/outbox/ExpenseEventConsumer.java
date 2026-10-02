package com.expensepilot.outbox;

import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * MQ 消费幂等示例。
 * eventId 先落 consumed_event；重复投递命中主键后直接跳过。
 */
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "expense-events",
        consumerGroup = "expensepilot-notification-consumer",
        selectorExpression = "*"
)
public class ExpenseEventConsumer implements RocketMQListener<String> {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void onMessage(String message) {
        // 演示项目使用消息内容 hash 作为 eventId；生产环境应把 eventId 放进消息 Header/Envelope。
        String eventId = Integer.toHexString(message.hashCode());
        try {
            jdbcTemplate.update(
                    "insert into consumed_event(event_id,consumer_group) values (?,?)",
                    eventId, "expensepilot-notification-consumer"
            );
        } catch (Exception duplicate) {
            return;
        }

        // 这里接企业通知服务。若通知失败应抛异常，让 RocketMQ 进入重试/死信流程。
    }
}
