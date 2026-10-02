package com.expensepilot.domain;

/** Agent 长流程任务状态。 */
public enum TaskStatus {
    CREATED,
    PLANNING,
    RUNNING,
    WAITING_INPUT,
    WAITING_MATERIAL,
    WAITING_APPROVAL,
    RETRYING,
    UNKNOWN,
    SUCCEEDED,
    REJECTED,
    FAILED,
    MANUAL_TAKEOVER
}
