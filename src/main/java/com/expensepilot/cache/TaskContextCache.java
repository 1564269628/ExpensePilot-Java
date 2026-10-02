package com.expensepilot.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 热点任务上下文缓存。
 *
 * <p>Redis 只负责读性能：读失败直接回退 MySQL，写/删缓存失败也不会阻断业务事务。
 * 通过短 TTL + 状态迁移主动失效，避免 Redis 成为第二事实源。</p>
 */
@Component
@RequiredArgsConstructor
public class TaskContextCache {

    private static final Duration TTL = Duration.ofMinutes(2);
    private static final String PREFIX = "expensepilot:task:view:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Optional<TaskStatusView> get(long taskId) {
        try {
            String json = redisTemplate.opsForValue().get(key(taskId));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, TaskStatusView.class));
        }
        catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public void put(TaskStatusView view) {
        try {
            redisTemplate.opsForValue().set(
                    key(view.taskId()),
                    objectMapper.writeValueAsString(view),
                    TTL
            );
        }
        catch (Exception ignored) {
            // 缓存不可用不影响 MySQL 事实查询。
        }
    }

    public void evict(long taskId) {
        try {
            redisTemplate.delete(key(taskId));
        }
        catch (Exception ignored) {
            // TTL 仍会最终清掉旧值。
        }
    }

    private String key(long taskId) {
        return PREFIX + taskId;
    }
}
