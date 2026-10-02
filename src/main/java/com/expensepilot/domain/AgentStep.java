package com.expensepilot.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** Planner 生成的 DAG 节点。dependsOnJson 保存上游 stepId 列表。 */
@Data
@TableName("agent_step")
public class AgentStep {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long taskId;
    private String stepKey;
    private StepType stepType;
    private StepStatus status;
    private String dependsOnJson;
    private String inputJson;
    private String outputJson;
    private String errorCode;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
