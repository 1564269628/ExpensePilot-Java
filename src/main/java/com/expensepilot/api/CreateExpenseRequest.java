package com.expensepilot.api;

import jakarta.validation.constraints.NotBlank;

/** 创建报销 Agent 任务的 API 入参。 */
public record CreateExpenseRequest(
        @NotBlank String userId,
        @NotBlank String requestText
) {}
