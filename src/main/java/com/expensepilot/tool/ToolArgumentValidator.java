package com.expensepilot.tool;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Tool Gateway 的确定性参数与身份边界。
 *
 * <p>所有 userId 都必须和 Graph 中由 JWT subject 固化的 call.userId 一致。
 * 这样即使某个节点参数拼装出错，也不能借生产 MCP 服务越权读取其他员工数据。</p>
 */
@Component
public class ToolArgumentValidator {

    public void validate(ToolCall call) {
        if (call == null) {
            throw new IllegalArgumentException("ToolCall 不能为空");
        }
        if (call.taskId() <= 0) {
            throw new IllegalArgumentException("taskId 必须为正数");
        }
        if (blank(call.userId())) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        if (blank(call.toolName())) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        if (blank(call.operationType())) {
            throw new IllegalArgumentException("operationType 不能为空");
        }
        if (call.arguments() == null) {
            throw new IllegalArgumentException("Tool arguments 不能为空");
        }

        switch (call.toolName()) {
            case "search_email", "search_drive", "query_travel" -> {
                requireIdentity(call);
                validateTripRange(call.arguments());
            }
            case "parse_invoices" -> {
                requireMap(call.arguments(), "email");
                requireMap(call.arguments(), "drive");
                requireMap(call.arguments(), "travel");
            }
            case "validate_materials" -> {
                requireMap(call.arguments(), "invoices");
                requireMap(call.arguments(), "travel");
            }
            case "check_expense_policy" -> {
                requireIdentity(call);
                requireString(call.arguments(), "city");
                requireMap(call.arguments(), "invoices");
                requireMap(call.arguments(), "travel");
            }
            case "build_expense_report_draft" -> {
                requireIdentity(call);
                requireString(call.arguments(), "requestText");
                requireMap(call.arguments(), "trip");
                requireMap(call.arguments(), "invoices");
                requireMap(call.arguments(), "policy");
            }
            case "submit_expense_report" -> {
                requireIdentity(call);
                requireMap(call.arguments(), "reportDraft");
                if (!call.sideEffect()) {
                    throw new IllegalArgumentException(
                            "submit_expense_report 必须标记 sideEffect=true");
                }
            }
            case "send_notification" -> {
                requireIdentity(call);
                requireString(call.arguments(), "eventId");
                requireString(call.arguments(), "eventType");
                requireString(call.arguments(), "templateCode");
                requireTaskIdMatch(call);

                if (!call.sideEffect()) {
                    throw new IllegalArgumentException(
                            "send_notification 必须标记 sideEffect=true");
                }
            }
            default -> throw new IllegalArgumentException(
                    "未定义参数契约的 Tool: " + call.toolName());
        }
    }

    private void requireIdentity(ToolCall call) {
        Object argumentUserId =
                call.arguments().get("userId");

        if (!(argumentUserId instanceof String value)
                || value.isBlank()) {
            throw new IllegalArgumentException(
                    call.toolName() + ".userId 不能为空");
        }

        if (!call.userId().equals(value)) {
            throw new IllegalArgumentException(
                    "Tool userId 与 Graph/JWT 身份不一致");
        }
    }

    private void requireTaskIdMatch(ToolCall call) {
        Object value = call.arguments().get("taskId");
        if (!(value instanceof Number number)
                || number.longValue() != call.taskId()) {
            throw new IllegalArgumentException(
                    "通知 taskId 与 ToolCall.taskId 不一致");
        }
    }

    private void validateTripRange(
            Map<String, Object> arguments) {

        String startText =
                requireString(arguments, "startDate");
        String endText =
                requireString(arguments, "endDate");
        requireString(arguments, "city");

        try {
            LocalDate start = LocalDate.parse(startText);
            LocalDate end = LocalDate.parse(endText);

            if (start.isAfter(end)) {
                throw new IllegalArgumentException(
                        "startDate 不能晚于 endDate");
            }
        }
        catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(
                    "startDate/endDate 必须是 ISO-8601 日期",
                    ex
            );
        }
    }

    private String requireString(
            Map<String, Object> arguments,
            String key) {
        Object value = arguments.get(key);
        if (!(value instanceof String text)
                || text.isBlank()) {
            throw new IllegalArgumentException(
                    key + " 必须是非空字符串");
        }
        return text;
    }

    private void requireMap(
            Map<String, Object> arguments,
            String key) {
        Object value = arguments.get(key);
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(
                    key + " 必须是对象");
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
