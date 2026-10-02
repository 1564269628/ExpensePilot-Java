package com.expensepilot.domain;

/**
 * Planner 主路径 DAG 中的业务步骤类型。
 *
 * <p>补件和人工审批是 Graph Runtime 条件分支；通知是提交成功后由
 * Outbox/RocketMQ 驱动的确定性异步链路，因此都不属于 Planner 主路径。</p>
 */
public enum StepType {
    RESOLVE_TRIP_RANGE,
    SEARCH_EMAIL,
    SEARCH_DRIVE,
    QUERY_TRAVEL,
    PARSE_INVOICE,
    CHECK_MATERIAL,
    CHECK_POLICY,
    REQUEST_SUPPLEMENT,
    HUMAN_APPROVAL,
    GENERATE_REPORT,
    SUBMIT_REPORT
}
