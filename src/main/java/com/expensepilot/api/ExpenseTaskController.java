package com.expensepilot.api;

import com.expensepilot.cache.TaskStatusView;
import com.expensepilot.service.ExpenseAgentService;
import com.expensepilot.service.ExpenseTaskQueryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/expense-tasks")
@RequiredArgsConstructor
public class ExpenseTaskController {

    private final ExpenseAgentService expenseAgentService;
    private final ExpenseTaskQueryService expenseTaskQueryService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody CreateExpenseRequest request) {
        long taskId = expenseAgentService.start(
                request.userId(),
                request.requestText()
        );
        return ResponseEntity.accepted().body(Map.of(
                "taskId", taskId,
                "message", "任务已进入 Spring AI Alibaba Graph 工作流"
        ));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<TaskStatusView> get(
            @PathVariable long taskId) {
        return ResponseEntity.ok(
                expenseTaskQueryService.get(taskId)
        );
    }

    /**
     * 仅用于故障恢复，不用于跳过 Human-in-the-loop。
     * WAITING_* 状态调用这里会返回 409。
     */
    @PostMapping("/{taskId}/resume")
    public ResponseEntity<Map<String, Object>> resume(
            @PathVariable long taskId) {
        expenseAgentService.resume(taskId);
        return ResponseEntity.accepted().body(Map.of(
                "taskId", taskId,
                "message", "故障恢复请求已提交"
        ));
    }
}
