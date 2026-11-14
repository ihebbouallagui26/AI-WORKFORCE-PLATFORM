package com.aiworkforce.core.agent;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Configuration of agent executions: limits, the small hand-over pool and the stale-execution reaper schedule. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ExecutionProperties.class)
class ExecutionConfig {

    /**
     * Pool of the hand-over to the orchestrator. Bounded on purpose, and it REJECTS when saturated
     * ({@code AbortPolicy}): a caller-runs policy would execute the orchestration HTTP call on the
     * request thread of the user. A rejection is handled explicitly by {@link ExecutionDispatcher}.
     */
    @Bean(name = "executionDispatchExecutor")
    ThreadPoolTaskExecutor executionDispatchExecutor(ExecutionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.min(2, properties.dispatchMaxThreads()));
        executor.setMaxPoolSize(properties.dispatchMaxThreads());
        executor.setQueueCapacity(properties.dispatchQueueCapacity());
        executor.setThreadNamePrefix("exec-dispatch-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
