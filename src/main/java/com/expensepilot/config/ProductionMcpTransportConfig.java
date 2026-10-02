package com.expensepilot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import org.springframework.ai.mcp.client.common.autoconfigure.NamedClientMcpTransport;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 为每个真实 MCP Server 创建独立 Streamable HTTP Transport。
 *
 * <p>Spring AI 仍负责后续 McpSyncClient 创建、initialize、ToolCallbackProvider
 * 与生命周期关闭；这里只替换 transport 创建方式，以支持 per-server 认证。</p>
 */
@Configuration
@EnableConfigurationProperties(ProductionMcpProperties.class)
public class ProductionMcpTransportConfig {

    private static final Set<String> REQUIRED_SERVERS =
            Set.of("email", "drive", "travel", "expense");

    @Bean
    public List<NamedClientMcpTransport> productionMcpTransports(
            ProductionMcpProperties properties,
            ObjectMapper objectMapper) {

        Map<String, ProductionMcpProperties.Server> servers =
                properties.servers();

        if (servers == null || !servers.keySet().containsAll(REQUIRED_SERVERS)) {
            throw new IllegalStateException(
                    "生产 MCP Server 配置不完整，必须包含: " + REQUIRED_SERVERS);
        }

        List<NamedClientMcpTransport> transports = new ArrayList<>();

        for (Map.Entry<String, ProductionMcpProperties.Server> entry
                : servers.entrySet()) {

            String name = entry.getKey();
            ProductionMcpProperties.Server server = entry.getValue();

            validateServer(name, server);

            WebClient.Builder webClient = WebClient.builder()
                    .baseUrl(server.url())
                    .defaultHeader(
                            "X-ExpensePilot-Client",
                            "expensepilot-java"
                    );

            if (StringUtils.hasText(server.bearerToken())) {
                webClient.defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + server.bearerToken()
                );
            }

            String endpoint = StringUtils.hasText(server.endpoint())
                    ? server.endpoint()
                    : "/mcp";

            var transport = WebClientStreamableHttpTransport
                    .builder(webClient)
                    .endpoint(endpoint)
                    .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                    .build();

            transports.add(
                    new NamedClientMcpTransport(name, transport)
            );
        }

        return List.copyOf(transports);
    }

    private void validateServer(
            String name,
            ProductionMcpProperties.Server server) {

        if (server == null || !StringUtils.hasText(server.url())) {
            throw new IllegalStateException(
                    "MCP Server " + name + " 缺少 url");
        }

        if (!server.url().startsWith("https://")
                && !server.url().startsWith("http://")) {
            throw new IllegalStateException(
                    "MCP Server " + name + " URL 非法: " + server.url());
        }
    }
}
