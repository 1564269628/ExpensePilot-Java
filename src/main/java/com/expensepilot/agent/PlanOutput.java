package com.expensepilot.agent;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Planner 的强类型 Structured Output。
 *
 * <p>这是 LLM 与可靠 Java 后端之间的边界：模型只能返回这个 Schema，
 * 后端随后还会做 DAG、权限和副作用校验。</p>
 */
public record PlanOutput(
        @JsonPropertyDescription("对用户报销目标的一句话摘要")
        String goal,

        @JsonPropertyDescription("从用户请求中解析出的出差范围")
        TripScope tripScope,

        @JsonPropertyDescription("执行计划，必须是有向无环图")
        List<PlannedTask> tasks,

        @JsonPropertyDescription("是否因为关键信息不足需要用户补充")
        boolean needsClarification,

        @JsonPropertyDescription("需要用户补充的问题；无需补充时必须返回空数组")
        List<String> clarificationQuestions,

        @JsonPropertyDescription("简短的计划说明，只描述为什么需要这些步骤，不输出模型思维链")
        String planSummary
) {}
