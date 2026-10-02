package com.expensepilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 资源隔离线程池。
 *
 * <p>Graph 负责拓扑和并行分支；每个外部系统节点再把真实 I/O 放到自己的有界线程池，
 * 因此邮箱慢不会占满差旅或 Graph 编排线程。</p>
 */
@Configuration
public class ExecutorConfig {

    @Bean("graphExecutor")
    public Executor graphExecutor() {
        return executor("graph-", 8, 16, 200);
    }

    @Bean("emailExecutor")
    public Executor emailExecutor() {
        return executor("email-", 8, 16, 100);
    }

    @Bean("driveExecutor")
    public Executor driveExecutor() {
        return executor("drive-", 8, 16, 100);
    }

    @Bean("travelExecutor")
    public Executor travelExecutor() {
        return executor("travel-", 8, 16, 100);
    }

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
