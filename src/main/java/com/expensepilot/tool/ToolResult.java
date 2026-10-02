package com.expensepilot.tool;

import java.util.Map;

public record ToolResult(
        boolean success,
        String code,
        String message,
        Map<String, Object> data,
        String externalBusinessNo
) {
    public static ToolResult ok(Map<String, Object> data) {
        return new ToolResult(true, "OK", "success", data, null);
    }
}
