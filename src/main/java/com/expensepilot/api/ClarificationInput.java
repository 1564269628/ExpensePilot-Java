package com.expensepilot.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/** Planner 无法安全确定出差范围时的人工补充输入。 */
public record ClarificationInput(
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotBlank String city
) {}
