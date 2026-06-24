package com.logistics.planner.config;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Value("${async.planning.core-pool-size:4}")
    private int corePoolSize;

    @Value("${async.planning.max-pool-size:10}")
    private int maxPoolSize;

    @Value("${async.planning.queue-capacity:50}")
    private int queueCapacity;

    @Bean(name = "planningExecutor")
    public Executor planningExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("planning-");
        executor.initialize();
        return executor;
    }
}
