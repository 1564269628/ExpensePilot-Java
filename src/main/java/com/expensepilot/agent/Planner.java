package com.expensepilot.agent;

import com.expensepilot.domain.StepType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 任务规划器。
 *
 * <p>生产环境可在这里接 Spring AI ChatClient + Structured Output，让模型返回 PlannedTask 列表；
 * 但无论模型输出什么，都必须经过 PlanValidator 校验，绝不能让 LLM 直接驱动副作用工具。</p>
 */
@Component
public class Planner {

    public List<PlannedTask> plan(String requestText) {
        // 默认计划体现真实报销主链，同时便于没有模型 Key 时本地演示。
        return List.of(
                new PlannedTask("resolve-trip-range", StepType.RESOLVE_TRIP_RANGE, List.of(), Map.of(), false),
                new PlannedTask("email", StepType.SEARCH_EMAIL, List.of("resolve-trip-range"), Map.of(), false),
                new PlannedTask("drive", StepType.SEARCH_DRIVE, List.of("resolve-trip-range"), Map.of(), false),
                new PlannedTask("travel", StepType.QUERY_TRAVEL, List.of("resolve-trip-range"), Map.of(), false),
                new PlannedTask("parse", StepType.PARSE_INVOICE, List.of("email", "drive"), Map.of(), false),
                new PlannedTask("material-check", StepType.CHECK_MATERIAL, List.of("parse", "travel"), Map.of(), false),
                new PlannedTask("policy-check", StepType.CHECK_POLICY, List.of("material-check"), Map.of(), false),
                new PlannedTask("generate-report", StepType.GENERATE_REPORT, List.of("policy-check"), Map.of(), false),
                new PlannedTask("submit-report", StepType.SUBMIT_REPORT, List.of("generate-report"), Map.of(), true),
                new PlannedTask("notify", StepType.SEND_NOTIFICATION, List.of("submit-report"), Map.of(), true)
        );
    }
}
