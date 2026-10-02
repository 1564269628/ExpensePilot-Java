package com.expensepilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 所有业务线程池都由配置驱动，避免 application.yml 和真实运行参数脱节。
 */
@ConfigurationProperties(prefix = "expensepilot.pools")
public record ExecutorPoolProperties(
        Pool graph,
        Pool email,
        Pool drive,
        Pool travel
) {

    public ExecutorPoolProperties {
        require("graph", graph);
        require("email", email);
        require("drive", drive);
        require("travel", travel);
    }

    public record Pool(
            int core,
            int max,
            int queue
    ) {
        public Pool {
            if (core <= 0) {
                throw new IllegalArgumentException(
                        "pool core 必须 > 0");
            }
            if (max < core) {
                throw new IllegalArgumentException(
                        "pool max 必须 >= core");
            }
            if (queue < 0) {
                throw new IllegalArgumentException(
                        "pool queue 必须 >= 0");
            }
        }
    }

    private static void require(
            String name,
            Pool pool) {
        if (pool == null) {
            throw new IllegalArgumentException(
                    "缺少线程池配置: expensepilot.pools."
                            + name);
        }
    }
}
