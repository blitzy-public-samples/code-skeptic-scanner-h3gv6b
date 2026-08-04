package com.codeskeptic.scanner.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Deferred-work infrastructure for the backend service: the scheduling capability and the thread
 * pool that serves it.
 *
 * <p>Ported from {@code backend/app/main.py:L43-48} and
 * {@code backend/app/tasks/response_generation.py:L8} (faithful port) - see
 * {@code docs/DECISION_LOG.md} DL-047, DL-058.
 *
 * <p>Two Python constructs are replaced. The first is {@code initialize_background_tasks()} at
 * {@code backend/app/main.py:L43-48}, whose comment at {@code L44} named a separate thread that was
 * never created: {@code start_tweet_stream()} at {@code L45} and
 * {@code schedule_response_generation()} at {@code L48} both ran on the calling thread, and
 * {@code L52} invoked the function before {@code app.run(debug=True)} at {@code L53}. The second is
 * {@code celery_app = Celery('code_skeptic_scanner')} at
 * {@code backend/app/tasks/response_generation.py:L8}, constructed with no broker argument and no
 * result backend; the {@code generate_response.delay(tweet.id)} dispatch at {@code L47} had no
 * transport.
 *
 * <p>{@code @EnableScheduling} is declared on this class and on no other class in this application.
 * It is what activates the {@code @Scheduled} annotations in the tree, among them
 * {@code @Scheduled(fixedDelayString = "${scanner.response-generation-delay-seconds}", timeUnit =
 * TimeUnit.SECONDS)} on {@code com.codeskeptic.scanner.task.ResponseGenerationScheduler}, which
 * holds the work the {@code while True} loop at
 * {@code backend/app/tasks/response_generation.py:L41-50} performed. Without the annotation on this
 * class those declarations are inert.
 *
 * <p>The class declares no scheduled method, reads no configuration key, injects no collaborator
 * and holds no mutable state. Pacing is declared per task at each {@code @Scheduled} site, not
 * here. {@code @EnableAsync} is not declared, and no type in this application is annotated
 * {@code @Async}. No message broker, queue, distributed scheduler lock, {@code ApplicationRunner}
 * or {@code CommandLineRunner} is declared here, and the X (Twitter) stream lifecycle belongs to
 * {@code com.codeskeptic.scanner.task.TweetStreamClient}.
 *
 * <p>Decisions covering this class are recorded in {@code docs/DECISION_LOG.md} DL-047 and DL-058;
 * construct-level provenance is recorded in {@code docs/TRACEABILITY_MATRIX.md}.
 */
@Configuration
@EnableScheduling
public class AsyncSchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncSchedulingConfig.class);

    /**
     * Number of platform threads in the scheduling pool. Scheduled work runs on these threads and
     * never on a request-handling thread.
     */
    private static final int POOL_SIZE = 2;

    /**
     * Prefix applied to the name of every thread in the pool. It appears in thread dumps and in log
     * output.
     */
    private static final String THREAD_NAME_PREFIX = "scanner-scheduler-";

    /**
     * Seconds the pool waits at shutdown for a scheduled run that is already in flight to finish
     * before the context closes.
     */
    private static final int AWAIT_TERMINATION_SECONDS = 30;

    /**
     * Publishes the scheduler that executes every {@code @Scheduled} method in this application.
     *
     * <p>The bean is named {@code taskScheduler}. That is the name
     * {@code ScheduledAnnotationBeanPostProcessor} falls back to when resolving a scheduler, and it
     * is the name Spring Boot's {@code TaskSchedulingConfigurations} gives the scheduler it
     * auto-configures. That auto-configuration is annotated
     * {@code @ConditionalOnMissingBean({TaskScheduler.class, ScheduledExecutorService.class})} and
     * backs off in favour of this bean, leaving exactly one scheduler in the context.
     *
     * <p>The pool is configured from the constants declared above; {@code spring.task.scheduling.*}
     * is not read and no property placeholder is resolved. No {@code ErrorHandler} is set, so the
     * framework default applies: an exception thrown by a scheduled run is logged and suppressed
     * and the task stays scheduled. Nothing configured here affects pacing - the fixed-delay
     * interval of each task is declared at its own {@code @Scheduled} site.
     *
     * <p>{@link ThreadPoolTaskScheduler} inherits {@code InitializingBean}, {@code DisposableBean}
     * and {@code SmartLifecycle}. The container builds the underlying executor during bean
     * initialisation and shuts it down when the context closes; this method returns the configured
     * instance without calling {@code initialize()}.
     *
     * @return the single {@link ThreadPoolTaskScheduler} bean in the application context, resolvable
     *     by type and by the name {@code taskScheduler}; never {@code null}
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(POOL_SIZE);
        scheduler.setThreadNamePrefix(THREAD_NAME_PREFIX);

        // Shutdown drains a scheduled run that is already in flight; it does not interrupt it.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);

        // Purge a cancelled task from the work queue at cancellation time.
        scheduler.setRemoveOnCancelPolicy(true);

        log.info(
                "Scheduling enabled: pool size {}, thread name prefix '{}', shutdown wait {}s",
                POOL_SIZE,
                THREAD_NAME_PREFIX,
                AWAIT_TERMINATION_SECONDS);

        return scheduler;
    }
}
