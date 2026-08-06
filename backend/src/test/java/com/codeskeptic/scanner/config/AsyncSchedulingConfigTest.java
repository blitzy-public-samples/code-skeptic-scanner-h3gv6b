package com.codeskeptic.scanner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.config.TriggerTask;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.task.BackgroundOwnership;
import com.codeskeptic.scanner.task.ResponseGenerationScheduler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

// Net-new (no Python counterpart: backend/app/tasks/response_generation.py:L41-50 paced its own
// `while True` loop) — DL-047, DL-250, DL-251 — see docs/DECISION_LOG.md
/**
 * Exercises the pacing {@link AsyncSchedulingConfig} declares for the response-generation pass.
 *
 * <p>Two properties are asserted: the interval is measured from the completion of the previous pass,
 * which is fixed-delay and not fixed-rate behaviour, and the interval in force is the
 * {@code response_generation_delay} row of the {@code settings} table whenever that row holds a
 * positive number of seconds, falling back to
 * {@code scanner.response-generation-delay-seconds} otherwise.
 *
 * <p>Every collaborator is a Mockito double; no Spring context is started and no scheduler thread is
 * created.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AsyncSchedulingConfig")
class AsyncSchedulingConfigTest {

    /** Key of the seeded row that paces the pass. */
    private static final String DELAY_KEY = "response_generation_delay";

    /** Value of {@code scanner.response-generation-delay-seconds} in these tests. */
    private static final long CONFIGURED_DELAY_SECONDS = 60L;

    /** Value the {@value #DELAY_KEY} row holds when a test stores one. */
    private static final long STORED_DELAY_SECONDS = 5L;

    /** Instant the previous pass completed at. */
    private static final Instant LAST_COMPLETION = Instant.parse("2026-08-04T12:00:00Z");

    /** Instant the fixed clock reports when no pass has completed yet. */
    private static final Instant NOW = Instant.parse("2026-08-04T11:00:00Z");

    @Mock
    private ResponseGenerationScheduler responseGenerationScheduler;

    @Mock
    private SettingRepository settingRepository;

    private AsyncSchedulingConfig config;

    @Mock
    private BackgroundOwnership backgroundOwnership;

    @BeforeEach
    void setUp() {
        // The lease is held and background work is switched on unless a case says otherwise —
        // DL-281
        lenient().when(backgroundOwnership.isOwner()).thenReturn(true);
        lenient().when(backgroundOwnership.isAutoStartup()).thenReturn(true);
        ScannerProperties properties = new ScannerProperties("jdbc:h2:mem:scheduling", 100,
                CONFIGURED_DELAY_SECONDS, null, null, null, null, null, null, null, null);
        config = new AsyncSchedulingConfig(responseGenerationScheduler, settingRepository,
                properties, backgroundOwnership);
    }

    @Test
    @DisplayName("publishes one scheduler named for this application's scheduled work")
    void publishesOneSchedulerNamedForThisApplicationsScheduledWork() {
        ThreadPoolTaskScheduler scheduler = config.taskScheduler();

        assertThat(scheduler).isNotNull();
        assertThat(scheduler.getThreadNamePrefix()).isEqualTo("scanner-scheduler-");
    }

    // Bounded graceful termination — DL-251 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("publishes a scheduler that awaits a running pass for a bounded time at shutdown")
    void publishesASchedulerThatAwaitsARunningPassAtShutdown() throws Exception {
        ThreadPoolTaskScheduler scheduler = config.taskScheduler();

        assertThat(readBoolean(scheduler, "waitForTasksToCompleteOnShutdown"))
                .as("waits for a running pass at shutdown").isTrue();
        assertThat(readInt(scheduler, "awaitTerminationMillis"))
                .as("bounded termination wait in milliseconds").isEqualTo(30_000);
    }

    // A settings read that fails leaves the configured value in force — DL-251
    @Test
    @DisplayName("applies the configured delay when the settings row cannot be read")
    void appliesTheConfiguredDelayWhenTheSettingsRowCannotBeRead() {
        when(settingRepository.findById(DELAY_KEY))
                .thenThrow(new DataAccessResourceFailureException("no connection"));

        Instant next = nextExecution(triggerContextCompletedAt(LAST_COMPLETION));

        assertThat(next).isEqualTo(LAST_COMPLETION.plusSeconds(CONFIGURED_DELAY_SECONDS));
    }

    // A resolved interval is read within the accepted bound — DL-251
    @Test
    @DisplayName("reads a stored interval above the one-year bound as that bound")
    void readsAStoredIntervalAboveTheBoundAsTheBound() {
        long oneYearSeconds = 365L * 24L * 60L * 60L;
        when(settingRepository.findById(DELAY_KEY)).thenReturn(Optional.of(
                new Setting(DELAY_KEY, String.valueOf(Long.MAX_VALUE), "d")));

        Instant next = nextExecution(triggerContextCompletedAt(LAST_COMPLETION));

        assertThat(next).isEqualTo(LAST_COMPLETION.plusSeconds(oneYearSeconds));
    }

    // Only the designated background worker registers the pass — DL-250
    @ParameterizedTest(name = "enabled={0}, responseGenerationEnabled={1} registers no task")
    @CsvSource({"false,true", "false,false", "true,false"})
    @DisplayName("registers no task in a process that does not run the pass")
    void registersNoTaskInAProcessThatDoesNotRunThePass(boolean enabled,
            boolean responseGenerationEnabled) {
        AsyncSchedulingConfig nonOwner = configWithBackground(
                ScannerProperties.Background.of(enabled, true, responseGenerationEnabled));
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        nonOwner.configureTasks(registrar);

        assertThat(registrar.getTriggerTaskList()).as("triggered tasks").isEmpty();
        assertThat(registrar.getFixedRateTaskList()).as("fixed-rate tasks").isEmpty();
        assertThat(registrar.getCronTaskList()).as("cron tasks").isEmpty();
        verifyNoInteractions(responseGenerationScheduler, settingRepository);
    }

    // Ownership renewal and the gate on the pass — DL-281 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "enabled={0}, streamEnabled={1}, generationEnabled={2}")
    @CsvSource({"false,true,true", "false,true,false", "false,false,false"})
    @DisplayName("registers no task at all in a process that runs no background path")
    void registersNoTaskAtAllInAProcessThatRunsNoBackgroundPath(boolean enabled,
            boolean streamEnabled,
            boolean generationEnabled) {
        AsyncSchedulingConfig idle = configWithBackground(
                ScannerProperties.Background.of(enabled, streamEnabled, generationEnabled));
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        idle.configureTasks(registrar);

        assertThat(registrar.getTriggerTaskList()).as("triggered tasks").isEmpty();
        assertThat(registrar.getFixedDelayTaskList()).as("fixed-delay tasks").isEmpty();
    }

    @ParameterizedTest(name = "streamEnabled={0}, generationEnabled={1}")
    @CsvSource({"true,false", "false,true", "true,true"})
    @DisplayName("registers the ownership renewal whenever a background path is enabled")
    void registersTheOwnershipRenewalWheneverABackgroundPathIsEnabled(boolean streamEnabled,
            boolean generationEnabled) {
        AsyncSchedulingConfig worker = configWithBackground(
                ScannerProperties.Background.of(true, streamEnabled, generationEnabled));
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        worker.configureTasks(registrar);

        assertThat(registrar.getFixedDelayTaskList()).as("fixed-delay tasks").hasSize(1);
    }

    @Test
    @DisplayName("renews the lease from the registered fixed-delay task")
    void renewsTheLeaseFromTheRegisteredFixedDelayTask() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        config.configureTasks(registrar);
        registrar.getFixedDelayTaskList().get(0).getRunnable().run();

        verify(backgroundOwnership).renewOwnership();
    }

    @Test
    @DisplayName("paces the renewal at the configured interval")
    void pacesTheRenewalAtTheConfiguredInterval() {
        AsyncSchedulingConfig worker = configWithBackground(
                new ScannerProperties.Background(true, true, true, 200L, 40L, 200));
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        worker.configureTasks(registrar);

        assertThat(registrar.getFixedDelayTaskList().get(0).getIntervalDuration())
                .isEqualTo(Duration.ofSeconds(40L));
    }

    @Test
    @DisplayName("runs no pass while the lease is not held")
    void runsNoPassWhileTheLeaseIsNotHeld() {
        when(backgroundOwnership.isOwner()).thenReturn(false);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        config.configureTasks(registrar);
        registrar.getTriggerTaskList().get(0).getRunnable().run();

        verifyNoInteractions(responseGenerationScheduler);
    }

    @Test
    @DisplayName("reports a skipped pass once for an uninterrupted run of skipped passes")
    void reportsASkippedPassOnceForAnUninterruptedRunOfSkippedPasses() {
        when(backgroundOwnership.isOwner()).thenReturn(false);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        config.configureTasks(registrar);
        Runnable pass = registrar.getTriggerTaskList().get(0).getRunnable();
        Logger configLogger = (Logger) LoggerFactory.getLogger(AsyncSchedulingConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        configLogger.addAppender(appender);
        try {
            pass.run();
            pass.run();
            pass.run();
        } finally {
            configLogger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list).filteredOn(record ->
                        record.getFormattedMessage().contains("does not hold the "
                                + "background-ownership lease"))
                .hasSize(1);
    }

    @Test
    @DisplayName("runs the pass again once the lease is regained")
    void runsThePassAgainOnceTheLeaseIsRegained() {
        when(backgroundOwnership.isOwner()).thenReturn(false, true);
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        config.configureTasks(registrar);
        Runnable pass = registrar.getTriggerTaskList().get(0).getRunnable();
        pass.run();
        pass.run();

        verify(responseGenerationScheduler).generatePendingResponses();
    }

    @Test
    @DisplayName("registers the pass in a process that runs it")
    void registersThePassInAProcessThatRunsIt() {
        AsyncSchedulingConfig owner = configWithBackground(
                ScannerProperties.Background.of(true, true, true));
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        owner.configureTasks(registrar);

        assertThat(registrar.getTriggerTaskList()).as("triggered tasks").hasSize(1);
    }

    @Test
    @DisplayName("registers the response generation pass as the one triggered task")
    void registersTheResponseGenerationPassAsTheOneTriggeredTask() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        config.configureTasks(registrar);

        List<TriggerTask> registered = registrar.getTriggerTaskList();
        assertThat(registered).hasSize(1);
        assertThat(registrar.getFixedRateTaskList()).isEmpty();
        assertThat(registrar.getCronTaskList()).isEmpty();

        registered.get(0).getRunnable().run();
        verify(responseGenerationScheduler).generatePendingResponses();
    }

    @Test
    @DisplayName("measures the interval from the completion of the previous pass")
    void measuresTheIntervalFromTheCompletionOfThePreviousPass() {
        when(settingRepository.findById(DELAY_KEY)).thenReturn(Optional.empty());

        Instant next = nextExecution(triggerContextCompletedAt(LAST_COMPLETION));

        assertThat(next).isEqualTo(LAST_COMPLETION.plusSeconds(CONFIGURED_DELAY_SECONDS));
    }

    // The first pass runs immediately — DL-251 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("runs the first pass at the current instant, reading no interval for it")
    void runsTheFirstPassAtTheCurrentInstant() {
        Instant next = nextExecution(triggerContextCompletedAt(null));

        assertThat(next).isEqualTo(NOW);
        verifyNoInteractions(settingRepository);
    }

    @Test
    @DisplayName("applies the stored setting row in place of the configured delay")
    void appliesTheStoredSettingRowInPlaceOfTheConfiguredDelay() {
        when(settingRepository.findById(DELAY_KEY)).thenReturn(
                Optional.of(new Setting(DELAY_KEY, "  " + STORED_DELAY_SECONDS + "  ",
                        "Seconds between response-generation sweeps.")));

        Instant next = nextExecution(triggerContextCompletedAt(LAST_COMPLETION));

        assertThat(next).isEqualTo(LAST_COMPLETION.plusSeconds(STORED_DELAY_SECONDS));
    }

    @Test
    @DisplayName("reads the setting row again before every pass so an edit takes effect at once")
    void readsTheSettingRowAgainBeforeEveryPass() {
        when(settingRepository.findById(DELAY_KEY))
                .thenReturn(Optional.of(new Setting(DELAY_KEY, "5", "d")))
                .thenReturn(Optional.of(new Setting(DELAY_KEY, "900", "d")));

        Trigger trigger = registeredTrigger();

        assertThat(trigger.nextExecution(triggerContextCompletedAt(LAST_COMPLETION)))
                .isEqualTo(LAST_COMPLETION.plusSeconds(5L));
        assertThat(trigger.nextExecution(triggerContextCompletedAt(LAST_COMPLETION)))
                .isEqualTo(LAST_COMPLETION.plusSeconds(900L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "sixty", "0", "-30", "12.5", "9999999999999999999999"})
    @DisplayName("falls back to the configured delay for a row that holds no positive second count")
    void fallsBackToTheConfiguredDelayForARowThatHoldsNoPositiveSecondCount(String stored) {
        when(settingRepository.findById(DELAY_KEY))
                .thenReturn(Optional.of(new Setting(DELAY_KEY, stored, "d")));

        Instant next = nextExecution(triggerContextCompletedAt(LAST_COMPLETION));

        assertThat(next).isEqualTo(LAST_COMPLETION.plusSeconds(CONFIGURED_DELAY_SECONDS));
    }

    @Test
    @DisplayName("falls back to the configured delay for a row that holds no value")
    void fallsBackToTheConfiguredDelayForARowThatHoldsNoValue() {
        when(settingRepository.findById(DELAY_KEY))
                .thenReturn(Optional.of(new Setting(DELAY_KEY, null, "d")));

        Instant next = nextExecution(triggerContextCompletedAt(LAST_COMPLETION));

        assertThat(next).isEqualTo(LAST_COMPLETION.plusSeconds(CONFIGURED_DELAY_SECONDS));
    }

    @Test
    @DisplayName("never schedules a pass at or before the completion of the previous one")
    void neverSchedulesAPassAtOrBeforeTheCompletionOfThePreviousOne() {
        ScannerProperties zeroDelay = new ScannerProperties("jdbc:h2:mem:scheduling", 100, 0L,
                null, null, null, null, null, null, null, null);
        AsyncSchedulingConfig withZeroDelay = new AsyncSchedulingConfig(
                responseGenerationScheduler, settingRepository, zeroDelay, backgroundOwnership);
        when(settingRepository.findById(DELAY_KEY)).thenReturn(Optional.empty());

        Instant next = registeredTrigger(withZeroDelay)
                .nextExecution(triggerContextCompletedAt(LAST_COMPLETION));

        assertThat(next).isAfter(LAST_COMPLETION);
        assertThat(Duration.between(LAST_COMPLETION, next)).isEqualTo(Duration.ofSeconds(1L));
    }

    // -----------------------------------------------------------------------
    // A standing misconfiguration is recorded once per process — DL-251 — see docs/DECISION_LOG.md
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("records an unreadable settings row once however many passes are paced")
    void recordsAnUnreadableSettingsRowOnceHoweverManyPassesArePaced() {
        when(settingRepository.findById(DELAY_KEY))
                .thenThrow(new DataAccessResourceFailureException("no connection"));
        Trigger trigger = registeredTrigger();

        List<String> warnings = warningsFrom(() -> {
            for (int pass = 0; pass < 4; pass++) {
                trigger.nextExecution(triggerContextCompletedAt(LAST_COMPLETION));
            }
        });

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("could not be read").contains(DELAY_KEY);
        verify(settingRepository, times(4)).findById(DELAY_KEY);
    }

    @Test
    @DisplayName("records a stored value that does not parse once however many passes are paced")
    void recordsAStoredValueThatDoesNotParseOnceHoweverManyPassesArePaced() {
        when(settingRepository.findById(DELAY_KEY))
                .thenReturn(Optional.of(new Setting(DELAY_KEY, "not-a-number", "d")));
        Trigger trigger = registeredTrigger();

        List<String> warnings = warningsFrom(() -> {
            for (int pass = 0; pass < 4; pass++) {
                trigger.nextExecution(triggerContextCompletedAt(LAST_COMPLETION));
            }
        });

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("does not hold an integer");
    }

    @Test
    @DisplayName("records a bounded interval once however many passes are paced")
    void recordsABoundedIntervalOnceHoweverManyPassesArePaced() {
        when(settingRepository.findById(DELAY_KEY)).thenReturn(Optional.of(
                new Setting(DELAY_KEY, String.valueOf(Long.MAX_VALUE), "d")));
        Trigger trigger = registeredTrigger();

        List<String> warnings = warningsFrom(() -> {
            for (int pass = 0; pass < 4; pass++) {
                trigger.nextExecution(triggerContextCompletedAt(LAST_COMPLETION));
            }
        });

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("exceeds the").contains("bound");
    }

    @Test
    @DisplayName("records nothing when the stored row paces the pass")
    void recordsNothingWhenTheStoredRowPacesThePass() {
        when(settingRepository.findById(DELAY_KEY)).thenReturn(Optional.of(
                new Setting(DELAY_KEY, String.valueOf(STORED_DELAY_SECONDS), "d")));
        Trigger trigger = registeredTrigger();

        assertThat(warningsFrom(() ->
                trigger.nextExecution(triggerContextCompletedAt(LAST_COMPLETION)))).isEmpty();
    }

    /**
     * Runs {@code work} with an appender attached to this class's logger.
     *
     * @param work the call to record
     * @return every {@code WARN} message the call emitted, in the order recorded
     */
    private static List<String> warningsFrom(Runnable work) {
        Logger logger = (Logger) LoggerFactory.getLogger(AsyncSchedulingConfig.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            work.run();
        } finally {
            logger.detachAppender(captured);
        }
        return captured.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private Instant nextExecution(TriggerContext context) {
        return registeredTrigger().nextExecution(context);
    }

    private Trigger registeredTrigger() {
        return registeredTrigger(config);
    }

    private Trigger registeredTrigger(AsyncSchedulingConfig target) {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        target.configureTasks(registrar);
        Trigger trigger = registrar.getTriggerTaskList().get(0).getTrigger();
        assertThat(trigger).isNotNull();
        return trigger;
    }

    /**
     * Builds the unit under test over a configuration root carrying the supplied background group.
     *
     * @param background the {@code scanner.background} group to bind
     * @return the unit under test
     */
    private AsyncSchedulingConfig configWithBackground(ScannerProperties.Background background) {
        ScannerProperties properties = new ScannerProperties("jdbc:h2:mem:scheduling", 100,
                CONFIGURED_DELAY_SECONDS, null, null, null, null, null, null, null, background);
        lenient().when(backgroundOwnership.isAutoStartup()).thenReturn(background == null
                || background.runsStream() || background.runsResponseGeneration());
        return new AsyncSchedulingConfig(responseGenerationScheduler, settingRepository, properties,
                backgroundOwnership);
    }

    /**
     * Reads a declared boolean field of the published scheduler.
     *
     * @param target the scheduler to inspect
     * @param name   the field to read, declared by the scheduler or one of its supertypes
     * @return the field's value
     * @throws ReflectiveOperationException when the field is absent
     */
    private static boolean readBoolean(Object target, String name)
            throws ReflectiveOperationException {
        return (boolean) readField(target, name);
    }

    /**
     * Reads a declared {@code int} or {@code long} field of the published scheduler.
     *
     * @param target the scheduler to inspect
     * @param name   the field to read, declared by the scheduler or one of its supertypes
     * @return the field's value
     * @throws ReflectiveOperationException when the field is absent
     */
    private static long readInt(Object target, String name) throws ReflectiveOperationException {
        Object value = readField(target, name);
        return ((Number) value).longValue();
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException absentHere) {
                continue;
            }
        }
        throw new NoSuchFieldException(name);
    }

    private TriggerContext triggerContextCompletedAt(Instant lastCompletion) {
        TriggerContext context = mock(TriggerContext.class);
        when(context.lastCompletion()).thenReturn(lastCompletion);
        if (lastCompletion == null) {
            when(context.getClock()).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        }
        return context;
    }
}
