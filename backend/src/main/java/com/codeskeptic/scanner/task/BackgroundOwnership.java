package com.codeskeptic.scanner.task;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.util.LogSafe;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The single owner of background work, held as a time-limited lease in the {@code settings} table.
 *
 * <p>Two background paths exist — the X filtered stream of {@code task/TweetStreamClient} and the
 * generation pass {@code config/AsyncSchedulingConfig} registers — and both run in one process at a
 * time. This class decides which process that is: it claims a lease, renews it while it holds it, and
 * releases it at shutdown. A process that does not hold the lease runs neither path.
 *
 * <p>The lease is one row of the existing {@code settings} table, keyed
 * {@value #OWNER_SETTING_KEY}, whose value carries the claiming process's identity and the instant
 * the claim lapses. No table, column, index or constraint is added — DL-281. The row is not part of
 * the configuration surface: {@code service/SettingsService} withholds it from
 * {@code GET /settings} and refuses to write it through {@code PUT /settings/{key}} — DL-284.
 *
 * <p>A claim is a compare-and-set. The claimant reads the row, decides whether the lease is vacant —
 * absent value, unreadable value, lapsed instant, or its own identity — and then writes through
 * {@link SettingRepository#replaceValueIfUnchanged(String, String, String)}, whose {@code WHERE}
 * clause names the value just read. Of several processes that read the same value, exactly one
 * update matches a row and exactly one process holds the lease. No advisory lock, vendor function
 * or native statement participates, and the mechanism is identical on every supported database —
 * DL-281.
 *
 * <p>The lease fails closed. Every failure of the claim — a rejected update, a race lost on insert,
 * or any {@link DataAccessException} at all, which includes an unreachable database — leaves
 * {@link #isOwner()} reporting {@code false}, and both background paths then do nothing. No failure
 * path reports ownership.
 *
 * <p>Renewal is paced by a timer. {@code config/AsyncSchedulingConfig} registers
 * {@link #renewOwnership()} as a fixed-delay task at {@code scanner.background.lease-renew-seconds},
 * which the bound of {@code config/ScannerProperties.Background} holds at or below half the lease
 * term. A renewal that succeeds extends the term, and starts the stream when this process runs the
 * stream and it is idle; a lease left behind by a terminated process is taken over at that point
 * with no operator step. A renewal that fails releases the local view of ownership and stops the
 * stream — DL-281.
 *
 * <p>{@link #isOwner()} reads process-local state only and issues no statement. The callers are
 * {@code task/TweetStreamClient#isAutoStartup()} and each generation pass.
 *
 * <p>This class publishes nothing to X and reaches no HTTP client.
 */
// Net-new (no Python counterpart): backend/app/main.py:L41-48 started both background paths in every
// process and coordinated nothing — DL-281 — see docs/DECISION_LOG.md
@Component
public class BackgroundOwnership implements SmartLifecycle {

    /** Records each claim, each renewal, each loss and the release. */
    private static final Logger log = LoggerFactory.getLogger(BackgroundOwnership.class);

    /** Primary key of the {@code settings} row the lease is held in — DL-281. */
    public static final String OWNER_SETTING_KEY = "background_owner";

    /** Description written when the lease row is created — DL-281. */
    private static final String OWNER_DESCRIPTION =
            "Background-ownership lease. Written by the service, not a configuration value.";

    /** Separates the claiming identity from the lapse instant inside the value — DL-281. */
    private static final char OWNER_SEPARATOR = '@';

    /** Value written on release, which every process reads as vacant — DL-281. */
    private static final String RELEASED_VALUE = "released" + OWNER_SEPARATOR + '0';

    /** Milliseconds in one second. */
    private static final long MILLIS_PER_SECOND = 1_000L;

    /**
     * Lifecycle phase of this bean. It is lower than the {@code SmartLifecycle} default
     * {@code TweetStreamClient} carries, so the lease is claimed before the stream is asked whether
     * to start and released after the stream has stopped — DL-281.
     */
    private static final int PHASE = Integer.MAX_VALUE - 1024;

    /** Holds the lease row. */
    private final SettingRepository settingRepository;

    /** Supplies the lease term and the renewal interval. */
    private final ScannerProperties properties;

    /** Reads the current instant, in UTC — DL-278. */
    private final Clock clock;

    /** Runs the read-then-compare-and-set of one claim in one transaction. */
    private final TransactionTemplate transactionTemplate;

    /**
     * Resolves the stream client on demand. The client depends on this bean, and the reference is
     * not held as a constructor argument — DL-281.
     */
    private final ObjectProvider<TweetStreamClient> streamClient;

    /** Identity this process claims the lease under, minted once per process. */
    private final String instanceId = UUID.randomUUID().toString();

    /** Reports whether {@link #start()} has run and {@link #stop()} has not. */
    private final AtomicBoolean started = new AtomicBoolean();

    /** The exact value this process last wrote, or {@code null} when it does not hold the lease. */
    private volatile String heldValue;

    /** Instant, in epoch milliseconds, this process's claim lapses at. */
    private volatile long heldUntilMillis;

    /**
     * Binds the collaborators one claim uses.
     *
     * @param settingRepository holds the lease row; never {@code null}
     * @param properties supplies the lease term and renewal interval; never {@code null}
     * @param clock reads the current instant; never {@code null}
     * @param transactionTemplate demarcates one claim; never {@code null}
     * @param streamClient resolves the stream client on demand; never {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    public BackgroundOwnership(SettingRepository settingRepository,
            ScannerProperties properties,
            Clock clock,
            TransactionTemplate transactionTemplate,
            ObjectProvider<TweetStreamClient> streamClient) {
        this.settingRepository = Objects.requireNonNull(settingRepository,
                "settingRepository must not be null.");
        this.properties = Objects.requireNonNull(properties, "properties must not be null.");
        this.clock = Objects.requireNonNull(clock, "clock must not be null.");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate,
                "transactionTemplate must not be null.");
        this.streamClient = Objects.requireNonNull(streamClient, "streamClient must not be null.");
    }

    /**
     * Reports whether this process holds the lease right now.
     *
     * <p>The answer is process-local: it is {@code true} while a claim this process made has not
     * lapsed by the clock. No statement is issued, so a caller may ask on every record or every pass.
     *
     * @return {@code true} while this process holds an unlapsed claim, {@code false} otherwise —
     *     including before the first claim, after a failed renewal and after the release
     */
    public boolean isOwner() {
        return heldValue != null && clock.millis() < heldUntilMillis;
    }

    /**
     * Reports the identity this process claims the lease under.
     *
     * @return the identity, never {@code null} and never blank
     */
    public String instanceId() {
        return instanceId;
    }

    /**
     * Reports whether any background path is configured to run in this process.
     *
     * @return {@code true} when {@code scanner.background.enabled} holds together with at least one
     *     of the two path switches; {@code true} when the group is unbound, which is the default of
     *     all three keys
     */
    @Override
    public boolean isAutoStartup() {
        ScannerProperties.Background background = properties.background();
        return background == null
                || background.runsStream()
                || background.runsResponseGeneration();
    }

    /**
     * Claims the lease.
     *
     * <p>The method records the outcome and raises nothing: a process that cannot claim the lease
     * starts normally and runs no background work until a renewal claims it.
     */
    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (claim()) {
            log.info("Background ownership claimed by instance {} for {}s; this process runs the "
                    + "background paths its switches enable",
                    LogSafe.correlation(instanceId), leaseTtlSeconds());
            return;
        }
        log.info("Background ownership not claimed by instance {}; this process runs no background "
                + "work until a renewal claims the lease", LogSafe.correlation(instanceId));
    }

    /**
     * Releases the lease so another process can claim it at once.
     *
     * <p>The release is itself a compare-and-set guarded by the value this process wrote, so a lease
     * another process has already taken over is left untouched.
     */
    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        String held = this.heldValue;
        this.heldValue = null;
        this.heldUntilMillis = 0L;
        if (held == null) {
            return;
        }
        try {
            boolean released = Boolean.TRUE.equals(transactionTemplate.execute(status ->
                    settingRepository.replaceValueIfUnchanged(
                            OWNER_SETTING_KEY, held, RELEASED_VALUE) == 1));
            if (released) {
                log.info("Background ownership released by instance {}",
                        LogSafe.correlation(instanceId));
                return;
            }
            log.info("Background ownership was already held elsewhere at release; instance {} left "
                    + "the lease untouched", LogSafe.correlation(instanceId));
        } catch (DataAccessException failure) {
            log.warn("Background ownership could not be released by instance {}: {}; the lease "
                    + "lapses on its own after {}s", LogSafe.correlation(instanceId),
                    LogSafe.type(failure), leaseTtlSeconds());
        }
    }

    /**
     * Reports whether this bean has started and not yet stopped.
     *
     * @return {@code true} between {@link #start()} and {@link #stop()}
     */
    @Override
    public boolean isRunning() {
        return started.get();
    }

    /**
     * Reports the lifecycle phase, which orders this bean ahead of the stream client.
     *
     * @return {@value #PHASE}
     */
    @Override
    public int getPhase() {
        return PHASE;
    }

    /**
     * Renews the lease, or claims it when it is vacant, and aligns the stream with the outcome.
     *
     * <p>{@code config/AsyncSchedulingConfig} registers this method as a fixed-delay task at
     * {@code scanner.background.lease-renew-seconds}. A renewal that succeeds while the stream is
     * configured for this process but not running starts it, which is how ownership left behind by a
     * terminated process is taken over. A renewal that fails releases the local view of ownership and
     * stops the stream.
     *
     * <p>The method raises nothing.
     *
     * @return {@code true} when this process holds the lease after the attempt
     */
    public boolean renewOwnership() {
        if (!started.get()) {
            return false;
        }

        boolean wasOwner = isOwner();
        boolean owner = claim();

        if (owner) {
            if (!wasOwner) {
                log.info("Background ownership claimed by instance {} at renewal",
                        LogSafe.correlation(instanceId));
            }
            startStreamIfIdle();
            return true;
        }

        if (wasOwner) {
            log.warn("Background ownership lost by instance {}; background work stops in this "
                    + "process", LogSafe.correlation(instanceId));
            stopStreamIfRunning();
        }
        return false;
    }

    /**
     * Reads the lease row and writes this process's claim when the lease is vacant or already its own.
     *
     * @return {@code true} when the write took effect, {@code false} on a rejected update, a lost
     *     insert race or any data-access failure
     */
    private boolean claim() {
        long now = clock.millis();
        long until = now + (leaseTtlSeconds() * MILLIS_PER_SECOND);
        String next = instanceId + OWNER_SEPARATOR + until;

        boolean claimed;
        try {
            claimed = Boolean.TRUE.equals(
                    transactionTemplate.execute(status -> writeClaim(next, now)));
        } catch (DataAccessException failure) {
            // Fail closed: an unreachable or refusing store means no ownership — DL-281
            log.warn("Background ownership could not be claimed by instance {}: {}",
                    LogSafe.correlation(instanceId), LogSafe.type(failure));
            claimed = false;
        }

        if (claimed) {
            this.heldValue = next;
            this.heldUntilMillis = until;
            return true;
        }
        this.heldValue = null;
        this.heldUntilMillis = 0L;
        return false;
    }

    /**
     * Performs the read-then-compare-and-set of one claim inside the caller's transaction.
     *
     * @param next the value to write, must not be {@code null}
     * @param now the instant the claim is decided at, in epoch milliseconds
     * @return {@code true} when one row was written
     */
    private boolean writeClaim(String next, long now) {
        Optional<Setting> row = settingRepository.findById(OWNER_SETTING_KEY);
        if (row.isEmpty()) {
            return insertLease(next);
        }

        String current = row.get().getValue();
        if (current == null) {
            return settingRepository.replaceAbsentValue(OWNER_SETTING_KEY, next) == 1;
        }
        if (!isVacant(current, now) && !isMine(current)) {
            return false;
        }
        return settingRepository.replaceValueIfUnchanged(OWNER_SETTING_KEY, current, next) == 1;
    }

    /**
     * Creates the lease row holding this process's claim.
     *
     * @param next the value to write, must not be {@code null}
     * @return {@code true} when the row was created, {@code false} when another process created it
     *     first
     */
    private boolean insertLease(String next) {
        Setting lease = new Setting();
        lease.setKey(OWNER_SETTING_KEY);
        lease.setValue(next);
        lease.setDescription(OWNER_DESCRIPTION);
        try {
            settingRepository.saveAndFlush(lease);
            return true;
        } catch (DataIntegrityViolationException race) {
            // Another process inserted the row between the read and this insert; the next renewal
            // competes on the compare-and-set instead — DL-281
            return false;
        }
    }

    /**
     * Reports whether a stored lease value is available to any claimant.
     *
     * @param value the stored value, must not be {@code null}
     * @param now the instant the claim is decided at, in epoch milliseconds
     * @return {@code true} when the value carries no readable lapse instant or an instant at or
     *     before {@code now}
     */
    private static boolean isVacant(String value, long now) {
        int separator = value.lastIndexOf(OWNER_SEPARATOR);
        if (separator < 0 || separator == value.length() - 1) {
            return true;
        }
        try {
            return Long.parseLong(value.substring(separator + 1).trim()) <= now;
        } catch (NumberFormatException unreadable) {
            return true;
        }
    }

    /**
     * Reports whether a stored lease value names this process.
     *
     * @param value the stored value, must not be {@code null}
     * @return {@code true} when the identity the value carries equals {@link #instanceId}
     */
    private boolean isMine(String value) {
        int separator = value.lastIndexOf(OWNER_SEPARATOR);
        return separator > 0 && instanceId.equals(value.substring(0, separator));
    }

    /** Starts the stream when this process runs it and it is not already running. */
    private void startStreamIfIdle() {
        ScannerProperties.Background background = properties.background();
        if (background != null && !background.runsStream()) {
            return;
        }
        TweetStreamClient client = streamClient.getIfAvailable();
        if (client != null && !client.isRunning()) {
            client.start();
        }
    }

    /** Stops the stream when it is running. */
    private void stopStreamIfRunning() {
        TweetStreamClient client = streamClient.getIfAvailable();
        if (client != null && client.isRunning()) {
            client.stop();
        }
    }

    /**
     * Reports the lease term in force.
     *
     * @return the bound value of {@code scanner.background.lease-ttl-seconds}, or the record default
     *     when the group is unbound
     */
    private long leaseTtlSeconds() {
        ScannerProperties.Background background = properties.background();
        return background == null
                ? ScannerProperties.Background.of(true, true, true).leaseTtlSeconds()
                : background.leaseTtlSeconds();
    }
}
