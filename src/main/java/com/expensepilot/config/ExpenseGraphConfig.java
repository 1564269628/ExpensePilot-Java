package com.expensepilot.config;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.observation.GraphObservationLifecycleListener;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import io.micrometer.observation.ObservationRegistry;
import com.expensepilot.graph.ExpenseGraphNodes;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.expensepilot.graph.ExpenseGraphKeys.*;

/**
 * ExpensePilot 的唯一工作流编排入口。
 *
 * <p>整个主流程由 Spring AI Alibaba Graph 驱动：
 * 节点负责业务动作，addEdge 负责固定流程，addConditionalEdges 负责业务分支，
 * 并行 fan-out/fan-in 由 Graph Runtime 管理，人工等待由 interruptAfter 管理。</p>
 */
@Configuration
public class ExpenseGraphConfig {

    @Bean
    public MysqlSaver expenseMysqlSaver(DataSource dataSource) {
        return MysqlSaver.builder()
                .dataSource(dataSource)
                .stateSerializer(StateGraph.DEFAULT_JACKSON_SERIALIZER)
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .maxCachedThreads(1000)
                .build();
    }

    @Bean
    public StateGraph expenseStateGraph(
            ExpenseGraphNodes nodes,
            @Qualifier("emailExecutor") Executor emailExecutor,
            @Qualifier("driveExecutor") Executor driveExecutor,
            @Qualifier("travelExecutor") Executor travelExecutor) throws GraphStateException {

        KeyStrategyFactory keys = () -> {
            HashMap<String, KeyStrategy> map = new HashMap<>();

            // 所有字段都是单值事实；并行分支写不同 key，因此不会互相覆盖。
            for (String key : new String[] {
                    TASK_ID, USER_ID, REQUEST_TEXT,
                    PLAN, TRIP_START, TRIP_END, TRIP_CITY, CLARIFICATION_QUESTIONS, PLANNER_ROUTE,
                    EMAIL_RESULT, DRIVE_RESULT, TRAVEL_RESULT, INVOICE_RESULT,
                    MATERIAL_RESULT, SUPPLEMENTAL_MATERIALS, MATERIAL_ROUTE,
                    POLICY_RESULT, POLICY_ROUTE, POLICY_APPROVED, POLICY_DECISION_ROUTE,
                    REPORT_DRAFT, SUBMIT_APPROVED, SUBMIT_DECISION_ROUTE, SUBMIT_RESULT,
                    TASK_STATUS, WAITING_REASON, "eventId"
            }) {
                map.put(key, new ReplaceStrategy());
            }
            return map;
        };

        AsyncNodeAction email = state -> CompletableFuture.supplyAsync(
                () -> nodes.searchEmail(state), emailExecutor);
        AsyncNodeAction drive = state -> CompletableFuture.supplyAsync(
                () -> nodes.searchDrive(state), driveExecutor);
        AsyncNodeAction travel = state -> CompletableFuture.supplyAsync(
                () -> nodes.queryTravel(state), travelExecutor);

        StateGraph graph = new StateGraph(keys)
                .addNode("planner", node_async(nodes::planner))
                .addNode("requestClarification", node_async(nodes::requestClarification))
                .addNode("resolveTripRange", node_async(nodes::resolveTripRange))

                // 三个生产系统真正并行。
                .addNode("emailSearch", email)
                .addNode("driveSearch", drive)
                .addNode("travelQuery", travel)
                .addNode("materialJoin", node_async(nodes::materialJoin))

                .addNode("parseInvoices", node_async(nodes::parseInvoices))
                .addNode("materialCheck", node_async(nodes::checkMaterial))
                .addNode("requestSupplement", node_async(nodes::requestSupplement))

                .addNode("policyCheck", node_async(nodes::checkPolicy))
                .addNode("humanApproval", node_async(nodes::humanApproval))
                .addNode("policyApprovalDecision", node_async(nodes::policyApprovalDecision))

                .addNode("generateReport", node_async(nodes::generateReport))
                .addNode("submitApproval", node_async(nodes::submitApproval))
                .addNode("submitDecision", node_async(nodes::submitDecision))
                .addNode("submit", node_async(nodes::submit))
                .addNode("outbox", node_async(nodes::outbox))
                .addNode("rejected", node_async(nodes::rejected));

        graph.addEdge(START, "planner");

        graph.addConditionalEdges(
                "planner",
                edge_async(state -> (String) state.value(PLANNER_ROUTE).orElse("CLARIFY")),
                Map.of(
                        "READY", "resolveTripRange",
                        "CLARIFY", "requestClarification"
                ));

        graph.addEdge("requestClarification", "resolveTripRange");

        // fan-out：Graph Runtime 同时调度三个材料来源。
        graph.addEdge("resolveTripRange", "emailSearch");
        graph.addEdge("resolveTripRange", "driveSearch");
        graph.addEdge("resolveTripRange", "travelQuery");

        // fan-in：三个并行分支都完成后，Graph 才进入 materialJoin。
        graph.addEdge("emailSearch", "materialJoin");
        graph.addEdge("driveSearch", "materialJoin");
        graph.addEdge("travelQuery", "materialJoin");

        graph.addEdge("materialJoin", "parseInvoices");
        graph.addEdge("parseInvoices", "materialCheck");

        graph.addConditionalEdges(
                "materialCheck",
                edge_async(state -> (String) state.value(MATERIAL_ROUTE).orElse("MISSING")),
                Map.of(
                        "READY", "policyCheck",
                        "MISSING", "requestSupplement"
                ));

        // 用户补件后重新做材料检查，不重复查询已经完成的邮箱/网盘/差旅节点。
        graph.addEdge("requestSupplement", "materialCheck");

        graph.addConditionalEdges(
                "policyCheck",
                edge_async(state -> (String) state.value(POLICY_ROUTE).orElse("CONFLICT")),
                Map.of(
                        "PASS", "generateReport",
                        "CONFLICT", "humanApproval"
                ));

        graph.addEdge("humanApproval", "policyApprovalDecision");
        graph.addConditionalEdges(
                "policyApprovalDecision",
                edge_async(state -> (String) state.value(POLICY_DECISION_ROUTE).orElse("REJECTED")),
                Map.of(
                        "APPROVED", "generateReport",
                        "REJECTED", "rejected"
                ));

        graph.addEdge("generateReport", "submitApproval");
        graph.addEdge("submitApproval", "submitDecision");
        graph.addConditionalEdges(
                "submitDecision",
                edge_async(state -> (String) state.value(SUBMIT_DECISION_ROUTE).orElse("REJECTED")),
                Map.of(
                        "APPROVED", "submit",
                        "REJECTED", "rejected"
                ));

        graph.addEdge("submit", "outbox");
        graph.addEdge("outbox", END);
        graph.addEdge("rejected", END);

        return graph;
    }

