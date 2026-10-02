package com.expensepilot.outbox;

import com.expensepilot.cache.TaskContextCache;
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
 * <p>任务成功状态和待发布事件在同一个 MySQL 事务里提交。
 * 状态更新使用 version CAS，成功后失效 Redis 热点视图。</p>
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TaskContextCache taskContextCache;

    @Transactional
    public String markTaskSucceededAndAppendEvent(
            long taskId,
            String businessNo) {

        Integer expectedVersion = jdbcTemplate.queryForObject(
                "select version from expense_task where id=?",
                Integer.class,
                taskId
        );
        if (expectedVersion == null) {
            throw new IllegalStateException("任务不存在: " + taskId);
        }

        int affected = jdbcTemplate.update("""
                update expense_task
                   set status='SUCCEEDED',
                       current_node='outbox',
                       last_error=null,
                       version=version+1
                 where id=? and version=?
                """,
                taskId,
                expectedVersion
        );

        if (affected != 1) {
            throw new IllegalStateException(
                    "任务成功状态 CAS 失败: taskId=" + taskId
                            + ", expectedVersion=" + expectedVersion);
        }

        String eventId = UUID.randomUUID().toString();

        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "taskId", taskId,
                    "businessNo", businessNo == null ? "" : businessNo
            ));

            jdbcTemplate.update("""
                    insert into outbox_event(
                        event_id,aggregate_id,event_type,payload_json,
                        status,retry_count,next_retry_at
                    )
                    values (
                        ?,?,'EXPENSE_SUBMITTED',cast(? as json),
                        'PENDING',0,now()
                    )
                    """,
                    eventId,
                    String.valueOf(taskId),
                    payload
            );

            taskContextCache.evict(taskId);
            return eventId;
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Outbox payload 序列化失败",
                    ex
            );
        }
    }
}
