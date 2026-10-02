package com.expensepilot.domain;

/** Agent 长流程任务状态。 */
public enum TaskStatus {
    CREATED,
    PLANNING,
    RUNNING,
    WAITING_MATERIAL,
    WAITING_APPROVAL,
    RETRYING,
    UNKNOWN,
    SUCCEEDED,
    FAILED,
    MANUAL_TAKEOVER
}
