package com.expensepilot.tool;

import java.util.Map;

/**
 * MCP 客户端抽象。
 *
 * <p>实际接企业系统时，可替换成 Spring AI MCP Client / HTTP SDK；
 * Agent 上层只依赖标准工具语义，不关心底层是 REST、RPC、SDK 还是数据库。</p>
 */
public interface ExpenseMcpClient {
    ToolResult invoke(String toolName, Map<String, Object> arguments);

    /**
     * 副作用调用超时后用于回查，解决“对方成功但响应丢失”的 UNKNOWN 状态。
     */
    ToolResult queryByRequestId(String toolName, String requestId);
}
