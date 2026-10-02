package com.expensepilot.config;

import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具命名策略。
 *
 * <p>ExpensePilot 对生产 MCP Server 约定了全局唯一的 canonical tool name，
 * 因此关闭自动前缀，ToolGateway 可以稳定按 search_email 等名称调用。</p>
 */
@Configuration
public class McpClientConfig {

    @Bean
    public McpToolNamePrefixGenerator mcpToolNamePrefixGenerator() {
        return McpToolNamePrefixGenerator.noPrefix();
    }
}
