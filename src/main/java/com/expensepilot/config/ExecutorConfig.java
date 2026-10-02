package com.expensepilot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 资源隔离有界线程池。
 *
 * <p>Graph 负责拓扑；邮箱、网盘、差旅分别使用独立池，慢上游不会把其他系统的
 * 执行线程全部占满。队列满时使用 AbortPolicy，调用方必须显式处理拒绝，而不是
 * 无限堆积内存。</p>
 */
@Configuration
@EnableConfigurationProperties(ExecutorPoolProperties.class)
public class ExecutorConfig {

    @Bean("graphExecutor")
    public Executor graphExecutor(
            ExecutorPoolProperties properties) {
        return executor(
                "graph-",
                properties.graph()
        );
    }

    @Bean("emailExecutor")
    public Executor emailExecutor(
            ExecutorPoolProperties properties) {
        return executor(
                "email-",
                properties.email()
        );
    }

    @Bean("driveExecutor")
    public Executor driveExecutor(
            ExecutorPoolProperties properties) {
        return executor(
                "drive-",
                properties.drive()
        );
    }

    @Bean("travelExecutor")
    public Executor travelExecutor(
            ExecutorPoolProperties properties) {
        return executor(
                "travel-",
                properties.travel()
        );
    }

    private Executor executor(
            String prefix,
            ExecutorPoolProperties.Pool pool) {

        ThreadPoolTaskExecutor executor =
                new ThreadPoolTaskExecutor();

        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(pool.core());
        executor.setMaxPoolSize(pool.max());
        executor.setQueueCapacity(pool.queue());
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();

        return executor;
    }
}
