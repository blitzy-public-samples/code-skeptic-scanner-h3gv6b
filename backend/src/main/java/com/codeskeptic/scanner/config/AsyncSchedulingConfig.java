package com.codeskeptic.scanner.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.task.BackgroundOwnership;
import com.codeskeptic.scanner.task.ResponseGenerationScheduler;
import com.codeskeptic.scanner.util.LogSafe;

/**
 * Deferred-work infrastructure for the backend service: the scheduling capability and the scheduler
 * that serves it.
 *
 * <p>{@code @EnableScheduling} is declared on this class and on no other class in this application.
 * It activates the scheduling that runs
 * {@link ResponseGenerationScheduler#generatePendingResponses()}, the one scheduled operation in the
 * application. That method carries no scheduling annotation: this class registers it as a trigger
 * task and holds the pacing value — DL-047, DL-227.
 *
 * <p>{@link #configureTasks(ScheduledTaskRegistrar)} registers exactly one task,
 * {@link ResponseGenerationScheduler#generatePendingResponses()}, as a fixed-delay task: the interval
 * runs from the completion of one pass to the start of the next, which is the work-then-sleep
 * behaviour of {@code backend/app/tasks/response_generation.py:L41-50} — DL-047. The interval itself
 * is resolved before every pass: the {@code response_generation_delay} row of the {@code settings}
 * table takes precedence, and {@code scanner.response-generation-delay-seconds} applies when that row
 * is absent or does not hold a positive number of seconds — DL-227.
 *
 * <p>The scheduler carries a pool of {@value #POOL_SIZE} threads — one for the generation pass
 * and one for the ownership renewal, so a pass that runs long cannot delay a renewal and let the
 * lease lapse (DL-281) — and at shutdown it stops accepting
 * work and awaits a pass that is already running for up to {@value #SHUTDOWN_AWAIT_SECONDS} seconds.
 * The cancellation policy is left at the {@link ThreadPoolTaskScheduler} default.
 * {@code @EnableAsync} is not declared. No message broker, queue, distributed scheduler lock,
 * {@code ApplicationRunner} or {@code CommandLineRunner} is declared here — DL-251.
 *
 * <p>The row is read when the next execution instant is computed, which happens at the completion
 * of a pass. An instant already computed is not recomputed, so an edit made while the scheduler is
 * waiting does not move the pass that is already scheduled; it paces every pass after it — see
 * docs/DECISION_LOG.md DL-228.
 *
 * <p>The pass is registered only in a process that runs it: {@code scanner.background.enabled} and
 * {@code scanner.background.response-generation-enabled} must both hold, and a process for which
 * either is {@code false} registers no task at all — see docs/DECISION_LOG.md DL-250.
 *
 * <p>The first pass runs immediately, and not one interval after startup, matching the
 * work-then-sleep order of {@code backend/app/tasks/response_generation.py:L41-50}. A resolved interval
 * is read within {@value #MINIMUM_DELAY_SECONDS} second and {@value #MAXIMUM_DELAY_SECONDS} seconds, a
 * settings read that fails leaves the configured value in force, and no failure of the trigger can
 * leave the task unscheduled. Each of those three conditions is a standing one, so each is recorded at
 * {@code WARN} once per process and not once per pass — see docs/DECISION_LOG.md DL-251.
 *
 * <p>This is a singleton configuration class holding its two collaborators in {@code final} fields;
 * every member declared here is safe for concurrent use.
 */
// The scheduling capability is ported from backend/app/main.py:L43-48 and
// backend/app/tasks/response_generation.py:L8 (faithful port) — see docs/DECISION_LOG.md DL-047.
// The thread pool and the settings-backed interval are net-new: neither source construct configured
// one — DL-227, DL-228, DL-251 — see docs/DECISION_LOG.md.
@Configuration
@EnableScheduling
public class AsyncSchedulingConfig implements SchedulingConfigurer {

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

    /** Shortest interval a resolved value can pace a pass at — DL-251. */
    private static final long MINIMUM_DELAY_SECONDS = 1L;

    /**
     * Longest interval a resolved value can pace a pass at, one year in seconds. A larger resolved
     * value is read as this one — DL-251 — see docs/DECISION_LOG.md.
     */
    private static final long MAXIMUM_DELAY_SECONDS = 365L * 24L * 60L * 60L;

