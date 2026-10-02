package com.expensepilot.tool;

/**
 * MCP 远端调用/网络层失败。
 *
 * <p>查询类 Tool 可以对此做有限重试；是否允许最终降级由 ToolGateway 决定。</p>
 */
public class McpTransportException extends RuntimeException {

    public McpTransportException(
            String message,
            Throwable cause) {
        super(message, cause);
    }
}
