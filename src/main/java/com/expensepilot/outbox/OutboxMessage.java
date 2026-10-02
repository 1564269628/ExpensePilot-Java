package com.expensepilot.outbox;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * RocketMQ 中实际传输的事件 Envelope。
 *
 * <p>eventId 必须跨 Outbox -> MQ -> Consumer 全链路保持不变，
 * 不能再用 message.hashCode() 之类的派生值代替。</p>
 */
public record OutboxMessage(
        String eventId,
        String eventType,
        JsonNode payload
) {}
