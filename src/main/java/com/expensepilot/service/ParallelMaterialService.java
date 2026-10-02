package com.expensepilot.service;

import com.expensepilot.tool.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.*;

/**
 * 邮箱、网盘、差旅互不依赖，因此并行执行。
 * 每类外部系统使用独立有界线程池，避免慢工具扩散。
 */
@Service
public class ParallelMaterialService {

    private final ToolGateway toolGateway;
    private final Executor emailExecutor;
    private final Executor driveExecutor;
    private final Executor travelExecutor;

    public ParallelMaterialService(
            ToolGateway toolGateway,
            @Qualifier("emailExecutor") Executor emailExecutor,
            @Qualifier("driveExecutor") Executor driveExecutor,
            @Qualifier("travelExecutor") Executor travelExecutor) {
        this.toolGateway = toolGateway;
        this.emailExecutor = emailExecutor;
        this.driveExecutor = driveExecutor;
        this.travelExecutor = travelExecutor;
    }

    public Map<String, ToolResult> collect(long taskId, String userId, Map<String, Object> range) {
        CompletableFuture<ToolResult> email = callAsync(taskId, userId, "search_email", range, emailExecutor);
        CompletableFuture<ToolResult> drive = callAsync(taskId, userId, "search_drive", range, driveExecutor);
        CompletableFuture<ToolResult> travel = callAsync(taskId, userId, "query_travel", range, travelExecutor);

        CompletableFuture.allOf(email, drive, travel).join();
        return Map.of("email", email.join(), "drive", drive.join(), "travel", travel.join());
    }

    private CompletableFuture<ToolResult> callAsync(
            long taskId, String userId, String tool, Map<String, Object> args, Executor executor) {
        return CompletableFuture.supplyAsync(
                () -> toolGateway.execute(new ToolCall(taskId, userId, tool, args, false, "QUERY")),
                executor
        ).orTimeout(5, TimeUnit.SECONDS);
    }
}
