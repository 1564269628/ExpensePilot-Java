package com.expensepilot.tool;

/**
 * MCP 返回内容违反 ExpensePilot 约定的业务 Envelope / JSON Schema。
 *
 * <p>这是代码或服务契约问题，不允许被“重试后降级为空材料”掩盖。</p>
 */
public class McpContractException extends RuntimeException {

    public McpContractException(String message) {
        super(message);
    }

    public McpContractException(
            String message,
            Throwable cause) {
        super(message, cause);
    }
}
