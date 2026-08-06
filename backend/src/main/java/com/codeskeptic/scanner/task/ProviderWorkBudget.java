package com.codeskeptic.scanner.task;

import com.codeskeptic.scanner.config.ScannerProperties;

import java.time.Clock;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The ceiling on paid provider work the two background paths may do, and the circuit that opens when
 * a provider keeps failing.
 *
 * <p>Both background paths reach charged third parties — Google Cloud Natural Language for a doubt
 * rating, OpenAI for a generated reply and Notion for a mirror — once per ingested record and once
 * per generation candidate. Executor concurrency bounds how much of that work runs at the same
 * moment; it bounds neither how much runs in total nor what it costs. This class supplies that
 * second bound — DL-283.
 *
 * <p>Two independent controls are applied, and a unit of work proceeds only when both allow it:
 *
 * <ul>
 *   <li><strong>A fixed-window allowance.</strong> At most
 *       {@code scanner.background.provider-calls-per-window} units are granted within each
 *       {@code scanner.background.provider-window-seconds} span. The window advances by whole spans
 *       from the first grant, and the count resets as it advances. A refused unit is not queued,
 *       deferred or retried: the caller skips the work and its own summary counts the skip.</li>
 *   <li><strong>A consecutive-failure circuit.</strong> {@code
 *       scanner.background.provider-failure-threshold} consecutive failures open the circuit for
 *       {@code scanner.background.provider-circuit-open-seconds}, during which every unit is refused.
 *       The first success after the span closes the circuit and clears the count.</li>
 * </ul>
 *
 * <p>The budget is process-local. {@code task/BackgroundOwnership} admits one process to background
 * work at a time, and that process's allowance is the deployment's allowance — DL-281, DL-283. No
 * store, table, column or shared counter is added.
 *
 * <p>Every method is safe for concurrent callers and none of them raises. Reporting is bounded to
 * transitions: one record marks entry into a refusing state and one marks the return to a granting
 * state. A refusal of any length writes two records in total, none per refused unit — DL-197.
 *
 * <p>This class performs no provider call itself, holds no provider client and publishes nothing to
 * X. It decides only whether a caller may proceed.
 */
// Net-new (no Python counterpart): backend/app/tasks/tweet_monitoring.py:L8-34 and
// backend/app/tasks/response_generation.py:L10-33 bounded provider work in no way — DL-283 — see
// docs/DECISION_LOG.md
@Component
public class ProviderWorkBudget {

    /** Records the transition into and out of a refusing state. */
    private static final Logger log = LoggerFactory.getLogger(ProviderWorkBudget.class);

    /** Milliseconds in one second. */
    private static final long MILLIS_PER_SECOND = 1_000L;

    /** Supplies the allowance, the window, the failure threshold and the open span. */
    private final ScannerProperties properties;

    /** Reads the current instant, in UTC — DL-278. */
    private final Clock clock;

    /** Instant, in epoch milliseconds, the current window opened at. */
    private long windowOpenedMillis;

    /** Units granted within the current window. */
    private int grantedInWindow;

    /** Consecutive failures recorded since the last success. */
    private int consecutiveFailures;

    /** Instant, in epoch milliseconds, an open circuit closes at; {@code 0} when it is closed. */
    private long circuitOpenUntilMillis;

    /** Whether the last decision refused a unit, so a transition is recorded once. */
    private boolean refusing;

    /**
     * Binds the configuration and the clock.
     *
     * @param properties supplies the allowance and the circuit settings; never {@code null}
     * @param clock reads the current instant; never {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    public ProviderWorkBudget(ScannerProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null.");
        this.clock = Objects.requireNonNull(clock, "clock must not be null.");
    }

    /**
     * Grants one unit of provider work when the allowance and the circuit both permit it.
     *
     * @return {@code true} when the caller may perform the work; {@code false} when the window
     *     allowance is spent or the circuit is open
     */
    public synchronized boolean tryAcquire() {
        long now = clock.millis();
        ScannerProperties.Background background = background();

        if (circuitOpenUntilMillis > now) {
            return refuse("the provider circuit is open for another {}s",
                    Math.max(1L, (circuitOpenUntilMillis - now) / MILLIS_PER_SECOND));
        }
        if (circuitOpenUntilMillis != 0L) {
            circuitOpenUntilMillis = 0L;
            consecutiveFailures = 0;
        }

        long windowMillis = background.providerWindowSeconds() * MILLIS_PER_SECOND;
        if (windowOpenedMillis == 0L || now - windowOpenedMillis >= windowMillis) {
            windowOpenedMillis = now;
            grantedInWindow = 0;
        }

        if (grantedInWindow >= background.providerCallsPerWindow()) {
            return refuse("the provider allowance of {} call(s) per {}s window is spent",
                    background.providerCallsPerWindow(), background.providerWindowSeconds());
        }

        grantedInWindow++;
        if (refusing) {
            refusing = false;
            log.info("Provider work is granted again; {} of {} call(s) used in the current window",
                    grantedInWindow, background.providerCallsPerWindow());
        }
        return true;
    }

    /**
     * Records that a granted unit of work completed, which closes the circuit.
     */
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        circuitOpenUntilMillis = 0L;
    }

    /**
     * Records that a granted unit of work failed, which opens the circuit at the configured
     * consecutive-failure threshold.
     */
    public synchronized void recordFailure() {
        ScannerProperties.Background background = background();
        consecutiveFailures++;
        if (consecutiveFailures < background.providerFailureThreshold()) {
            return;
        }
        circuitOpenUntilMillis =
                clock.millis() + (background.providerCircuitOpenSeconds() * MILLIS_PER_SECOND);
        log.warn("Provider work is suspended for {}s after {} consecutive failure(s)",
                background.providerCircuitOpenSeconds(), consecutiveFailures);
        consecutiveFailures = 0;
    }

    /**
     * Reports the units still available in the current window.
     *
     * @return the remaining allowance, which is {@code 0} while the circuit is open
     */
    public synchronized int remainingInWindow() {
        long now = clock.millis();
        ScannerProperties.Background background = background();
        if (circuitOpenUntilMillis > now) {
            return 0;
        }
        long windowMillis = background.providerWindowSeconds() * MILLIS_PER_SECOND;
        if (windowOpenedMillis == 0L || now - windowOpenedMillis >= windowMillis) {
            return background.providerCallsPerWindow();
        }
        return Math.max(0, background.providerCallsPerWindow() - grantedInWindow);
    }

    /**
     * Records one refusal, writing the reason only on the transition into the refusing state.
     *
     * @param reason the parameterised reason
     * @param arguments the reason's arguments
     * @return {@code false} always, which is the decision the caller returns
     */
    private boolean refuse(String reason, Object... arguments) {
        if (!refusing) {
            refusing = true;
            log.warn("Provider work is refused: " + reason, arguments);
        }
        return false;
    }

    /**
     * Reads the bound group, substituting the declared defaults when it is unbound.
     *
     * @return the group, never {@code null}
     */
    private ScannerProperties.Background background() {
        ScannerProperties.Background bound = properties.background();
        return bound == null ? ScannerProperties.Background.of(true, true, true) : bound;
    }
}
