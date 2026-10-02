package com.expensepilot.agent;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Spring AI Alibaba Graph 的流程定义语义集中在这里。
 *
 * <p>为了避免业务逻辑和框架 API 强耦合，本类把节点和条件路由定义成稳定的业务方法，
 * ExpenseWorkflowRuntime 负责执行。生产版可直接把这些方法注册为 StateGraph Node：</p>
 *
 * <pre>
 * START -> planner -> collectMaterials -> materialCheck
 * materialCheck --MISSING--> requestSupplement
 * materialCheck --READY--> policyCheck
 * policyCheck --CONFLICT--> humanApproval
 * policyCheck --PASS--> generateReport -> submit -> outbox -> END
 * </pre>
 *
 * <p>这种组织方式与 Spring AI Alibaba Graph 的 addNode / addEdge /
 * addConditionalEdges 一一对应，同时让 Checkpoint、幂等和恢复逻辑保持可测试。</p>
 */
@Component
public class ExpenseAgentGraph {

    public String routeAfterMaterialCheck(AgentState state) {
        boolean missing = Boolean.TRUE.equals(state.getContext().get("missingMaterial"));
        return missing ? "requestSupplement" : "policyCheck";
    }

    public String routeAfterPolicyCheck(AgentState state) {
        boolean conflict = Boolean.TRUE.equals(state.getContext().get("policyConflict"));
        return conflict ? "humanApproval" : "generateReport";
    }

    public Map<String, String> routeMap() {
        return Map.of(
                "MISSING", "requestSupplement",
                "READY", "policyCheck",
                "CONFLICT", "humanApproval",
                "PASS", "generateReport"
        );
    }
}
