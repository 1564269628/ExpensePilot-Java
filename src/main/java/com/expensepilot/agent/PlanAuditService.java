package com.expensepilot.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Structured Output 计划审计。
 *
 * <p>Graph Checkpoint 能恢复运行状态，但审计/排障还需要一份易查询的业务计划快照。
 * 因此 Planner 校验通过后，把 PlanOutput 与每个 DAG 节点写入 MySQL。</p>
 */
@Service
@RequiredArgsConstructor
public class PlanAuditService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Transactional
    public void persist(long taskId, PlanOutput output) {
        try {
            String tripScopeJson =
                    objectMapper.writeValueAsString(output.tripScope());

            jdbcTemplate.update("""
                    insert into agent_plan(
                        task_id,
                        goal,
                        trip_scope_json,
                        needs_clarification,
                        clarification_questions_json,
                        plan_summary
                    )
                    values (
                        ?,?,
                        cast(? as json),
                        ?,
                        cast(? as json),
                        ?
                    )
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
                    tripScopeJson,
                    output.needsClarification(),
                    objectMapper.writeValueAsString(
                            output.clarificationQuestions()),
                    output.planSummary()
            );

            for (PlannedTask task : output.tasks()) {
                jdbcTemplate.update("""
                        insert into agent_step(
                            task_id,
                            step_key,
                            step_type,
                            status,
                            depends_on_json,
                            input_json,
                            retry_count
                        )
                        values (
                            ?,?,?,
                            'PENDING',
                            cast(? as json),
                            cast(? as json),
                            0
                        )
                        on duplicate key update
                            step_type=values(step_type),
                            depends_on_json=values(depends_on_json),
                            input_json=values(input_json)
                        """,
                        taskId,
                        task.stepKey(),
                        task.stepType().name(),
                        objectMapper.writeValueAsString(task.dependsOn()),
                        objectMapper.writeValueAsString(task.input())
                );
            }
        }
        catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "PlanOutput 持久化失败",
                    ex
            );
        }
    }
}
