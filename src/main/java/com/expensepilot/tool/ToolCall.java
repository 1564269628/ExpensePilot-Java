package com.expensepilot.tool;

import java.util.Map;

/** Tool Gateway 的统一调用对象。 */
public record ToolCall(
        long taskId,
        String userId,
        String toolName,
        Map<String, Object> arguments,
        boolean sideEffect,
        String operationType
) {}
