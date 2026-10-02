package com.expensepilot.agent;

import com.expensepilot.domain.StepType;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Structured Output 之后的确定性安全校验。
 *
 * <p>Planner 输出的是“主路径 Task DAG”；补件、人工审批、重规划属于 Graph Runtime
 * 的条件分支，不进入 DAG，否则补件 -> 重新检查材料会天然形成环。</p>
 *
 * <p>LLM 只能决定受约束的计划实例，不能改变企业工作流的安全拓扑。</p>
 */
@Component
public class PlanValidator {

    private static final Set<StepType> SIDE_EFFECT_TYPES =
            Set.of(StepType.SUBMIT_REPORT, StepType.SEND_NOTIFICATION);

    private static final Set<StepType> RUNTIME_BRANCH_TYPES =
            Set.of(StepType.REQUEST_SUPPLEMENT, StepType.HUMAN_APPROVAL);

    /**
     * 真实 StateGraph 主路径的依赖约束。
     * key 为当前步骤，value 为它必须直接依赖的步骤类型。
     */
    private static final Map<StepType, Set<StepType>> REQUIRED_DEPENDENCIES =
            Map.of(
                    StepType.RESOLVE_TRIP_RANGE, Set.of(),
                    StepType.SEARCH_EMAIL, Set.of(StepType.RESOLVE_TRIP_RANGE),
                    StepType.SEARCH_DRIVE, Set.of(StepType.RESOLVE_TRIP_RANGE),
                    StepType.QUERY_TRAVEL, Set.of(StepType.RESOLVE_TRIP_RANGE),
                    StepType.PARSE_INVOICE, Set.of(
                            StepType.SEARCH_EMAIL,
                            StepType.SEARCH_DRIVE,
                            StepType.QUERY_TRAVEL
                    ),
                    StepType.CHECK_MATERIAL, Set.of(StepType.PARSE_INVOICE),
                    StepType.CHECK_POLICY, Set.of(StepType.CHECK_MATERIAL),
                    StepType.GENERATE_REPORT, Set.of(StepType.CHECK_POLICY),
                    StepType.SUBMIT_REPORT, Set.of(StepType.GENERATE_REPORT),
                    StepType.SEND_NOTIFICATION, Set.of(StepType.SUBMIT_REPORT)
            );

    public void validate(PlanOutput output) {
        if (output.tasks() == null || output.tasks().isEmpty()) {
            throw new IllegalArgumentException("tasks 不能为空");
        }
        if (output.tripScope() == null) {
            throw new IllegalArgumentException("tripScope 不能为空");
        }
        if (output.tripScope().evidence() == null) {
            throw new IllegalArgumentException(
                    "tripScope.evidence 必须返回数组");
        }
        if (output.tripScope().unknownFields() == null) {
            throw new IllegalArgumentException(
                    "tripScope.unknownFields 必须返回数组");
        }
        if (output.clarificationQuestions() == null) {
            throw new IllegalArgumentException(
                    "clarificationQuestions 必须返回数组");
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
        Set<String> stepKeys = new HashSet<>();
        EnumMap<StepType, PlannedTask> byType =
                new EnumMap<>(StepType.class);

        for (PlannedTask task : tasks) {
            validateTaskShape(task, stepKeys);

            if (RUNTIME_BRANCH_TYPES.contains(task.stepType())) {
                throw new IllegalArgumentException(
                        task.stepType()
                                + " 属于 Graph 条件分支，不能放进主路径 DAG");
            }

            PlannedTask previous = byType.put(
                    task.stepType(),
                    task
            );
            if (previous != null) {
                throw new IllegalArgumentException(
                        "每种主路径 StepType 只能出现一次: "
                                + task.stepType());
            }
        }

        validateDependencyKeys(tasks, stepKeys);
        detectCycle(tasks);
        validateRequiredBusinessSteps(byType);
        validateTopology(byType);
    }

    private void validateTaskShape(
            PlannedTask task,
            Set<String> stepKeys) {
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
        if (!stepKeys.add(task.stepKey())) {
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

    private void validateDependencyKeys(
            List<PlannedTask> tasks,
            Set<String> stepKeys) {
        for (PlannedTask task : tasks) {
            for (String dep : task.dependsOn()) {
                if (!stepKeys.contains(dep)) {
                    throw new IllegalArgumentException(
                            "不存在的依赖: " + dep);
                }
                if (dep.equals(task.stepKey())) {
                    throw new IllegalArgumentException(
                            "步骤不能依赖自身: " + dep);
                }
            }
        }
    }

    private void validateRequiredBusinessSteps(
            EnumMap<StepType, PlannedTask> byType) {
        if (!byType.keySet().containsAll(
                REQUIRED_DEPENDENCIES.keySet())) {
            EnumSet<StepType> missing =
                    EnumSet.copyOf(REQUIRED_DEPENDENCIES.keySet());
            missing.removeAll(byType.keySet());
            throw new IllegalArgumentException(
                    "计划缺少必要业务步骤: " + missing);
        }
    }

    /**
     * 把 LLM 的 stepKey 依赖转换为 StepType，再与真实 Graph 拓扑比较。
     * 这样数据库里审计的 Task DAG 与真正执行的 StateGraph 不会出现“两套流程”。
     */
    private void validateTopology(
            EnumMap<StepType, PlannedTask> byType) {
        Map<String, StepType> keyToType = new HashMap<>();
        byType.forEach(
                (type, task) -> keyToType.put(task.stepKey(), type)
        );

        for (Map.Entry<StepType, Set<StepType>> rule
                : REQUIRED_DEPENDENCIES.entrySet()) {
            PlannedTask task = byType.get(rule.getKey());

            Set<StepType> actual = new HashSet<>();
            for (String dependencyKey : task.dependsOn()) {
                StepType type = keyToType.get(dependencyKey);
                if (type == null) {
                    throw new IllegalArgumentException(
                            "无法解析依赖 StepType: "
                                    + dependencyKey);
                }
                actual.add(type);
            }

            if (!actual.equals(rule.getValue())) {
                throw new IllegalArgumentException(
                        "步骤 " + rule.getKey()
                                + " 的依赖与生产 Graph 不一致，expected="
                                + rule.getValue()
                                + ", actual=" + actual);
            }
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
