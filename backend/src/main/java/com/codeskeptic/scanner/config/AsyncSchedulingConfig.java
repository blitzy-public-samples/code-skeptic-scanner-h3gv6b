package com.codeskeptic.scanner.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.codeskeptic.scanner.task.ResponseGenerationScheduler;

/**
 * Deferred-work infrastructure for the backend service: the scheduling capability and the scheduler
 * that serves it.
 *
 * <p>{@code @EnableScheduling} is declared on this class and on no other class in this application.
 * It activates the scheduling that runs
 * {@link ResponseGenerationScheduler#generatePendingResponses()}, the one scheduled operation in the
 * application. That method carries its own
 * {@code @Scheduled(fixedDelayString = "${scanner.response-generation-delay-seconds}")} in seconds,
 * so the interval runs from the completion of one pass to the start of the next — the work-then-sleep
 * behaviour of {@code backend/app/tasks/response_generation.py:L41-50} — and this class holds no
 * pacing value, registers no task and reads no {@code settings} row — DL-047, DL-227.
 *
 * <p>The scheduler carries a pool of {@value #POOL_SIZE} threads. At shutdown it stops accepting work
 * and awaits a pass that is already running for up to {@value #SHUTDOWN_AWAIT_SECONDS} seconds. The
 * cancellation policy is left at the {@link ThreadPoolTaskScheduler} default. {@code @EnableAsync} is
 * not declared. No {@code SchedulingConfigurer}, {@code Trigger}, message broker, queue, distributed
 * scheduler lock, {@code ApplicationRunner} or {@code CommandLineRunner} is declared here — DL-047.
 *
 * <p>This is a singleton configuration class holding no mutable state; every member declared here is
 * safe for concurrent use.
 */
// The scheduling capability is ported from backend/app/main.py:L43-48 and
// backend/app/tasks/response_generation.py:L8 (faithful port) — see docs/DECISION_LOG.md DL-047.
// The thread pool is net-new: no source construct configured one — DL-251 — see
// docs/DECISION_LOG.md.
@Configuration
@EnableScheduling
public class AsyncSchedulingConfig {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(AsyncSchedulingConfig.class);

    private static final String THREAD_NAME_PREFIX = "scanner-scheduler-";

    /** Threads the scheduler runs concurrently. */
    private static final int POOL_SIZE = 2;

    /**
     * Seconds the container waits at shutdown for a pass that is already running — DL-251 — see
     * docs/DECISION_LOG.md.
     */
    private static final int SHUTDOWN_AWAIT_SECONDS = 30;

    /**
     * Publishes the scheduler that executes the annotated method.
     *
     * <p>The bean is named {@code taskScheduler}, which is both the name
     * {@code ScheduledAnnotationBeanPostProcessor} falls back to when resolving a scheduler and the
     * name Spring Boot's auto-configured scheduler carries. Auto-configuration backs off and the
     * context holds exactly one scheduler.
     *
     * <p>Four values are set on the instance: the thread-name prefix, a pool of
     * {@value #POOL_SIZE} threads, waiting for tasks to complete on shutdown, and a termination wait of
     * {@value #SHUTDOWN_AWAIT_SECONDS} seconds — DL-251. The cancellation policy is left at the
     * {@link ThreadPoolTaskScheduler} default and {@code spring.task.scheduling.*} is not read. No
     * {@code ErrorHandler} is set; the framework default applies, under which an exception thrown by a
     * scheduled run is logged and suppressed and the task stays scheduled.
     *
     * <p>The container builds the underlying executor during bean initialisation and shuts it down
     * when the context closes; this method returns the configured instance without calling
     * {@code initialize()}.
     *
     * @return the single {@link ThreadPoolTaskScheduler} bean in the application context, resolvable
     *     by type and by the name {@code taskScheduler}; never {@code null}
     */
    // Net-new (no source construct) — see docs/DECISION_LOG.md DL-047
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix(THREAD_NAME_PREFIX);
        // Pool size stated explicitly — see docs/DECISION_LOG.md DL-251
        scheduler.setPoolSize(POOL_SIZE);
        // A pass already running is awaited for a bounded time at shutdown — DL-251 — see
        // docs/DECISION_LOG.md
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);

        log.info("Scheduling enabled; scheduled work runs on {} thread(s) named '{}' and shutdown "
                + "awaits a running pass for up to {}s",
                POOL_SIZE, THREAD_NAME_PREFIX, SHUTDOWN_AWAIT_SECONDS);

        return scheduler;
    }
}
