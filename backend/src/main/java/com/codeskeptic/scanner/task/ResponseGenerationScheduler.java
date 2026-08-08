package com.codeskeptic.scanner.task;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.NotionService;
import com.codeskeptic.scanner.service.ResponseService;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Periodic pass that generates a stored reply for every {@code tweets} row that has none, and mirrors
 * that reply onto the matching Notion page.
 *
 * <p>The sweep reads its candidates in consecutive batches of at most {@value #CANDIDATE_BATCH_ROWS}
 * rows, each batch taken from the rows whose identifier exceeds the last one the pass handled, and it
 * continues until a batch comes back short. One pass still considers the whole backlog, and the rows
 * one statement returns and the rows the pass holds at any moment are both bounded — DL-248. The pass
 * declares no transaction, so each batch is read in the repository's own transaction, its rows are
 * detached when that call returns, and the calls to OpenAI and Notion run with no transaction open.
 *
 * <p>Pacing is a fixed delay measured from the completion of one pass to the start of the next,
 * which is the work-then-sleep order of {@code backend/app/tasks/response_generation.py:L41-50} —
 * DL-047. The interval in force is {@link #responseGenerationDelaySecondsInForce()}: the
 * {@code response_generation_delay} {@code settings} row when it holds a positive whole number of
 * seconds, and {@code scanner.response-generation-delay-seconds} otherwise, so an operator's edit
 * through {@code PUT /settings/{key}} changes the cadence of the next pass without a restart —
 * DL-227, DL-309. {@code config/AsyncSchedulingConfig} carries {@code @EnableScheduling} and
 * registers this pass against a trigger that consults that method once per pass, at the moment the
 * next instant is computed — DL-228, DL-309; this class declares no thread and submits to no
 * executor. No message broker, queue or task-dispatch infrastructure participates — DL-047.
 *
 * <p>Each candidate is handled independently. A failure is recorded against the candidate's
 * identifier and the pass continues with the next candidate; a failure of the pass itself is
 * recorded and the pass returns normally, leaving the task scheduled. The pass re-attempts no
 * candidate and caches nothing.
 *
 * <p>One ceiling bounds one pass: it attempts at most
 * {@code scanner.background.max-candidates-per-pass} candidates, whatever the backlog holds. A pass
 * that reaches that ceiling ends early, records it with the counts it had reached, and leaves the
 * untouched backlog to the following pass — DL-282. No allowance, window, attempt counter or failure
 * circuit stands between a candidate and its provider call.
 *
 *
 * <p>A candidate that fails on {@value #CANDIDATE_FAILURE_LIMIT} consecutive passes is set aside and
 * passed over on later passes until it succeeds or the process restarts, so a candidate failing for a
 * reason that will not change stops consuming one provider call per pass forever — it stays
 * unanswered, and therefore stays selected, so without this bound the pass would re-attempt it
 * indefinitely. The count is per candidate identifier, is cleared the moment the candidate is handled
 * without raising, is held for at most {@value #TRACKED_FAILING_CANDIDATES} candidates, and lives
 * only in memory: no column, table or dependency records it. It holds no tweet or reply data, serves
 * no read, and paces nothing — see docs/DECISION_LOG.md DL-300.
 *
 * <p>This pass does not own ingestion's replies. It reaches
 * {@link ResponseService#generateResponseIfAbsentFor(Tweet)}, the entity-shaped signature of the one
 * claim-aware operation both background paths reach — {@code task.TweetStreamListener} reaches its
 * identifier-shaped signature and both run one body — so a candidate that listener is answering, or has
 * answered since the candidate query ran, stores nothing and is counted as skipped — DL-195, DL-226. A
 * selected row is handed to {@link ResponseService} as it was selected, so no candidate is read a
 * second time — DL-226. {@link Tweet#getResponses()} is lazy, {@code spring.jpa.open-in-view} is
 * {@code false}, and the collection is never traversed here.
 *
 * <p>The relational database is the system of record and Notion is a secondary mirror. A mirror write
 * is retried within the budget {@code service/NotionService} carries; a mirror still rejected after
 * that leaves the already stored reply in place, is counted separately in the pass summary as stored
 * without a mirror, and is not attempted again — DL-253.
 *
 * <p>No code path here publishes anything to X. The class reaches no HTTP client, and it does not
 * read, set or branch on the {@code responses.is_approved} flag of
 * {@code backend/app/db/models.py:L26}, which a human reads.
 *
 * <p>No transaction is declared on the pass: the candidate query and every write inside
 * {@link ResponseService} run within the boundaries those components declare, and the calls to
 * OpenAI and Notion run with no transaction open.
 *
 * <p>A pass in flight abandons its remainder when the application context begins to close. This class
 * listens for {@link ContextClosedEvent} — the first step of the close, published before any lifecycle
 * bean is stopped and before any singleton is destroyed — and raises a flag the pass reads between two
 * candidates and before each batch read. The pass then reports what it completed and returns, so the
 * bounded wait {@code config/AsyncSchedulingConfig} places on a running pass is a backstop rather than
 * the thing that decides how long a shutdown takes, and no candidate is handled after the resources it
 * needs have been closed. Nothing is cancelled, interrupted or rolled back: whatever a candidate
 * already committed stays committed, and the candidates the pass did not reach stay unanswered and are
 * therefore selected again by the first pass after the next start — DL-301.
 *
 * <p>A selected row is handed to {@link ResponseService} as it was selected, so no candidate is read a
 * second time: the candidate query already carries every column the generator reads — DL-226.
 * {@link Tweet#getResponses()} is lazy and {@code spring.jpa.open-in-view} is {@code false}; the
 * collection is never traversed here.
 *
 * @see ResponseService#generateResponseIfAbsentFor(Tweet)
 * @see NotionService#updateTweetResponse(String, String)
 */
// Fixed-delay intent ported from schedule_response_generation at
// backend/app/tasks/response_generation.py:L35-50, absorbing the task body at :L10-33 (faithful port
// of intent) — see docs/DECISION_LOG.md DL-047
@Component
public class ResponseGenerationScheduler implements ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ResponseGenerationScheduler.class);

    /**
     * Key of the {@code settings} row that sets this pass's interval, seeded by
     * {@code service.SettingsService} — DL-040, DL-227 — see docs/DECISION_LOG.md.
     */
    private static final String RESPONSE_GENERATION_DELAY_SETTING_KEY = "response_generation_delay";

    /** Rows one candidate statement returns — DL-248 — see docs/DECISION_LOG.md. */
    private static final int CANDIDATE_BATCH_ROWS = 100;

    /**
     * Row bound and sort of one candidate statement: {@value #CANDIDATE_BATCH_ROWS} rows, ordered by
     * {@code tweets.id} ascending, which is the order the keyset cursor advances in — DL-248 — see
     * docs/DECISION_LOG.md.
     */
    private static final Pageable CANDIDATE_BATCH =
            PageRequest.of(0, CANDIDATE_BATCH_ROWS, Sort.by(Sort.Direction.ASC, "id"));

    /**
     * Consecutive failures after which a candidate is passed over instead of attempted again. A
     * candidate that fails for a reason that will not change — its own stored column values, or a
     * reply the provider keeps producing that cannot be stored — otherwise consumes one provider call
     * on every later pass forever, because it stays unanswered and so stays selected. Three attempts
     * still absorb a transient provider or database outage spanning several passes — DL-300 — see
     * docs/DECISION_LOG.md.
     */
    private static final int CANDIDATE_FAILURE_LIMIT = 3;

    /**
     * Most failing candidates whose consecutive-failure count is held at once. Past this many the
     * least recently failing candidate is dropped, which bounds the memory this bookkeeping occupies
     * regardless of backlog size and simply grants the dropped candidate fresh attempts — DL-300 —
     * see docs/DECISION_LOG.md.
     */
    private static final int TRACKED_FAILING_CANDIDATES = 1_000;

    /**
     * Raised once the application context has begun to close.
     *
     * <p>Written by {@link #onApplicationEvent(ContextClosedEvent)} on the thread that closes the
     * context and read by {@link #generatePendingResponses()} on a scheduler pool thread, so the field
     * is {@code volatile}: the write must be visible to the pass without any lock between them. It is
     * never lowered again, because a closed context is not reopened — DL-301 — see
     * docs/DECISION_LOG.md.
     */
    // Net-new cooperative stop signal (the source loop had no stop path at all:
    // backend/app/tasks/response_generation.py:L41 is `while True`) — DL-301
    private volatile boolean contextClosing;

    private final TweetRepository tweetRepository;

    private final ResponseService responseService;

    private final NotionService notionService;

    /**
     * Supplies the per-pass candidate ceiling and the configured interval this pass falls back to —
     * DL-282, DL-227.
     */
    private final ScannerProperties properties;

    /** Reads the {@code response_generation_delay} row that sets the interval — DL-227, DL-309. */
    private final SettingRepository settingRepository;

    /**
     * Value of the {@value #RESPONSE_GENERATION_DELAY_SETTING_KEY} row the last unusable-value
     * warning was recorded for, so a row that stays unusable cannot fill the log — DL-309.
     *
     * <p>Read and written from the scheduler pool thread that computes the next instant, and from any
     * thread that calls {@link #responseGenerationDelaySecondsInForce()} directly; the reference is
     * atomic so that comparison and replacement are one step.
     */
    // Net-new: one warning per distinct unusable value — DL-309 — see docs/DECISION_LOG.md
    private final AtomicReference<String> lastUnusableDelay = new AtomicReference<>();

    /**
     * Consecutive failures per candidate identifier, most recently failing last.
     *
     * <p>Insertion order is refreshed on every recorded failure — the entry is removed and re-inserted
     * — so the iteration order is least-recently-failing first and the eviction that keeps the map
     * within {@value #TRACKED_FAILING_CANDIDATES} entries drops the candidate whose last failure is
     * oldest. An entry is removed the moment its candidate is handled without raising.
     *
     * <p>This is in-memory bookkeeping and nothing else: no {@code tweets} column records it, no table
     * is added, and a restart clears it, which is what grants a candidate set aside during a long
     * outage its attempts back — DL-300 — see docs/DECISION_LOG.md.
     *
     * <p>Passes never overlap, because pacing is a fixed delay measured from the end of one pass, so
     * this map is reached by one thread at a time; every access is nonetheless taken under the map's
     * own monitor so that a pass running on a different pool thread than the previous one observes
     * what that pass recorded.
     */
    // Net-new (no Python counterpart; the source re-selected a failing candidate on every loop
    // iteration) — DL-300 — see docs/DECISION_LOG.md
    private final Map<Integer, Integer> consecutiveFailures = new LinkedHashMap<>();

    /**
     * Binds the three collaborators one pass uses.
     *
     * @param tweetRepository selects the {@code tweets} rows that carry no {@code responses} row;
     *     never {@code null}
     * @param responseService generates and stores one reply per candidate; never {@code null}
     * @param notionService mirrors a stored reply onto its Notion page; never {@code null}
     * @param properties supplies the per-pass candidate ceiling and the configured interval this
     *     pass falls back to; never {@code null}
     * @param settingRepository reads the {@value #RESPONSE_GENERATION_DELAY_SETTING_KEY} row that
     *     sets the interval; never {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    // Constructor injection replaces the in-function LLMService() at
    // backend/app/tasks/response_generation.py:L19 and NotionService() at :L29; the get_settings()
    // call at :L37 is replaced by the injected configuration root — DL-227 — see
    // docs/DECISION_LOG.md
    public ResponseGenerationScheduler(TweetRepository tweetRepository,
            ResponseService responseService,
            NotionService notionService,
            ScannerProperties properties,
            SettingRepository settingRepository) {
        this.tweetRepository = Objects.requireNonNull(tweetRepository,
                "tweetRepository must not be null.");
        this.responseService = Objects.requireNonNull(responseService,
                "responseService must not be null.");
        this.notionService = Objects.requireNonNull(notionService,
                "notionService must not be null.");
        this.properties = Objects.requireNonNull(properties, "properties must not be null.");
        this.settingRepository = Objects.requireNonNull(settingRepository,
                "settingRepository must not be null.");
    }

    // Ported from the settings-backed interval the source loop read at
    // backend/app/tasks/response_generation.py:L50 — which named
    // settings.response_generation_interval where backend/app/core/config.py:L11 declared
    // RESPONSE_GENERATION_DELAY — DL-227, DL-309 — see docs/DECISION_LOG.md
    /**
     * Resolves the interval between the completion of one pass and the start of the next, in seconds.
     *
     * <p>The {@value #RESPONSE_GENERATION_DELAY_SETTING_KEY} {@code settings} row is read first and
     * its value, once surrounding whitespace is discarded, is used when it parses as a positive
     * {@code long}. {@code scanner.response-generation-delay-seconds} applies when that row is absent,
     * holds {@code null}, holds a value that does not parse, or holds a value that is not positive —
     * the last two are logged at {@code WARN} naming the key without recording the stored value.
     *
     * <p>A non-positive stored interval is refused rather than honoured: a zero or negative delay
     * would place the next instant at or before the previous completion, so the pass would run
     * without pause. That is the one respect in which this row differs from
     * {@code tweet_popularity_threshold}, which honours a negative value as stored — DL-309.
     *
     * <p>The warning is recorded once per distinct unusable value: a value identical to the one the
     * last warning was recorded for is recorded at {@code DEBUG} instead. A usable value clears that
     * memory, so the same unusable value is reported again if it returns.
     *
     * <p>The trigger of {@code config/AsyncSchedulingConfig} calls this once per pass, when it
     * computes the next instant, so an edit written through {@code PUT /settings/{key}} takes effect
     * on the following interval without a restart — DL-228, DL-309.
     *
     * @return the interval in force, always positive
     */
    public long responseGenerationDelaySecondsInForce() {
        String storedDelay = settingRepository.findById(RESPONSE_GENERATION_DELAY_SETTING_KEY)
                .map(settingRow -> settingRow.getValue())
                .orElse(null);

        if (storedDelay != null) {
            try {
                long parsed = Long.parseLong(storedDelay.trim());
                if (parsed > 0L) {
                    lastUnusableDelay.set(null);
                    return parsed;
                }
                reportUnusableDelay(storedDelay, "is not a positive number of seconds");
            } catch (NumberFormatException ex) {
                reportUnusableDelay(storedDelay, "does not hold a whole number of seconds");
            }
        } else {
            lastUnusableDelay.set(null);
        }

        // backend/app/core/config.py:L11 — the configured interval
        return properties.responseGenerationDelaySeconds();
    }

    // Net-new: one warning per distinct unusable value — DL-309 — see docs/DECISION_LOG.md
    /**
     * Records that the {@value #RESPONSE_GENERATION_DELAY_SETTING_KEY} row does not hold a usable
     * interval.
     *
     * <p>The record is emitted at {@code WARN} when the offending value differs from the one the last
     * warning was recorded for, and at {@code DEBUG} otherwise. Neither record carries the stored
     * value; the value is held only to compare against the next one — DL-052.
     *
     * @param storedDelay the value that cannot be used, never {@code null}
     * @param reason      a fixed description of why it cannot be used
     */
    private void reportUnusableDelay(String storedDelay, String reason) {
        String previous = lastUnusableDelay.getAndSet(storedDelay);
        if (storedDelay.equals(previous)) {
            log.debug("Setting '{}' still {}; applying "
                            + "scanner.response-generation-delay-seconds instead.",
                    RESPONSE_GENERATION_DELAY_SETTING_KEY, reason);
            return;
        }

        log.warn("Setting '{}' {}; applying scanner.response-generation-delay-seconds instead.",
                RESPONSE_GENERATION_DELAY_SETTING_KEY, reason);
    }

    /**
     * Runs one generation pass over every {@code tweets} row that has no {@code responses} row.
     *
     * <p>The pass reads its candidates in consecutive batches of at most
     * {@value #CANDIDATE_BATCH_ROWS} rows, and for each candidate of each batch generates and stores a
     * reply and mirrors it to Notion. The next batch is taken from the rows whose identifier exceeds
     * the last candidate handled, so no candidate is read twice and none is skipped, and the sweep ends
     * with the first batch that comes back short of that bound. A candidate another path already
     * answered stores nothing and is counted as skipped. A candidate whose handling raises is recorded
     * and skipped, and the pass continues with the next candidate; one more consecutive failure is
     * counted against it, and once it has failed on {@value #CANDIDATE_FAILURE_LIMIT} consecutive
     * passes it is passed over without reaching the generator and counted as set aside — DL-300. The
     * pass closes with a summary of the attempted, succeeded, unmirrored, skipped, failed and set-aside
     * counts and the number of batches it read — see docs/DECISION_LOG.md DL-248, DL-253 and DL-300.
     *
     * <p>One ceiling bounds one pass: it attempts at most
     * {@code scanner.background.max-candidates-per-pass} candidates, whatever the backlog holds,
     * and leaves the untouched backlog to the following pass — DL-282.
     *
     * <p>A candidate ingested after the pass began is answered by this pass when its identifier lies
     * past the cursor at the time the next batch is read, and by the following pass otherwise.
     *
     * <p>The interval is measured from the completion of one pass to the start of the next, so no pass
     * overlaps its predecessor, and the first pass runs at the startup instant — the work-then-sleep
     * order of {@code backend/app/tasks/response_generation.py:L41-50}. The interval in force is
     * {@link #responseGenerationDelaySecondsInForce()}, consulted once per pass by the trigger
     * {@code config/AsyncSchedulingConfig} registers, so the {@code response_generation_delay}
     * {@code settings} row sets the cadence and an edit takes effect on the following interval —
     * DL-047, DL-227, DL-228, DL-309.
     *
     * <p>Nothing runs in a process that does not carry the pass: {@code scanner.background.enabled} and
     * {@code scanner.background.response-generation-enabled} must both hold, and a tick in a process
     * for which either is {@code false} returns without reading a candidate — DL-250. Every
     * {@link RuntimeException} raised inside this method is recorded and suppressed.
     */
    // Ported from schedule_response_generation() at
    // backend/app/tasks/response_generation.py:L35-50 (faithful port) — see docs/DECISION_LOG.md
    // DL-047.
    // The completion-based trigger registered by config/AsyncSchedulingConfig replaces the
    // `while True` loop at :L41 and the trailing `time.sleep(...)` at :L50, which read
    // `settings.response_generation_interval` while backend/app/core/config.py:L11 declared
    // RESPONSE_GENERATION_DELAY — see docs/DECISION_LOG.md DL-047, DL-227, DL-228 and DL-309.
    public void generatePendingResponses() {
        // Only a process that carries the pass runs it — DL-250 — see docs/DECISION_LOG.md
        if (!runsResponseGeneration()) {
            log.debug("Response generation is not carried by this process; the pass reads nothing.");
            return;
        }

        try {
            int attempted = 0;
            int succeeded = 0;
            int unmirrored = 0;
            int skipped = 0;
            int failed = 0;
            int setAside = 0;
            int batches = 0;
            int candidateCeiling = maxCandidatesPerPass();
            boolean ceilingReached = false;
            // Keyset cursor over tweets.id; null opens the sweep — DL-248 — see
            // docs/DECISION_LOG.md
            Integer afterId = null;
            boolean abandoned = false;

            while (true) {
                // No further batch is read once the context has begun to close — DL-301 — see
                // docs/DECISION_LOG.md
                if (shouldAbandonPass()) {
                    abandoned = true;
                    break;
                }

                List<Tweet> batch = tweetRepository.findUnansweredBatchAfter(afterId, CANDIDATE_BATCH);

                if (batch.isEmpty()) {
                    break;
                }

                batches++;
                if (batches == 1) {
                    log.info("Response generation pass started with {} tweet(s) in the first batch "
                            + "of at most {}", batch.size(), CANDIDATE_BATCH_ROWS);
                }

                for (Tweet candidate : batch) {
                    // Observed between two candidates, so the candidate in flight completes and the
                    // remainder is left to the next start — DL-301 — see docs/DECISION_LOG.md
                    if (shouldAbandonPass()) {
                        abandoned = true;
                        break;
                    }
                    // Per-pass candidate ceiling — DL-282 — see docs/DECISION_LOG.md
                    if (attempted >= candidateCeiling) {
                        ceilingReached = true;
                        break;
                    }

                    Integer candidateId = candidate.getId();
                    String tweetId = String.valueOf(candidateId);
                    // The cursor advances before the candidate is handled; a candidate that fails is
                    // not revisited in this pass — DL-248 — see docs/DECISION_LOG.md
                    afterId = candidateId;

                    // A candidate that has failed on this many consecutive passes is passed over
                    // without reaching the generator, so it consumes no provider call — DL-300 —
                    // see docs/DECISION_LOG.md
                    if (hasReachedFailureLimit(candidateId)) {
                        setAside++;
                        log.debug("Tweet {} is set aside after {} consecutive failures; this pass "
                                + "attempts no generation for it", tweetId,
                                CANDIDATE_FAILURE_LIMIT);
                        continue;
                    }

                    attempted++;
                    try {
                        // The mirror outcome is counted separately from the stored reply — DL-253 —
                        // see docs/DECISION_LOG.md
                        switch (generateAndMirror(candidate)) {
                            case STORED_AND_MIRRORED -> succeeded++;
                            case STORED_UNMIRRORED -> {
                                succeeded++;
                                unmirrored++;
                            }
                            case SKIPPED -> skipped++;
                        }
                        // Handled without raising, so the candidate carries no consecutive-failure
                        // count into the next pass — DL-300 — see docs/DECISION_LOG.md
                        forgetFailures(candidateId);

                    } catch (RuntimeException e) {
                        failed++;
                        recordFailure(candidateId);
                        log.error("Scheduled response generation failed for tweet {}: {}",
                                tweetId, e.getClass().getSimpleName());
                    }
                }

                if (ceilingReached || abandoned || batch.size() < CANDIDATE_BATCH_ROWS) {
                    break;
                }
            }

            if (abandoned) {
                log.info("Response generation pass abandoned because the context is closing: {} "
                                + "attempted, {} succeeded ({} stored without a Notion mirror), {} "
                                + "skipped, {} failed, {} set aside, over {} batch(es); every "
                                + "candidate it did not reach stays unanswered and is selected again "
                                + "by the first pass after the next start",
                        attempted, succeeded, unmirrored, skipped, failed, setAside, batches);
                return;
            }

            if (ceilingReached) {
                log.warn("Response generation pass stopped at its ceiling of {} candidate(s); the "
                        + "remaining backlog is left to the following pass", candidateCeiling);
            }

            if (attempted == 0 && setAside == 0) {
                log.debug("Response generation pass found no tweet awaiting a response");
                return;
            }

            log.info("Response generation pass finished: {} attempted, {} succeeded ({} stored "
                            + "without a Notion mirror), {} skipped, {} failed, {} set aside after "
                            + "{} consecutive failures, over {} batch(es)",
                    attempted, succeeded, unmirrored, skipped, failed, setAside,
                    CANDIDATE_FAILURE_LIMIT, batches);
        } catch (RuntimeException e) {
            // Sanitized record: operation and exception class only — DL-084 — see
            // docs/DECISION_LOG.md
            log.error("Response generation pass failed: {}", e.getClass().getSimpleName());
        }
    }

    // Net-new enablement switch — DL-250 — see docs/DECISION_LOG.md
    /**
     * Reports whether this process carries the scheduled response-generation pass.
     *
     * @return {@code true} when {@code scanner.background.enabled} and
     *     {@code scanner.background.response-generation-enabled} both hold; {@code true} when the group
     *     is unbound, which is the declared default of both keys
     */
    private boolean runsResponseGeneration() {
        ScannerProperties.Background background = properties.background();
        return background == null || background.runsResponseGeneration();
    }

    // Net-new per-pass ceiling — DL-282 — see docs/DECISION_LOG.md
    /**
     * Reports the candidate ceiling in force for one pass.
     *
     * @return the bound value of {@code scanner.background.max-candidates-per-pass}, or the declared
     *     default when the group is unbound
     */
    private int maxCandidatesPerPass() {
        ScannerProperties.Background background = properties.background();
        return background == null
                ? ScannerProperties.Background.of(true, true, true).maxCandidatesPerPass()
                : background.maxCandidatesPerPass();
    }

    /**
     * Generates and stores one reply for the supplied candidate row, then mirrors it to Notion.
     *
     * <p>The row is handed on as the candidate query selected it, so the generator reads no row of its
     * own — see docs/DECISION_LOG.md DL-226. Nothing is stored and nothing is mirrored when
     * {@link ResponseService#generateResponseIfAbsentFor(Tweet)} reports an empty result, which means the
     * row was answered elsewhere — see docs/DECISION_LOG.md DL-195. {@code responses.content} is
     * nullable and {@code dto/ResponseDto} carries an empty column through as {@code null} (DL-080),
     * so the reported character count reads the value defensively and a {@code null} reply is mirrored
     * as an empty string. A mirror rejection is recorded without failing the candidate, the stored
     * reply is left in place in every case, and the mirror is not re-attempted — see
     * docs/DECISION_LOG.md DL-194.
     *
     * @param candidate the {@code tweets} row to reply to, carrying its assigned identifier; never
     *     {@code null}
     * @return {@link CandidateOutcome#STORED_AND_MIRRORED} when a reply was stored and mirrored,
     *     {@link CandidateOutcome#STORED_UNMIRRORED} when it was stored and Notion refused the mirror,
     *     and {@link CandidateOutcome#SKIPPED} when the row was answered elsewhere and nothing was
     *     stored
     * @throws RuntimeException as raised by
     *     {@link ResponseService#generateResponseIfAbsentFor(Tweet)}; the caller records it against the
     *     candidate's identifier and continues with the next candidate
     */
    private CandidateOutcome generateAndMirror(Tweet candidate) {
        String tweetId = String.valueOf(candidate.getId());
        // Direct in-process call replacing `generate_response.delay(tweet.id)` at
        // backend/app/tasks/response_generation.py:L47 — see docs/DECISION_LOG.md DL-047.
        // The guarded path task.TweetStreamListener also calls — DL-195 — see docs/DECISION_LOG.md.
        // ResponseService owns the LLM call, the stored row and the only
        // ResponseGenerationException — see docs/DECISION_LOG.md
        // The single background generation entry point — DL-195 — see docs/DECISION_LOG.md
        // The already-selected row is handed on — DL-226 — see
        // docs/DECISION_LOG.md
        Optional<ResponseDto> result = responseService.generateResponseIfAbsentFor(candidate);

        if (result.isEmpty()) {
            log.debug("Tweet {} was answered elsewhere; this pass stores nothing and skips the "
                    + "Notion mirror", tweetId);

            return CandidateOutcome.SKIPPED;
        }

        ResponseDto generated = result.get();
        // responses.content is nullable and dto/ResponseDto carries an empty column through as null,
        // so the length is read defensively — DL-080 — see docs/DECISION_LOG.md
        String content = generated.content();

        // Identifier and length only; the generated text is not recorded — see
        // docs/DECISION_LOG.md DL-052
        log.info("Stored response {} for tweet {} ({} character(s))",
                generated.id(), tweetId, content == null ? 0 : content.length());

        // Closes the call to the absent NotionService.update_tweet_response at
        // backend/app/tasks/response_generation.py:L30, which the documented step "Update Notion
        // database with response" names (documentation/Code Structure.md) — see
        // docs/DECISION_LOG.md
        // service/NotionService applies the bounded mirror retry; this class applies none —
        // see docs/DECISION_LOG.md DL-253
        try {
            notionService.updateTweetResponse(tweetId, content);
        } catch (RuntimeException failure) {
            // service/NotionService owns the failure record — see docs/DECISION_LOG.md DL-197
            log.debug("Mirroring response {} for tweet {} to the Notion database failed with {}",
                    generated.id(), tweetId, failure.getClass().getSimpleName());
            return CandidateOutcome.STORED_UNMIRRORED;
        }
        return CandidateOutcome.STORED_AND_MIRRORED;
    }

    /**
     * Raises the stop signal the moment the application context begins to close.
     *
     * <p>{@link ContextClosedEvent} is published as the first step of the close, before any
     * {@code SmartLifecycle} bean is stopped and before any singleton is destroyed, which is why it is
     * the hook this class uses: a pass reading the flag between two candidates therefore stops before
     * the scheduler pool, the entity manager factory and the connection pool are taken away from it.
     * The method performs no work of its own and never raises — DL-301.
     *
     * @param event the close notification, never {@code null}
     */
    // Net-new (no source construct: the source loop could not be stopped) — DL-301 — see
    // docs/DECISION_LOG.md
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        contextClosing = true;
        log.info("Context close observed; no further response generation batch or candidate is "
                + "started");
    }

    /**
     * Reports whether the pass must stop where it is.
     *
     * <p>Two signals are honoured. The flag {@link #onApplicationEvent(ContextClosedEvent)} raises is
     * the one an orderly shutdown uses. The calling thread's interrupt status is honoured as well, so a
     * pool that is shut down with cancellation rather than awaited also stops the pass; the status is
     * read and not cleared, so whatever the pool does with it afterwards is unaffected — DL-301.
     *
     * @return {@code true} when neither another candidate nor another batch may be started
     */
    // Net-new — DL-301 — see docs/DECISION_LOG.md
    private boolean shouldAbandonPass() {
        return contextClosing || Thread.currentThread().isInterrupted();
    }

    /**
     * Reports whether a candidate has failed on {@value #CANDIDATE_FAILURE_LIMIT} consecutive passes.
     *
     * @param candidateId identifier of the {@code tweets} row; never {@code null}
     * @return {@code true} when the candidate is to be passed over without reaching the generator
     */
    // Net-new (no Python counterpart) — DL-300 — see docs/DECISION_LOG.md
    private boolean hasReachedFailureLimit(Integer candidateId) {
        synchronized (consecutiveFailures) {
            Integer failures = consecutiveFailures.get(candidateId);
            return failures != null && failures >= CANDIDATE_FAILURE_LIMIT;
        }
    }

    /**
     * Counts one more consecutive failure against a candidate.
     *
     * <p>The entry is removed and re-inserted so that it becomes the most recent, and the map is then
     * trimmed to {@value #TRACKED_FAILING_CANDIDATES} entries from the least recent end. Reaching
     * {@value #CANDIDATE_FAILURE_LIMIT} is recorded once at {@code WARN}, because from that point the
     * candidate stays unanswered until something about it changes.
     *
     * @param candidateId identifier of the {@code tweets} row; never {@code null}
     */
    // Net-new (no Python counterpart) — DL-300 — see docs/DECISION_LOG.md
    private void recordFailure(Integer candidateId) {
        int failures;
        int tracked;
        synchronized (consecutiveFailures) {
            Integer previous = consecutiveFailures.remove(candidateId);
            failures = (previous == null) ? 1 : previous + 1;
            consecutiveFailures.put(candidateId, failures);

            Iterator<Integer> leastRecentFirst = consecutiveFailures.keySet().iterator();
            while (consecutiveFailures.size() > TRACKED_FAILING_CANDIDATES
                    && leastRecentFirst.hasNext()) {
                leastRecentFirst.next();
                leastRecentFirst.remove();
            }
            tracked = consecutiveFailures.size();
        }

        if (failures == CANDIDATE_FAILURE_LIMIT) {
            log.warn("Tweet {} has failed generation on {} consecutive passes and is set aside; no "
                            + "further generation is attempted for it while this process runs and it "
                            + "keeps failing ({} candidate(s) tracked)",
                    candidateId, failures, tracked);
        }
    }

    /**
     * Drops any consecutive-failure count held against a candidate.
     *
     * @param candidateId identifier of the {@code tweets} row; never {@code null}
     */
    // Net-new (no Python counterpart) — DL-300 — see docs/DECISION_LOG.md
    private void forgetFailures(Integer candidateId) {
        synchronized (consecutiveFailures) {
            consecutiveFailures.remove(candidateId);
        }
    }

    // The mirror outcome is reported separately from the stored reply — DL-253 — see
    // docs/DECISION_LOG.md
    private enum CandidateOutcome {

        STORED_AND_MIRRORED,

        /**
         * A reply was stored and Notion refused the mirror after its retries. The stored reply stays in
         * place and the mirror is not attempted again by this service.
         */
        STORED_UNMIRRORED,

        /** The row was answered elsewhere, so nothing was stored and no mirror was attempted. */
        SKIPPED
    }
}
