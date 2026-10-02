package com.expensepilot.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 生产启动自检：必需 MCP 工具缺一个就启动失败。
 *
 * <p>这样不会出现“服务启动看起来正常，跑到提交阶段才发现报销系统根本没接上”的假联通。</p>
 */
@Component
@RequiredArgsConstructor
public class RequiredMcpToolsVerifier implements ApplicationRunner {

    private static final List<String> REQUIRED_TOOLS = List.of(
            "search_email",
            "search_drive",
            "query_travel",
            "query_policy",
            "create_expense_report",
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