    /**
     * 主 Graph 唯一使用的 CompileConfig。
     *
     * <p>显式注入 ObservationRegistry 与 GraphObservationLifecycleListener。
     * 这样 spring-ai-alibaba-starter-graph-observation 产生的监听器真正挂到
     * ExpensePilot 的 CompiledGraph，而不是只创建一个未被使用的默认配置 Bean。</p>
     */
    @Bean
    public CompileConfig expenseCompileConfig(
            MysqlSaver expenseMysqlSaver,
            ObservationRegistry observationRegistry,
            GraphObservationLifecycleListener observationLifecycleListener) {

        SaverConfig saverConfig = SaverConfig.builder()
                .register(expenseMysqlSaver)
                .build();

        return CompileConfig.builder()
                .saverConfig(saverConfig)
                .observationRegistry(observationRegistry)
                .withLifecycleListener(
                        observationLifecycleListener
                )
                // 节点先把 WAITING_* 状态落业务表，然后 Graph 保存 checkpoint 并暂停。
                .interruptAfter(
                        "requestClarification",
                        "requestSupplement",
                        "humanApproval",
                        "submitApproval"
                )
                .build();
    }

    @Bean
    public CompiledGraph expenseCompiledGraph(
            StateGraph expenseStateGraph,
            CompileConfig expenseCompileConfig)
            throws GraphStateException {

        return expenseStateGraph.compile(
                expenseCompileConfig
        );
    }
}
