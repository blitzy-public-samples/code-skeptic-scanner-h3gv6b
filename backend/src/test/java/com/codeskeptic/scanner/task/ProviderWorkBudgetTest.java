package com.codeskeptic.scanner.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.codeskeptic.scanner.config.ScannerProperties;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests for {@link ProviderWorkBudget}, the ceiling on paid provider work the background paths
 * may do and the circuit that opens when a provider keeps failing — DL-283.
 *
 * <p>Every case drives a controllable clock, so the fixed window and the open span are exercised
 * without waiting. No Spring context is started and no provider is contacted: this component decides
 * only whether a caller may proceed.
 */
// Net-new (no Python counterpart) — DL-283 — see docs/DECISION_LOG.md
@DisplayName("ProviderWorkBudget")
class ProviderWorkBudgetTest {

    /** Allowance every case is configured with unless it says otherwise. */
    private static final int ALLOWANCE = 3;

    /** Window every case is configured with unless it says otherwise, in seconds. */
    private static final long WINDOW_SECONDS = 60L;

    /** Consecutive-failure threshold every case is configured with unless it says otherwise. */
    private static final int FAILURE_THRESHOLD = 2;

    /** Span an open circuit stays open for, in seconds. */
    private static final long CIRCUIT_OPEN_SECONDS = 30L;

    /** Instant every case starts at. */
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    /** Clock the unit under test reads. */
    private MutableClock clock;

    /** Unit under test. */
    private ProviderWorkBudget budget;

    /** Records what the unit writes. */
    private ListAppender<ILoggingEvent> appender;

