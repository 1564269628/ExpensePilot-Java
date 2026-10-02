package com.expensepilot.api;

import com.expensepilot.approval.ApprovalService;
import com.expensepilot.service.ExpenseAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 人工审批入口。审批后可让原 Agent 从 Checkpoint 继续。 */
@RestController
@RequestMapping("/api/v1/expense-tasks/{taskId}/approval")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;
    private final ExpenseAgentService expenseAgentService;

    @PostMapping("/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable long taskId,
            @RequestParam(defaultValue = "SUBMIT_REPORT") String operationType,
            @RequestParam String approver,
            @RequestParam(defaultValue = "") String comment) {
        approvalService.approve(taskId, operationType, approver, comment);
        expenseAgentService.resume(taskId);
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", "APPROVED"));
    }

    @PostMapping("/reject")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable long taskId,
            @RequestParam(defaultValue = "SUBMIT_REPORT") String operationType,
            @RequestParam String approver,
            @RequestParam(defaultValue = "") String comment) {
        approvalService.reject(taskId, operationType, approver, comment);
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", "REJECTED"));
    }
}
