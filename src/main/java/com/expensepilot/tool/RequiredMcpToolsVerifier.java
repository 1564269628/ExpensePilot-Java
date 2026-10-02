package com.expensepilot.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动即校验完整生产工具契约，缺任何一个都拒绝启动。
 */
@Component
@RequiredArgsConstructor
public class RequiredMcpToolsVerifier implements ApplicationRunner {

    private static final List<String> REQUIRED_TOOLS = List.of(
            "search_email",
            "search_drive",
            "query_travel",
            "parse_invoices",
            "validate_materials",
            "check_expense_policy",
            "build_expense_report_draft",
            "submit_expense_report",
            "query_expense_submission",
            "send_notification",
            "query_notification"
    );

    private final SpringAiMcpExpenseClient mcpClient;

    @Override
    public void run(ApplicationArguments args) {
        List<String> missing = REQUIRED_TOOLS.stream()
                .filter(name -> !mcpClient.hasTool(name))
                .toList();

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "生产 MCP 工具契约不完整，缺少: " + missing);
        }
    }
}
