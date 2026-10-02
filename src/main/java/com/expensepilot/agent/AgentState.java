package com.expensepilot.agent;

import com.expensepilot.domain.TaskStatus;
import lombok.Builder;
import lombok.Data;

import java.util.*;

/**
 * Graph 节点之间传递的统一 State。
 *
 * <p>并行节点不要随意覆盖整个 State，而是只更新自己负责的字段。
 * materials/toolResults 使用 map 合并，完成步骤使用 set 追加，避免并行分支“最后写入覆盖前一个”。</p>
 */
@Data
@Builder(toBuilder = true)
public class AgentState {
    private long taskId;
    private String userId;
    private String requestText;
    private String currentNode;
    private TaskStatus taskStatus;

    @Builder.Default
    private Map<String, Object> context = new HashMap<>();
    @Builder.Default
    private Map<String, Object> materials = new HashMap<>();
    @Builder.Default
    private Map<String, Object> toolResults = new HashMap<>();
    @Builder.Default
    private Set<String> completedSteps = new LinkedHashSet<>();
    @Builder.Default
    private List<String> errors = new ArrayList<>();
    @Builder.Default
    private List<PlannedTask> plan = new ArrayList<>();

    public AgentState mergeMaterial(String source, Object value) {
        this.materials.put(source, value);
        return this;
    }

    public AgentState markCompleted(String stepKey) {
        this.completedSteps.add(stepKey);
        return this;
    }
}
