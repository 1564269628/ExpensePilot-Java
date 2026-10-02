package com.expensepilot.agent;

import com.expensepilot.cache.TaskContextCache;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Structured Output 计划审计。
 */
@Service
@RequiredArgsConstructor
public class PlanAuditService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TaskContextCache taskContextCache;

    @Transactional
    public void persist(long taskId, PlanOutput output) {
        try {
            jdbcTemplate.update("""
                    insert into agent_plan(
                        task_id,goal,trip_scope_json,needs_clarification,
                        clarification_questions_json,plan_summary
                    )
                    values (?, ?, cast(? as json), ?, cast(? as json), ?)
                    on duplicate key update
                        goal=values(goal),
                        trip_scope_json=values(trip_scope_json),
                        needs_clarification=values(needs_clarification),
                        clarification_questions_json=values(clarification_questions_json),
                        plan_summary=values(plan_summary),
                        updated_at=now()
                    """,
                    taskId,
                    output.goal(),
                    objectMapper.writeValueAsString(output.tripScope()),
                    output.needsClarification(),
                    objectMapper.writeValueAsString(output.clarificationQuestions()),
                    output.planSummary()
            );

            // agent_plan 表示“当前生效计划”；Replan 后只保留当前计划对应的 step 快照，
            // 避免新的 LLM stepKey 与旧 stepKey 同时残留，造成一条任务看起来有两套 DAG。
            jdbcTemplate.update(
                    "delete from agent_step where task_id=?",
                    taskId
            );

            for (PlannedTask task : output.tasks()) {
                jdbcTemplate.update("""
                        insert into agent_step(
                            task_id,step_key,step_type,status,
                            depends_on_json,input_json,retry_count
                        )
                        values (?, ?, ?, 'PENDING', cast(? as json), cast(? as json), 0)
                        """,
                        taskId,
                        task.stepKey(),
                        task.stepType().name(),
                        objectMapper.writeValueAsString(task.dependsOn()),
                        objectMapper.writeValueAsString(task.input())
                );
            }

            taskContextCache.evict(taskId);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException("PlanOutput 持久化失败", ex);
        }
    }
}
