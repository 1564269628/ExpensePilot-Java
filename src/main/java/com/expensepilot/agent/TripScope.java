package com.expensepilot.agent;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.time.LocalDate;
import java.util.List;

/**
 * Planner 对用户自然语言中的出差范围做的结构化理解。
 * unknownFields 不为空时，后续 Graph 可以进入补充信息/澄清路径。
 */
public record TripScope(
        @JsonPropertyDescription("出差开始日期；无法确定时为 null")
        LocalDate startDate,

        @JsonPropertyDescription("出差结束日期；无法确定时为 null")
        LocalDate endDate,

        @JsonPropertyDescription("出差城市；无法确定时为空字符串")
        String city,

        @JsonPropertyDescription("Planner 用于确定范围的用户原文证据，例如‘上周去上海’")
        List<String> evidence,

        @JsonPropertyDescription("仍然缺失、不能安全猜测的字段，例如 startDate")
        List<String> unknownFields
) {}
