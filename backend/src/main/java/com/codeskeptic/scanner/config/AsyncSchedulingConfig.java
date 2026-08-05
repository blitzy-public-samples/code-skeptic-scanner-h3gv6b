package com.codeskeptic.scanner.config;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

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
import com.codeskeptic.scanner.task.ResponseGenerationScheduler;

/**
 * Deferred-work infrastructure for the backend service: the scheduling capability, the scheduler that
 * serves it and the pacing of the one scheduled task.
 *
 * <p>{@code @EnableScheduling} is declared on this class and on no other class in this application.
 *
 * <p>{@link #configureTasks(ScheduledTaskRegistrar)} registers exactly one task,
 * {@link ResponseGenerationScheduler#generatePendingResponses()}, as a fixed-delay task: the interval
 * runs from the completion of one pass to the start of the next, which is the work-then-sleep
 * behaviour of {@code backend/app/tasks/response_generation.py:L41-50} — DL-047. The interval itself
 * is resolved before every pass: the {@code response_generation_delay} row of the {@code settings}
 * table takes precedence, and {@code scanner.response-generation-delay-seconds} applies when that row
 * is absent or does not hold a positive number of seconds — DL-192.
 *
 * <p>No pool size, shutdown policy, termination wait or cancellation policy is set on the scheduler:
 * the {@link ThreadPoolTaskScheduler} defaults apply unchanged. {@code @EnableAsync} is not declared.
 * No message broker, queue, distributed scheduler lock, {@code ApplicationRunner} or
 * {@code CommandLineRunner} is declared here.
 *
 * <p>This is a singleton configuration class holding its two collaborators in {@code final} fields;
 * every member declared here is safe for concurrent use.
 */
// The scheduling capability is ported from backend/app/main.py:L43-48 and
// backend/app/tasks/response_generation.py:L8 (faithful port) — see docs/DECISION_LOG.md DL-047.
// The thread pool and the settings-backed interval are net-new: neither source construct configured
// one — DL-192 — see docs/DECISION_LOG.md.
@Configuration
@EnableScheduling
public class AsyncSchedulingConfig implements SchedulingConfigurer {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(AsyncSchedulingConfig.class);

    private static final String THREAD_NAME_PREFIX = "scanner-scheduler-";

    /** Threads the scheduler runs concurrently. */
    private static final int POOL_SIZE = 1;

    /**
     * Key of the {@code settings} row that paces the response-generation pass. It is seeded by
     * {@code service.SettingsService} and is editable through {@code PUT /settings/{key}} — DL-040,
     * DL-192.
     */
    private static final String RESPONSE_GENERATION_DELAY_SETTING_KEY = "response_generation_delay";

    /** The pass that runs on the registered fixed-delay task. */
    private final ResponseGenerationScheduler responseGenerationScheduler;

    /** Data access for the {@code settings} table, read once per pass — DL-192. */
    private final SettingRepository settingRepository;

    /** Supplies {@code scanner.response-generation-delay-seconds}. */
    private final ScannerProperties properties;