    /**
     * Key of the {@code settings} row that paces the response-generation pass. It is seeded by
     * {@code service.SettingsService} and is editable through {@code PUT /settings/{key}} — DL-040,
     * DL-227.
     */
    private static final String RESPONSE_GENERATION_DELAY_SETTING_KEY = "response_generation_delay";

    /** The pass that runs on the registered fixed-delay task. */
    private final ResponseGenerationScheduler responseGenerationScheduler;

    /** Decides whether this process runs background work at all — DL-281. */
    private final BackgroundOwnership backgroundOwnership;

    /** Data access for the {@code settings} table, read once per pass — DL-227. */
    private final SettingRepository settingRepository;

    /** Supplies {@code scanner.response-generation-delay-seconds}. */
    private final ScannerProperties properties;

    /** Guards the record raised when the {@code settings} row cannot be read — DL-251. */
    private final AtomicBoolean settingReadFailureReported = new AtomicBoolean();

    /** Guards the record raised when the stored interval does not parse — DL-251. */
    private final AtomicBoolean settingValueRejectedReported = new AtomicBoolean();

    /** Guards the record raised when the resolved interval is replaced by a bound — DL-251. */
    private final AtomicBoolean delayBoundedReported = new AtomicBoolean();

    /** Guards the record raised when a pass is skipped for want of ownership — DL-281. */
    private final AtomicBoolean passSkippedReported = new AtomicBoolean();

    /**
     * Binds the scheduled pass, the {@code settings} row that paces it and the configured fallback.
     *
     * @param responseGenerationScheduler the component carrying the scheduled pass, must not be
     *                                    {@code null}
     * @param settingRepository           the repository the {@code response_generation_delay} row is
     *                                    read from, must not be {@code null}
     * @param properties                  the bound configuration root carrying the fallback interval,
     *                                    must not be {@code null}
     * @param backgroundOwnership         the lease every registered task is gated on, must not be
     *                                    {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    public AsyncSchedulingConfig(ResponseGenerationScheduler responseGenerationScheduler,
            SettingRepository settingRepository,
            ScannerProperties properties,
            BackgroundOwnership backgroundOwnership) {
        this.responseGenerationScheduler = Objects.requireNonNull(responseGenerationScheduler,
                "responseGenerationScheduler must not be null.");
        this.settingRepository = Objects.requireNonNull(settingRepository,
                "settingRepository must not be null.");
        this.properties = Objects.requireNonNull(properties, "properties must not be null.");
        this.backgroundOwnership = Objects.requireNonNull(backgroundOwnership,
                "backgroundOwnership must not be null.");
    }

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

    // Replaces the `while True` loop and the trailing `time.sleep(...)` of
    // schedule_response_generation() at backend/app/tasks/response_generation.py:L41-50 (faithful
    // port of the pacing) — DL-047, DL-227, DL-228 — see docs/DECISION_LOG.md
    /**
     * Registers the response-generation pass as the one scheduled task.
     *
     * <p>The task is registered with a {@link Trigger} that adds the interval in force to the
     * completion of the previous pass, so no pass overlaps its predecessor and the interval is
     * measured end-to-start. The first pass runs as soon as the scheduler starts — DL-251.
     *
     * <p>Nothing is registered when this process does not run the pass — DL-250.
     *
     * @param registrar the registrar Spring supplies for this context
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        // The renewal keeps or takes the lease every background path is gated on, so it is registered
        // in every process whose switches admit any background work — DL-281 — see
        // docs/DECISION_LOG.md
        if (backgroundOwnership.isAutoStartup()) {
            long renewSeconds = leaseRenewSeconds();
            registrar.addFixedDelayTask(backgroundOwnership::renewOwnership,
                    Duration.ofSeconds(renewSeconds));
            log.info("Background-ownership renewal registered as a fixed-delay task every {}s",
                    renewSeconds);
        }

        // Only the designated background worker registers the pass — DL-250 — see
        // docs/DECISION_LOG.md
        if (!runsResponseGeneration()) {
            log.info("Response generation not registered in this process: "
                    + "scanner.background.enabled and scanner.background."
                    + "response-generation-enabled must both hold");
            return;
        }

        registrar.addTriggerTask(this::runResponseGenerationPass,
                this::nextResponseGenerationPass);

        log.info("Response generation registered as a fixed-delay task paced by the '{}' setting row, "
                + "falling back to scanner.response-generation-delay-seconds ({}s); the first pass "
                + "runs immediately",
                RESPONSE_GENERATION_DELAY_SETTING_KEY, properties.responseGenerationDelaySeconds());
    }

    // Net-new ownership gate on the registered pass — DL-281 — see docs/DECISION_LOG.md
    /**
     * Runs one generation pass when this process holds the background-ownership lease.
     *
     * <p>A pass is skipped whenever the lease is not held, which is the state of every process that is
     * not the background owner and of the owner itself once a renewal has failed. The skip is
     * recorded once per uninterrupted run of skipped passes: a process that never owns the lease
     * writes one record in total, none per interval — DL-197, DL-281.
     *
     * <p>The method raises nothing: {@link ResponseGenerationScheduler#generatePendingResponses()}
     * records and suppresses every failure of its own.
     */
    private void runResponseGenerationPass() {
        if (!backgroundOwnership.isOwner()) {
            if (passSkippedReported.compareAndSet(false, true)) {
                log.info("Response generation pass skipped: this process does not hold the "
                        + "background-ownership lease");
            }
            return;
        }
        passSkippedReported.set(false);
        responseGenerationScheduler.generatePendingResponses();
    }