    /** Logger the appender is attached to. */
    private Logger budgetLogger;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        budget = new ProviderWorkBudget(
                bound(ALLOWANCE, WINDOW_SECONDS, FAILURE_THRESHOLD, CIRCUIT_OPEN_SECONDS), clock);
        appender = new ListAppender<>();
        appender.start();
        budgetLogger = (Logger) LoggerFactory.getLogger(ProviderWorkBudget.class);
        budgetLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        budgetLogger.detachAppender(appender);
        appender.stop();
    }

    @Nested
    @DisplayName("the fixed-window allowance")
    class WindowAllowance {

        @Test
        @DisplayName("grants exactly the configured number of units within one window")
        void grantsExactlyTheConfiguredNumberOfUnitsWithinOneWindow() {
            List<Boolean> decisions = IntStream.range(0, ALLOWANCE + 2)
                    .mapToObj(attempt -> budget.tryAcquire())
                    .collect(Collectors.toList());

            assertThat(decisions).containsExactly(true, true, true, false, false);
        }

        @Test
        @DisplayName("reports the remaining allowance as it is spent")
        void reportsTheRemainingAllowanceAsItIsSpent() {
            assertThat(budget.remainingInWindow()).isEqualTo(ALLOWANCE);

            budget.tryAcquire();
            assertThat(budget.remainingInWindow()).isEqualTo(ALLOWANCE - 1);

            budget.tryAcquire();
            budget.tryAcquire();
            assertThat(budget.remainingInWindow()).isZero();
        }

        @Test
        @DisplayName("grants again once the window has advanced")
        void grantsAgainOnceTheWindowHasAdvanced() {
            IntStream.range(0, ALLOWANCE).forEach(attempt -> budget.tryAcquire());
            assertThat(budget.tryAcquire()).isFalse();

            clock.advance(Duration.ofSeconds(WINDOW_SECONDS));

            assertThat(budget.tryAcquire()).isTrue();
            assertThat(budget.remainingInWindow()).isEqualTo(ALLOWANCE - 1);
        }

        @Test
        @DisplayName("refuses within the window even as it nears its end")
        void refusesWithinTheWindowEvenAsItNearsItsEnd() {
            IntStream.range(0, ALLOWANCE).forEach(attempt -> budget.tryAcquire());

            clock.advance(Duration.ofSeconds(WINDOW_SECONDS - 1L));

            assertThat(budget.tryAcquire()).isFalse();
        }

        @ParameterizedTest(name = "allowance {0} grants {0} unit(s)")
        @CsvSource({"1", "2", "5", "50"})
        @DisplayName("honours the configured allowance exactly")
        void honoursTheConfiguredAllowanceExactly(int allowance) {
            ProviderWorkBudget configured = new ProviderWorkBudget(
                    bound(allowance, WINDOW_SECONDS, FAILURE_THRESHOLD, CIRCUIT_OPEN_SECONDS),
                    clock);

            long granted = IntStream.range(0, allowance + 3)
                    .filter(attempt -> configured.tryAcquire())
                    .count();

            assertThat(granted).isEqualTo(allowance);
        }

        @Test
        @DisplayName("reads an allowance below the floor as the floor")
        void readsAnAllowanceBelowTheFloorAsTheFloor() {
            ProviderWorkBudget configured = new ProviderWorkBudget(
                    bound(0, WINDOW_SECONDS, FAILURE_THRESHOLD, CIRCUIT_OPEN_SECONDS), clock);

            assertThat(configured.tryAcquire()).isTrue();
            assertThat(configured.tryAcquire()).isFalse();
        }

        @Test
        @DisplayName("applies the declared defaults when the group is unbound")
        void appliesTheDeclaredDefaultsWhenTheGroupIsUnbound() {
            ProviderWorkBudget unbound = new ProviderWorkBudget(
                    new ScannerProperties(null, 100, 60L, null, null, null, null, null, null, null,
                            null),
                    clock);

            assertThat(unbound.remainingInWindow())
                    .isEqualTo(ScannerProperties.Background.of(true, true, true)
                            .providerCallsPerWindow());
        }
    }

    @Nested
    @DisplayName("the consecutive-failure circuit")
    class FailureCircuit {

        @Test
        @DisplayName("opens at the configured threshold and refuses every unit")
        void opensAtTheConfiguredThresholdAndRefusesEveryUnit() {
            budget.recordFailure();
            assertThat(budget.tryAcquire()).as("one failure leaves the circuit closed").isTrue();

            budget.recordFailure();
            budget.recordFailure();

            assertThat(budget.tryAcquire()).isFalse();
            assertThat(budget.remainingInWindow()).isZero();
        }

        @Test
        @DisplayName("closes once the open span has elapsed")
        void closesOnceTheOpenSpanHasElapsed() {
            budget.recordFailure();
            budget.recordFailure();
            assertThat(budget.tryAcquire()).isFalse();

            clock.advance(Duration.ofSeconds(CIRCUIT_OPEN_SECONDS));

            assertThat(budget.tryAcquire()).isTrue();
        }

        @Test
        @DisplayName("stays open until the span has fully elapsed")
        void staysOpenUntilTheSpanHasFullyElapsed() {
            budget.recordFailure();
            budget.recordFailure();

            clock.advance(Duration.ofSeconds(CIRCUIT_OPEN_SECONDS - 1L));

            assertThat(budget.tryAcquire()).isFalse();
        }

        @Test
        @DisplayName("clears the consecutive count on a success")
        void clearsTheConsecutiveCountOnASuccess() {
            budget.recordFailure();
            budget.recordSuccess();
            budget.recordFailure();

            assertThat(budget.tryAcquire()).as("the count restarted at the success").isTrue();
        }

        @Test
        @DisplayName("closes an open circuit on a success")
        void closesAnOpenCircuitOnASuccess() {
            budget.recordFailure();
            budget.recordFailure();
            assertThat(budget.tryAcquire()).isFalse();

            budget.recordSuccess();

            assertThat(budget.tryAcquire()).isTrue();
        }

        @ParameterizedTest(name = "threshold {0}")
        @CsvSource({"1", "2", "3", "10"})
        @DisplayName("opens only at the configured number of consecutive failures")
        void opensOnlyAtTheConfiguredNumberOfConsecutiveFailures(int threshold) {
            ProviderWorkBudget configured = new ProviderWorkBudget(
                    bound(1_000, WINDOW_SECONDS, threshold, CIRCUIT_OPEN_SECONDS), clock);

            IntStream.range(0, threshold - 1).forEach(attempt -> configured.recordFailure());
            assertThat(configured.tryAcquire()).as("one short of the threshold").isTrue();

            configured.recordFailure();

            assertThat(configured.tryAcquire()).as("at the threshold").isFalse();
        }
    }

    @Nested
    @DisplayName("what it records")
    class Records {

        @Test
        @DisplayName("writes one record for an uninterrupted run of refusals")
        void writesOneRecordForAnUninterruptedRunOfRefusals() {
            IntStream.range(0, ALLOWANCE + 10).forEach(attempt -> budget.tryAcquire());

            assertThat(rendered(Level.WARN))
                    .filteredOn(message -> message.contains("allowance"))
                    .hasSize(1);
        }

        @Test
        @DisplayName("names the allowance and the window and no other value")
        void namesTheAllowanceAndTheWindowAndNoOtherValue() {
            IntStream.range(0, ALLOWANCE + 1).forEach(attempt -> budget.tryAcquire());

            assertThat(rendered(Level.WARN))
                    .anySatisfy(message -> assertThat(message)
                            .contains(String.valueOf(ALLOWANCE))
                            .contains(String.valueOf(WINDOW_SECONDS))
                            .contains("refused"));
        }

        @Test
        @DisplayName("records the return to granting once the window advances")
        void recordsTheReturnToGrantingOnceTheWindowAdvances() {
            IntStream.range(0, ALLOWANCE + 1).forEach(attempt -> budget.tryAcquire());
            clock.advance(Duration.ofSeconds(WINDOW_SECONDS));

            budget.tryAcquire();

            assertThat(rendered(Level.INFO))
                    .anySatisfy(message -> assertThat(message).contains("granted again"));
        }

        @Test
        @DisplayName("records the suspension with the span and the failure count")
        void recordsTheSuspensionWithTheSpanAndTheFailureCount() {
            budget.recordFailure();
            budget.recordFailure();

            assertThat(rendered(Level.WARN))
                    .anySatisfy(message -> assertThat(message)
                            .contains("suspended")
                            .contains(String.valueOf(CIRCUIT_OPEN_SECONDS))
                            .contains(String.valueOf(FAILURE_THRESHOLD)));
        }

        @Test
        @DisplayName("writes one record for an uninterrupted run of refusals by an open circuit")
        void writesOneRecordForAnUninterruptedRunOfRefusalsByAnOpenCircuit() {
            budget.recordFailure();
            budget.recordFailure();

            IntStream.range(0, 8).forEach(attempt -> budget.tryAcquire());

            assertThat(rendered(Level.WARN))
                    .filteredOn(message -> message.contains("circuit is open"))
                    .hasSize(1);
        }

        @Test
        @DisplayName("renders no line break or control character in any record")
        void rendersNoLineBreakOrControlCharacterInAnyRecord() {
            IntStream.range(0, ALLOWANCE + 2).forEach(attempt -> budget.tryAcquire());
            budget.recordFailure();
            budget.recordFailure();
            budget.tryAcquire();

            assertThat(appender.list)
                    .isNotEmpty()
                    .allSatisfy(record -> assertThat(record.getFormattedMessage())
                            .doesNotContain("\n")
                            .doesNotContain("\r")
                            .doesNotContain("\t"));
        }
    }

    @Nested
    @DisplayName("the declared shape")
    class DeclaredShape {

        @Test
        @DisplayName("rejects an absent collaborator, naming the one that is absent")
        void rejectsAnAbsentCollaboratorNamingTheOneThatIsAbsent() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new ProviderWorkBudget(null, clock))
                    .withMessageContaining("properties");
            assertThatNullPointerException()
                    .isThrownBy(() -> new ProviderWorkBudget(
                            bound(ALLOWANCE, WINDOW_SECONDS, FAILURE_THRESHOLD,
                                    CIRCUIT_OPEN_SECONDS),
                            null))
                    .withMessageContaining("clock");
        }

        @Test
        @DisplayName("reaches no provider client of any kind")
        void reachesNoProviderClientOfAnyKind() {
            assertThat(ProviderWorkBudget.class.getDeclaredFields())
                    .extracting(field -> field.getType().getSimpleName())
                    .doesNotContain("WebClient", "RestClient", "OpenAIClient",
                            "LanguageServiceClient", "NotionService", "LlmService");
        }
    }

    /**
     * Builds a bound configuration carrying the supplied provider controls.
     *
     * @param allowance value of {@code scanner.background.provider-calls-per-window}
     * @param windowSeconds value of {@code scanner.background.provider-window-seconds}
     * @param failureThreshold value of {@code scanner.background.provider-failure-threshold}
     * @param circuitOpenSeconds value of {@code scanner.background.provider-circuit-open-seconds}
     * @return the bound configuration
     */
    private static ScannerProperties bound(int allowance,
            long windowSeconds,
            int failureThreshold,
            long circuitOpenSeconds) {
        return new ScannerProperties(null, 100, 60L, null, null, null, null, null, null, null,
                new ScannerProperties.Background(true, true, true, 120L, 30L, 200, allowance,
                        windowSeconds, failureThreshold, circuitOpenSeconds));
    }

    /**
     * Renders every record written at one level.
     *
     * @param level the level to select
     * @return the formatted messages, in the order they were written
     */
    private List<String> rendered(Level level) {
        return appender.list.stream()
                .filter(record -> record.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    /** Clock a case advances by hand. */
    private static final class MutableClock extends Clock {

        /** Instant this clock reports. */
        private Instant now;

        /**
         * Starts the clock at one instant.
         *
         * @param start the instant to report until the clock is advanced
         */
        private MutableClock(Instant start) {
            this.now = start;
        }

        /**
         * Moves the reported instant forward.
         *
         * @param amount the span to move forward by
         */
        void advance(Duration amount) {
            this.now = this.now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
