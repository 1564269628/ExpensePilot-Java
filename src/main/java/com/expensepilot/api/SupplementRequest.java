package com.expensepilot.api;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 补件接口只接收已经上传到企业文件系统后的材料引用，
 * 不把大文件二进制直接塞进 Graph Checkpoint。
 */
public record SupplementRequest(
        @NotEmpty List<String> materialRefs
) {}
