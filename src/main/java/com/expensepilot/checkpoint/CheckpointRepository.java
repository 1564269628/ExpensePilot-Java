package com.expensepilot.checkpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.expensepilot.agent.AgentState;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Checkpoint 持久化仓库。
 *
 * <p>关键节点执行完成后，把 AgentState 序列化到 MySQL。恢复时读取最新快照，
 * 因此服务重启不需要从“查邮件”重新开始，也不会重复执行已经完成的副作用。</p>
 */
@Repository
@RequiredArgsConstructor
public class CheckpointRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AtomicLong sequence = new AtomicLong(System.currentTimeMillis());

    public void save(AgentState state, String nodeName) {
        try {
            String json = objectMapper.writeValueAsString(state);
            jdbcTemplate.update("""
                    insert into agent_checkpoint(task_id,checkpoint_no,node_name,state_json)
                    values (?,?,?,CAST(? AS JSON))
                    """, state.getTaskId(), sequence.incrementAndGet(), nodeName, json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Checkpoint 序列化失败", e);
        }
    }

    public Optional<AgentState> loadLatest(long taskId) {
        var rows = jdbcTemplate.query("""
                select state_json from agent_checkpoint
                where task_id=? order by checkpoint_no desc limit 1
                """, (rs, i) -> rs.getString(1), taskId);
        if (rows.isEmpty()) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(rows.getFirst(), AgentState.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Checkpoint 反序列化失败", e);
        }
    }
}
