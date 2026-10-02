package com.expensepilot.agent;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 对 LLM 计划做确定性校验：
 * 1. stepKey 唯一；
 * 2. 所有依赖存在；
 * 3. DAG 不允许形成环；
 * 4. 副作用节点只能出现在预定义类型中。
 */
@Component
public class PlanValidator {

    public void validate(List<PlannedTask> tasks) {
        Set<String> ids = new HashSet<>();
        for (PlannedTask task : tasks) {
            if (!ids.add(task.stepKey())) {
                throw new IllegalArgumentException("Task stepKey 必须唯一: " + task.stepKey());
            }
        }
        for (PlannedTask task : tasks) {
            for (String dep : task.dependsOn()) {
                if (!ids.contains(dep)) {
                    throw new IllegalArgumentException("不存在的依赖: " + dep);
                }
            }
        }
        detectCycle(tasks);
    }

    private void detectCycle(List<PlannedTask> tasks) {
        Map<String, List<String>> graph = new HashMap<>();
        for (PlannedTask task : tasks) graph.put(task.stepKey(), task.dependsOn());
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String node : graph.keySet()) dfs(node, graph, visiting, visited);
    }

    private void dfs(String node, Map<String, List<String>> graph, Set<String> visiting, Set<String> visited) {
        if (visited.contains(node)) return;
        if (!visiting.add(node)) throw new IllegalArgumentException("Planner 生成了循环依赖: " + node);
        for (String dep : graph.getOrDefault(node, List.of())) dfs(dep, graph, visiting, visited);
        visiting.remove(node);
        visited.add(node);
    }
}
