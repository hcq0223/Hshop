package com.shopai.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 配置延迟执行调度器，用于缓存的双删策略。
 * 在应用关闭时，尝试中断正在运行的任务并清空等待队列。
 * <p>
 * 微服务拆分说明：从原单体 com.example.demo.Config.SchedulerConfig 迁移至公共库。
 * 使用"延迟双删缓存"的服务通过组件扫描自动装配 cacheDelayScheduler Bean。
 */
@Configuration
public class SchedulerConfig {

    @Bean(destroyMethod = "shutdownNow", name = "cacheDelayScheduler")
    public ScheduledExecutorService cacheDelayScheduler() {
        // 核心线程数可根据并发量调整，一般 2~5 个足够
        return Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "cache-delay-");
            t.setDaemon(true); // 设置为守护线程，不影响 JVM 关闭
            return t;
        });
    }

}
