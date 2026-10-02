package com.expensepilot.api;

import com.expensepilot.service.ExpenseAgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Graph 中断点的人工作业输入。 */
@RestController
@RequestMapping("/api/v1/expense-tasks/{taskId}")
@RequiredArgsConstructor
public class HumanInputController {

    private final ExpenseAgentService expenseAgentService;

    @PostMapping("/clarification")
    public ResponseEntity<Map<String, Object>> clarification(
            @PathVariable long taskId,
            @Valid @RequestBody ClarificationInput input) {

        expenseAgentService.resumeClarification(taskId, Map.of(
                "tripStart", input.startDate().toString(),
                "tripEnd", input.endDate().toString(),
                "tripCity", input.city()
        ));

        return ResponseEntity.accepted().body(Map.of(
                "taskId", taskId,
                "message", "澄清信息已写入 Graph State，工作流继续执行"
        ));
    }

    @PostMapping("/supplement")
    public ResponseEntity<Map<String, Object>> supplement(
            @PathVariable long taskId,
            @Valid @RequestBody SupplementRequest input) {

        expenseAgentService.resumeSupplement(taskId, Map.of(
                "supplementalMaterials", input.materialRefs()
        ));

        return ResponseEntity.accepted().body(Map.of(
                "taskId", taskId,
                "message", "补充材料引用已写入 Graph State，重新进行材料核验"
        ));
    }
}
