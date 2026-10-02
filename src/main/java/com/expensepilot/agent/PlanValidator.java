package com.expensepilot.agent;

import com.expensepilot.domain.StepType;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Structured Output 之后的确定性安全校验。
 *
 * <p>LLM 负责生成候选计划，Java 代码负责决定这个计划能不能执行。</p>
 */
@Component
public class PlanValidator {

    private static final Set<StepType> SIDE_EFFECT_TYPES =
            Set.of(StepType.SUBMIT_REPORT, StepType.SEND_NOTIFICATION);

    public void validate(PlanOutput output) {
        if (output.tasks() == null || output.tasks().isEmpty()) {
            throw new IllegalArgumentException("tasks 不能为空");
        }
        if (output.tripScope() == null) {
            throw new IllegalArgumentException("tripScope 不能为空");
        }
        if (output.tripScope().evidence() == null) {
            throw new IllegalArgumentException("tripScope.evidence 必须返回数组");
        }
        if (output.tripScope().unknownFields() == null) {
            throw new IllegalArgumentException("tripScope.unknownFields 必须返回数组");
        }
        if (output.clarificationQuestions() == null) {
            throw new IllegalArgumentException("clarificationQuestions 必须返回数组");
        }

        validate(output.tasks());

        if (!output.tripScope().unknownFields().isEmpty()
                && !output.needsClarification()) {
            throw new IllegalArgumentException(
                    "存在 unknownFields 时 needsClarification 必须为 true");
        }

        if (output.needsClarification()
                && output.clarificationQuestions().isEmpty()) {
            throw new IllegalArgumentException(
                    "needsClarification=true 时必须提供 clarificationQuestions");
        }
    }

    public void validate(List<PlannedTask> tasks) {
        Set<String> ids = new HashSet<>();

        for (PlannedTask task : tasks) {
            if (task.stepKey() == null || task.stepKey().isBlank()) {
                throw new IllegalArgumentException("stepKey 不能为空");
            }
            if (task.stepType() == null) {
                throw new IllegalArgumentException(
                        "stepType 不能为空: " + task.stepKey());
            }
            if (task.dependsOn() == null) {
                throw new IllegalArgumentException(
                        "dependsOn 必须返回数组: " + task.stepKey());
            }
            if (task.input() == null) {
                throw new IllegalArgumentException(
                        "input 必须返回对象: " + task.stepKey());
            }
            if (!ids.add(task.stepKey())) {
                throw new IllegalArgumentException(
                        "Task stepKey 必须唯一: " + task.stepKey());
            }

            boolean shouldSideEffect =
                    SIDE_EFFECT_TYPES.contains(task.stepType());
            if (task.sideEffect() != shouldSideEffect) {
                throw new IllegalArgumentException(
                        task.stepType()
                                + " 的 sideEffect 应为 "
                                + shouldSideEffect);
            }
        }

        for (PlannedTask task : tasks) {
            for (String dep : task.dependsOn()) {
                if (!ids.contains(dep)) {
                    throw new IllegalArgumentException("不存在的依赖: " + dep);
                }
                if (dep.equals(task.stepKey())) {
                    throw new IllegalArgumentException(
                            "步骤不能依赖自身: " + dep);
                }
            }
        }

        detectCycle(tasks);
        validateRequiredBusinessSteps(tasks);
    }

    private void validateRequiredBusinessSteps(List<PlannedTask> tasks) {
        EnumSet<StepType> types = EnumSet.noneOf(StepType.class);
        tasks.forEach(task -> types.add(task.stepType()));

        Set<StepType> required = EnumSet.of(
                StepType.RESOLVE_TRIP_RANGE,
                StepType.SEARCH_EMAIL,
                StepType.SEARCH_DRIVE,
                StepType.QUERY_TRAVEL,
                StepType.PARSE_INVOICE,
                StepType.CHECK_MATERIAL,
                StepType.CHECK_POLICY,
                StepType.GENERATE_REPORT,
                StepType.SUBMIT_REPORT,
                StepType.SEND_NOTIFICATION
        );

        if (!types.containsAll(required)) {
            EnumSet<StepType> missing = EnumSet.copyOf(required);
            missing.removeAll(types);
            throw new IllegalArgumentException(
                    "计划缺少必要业务步骤: " + missing);
        }
    }

    private void detectCycle(List<PlannedTask> tasks) {
        Map<String, List<String>> graph = new HashMap<>();
        for (PlannedTask task : tasks) {
            graph.put(task.stepKey(), task.dependsOn());
        }

        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();

        for (String node : graph.keySet()) {
            dfs(node, graph, visiting, visited);
        }
    }

    private void dfs(
            String node,
            Map<String, List<String>> graph,
            Set<String> visiting,
            Set<String> visited) {

        if (visited.contains(node)) {
            return;
        }
        if (!visiting.add(node)) {
            throw new IllegalArgumentException(
                    "Planner 生成了循环依赖: " + node);
        }

        for (String dep : graph.getOrDefault(node, List.of())) {
            dfs(dep, graph, visiting, visited);
        }

        visiting.remove(node);
        visited.add(node);
    }
}
