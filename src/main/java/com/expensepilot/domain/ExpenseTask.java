package com.expensepilot.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * MySQL 中的任务事实记录。
 * version 用于 CAS：即使 Redis 租约因 GC/网络抖动失效，也不允许旧执行者覆盖新状态。
 */
@Data
@TableName("expense_task")
public class ExpenseTask {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String userId;
    private String requestText;
    private TaskStatus status;
    private String currentNode;
    private String threadId;
    private String lastError;
    private Integer retryCount;

    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
