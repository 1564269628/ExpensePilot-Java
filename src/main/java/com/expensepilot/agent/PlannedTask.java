package com.expensepilot.agent;

import com.expensepilot.domain.StepType;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;
import java.util.Map;

/**
 * Spring AI Structured Output 中的一个 DAG 节点。
 *
 * <p>stepType 必须来自固定枚举，不能让模型输出任意工具名；
 * 真正允许调用什么工具仍由 ToolGateway 白名单决定。</p>
 */
public record PlannedTask(
        @JsonPropertyDescription("稳定且唯一的步骤标识，例如 email-search")
        String stepKey,

        @JsonPropertyDescription("步骤类型，只能使用后端定义的 StepType 枚举")
        StepType stepType,

        @JsonPropertyDescription("前置步骤 stepKey 列表；无依赖时返回空数组")
        List<String> dependsOn,

        @JsonPropertyDescription("给该步骤的结构化输入；没有额外输入时返回空对象")
        Map<String, Object> input,

        @JsonPropertyDescription("该步骤是否会改变外部系统状态，例如提交报销或发送通知")
        boolean sideEffect
) {}
