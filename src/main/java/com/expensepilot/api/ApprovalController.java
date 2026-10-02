package com.expensepilot.api;

import com.expensepilot.approval.ApprovalService;
import com.expensepilot.security.CurrentUser;
import com.expensepilot.service.ExpenseAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Graph Human-in-the-loop 审批入口。
 *
 * <p>审批人只能取已验证 JWT subject，并要求 expense.approve OAuth scope。</p>
 *
 * <p>最终决定先写 MySQL，再尝试恢复 Graph。即使恢复投递时线程池满或进程崩溃，
 * RecoveryWorker 也能从 approval_record 重新发现并继续。</p>
 */
@RestController
@RequestMapping("/api/v1/expense-tasks/{taskId}/approval")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;
    private final ExpenseAgentService expenseAgentService;
    private final CurrentUser currentUser;

    @PostMapping("/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable long taskId,
            @RequestParam String operationType,
            @RequestParam(defaultValue = "") String comment) {

        String approver = requireApprover();

        // 没有 Graph 预创建的 PENDING 记录时，这里会失败；
        // 因此不能提前审批一个还没进入审批节点的任务。
        approvalService.approve(
                taskId,
                operationType,
                approver,
                comment
        );

        expenseAgentService.resumeApproval(
                taskId,
                operationType,
                true,
                approver
        );

        return ResponseEntity.ok(Map.of(
                "taskId", taskId,
                "operationType", operationType,
                "status", "APPROVED"
        ));
    }

    @PostMapping("/reject")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable long taskId,
            @RequestParam String operationType,
            @RequestParam(defaultValue = "") String comment) {

        String approver = requireApprover();

        approvalService.reject(
                taskId,
                operationType,
                approver,
                comment
        );

        expenseAgentService.resumeApproval(
                taskId,
                operationType,
                false,
                approver
        );

        return ResponseEntity.ok(Map.of(
                "taskId", taskId,
                "operationType", operationType,
                "status", "REJECTED"
        ));
    }

    private String requireApprover() {
        currentUser.requireAnyAuthority(
                "SCOPE_expense.approve"
        );

        return currentUser.userId();
    }
}
