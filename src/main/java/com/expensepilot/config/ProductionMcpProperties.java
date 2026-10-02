package com.expensepilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 生产 MCP Server 配置。
 *
 * <p>每个 server 拥有独立 URL、endpoint 与凭证，避免一个全局 WebClient
 * 把邮箱 token 错发给差旅或模型供应商。</p>
 */
@ConfigurationProperties(prefix = "expensepilot.mcp")
public record ProductionMcpProperties(
        Map<String, Server> servers
) {
    public record Server(
            String url,
            String endpoint,
            String bearerToken
    ) {}
}
