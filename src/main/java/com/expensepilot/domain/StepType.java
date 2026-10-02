package com.expensepilot.domain;

/**
 * DAG 中的任务类型。
 * 查询型步骤和副作用步骤分开，是后续重试与幂等策略的基础。
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
    SUBMIT_REPORT,
    SEND_NOTIFICATION
}
