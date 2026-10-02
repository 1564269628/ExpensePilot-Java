package com.expensepilot.outbox;

import com.expensepilot.tool.ToolCall;
import com.expensepilot.tool.ToolGateway;
import com.expensepilot.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RocketMQ 通知消费者。
 *
 * <p>通知不是“打印日志”，而是真正调用生产 notification MCP Tool。
 * 消息系统是至少一次投递，因此这里有两层幂等：</p>
 *
 * <ol>
 *   <li>ToolGateway/SideEffectGuard 用 eventId 参与业务幂等键，保护真正的外部通知。</li>
 *   <li>外部副作用成功后再写 consumed_event，快速跳过之后的重复 MQ 投递。</li>
 * </ol>
 *
 * <p>注意不能在调用通知系统之前就写 consumed_event；否则进程在二者之间崩溃，
 * 重投消息会被误认为已经处理，通知将永久丢失。</p>
 */
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "expense-events",
        consumerGroup = "expensepilot-notification-consumer",
        selectorExpression = "EXPENSE_SUBMITTED"
)
public class ExpenseEventConsumer implements RocketMQListener<String> {

    private static final String CONSUMER_GROUP = "expensepilot-notification-consumer";

    private final JdbcTemplate jdbcTemplate;
    private final ToolGateway toolGateway;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String message) {
        try {
            OutboxMessage envelope = objectMapper.readValue(message, OutboxMessage.class);

            if (alreadyConsumed(envelope.eventId())) {
                return;
            }

            long taskId = envelope.payload().path("taskId").asLong();
            if (taskId <= 0) {
                throw new IllegalStateException("Outbox payload 缺少有效 taskId");
            }

            String userId = jdbcTemplate.queryForObject(
                    "select user_id from expense_task where id=?",
                    String.class,
                    taskId
            );

            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("eventId", envelope.eventId());
            arguments.put("eventType", envelope.eventType());
            arguments.put("taskId", taskId);
            arguments.put("userId", userId);
            arguments.put(
                    "businessNo",
                    envelope.payload().path("businessNo").asText("")
            );
            arguments.put(
                    "templateCode",
                    "EXPENSE_SUBMITTED"
            );

            ToolResult result = toolGateway.execute(new ToolCall(
                    taskId,
                    userId,
                    "send_notification",
                    arguments,
                    true,
                    "SEND_NOTIFICATION:" + envelope.eventId()
            ));

            if (!result.success()) {
                // 抛出异常交给 RocketMQ 自身的消费重试 / DLQ，不吞掉失败。
                throw new IllegalStateException(
                        "生产通知 MCP 失败: " + result.code() + " " + result.message());
            }

            // 只有外部通知已经成功（或命中 ToolGateway 幂等记录）后，才记录消费完成。
            jdbcTemplate.update("""
                    insert into consumed_event(event_id,consumer_group)
                    values (?,?)
                    on duplicate key update consumed_at=consumed_at
                    """, envelope.eventId(), CONSUMER_GROUP);
        }
        catch (RuntimeException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new IllegalStateException("处理 Expense Outbox 事件失败", ex);
        }
    }

    private boolean alreadyConsumed(String eventId) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*)
                  from consumed_event
                 where event_id=? and consumer_group=?
                """, Integer.class, eventId, CONSUMER_GROUP);

        return count != null && count > 0;
    }
}
