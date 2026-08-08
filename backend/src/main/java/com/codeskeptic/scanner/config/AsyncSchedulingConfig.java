package com.codeskeptic.scanner.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import com.codeskeptic.scanner.task.ResponseGenerationScheduler;

/**
 * Deferred-work infrastructure: the scheduling capability, the scheduler that serves it, and the
 * registration that paces the one scheduled operation.
 *
 * <p>{@code @EnableScheduling} is declared here and on no other class. The one scheduled operation is
 * {@link ResponseGenerationScheduler#generatePendingResponses()}, registered by
 * {@link #configureTasks(ScheduledTaskRegistrar)} against a completion-based {@link Trigger} rather
 * than by an annotation on the method — DL-047, DL-309. The interval runs from the completion of one
 * pass to the start of the next, which is the work-then-sleep behaviour of
 * {@code backend/app/tasks/response_generation.py:L41-50}; the first pass runs at the startup instant.
 *
 * <p>The trigger holds no pacing value of its own. It asks
 * {@link ResponseGenerationScheduler#responseGenerationDelaySecondsInForce()} once per pass, at the
 * moment the next instant is computed, so the {@code response_generation_delay} {@code settings} row
 * sets the cadence and an operator's edit through {@code PUT /settings/{key}} takes effect on the
 * following interval without a restart — DL-227, DL-228, DL-309. An instant already computed is never
 * revised, so an edit observed part-way through a wait moves the interval after that wait and not the
 * wait itself.
 *
 * <p>At shutdown the scheduler stops accepting work and awaits a pass already running for up to
 * {@value #SHUTDOWN_AWAIT_SECONDS} seconds; the cancellation policy is the
 * {@link ThreadPoolTaskScheduler} default. {@code @EnableAsync} is not declared, and no message
 * broker, queue, distributed scheduler lock, {@code ApplicationRunner} or {@code CommandLineRunner} is
 * declared here — DL-047.
 *
 * <p>Singleton configuration class holding no mutable state; every member declared here is safe for
 * concurrent use.
 */
// The scheduling capability is ported from backend/app/main.py:L43-48 and
// backend/app/tasks/response_generation.py:L8 (faithful port) — see docs/DECISION_LOG.md DL-047.
// The completion-based trigger is ported from the work-then-sleep order of :L41-50 and the
// settings-backed interval :L50 read — DL-227, DL-228, DL-309 — see docs/DECISION_LOG.md.
// The thread pool is net-new: no source construct configured one — DL-251 — see
// docs/DECISION_LOG.md.
@Configuration
@EnableScheduling
public class AsyncSchedulingConfig implements SchedulingConfigurer {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(AsyncSchedulingConfig.class);

    private static final String THREAD_NAME_PREFIX = "scanner-scheduler-";

    private static final int POOL_SIZE = 2;

    /**
     * Seconds the container waits at shutdown for a pass that is already running — DL-251 — see
     * docs/DECISION_LOG.md.
     */
    private static final int SHUTDOWN_AWAIT_SECONDS = 30;

    /**
     * Resolves the pass on first execution rather than at configuration time — DL-309.
     *
     * <p>{@link #configureTasks(ScheduledTaskRegistrar)} runs while the framework is registering
     * scheduled tasks, which is before every singleton is necessarily initialised, so the task and the
     * trigger both reach the bean through this provider at the instant they need it rather than
     * holding a reference resolved earlier.
     */
    private final ObjectProvider<ResponseGenerationScheduler> responseGenerationScheduler;

    /**
     * Binds the provider of the one scheduled operation.
     *
     * @param responseGenerationScheduler resolves {@link ResponseGenerationScheduler} at execution
     *     time; never {@code null}
     * @throws NullPointerException when the argument is {@code null}
     */
    public AsyncSchedulingConfig(
            ObjectProvider<ResponseGenerationScheduler> responseGenerationScheduler) {
        this.responseGenerationScheduler = Objects.requireNonNull(responseGenerationScheduler,
                "responseGenerationScheduler must not be null.");
    }

    /**
     * Publishes the scheduler that executes the registered task.
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

    /**
     * Registers the response-generation pass against the completion-based trigger.
     *
     * <p>Exactly one task is registered, and it is the only scheduled work in this application. The
     * trigger is {@link #nextResponseGenerationPass(TriggerContext)} — DL-309.
     *
     * @param registrar the framework's task registrar, never {@code null}
     */
    // Net-new registration site; the pacing it carries is ported from
    // backend/app/tasks/response_generation.py:L41-50 — DL-309 — see docs/DECISION_LOG.md
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(
                () -> responseGenerationScheduler.getObject().generatePendingResponses(),
                this::nextResponseGenerationPass);

        log.info("Response generation registered against a completion-based trigger; the interval is "
                + "read from the 'response_generation_delay' setting before each pass, falling back "
                + "to scanner.response-generation-delay-seconds");
    }

    /**
     * Computes the instant of the next response-generation pass.
     *
     * <p>Before the first pass there is no completion to measure from, so the returned instant is the
     * current one and the first pass runs at the startup instant — the order the source loop ran in,
     * which performed work before it slept. Afterwards the instant is the previous pass's completion
     * plus the interval in force, which is a fixed <em>delay</em> and never a fixed rate: no pass can
     * overlap its predecessor however long a pass takes — DL-047, IR10.
     *
     * <p>The interval is read here, once per pass, from
     * {@link ResponseGenerationScheduler#responseGenerationDelaySecondsInForce()} — which prefers the
     * {@code response_generation_delay} {@code settings} row and falls back to
     * {@code scanner.response-generation-delay-seconds} — so an operator edit is picked up by the next
     * computation and an instant already computed is left alone — DL-227, DL-228, DL-309.
     *
     * @param context the framework's record of the previous execution, never {@code null}
     * @return the instant the next pass runs at; never {@code null}
     */
    // Ported from the work-then-sleep order of backend/app/tasks/response_generation.py:L41-50
    // (faithful port of intent) — DL-309 — see docs/DECISION_LOG.md
    Instant nextResponseGenerationPass(TriggerContext context) {
        Instant lastCompletion = context.lastCompletion();
        if (lastCompletion == null) {
            return context.getClock().instant();
        }

        long delaySeconds =
                responseGenerationScheduler.getObject().responseGenerationDelaySecondsInForce();
        return lastCompletion.plus(Duration.ofSeconds(delaySeconds));
    }
}
