package com.expensepilot.tool;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 业务返回值的关键语义校验。
 *
 * <p>通用 Envelope 正确还不够：如果材料核验没有 complete、政策核验没有 compliant，
 * Graph 不能把缺字段默认为 false 后继续走业务分支。</p>
 */
@Component
public class McpToolResultValidator {

    public void validate(
            String toolName,
            ToolResult result) {

        if (result == null) {
            throw new McpContractException(
                    "MCP ToolResult 不能为空: " + toolName);
        }

        // success=false 是明确的业务失败，由上层根据 code 决定重试/失败，不要求成功字段。
        if (!result.success()) {
            return;
        }

        switch (toolName) {
            case "validate_materials" -> {
                requireBoolean(
                        toolName,
                        result,
                        "complete"
                );
                requireList(
                        toolName,
                        result,
                        "missingItems"
                );
            }
            case "check_expense_policy" -> {
                requireBoolean(
                        toolName,
                        result,
                        "compliant"
                );
                requireList(
                        toolName,
                        result,
                        "violations"
                );
            }
            case "submit_expense_report",
                 "query_expense_submission" -> {
                if (result.externalBusinessNo() == null
                        || result.externalBusinessNo().isBlank()) {
                    throw new McpContractException(
                            toolName
                                    + " 成功时必须返回 externalBusinessNo");
                }
            }
            default -> {
                // 其他 Tool 使用通用 Envelope 校验即可。
            }
        }
    }

    private void requireBoolean(
            String toolName,
            ToolResult result,
            String key) {

        Object value = result.data().get(key);
        if (!(value instanceof Boolean)) {
            throw new McpContractException(
                    toolName + "." + key
                            + " 必须是 boolean");
        }
    }

    private void requireList(
            String toolName,
            ToolResult result,
            String key) {

        Object value = result.data().get(key);
        if (!(value instanceof List<?>)) {
            throw new McpContractException(
                    toolName + "." + key
                            + " 必须是数组");
        }
    }
}