    /**
     * Reports the renewal interval in force.
     *
     * @return the bound value of {@code scanner.background.lease-renew-seconds}, or the declared
     *     default when the group is unbound
     */
    private long leaseRenewSeconds() {
        ScannerProperties.Background background = properties.background();
        return background == null
                ? ScannerProperties.Background.of(true, true, true).leaseRenewSeconds()
                : background.leaseRenewSeconds();
    }

    /**
     * Reports whether this process runs the scheduled response-generation pass.
     *
     * @return {@code true} when {@code scanner.background.enabled} and
     *     {@code scanner.background.response-generation-enabled} both hold; {@code true} when the
     *     group is unbound, which is the default of both keys
     */
    // Net-new ownership switch — DL-250 — see docs/DECISION_LOG.md
    private boolean runsResponseGeneration() {
        ScannerProperties.Background background = properties.background();
        return background == null || background.runsResponseGeneration();
    }

    /**
     * Computes when the next response-generation pass starts.
     *
     * <p>Before the first pass the current instant is returned unchanged, so the pass runs and only
     * then waits — the work-then-sleep order of
     * {@code backend/app/tasks/response_generation.py:L41-50} — DL-251. Afterwards the interval in
     * force is added to the completion of the previous pass.
     *
     * <p>The instant is computed once per pass, from the interval in force at that moment. It is not
     * revised while the scheduler waits for it — see docs/DECISION_LOG.md DL-228.
     *
     * <p>A failure raised while the interval is resolved is recorded and answered with
     * {@value #MINIMUM_DELAY_SECONDS} second after the previous pass's completion, so the task always
     * stays scheduled — DL-251.
     *
     * @param context the completion of the previous pass, or an empty context before the first one
     * @return the current instant before the first pass, and otherwise the interval in force added to
     *     the previous pass's completion
     */
    // The first pass runs at once and a failed computation never ends the recurrence — DL-251 — see
    // docs/DECISION_LOG.md
    private Instant nextResponseGenerationPass(TriggerContext context) {
        Instant reference = context.lastCompletion();
        if (reference == null) {
            // The first pass runs at once, as the source ran its work before its first sleep at
            // backend/app/tasks/response_generation.py:L41-50 — DL-251 — see docs/DECISION_LOG.md
            return context.getClock().instant();
        }

        // No failure of this method may leave the task unscheduled — DL-251 — see
        // docs/DECISION_LOG.md
        try {
            return reference.plus(resolveDelay());
        } catch (RuntimeException unresolvable) {
            log.error("Could not compute the next response-generation pass ({}); pacing the next pass "
                    + "{}s after the previous one instead.", LogSafe.type(unresolvable),
                    MINIMUM_DELAY_SECONDS);
            return reference.plusSeconds(MINIMUM_DELAY_SECONDS);
        }
    }

