package com.expensepilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 外部系统采用独立有界线程池，避免某个慢 MCP 把核心调度线程全部占满。
 * 这是舱壁隔离思想：邮箱慢只影响邮箱，不拖垮差旅、网盘和整个 Agent。
 */
@Configuration
public class ExecutorConfig {

    @Bean("emailExecutor")
    public Executor emailExecutor() { return executor("email-", 8, 16, 100); }

    @Bean("driveExecutor")
    public Executor driveExecutor() { return executor("drive-", 8, 16, 100); }

    @Bean("travelExecutor")
    public Executor travelExecutor() { return executor("travel-", 8, 16, 100); }

    private Executor executor(String prefix, int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
