package com.expensepilot.cache;

import java.time.LocalDateTime;

/** 对前端/运营最常访问的任务状态视图。 */
public record TaskStatusView(
        long taskId,
        String userId,
        String status,
        String currentNode,
        int retryCount,
        int version,
        String lastError,
        String goal,
        String planSummary,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
