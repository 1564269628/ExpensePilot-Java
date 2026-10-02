package com.expensepilot.api;

import com.expensepilot.cache.TaskStatusView;
import com.expensepilot.security.CurrentUser;
import com.expensepilot.security.TaskAuthorizationService;
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
    private final CurrentUser currentUser;
    private final TaskAuthorizationService taskAuthorizationService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(
            @Valid @RequestBody CreateExpenseRequest request) {

        String userId = currentUser.userId();

        long taskId = expenseAgentService.start(
                userId,
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

        taskAuthorizationService.assertOwner(
                taskId,
                currentUser.userId()
        );

        return ResponseEntity.ok(
                expenseTaskQueryService.get(taskId)
        );
    }

    @PostMapping("/{taskId}/resume")
    public ResponseEntity<Map<String, Object>> resume(
            @PathVariable long taskId) {

        taskAuthorizationService.assertOwner(
                taskId,
                currentUser.userId()
        );

        expenseAgentService.resume(taskId);

        return ResponseEntity.accepted().body(Map.of(
                "taskId", taskId,
                "message", "故障恢复请求已提交"
        ));
    }
}
