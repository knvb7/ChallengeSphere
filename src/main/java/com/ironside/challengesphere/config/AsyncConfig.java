package com.ironside.challengesphere.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

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
 * Enables asynchronous method execution for cache, moderation, and notification work.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger logger = LogManager.getLogger(AsyncConfig.class);

    /**
     * Configure the shared executor for asynchronous background work.
     */
    @Bean(name = "asyncExecutor")
    public Executor asyncExecutor() {
        logger.info("Configuring shared async executor");
        
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int corePoolSize = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(corePoolSize + 5);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("app-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // Notification work shares this executor with cache work. Running a saturated
        // task on the caller thread preserves delivery instead of silently dropping it.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();

        logger.info("Async executor configured: corePoolSize={}, maxPoolSize={}, queueCapacity=100",
                corePoolSize, corePoolSize + 5);
        return executor;
    }

    /**
     * Moderation must never fall back to the HTTP request thread. If this queue
     * is temporarily full, dispatch is rejected and the database-backed
     * moderation scheduler will pick up the still-PENDING submission.
     */
    @Bean(name = "moderationExecutor")
    public Executor moderationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(Math.max(1, Math.min(2, availableProcessors)));
        executor.setMaxPoolSize(Math.max(2, Math.min(4, availableProcessors)));
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("moderation-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return asyncExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, ignoredArguments) -> {
            logger.error("Async method failed | method={} exception={}",
                    method.getName(), ex.getClass().getSimpleName());
        };
    }
}
