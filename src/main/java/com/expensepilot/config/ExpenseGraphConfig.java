package com.expensepilot.config;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * Spring AI Alibaba Graph 的真实 StateGraph 装配示例。
 *
 * <p>业务 Runtime 仍保留自己的持久化/幂等能力；Graph 负责表达节点拓扑和条件路由。
 * 这样即使未来切换 Graph Checkpointer，也不会破坏 MySQL 中的业务事实状态。</p>
 */
@Configuration
public class ExpenseGraphConfig {

    @Bean
    public StateGraph expenseStateGraph() throws GraphStateException {
        KeyStrategyFactory keys = () -> {
            HashMap<String, KeyStrategy> map = new HashMap<>();
            map.put("taskId", new ReplaceStrategy());
            map.put("missingMaterial", new ReplaceStrategy());
            map.put("policyConflict", new ReplaceStrategy());
            map.put("businessNo", new ReplaceStrategy());
            map.put("nextNode", new ReplaceStrategy());
            return map;
        };

        var graph = new StateGraph(keys)
                .addNode("planner", node_async(state -> Map.of("nextNode", "collectMaterials")))
                .addNode("collectMaterials", node_async(state -> Map.of("nextNode", "materialCheck")))
                .addNode("materialCheck", node_async(state -> {
                    boolean missing = state.value("missingMaterial")
                            .map(Boolean.class::cast).orElse(false);
                    return Map.of("nextNode", missing ? "requestSupplement" : "policyCheck");
                }))
                .addNode("requestSupplement", node_async(state -> Map.of("nextNode", "end")))
                .addNode("policyCheck", node_async(state -> {
                    boolean conflict = state.value("policyConflict")
                            .map(Boolean.class::cast).orElse(false);
                    return Map.of("nextNode", conflict ? "humanApproval" : "generateReport");
                }))
                .addNode("humanApproval", node_async(state -> Map.of("nextNode", "generateReport")))
                .addNode("generateReport", node_async(state -> Map.of("nextNode", "submit")))
                .addNode("submit", node_async(state -> Map.of("nextNode", "end")));

        graph.addEdge(START, "planner");
        graph.addEdge("planner", "collectMaterials");
        graph.addEdge("collectMaterials", "materialCheck");

        graph.addConditionalEdges("materialCheck",
                edge_async(state -> (String) state.value("nextNode").orElse("policyCheck")),
                Map.of(
                        "requestSupplement", "requestSupplement",
                        "policyCheck", "policyCheck"
                ));

        graph.addConditionalEdges("policyCheck",
                edge_async(state -> (String) state.value("nextNode").orElse("generateReport")),
                Map.of(
                        "humanApproval", "humanApproval",
                        "generateReport", "generateReport"
                ));

        graph.addEdge("humanApproval", "generateReport");
        graph.addEdge("generateReport", "submit");
        graph.addEdge("requestSupplement", END);
        graph.addEdge("submit", END);
        return graph;
    }
}
