package com.codeskeptic.scanner.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Deferred-work infrastructure for the backend service: the scheduling capability and the
 * scheduler that serves it.
 *
 * <p>{@code @EnableScheduling} is declared on this class and on no other class in this application.
 * It is what activates every {@code @Scheduled} annotation in the tree.
 *
 * <p>The class declares no scheduled method, reads no configuration key, injects no collaborator and
 * holds no mutable state. Pacing is declared per task at each {@code @Scheduled} site, not here. No
 * pool size, shutdown policy, termination wait or cancellation policy is set on the scheduler: the
 * {@link ThreadPoolTaskScheduler} defaults apply unchanged. {@code @EnableAsync} is not declared. No
 * message broker, queue, distributed scheduler lock, {@code ApplicationRunner} or
 * {@code CommandLineRunner} is declared here.
 */
// The scheduling capability is ported from backend/app/main.py:L43-48 and
// backend/app/tasks/response_generation.py:L8 (faithful port) — see docs/DECISION_LOG.md DL-047.
// The thread pool below is net-new: neither source construct configured one.
@Configuration
@EnableScheduling
public class AsyncSchedulingConfig {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(AsyncSchedulingConfig.class);

    private static final String THREAD_NAME_PREFIX = "scanner-scheduler-";

    /**
     * Publishes the scheduler that executes every {@code @Scheduled} method in this application.
     *
     * <p>Net-new (no source construct) — see docs/DECISION_LOG.md DL-047.
     *
     * <p>The bean is named {@code taskScheduler}, which is both the name
     * {@code ScheduledAnnotationBeanPostProcessor} falls back to when resolving a scheduler and the
     * name Spring Boot's auto-configured scheduler carries. Auto-configuration backs off and the
     * context holds exactly one scheduler.
     *
     * <p>The thread-name prefix is the only value set on the instance. Pool size, shutdown
     * behaviour, termination wait and cancellation policy are left at the
     * {@link ThreadPoolTaskScheduler} defaults - a pool of one thread, no wait for in-flight work at
     * shutdown, and no purge of cancelled tasks - which are the same values Spring Boot's
     * auto-configured scheduler carries. {@code spring.task.scheduling.*} is not read and no
     * property placeholder is resolved. No {@code ErrorHandler} is set; the framework default
     * applies, under which an exception thrown by a scheduled run is logged and suppressed and the
     * task stays scheduled. Nothing set here affects pacing.
     *
     * <p>The container builds the underlying executor during bean initialisation and shuts it down
     * when the context closes; this method returns the configured instance without calling
     * {@code initialize()}.
     *
     * @return the single {@link ThreadPoolTaskScheduler} bean in the application context, resolvable
     *     by type and by the name {@code taskScheduler}; never {@code null}
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix(THREAD_NAME_PREFIX);

        log.info("Scheduling enabled; scheduled work runs on threads named '{}'", THREAD_NAME_PREFIX);

        return scheduler;
    }
}