    // Net-new one-shot reporting so a standing misconfiguration cannot flood the log — DL-251 — see
    // docs/DECISION_LOG.md
    /**
     * Records {@code message} at {@code WARN} the first time {@code guard} is raised and never again.
     *
     * @param guard     the one-shot guard for this condition, must not be {@code null}
     * @param message   the parameterised record, must not be {@code null}
     * @param arguments the record's arguments
     */
    private static void reportOnce(AtomicBoolean guard, String message, Object... arguments) {
        if (guard.compareAndSet(false, true)) {
            log.warn(message, arguments);
        }
    }

    /**
     * Resolves the interval in force.
     *
     * <p>The {@value #RESPONSE_GENERATION_DELAY_SETTING_KEY} row is read first and its value, once
     * surrounding whitespace is discarded, is used when it parses as a positive number of seconds. The
     * configured {@code scanner.response-generation-delay-seconds} applies when that row is absent,
     * holds {@code null}, holds a value that does not parse, or holds a value that is not positive;
     * the last two are recorded once at {@code WARN}, naming the key without recording the stored
     * value — DL-227.
     *
     * <p>A settings read that fails leaves the configured value in force and is recorded at
     * {@code WARN} — DL-251.
     *
     * <p>A configured value that is itself not positive is replaced by
     * {@value #MINIMUM_DELAY_SECONDS} second, so a pass can never be scheduled at or before the instant
     * the previous one completed, and a resolved value above {@value #MAXIMUM_DELAY_SECONDS} seconds is
     * read as that bound — DL-251.
     *
     * @return the interval before the next pass, never {@code null}, never shorter than
     *     {@value #MINIMUM_DELAY_SECONDS} second and never longer than
     *     {@value #MAXIMUM_DELAY_SECONDS} seconds
     */
    private Duration resolveDelay() {
        String stored;
        try {
            stored = settingRepository.findById(RESPONSE_GENERATION_DELAY_SETTING_KEY)
                    .map(Setting::getValue)
                    .orElse(null);
        } catch (RuntimeException unreadable) {
            // A settings read that fails leaves the configured value in force, and a standing failure
            // is recorded once — DL-251 — see docs/DECISION_LOG.md
            reportOnce(settingReadFailureReported,
                    "Setting '{}' could not be read ({}); applying "
                            + "scanner.response-generation-delay-seconds instead",
                    RESPONSE_GENERATION_DELAY_SETTING_KEY, LogSafe.type(unreadable));
            stored = null;
        }

        if (stored != null) {
            try {
                long seconds = Long.parseLong(stored.trim());
                if (seconds > 0L) {
                    return Duration.ofSeconds(bounded(seconds));
                }
                reportOnce(settingValueRejectedReported,
                        "Setting '{}' does not hold a positive number of seconds; applying "
                                + "scanner.response-generation-delay-seconds instead",
                        RESPONSE_GENERATION_DELAY_SETTING_KEY);
            } catch (NumberFormatException notANumber) {
                reportOnce(settingValueRejectedReported,
                        "Setting '{}' does not hold an integer; applying "
                                + "scanner.response-generation-delay-seconds instead",
                        RESPONSE_GENERATION_DELAY_SETTING_KEY);
            }
        }

        // backend/app/core/config.py:L11 — the configured delay
        long configured = properties.responseGenerationDelaySeconds();
        return Duration.ofSeconds(configured > 0L ? bounded(configured) : MINIMUM_DELAY_SECONDS);
    }

    /**
     * Reads a resolved number of seconds within the accepted interval.
     *
     * @param seconds the resolved value, already known to be positive
     * @return {@code seconds} when it is at most {@value #MAXIMUM_DELAY_SECONDS}, and
     *     {@value #MAXIMUM_DELAY_SECONDS} otherwise, which is recorded once at {@code WARN}
     */
    // Net-new bound on a resolved interval, reported once — DL-251 — see docs/DECISION_LOG.md
    private long bounded(long seconds) {
        if (seconds <= MAXIMUM_DELAY_SECONDS) {
            return seconds;
        }
        reportOnce(delayBoundedReported,
                "A response-generation interval of {}s exceeds the {}s bound; pacing at the bound "
                        + "instead", seconds, MAXIMUM_DELAY_SECONDS);
        return MAXIMUM_DELAY_SECONDS;
    }
}
