package com.expensepilot.agent;

import com.expensepilot.domain.StepType;
import java.util.List;
import java.util.Map;

/** Planner 输出的 DAG 节点。dependsOn 使用 stepKey 引用前置节点。 */
public record PlannedTask(
        String stepKey,
        StepType stepType,
        List<String> dependsOn,
        Map<String, Object> input,
        boolean sideEffect
) {}
