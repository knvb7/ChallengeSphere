package com.ironside.challengesphere.config;

import java.util.concurrent.Executor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Async Configuration for ChallengeSphere
 * Enables asynchronous method execution for non-blocking cache operations
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger logger = LogManager.getLogger(AsyncConfig.class);

    /**
     * Configure the executor for async cache operations
     * Uses a thread pool to handle cache writes without blocking main request threads
     */
    @Bean(name = "cacheExecutor")
    public Executor cacheExecutor() {
        logger.info("Configuring async cache executor");
        
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int corePoolSize = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("cache-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler((r, e) ->
            logger.warn("Cache async task rejected due to thread pool exhaustion"));
        executor.initialize();

        logger.info("Async cache executor configured: corePoolSize={}, maxPoolSize=10, queueCapacity=100", corePoolSize);
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return cacheExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> {
            logger.error("Async exception in method '{}' with params {}: {}", 
                    method.getName(), params, ex.getMessage(), ex);
        };
    }
}
