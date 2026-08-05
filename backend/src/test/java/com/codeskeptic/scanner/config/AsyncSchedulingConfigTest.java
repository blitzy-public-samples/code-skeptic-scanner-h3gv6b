package com.codeskeptic.scanner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.config.TriggerTask;

import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.task.ResponseGenerationScheduler;

// Net-new (no Python counterpart: backend/app/tasks/response_generation.py:L41-50 paced its own
// `while True` loop) — DL-192 — see docs/DECISION_LOG.md
/**
 * Exercises the pacing {@link AsyncSchedulingConfig} declares for the response-generation pass.
 *
 * <p>Two properties are asserted: the interval is measured from the completion of the previous pass,
 * which is fixed-delay rather than fixed-rate behaviour, and the interval in force is the
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

    @BeforeEach
    void setUp() {
        ScannerProperties properties = new ScannerProperties("jdbc:h2:mem:scheduling", 100,
                CONFIGURED_DELAY_SECONDS, null, null, null, null, null, null, null);
        config = new AsyncSchedulingConfig(responseGenerationScheduler, settingRepository,
                properties);
    }

    @Test
    @DisplayName("publishes one scheduler named for this application's scheduled work")
    void publishesOneSchedulerNamedForThisApplicationsScheduledWork() {
        ThreadPoolTaskScheduler scheduler = config.taskScheduler();

        assertThat(scheduler).isNotNull();
        assertThat(scheduler.getThreadNamePrefix()).isEqualTo("scanner-scheduler-");
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

    @Test
    @DisplayName("measures the first interval from the current instant")
    void measuresTheFirstIntervalFromTheCurrentInstant() {
        when(settingRepository.findById(DELAY_KEY)).thenReturn(Optional.empty());

        Instant next = nextExecution(triggerContextCompletedAt(null));

        assertThat(next).isEqualTo(NOW.plusSeconds(CONFIGURED_DELAY_SECONDS));
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
                null, null, null, null, null, null, null);
        AsyncSchedulingConfig withZeroDelay =
                new AsyncSchedulingConfig(responseGenerationScheduler, settingRepository, zeroDelay);
        when(settingRepository.findById(DELAY_KEY)).thenReturn(Optional.empty());

        Instant next = registeredTrigger(withZeroDelay)
                .nextExecution(triggerContextCompletedAt(LAST_COMPLETION));

        assertThat(next).isAfter(LAST_COMPLETION);
        assertThat(Duration.between(LAST_COMPLETION, next)).isEqualTo(Duration.ofSeconds(1L));
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

    private TriggerContext triggerContextCompletedAt(Instant lastCompletion) {
        TriggerContext context = mock(TriggerContext.class);
        when(context.lastCompletion()).thenReturn(lastCompletion);
        if (lastCompletion == null) {
            when(context.getClock()).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        }
        return context;
    }
}
