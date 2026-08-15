package com.hamstrack.common.async;

import com.hamstrack.common.config.MailAsyncProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Dedicated bounded executor for {@code @Async} mail (HD-78). {@code @EnableAsync}
 * with no configured executor falls back to Spring's unbounded, thread-per-task
 * {@code SimpleAsyncTaskExecutor} — under an SMTP stall (now bounded by HD-76 to
 * ≤10s/send) that spawns a thread per queued email. A bounded pool gives an
 * explicit queue + rejection policy: {@code CallerRunsPolicy} applies backpressure
 * (the request thread sends inline when the queue is full) rather than dropping
 * mail. Named {@code mailExecutor} and referenced by {@code @Async("mailExecutor")}
 * so mail can't starve any other future {@code @Async} work.
 */
@Configuration
@RequiredArgsConstructor
public class AsyncConfig {

    private final MailAsyncProperties properties;

    @Bean("mailExecutor")
    public TaskExecutor mailExecutor() {
        var async = properties.async();
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(async.corePoolSize());
        executor.setMaxPoolSize(async.maxPoolSize());
        executor.setQueueCapacity(async.queueCapacity());
        executor.setThreadNamePrefix("mail-");
        // Backpressure: send on the caller thread when the queue is full (bounded by
        // HD-76 SMTP timeouts) instead of dropping account-critical mail.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // Flush in-flight mail on graceful shutdown, bounded so we never hang.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(15);
        executor.initialize();
        return executor;
    }
}