    /**
     * Binds the pass, the settings table and the bound configuration root.
     *
     * @param responseGenerationScheduler the pass to schedule, must not be {@code null}
     * @param settingRepository           data access for the {@code settings} table, must not be
     *                                    {@code null}
     * @param properties                  bound configuration root, must not be {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    public AsyncSchedulingConfig(ResponseGenerationScheduler responseGenerationScheduler,
            SettingRepository settingRepository,
            ScannerProperties properties) {
        this.responseGenerationScheduler = Objects.requireNonNull(responseGenerationScheduler,
                "responseGenerationScheduler must not be null.");
        this.settingRepository = Objects.requireNonNull(settingRepository,
                "settingRepository must not be null.");
        this.properties = Objects.requireNonNull(properties, "properties must not be null.");
    }

    /**
     * Publishes the scheduler that executes the registered task.
     *
     * <p>The bean is named {@code taskScheduler}, which is both the name
     * {@code ScheduledAnnotationBeanPostProcessor} falls back to when resolving a scheduler and the
     * name Spring Boot's auto-configured scheduler carries. Auto-configuration backs off and the
     * context holds exactly one scheduler.
     *
     * <p>The thread-name prefix is the only value set on the instance. Pool size, shutdown behaviour,
     * termination wait and cancellation policy are left at the {@link ThreadPoolTaskScheduler}
     * defaults — a pool of one thread, no wait for in-flight work at shutdown, and no purge of
     * cancelled tasks — which are the same values Spring Boot's auto-configured scheduler carries.
     * {@code spring.task.scheduling.*} is not read. No {@code ErrorHandler} is set; the framework
     * default applies, under which an exception thrown by a scheduled run is logged and suppressed and
     * the task stays scheduled.
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
        // Pool size stated rather than inherited — see docs/DECISION_LOG.md DL-195
        scheduler.setPoolSize(POOL_SIZE);

        log.info("Scheduling enabled; scheduled work runs on {} thread(s) named '{}'",
                POOL_SIZE, THREAD_NAME_PREFIX);

        return scheduler;
    }

    // Replaces the `while True` loop and the trailing `time.sleep(...)` of
    // schedule_response_generation() at backend/app/tasks/response_generation.py:L41-50 (faithful
    // port of the pacing) — DL-047, DL-192 — see docs/DECISION_LOG.md
    /**
     * Registers the response-generation pass as the one scheduled task.
     *
     * <p>The task is registered with a {@link Trigger} that adds the interval in force to the
     * completion of the previous pass, so no pass overlaps its predecessor and the interval is
     * measured end-to-start. The first pass runs one interval after the scheduler starts.
     *
     * @param registrar the registrar Spring supplies for this context
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(responseGenerationScheduler::generatePendingResponses,
                this::nextResponseGenerationPass);

        log.info("Response generation registered as a fixed-delay task paced by the '{}' setting row, "
                + "falling back to scanner.response-generation-delay-seconds ({}s)",
                RESPONSE_GENERATION_DELAY_SETTING_KEY, properties.responseGenerationDelaySeconds());
    }

    /**
     * Computes when the next response-generation pass starts.
     *
     * @param context the completion of the previous pass, or an empty context before the first one
     * @return the interval in force added to the previous pass's completion, or to the current instant
     *     before the first pass
     */
    private Instant nextResponseGenerationPass(TriggerContext context) {
        Instant reference = context.lastCompletion();
        if (reference == null) {
            reference = context.getClock().instant();
        }
        return reference.plus(resolveDelay());
    }

    /**
     * Resolves the interval in force.
     *
     * <p>The {@value #RESPONSE_GENERATION_DELAY_SETTING_KEY} row is read first and its value, once
     * surrounding whitespace is discarded, is used when it parses as a positive number of seconds. The
     * configured {@code scanner.response-generation-delay-seconds} applies when that row is absent,
     * holds {@code null}, holds a value that does not parse, or holds a value that is not positive;
     * the last two are recorded once at {@code WARN}, naming the key without recording the stored
     * value — DL-192.
     *
     * <p>A configured value that is itself not positive is replaced by one second, so a pass can never
     * be scheduled at or before the instant the previous one completed.
     *
     * @return the interval before the next pass, never {@code null} and never shorter than one second
     */
    private Duration resolveDelay() {
        String stored = settingRepository.findById(RESPONSE_GENERATION_DELAY_SETTING_KEY)
                .map(Setting::getValue)
                .orElse(null);

        if (stored != null) {
            try {
                long seconds = Long.parseLong(stored.trim());
                if (seconds > 0L) {
                    return Duration.ofSeconds(seconds);
                }
                log.warn("Setting '{}' does not hold a positive number of seconds; applying "
                        + "scanner.response-generation-delay-seconds instead",
                        RESPONSE_GENERATION_DELAY_SETTING_KEY);
            } catch (NumberFormatException notANumber) {
                log.warn("Setting '{}' does not hold an integer; applying "
                        + "scanner.response-generation-delay-seconds instead",
                        RESPONSE_GENERATION_DELAY_SETTING_KEY);
            }
        }

        // backend/app/core/config.py:L11 — the configured delay
        long configured = properties.responseGenerationDelaySeconds();
        return Duration.ofSeconds(configured > 0L ? configured : 1L);
    }
}
