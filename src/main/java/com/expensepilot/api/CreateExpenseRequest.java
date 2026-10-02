package com.expensepilot.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 创建报销 Agent 任务的 API 入参。
 *
 * <p>userId 不再由请求体提供，而是从已验证 JWT 的 subject 获取。</p>
 */
public record CreateExpenseRequest(
        @NotBlank String requestText
) {}
