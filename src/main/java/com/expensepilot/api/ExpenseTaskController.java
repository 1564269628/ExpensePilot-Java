package com.expensepilot.api;

import com.expensepilot.service.ExpenseAgentService;
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

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateExpenseRequest request) {
        long taskId = expenseAgentService.start(request.userId(), request.requestText());
        return ResponseEntity.accepted().body(Map.of(
                "taskId", taskId,
                "message", "任务已进入 ExpensePilot Agent 工作流"
        ));
    }

    @PostMapping("/{taskId}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable long taskId) {
        expenseAgentService.resume(taskId);
        return ResponseEntity.accepted().body(Map.of("taskId", taskId, "message", "恢复请求已接收"));
    }
}
