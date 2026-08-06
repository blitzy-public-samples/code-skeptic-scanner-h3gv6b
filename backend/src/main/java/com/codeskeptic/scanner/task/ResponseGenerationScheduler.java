package com.codeskeptic.scanner.task;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.NotionService;
import com.codeskeptic.scanner.service.ResponseService;
import com.codeskeptic.scanner.util.LogSafe;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * Periodic pass that generates a stored reply for every {@code tweets} row that has none.
 *
 * <p>One pass sweeps the {@code tweets} rows with no associated {@code responses} row and, for each
 * of them, calls {@link ResponseService#generateResponseIfAbsentFor(Tweet)} to generate and store a reply
 * and then mirrors that reply onto the matching Notion page.
 *
 * <p>The sweep reads its candidates in consecutive batches of at most {@value #CANDIDATE_BATCH_ROWS}
 * rows, each batch taken from the rows whose identifier exceeds the last one the pass handled, and it
 * continues until a batch comes back short. One pass still considers the whole backlog, and
 * the rows one statement returns and the rows the pass holds at any moment are both bounded — see
 * docs/DECISION_LOG.md DL-248. The pass declares no transaction, so each batch is read in
 * the repository's own transaction and its rows are detached when that call returns.
 *
 * <p>Pacing lives entirely in {@code config/AsyncSchedulingConfig}, which registers the tick that
 * calls {@link #generatePendingResponses()} as a fixed-delay trigger task: the interval is measured
 * from the end of one pass to the start of the next, is resolved once per pass from the
 * {@code response_generation_delay} {@code settings} row, and falls back to
 * {@code scanner.response-generation-delay-seconds} when that row supplies no positive value — see
 * docs/DECISION_LOG.md DL-047, DL-197, DL-227. This class reads no pacing value and defers no pass: a
 * tick runs a pass.
 * The scheduling capability is activated by {@code config/AsyncSchedulingConfig}, the single carrier
 * of {@code @EnableScheduling} in this application; this class declares no thread and submits to no
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
 * circuit stands between a candidate and its provider call, and the ceiling defers, queues and
 * re-attempts nothing.
 *
 * <p>This pass does not own ingestion's replies. It reaches
 * {@link ResponseService#generateResponseIfAbsentFor(Tweet)}, the entity-shaped signature of the one
 * claim-aware operation both background paths reach — {@code task.TweetStreamListener} reaches its
 * identifier-shaped signature and both run one body — so a candidate that listener is answering, or has
 * answered since the candidate query ran, stores nothing and is counted as skipped — see
 * docs/DECISION_LOG.md DL-195 and DL-226.
 *
 * <p>The relational database is the system of record and Notion is a secondary mirror. A mirror write
 * is retried within the budget {@code service/NotionService} carries; a mirror still rejected after
 * that leaves the already stored reply in place, is counted separately in the pass summary as stored
 * without a mirror, and is not attempted again — no compensation, re-generation or durable
 * reconciliation of unmirrored replies exists — see docs/DECISION_LOG.md DL-253.
 *
 * <p>No code path here publishes anything to X. The class reaches no HTTP client, and it does not
 * read, set or branch on the {@code responses.is_approved} flag of
 * {@code backend/app/db/models.py:L26}, which a human reads.
 *
 * <p>No transaction is declared on the pass: the candidate query and every write inside
 * {@link ResponseService} run within the boundaries those components declare, and the calls to
 * OpenAI and Notion run with no transaction open.
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
public class ResponseGenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ResponseGenerationScheduler.class);

    /** Rows one candidate statement returns — DL-248 — see docs/DECISION_LOG.md. */
    private static final int CANDIDATE_BATCH_ROWS = 100;

    /**
     * Row bound and sort of one candidate statement: {@value #CANDIDATE_BATCH_ROWS} rows, ordered by
     * {@code tweets.id} ascending, which is the order the keyset cursor advances in — DL-248 — see
     * docs/DECISION_LOG.md.
     */
    private static final Pageable CANDIDATE_BATCH =
            PageRequest.of(0, CANDIDATE_BATCH_ROWS, Sort.by(Sort.Direction.ASC, "id"));

    private final TweetRepository tweetRepository;

    private final ResponseService responseService;

    private final NotionService notionService;

    /** Supplies the per-pass candidate ceiling — DL-282. */
    private final ScannerProperties properties;

    /**
     * Binds the three collaborators one pass uses.
     *
     * @param tweetRepository selects the {@code tweets} rows that carry no {@code responses} row;
     *     never {@code null}
     * @param responseService generates and stores one reply per candidate; never {@code null}
     * @param notionService mirrors a stored reply onto its Notion page; never {@code null}
     * @param properties supplies the per-pass candidate ceiling; never {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    // Constructor injection replaces the in-function LLMService() at
    // backend/app/tasks/response_generation.py:L19 and NotionService() at :L29; the get_settings()
    // call at :L37 is replaced by the per-pass resolution config/AsyncSchedulingConfig performs —
    // DL-227 — see docs/DECISION_LOG.md
    public ResponseGenerationScheduler(TweetRepository tweetRepository,
            ResponseService responseService,
            NotionService notionService,
            ScannerProperties properties) {
        this.tweetRepository = Objects.requireNonNull(tweetRepository,
                "tweetRepository must not be null.");
        this.responseService = Objects.requireNonNull(responseService,
                "responseService must not be null.");
        this.notionService = Objects.requireNonNull(notionService,
                "notionService must not be null.");
        this.properties = Objects.requireNonNull(properties, "properties must not be null.");
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
     * and skipped, and the pass continues with the next candidate. The pass closes with a summary of
     * the attempted, succeeded, unmirrored, skipped and failed counts and the number of batches it read
     * — see docs/DECISION_LOG.md DL-248 and DL-253.
     *
     * <p>A candidate ingested after the pass began is answered by this pass when its identifier lies
     * past the cursor at the time the next batch is read, and by the following pass otherwise.
     *
     * <p>Every tick runs a pass. This method carries no scheduling annotation: when a pass runs is
     * decided entirely by {@code config.AsyncSchedulingConfig}, which registers this method as a
     * trigger task and adds the interval in force to the completion of the previous pass, so no pass
     * overlaps its predecessor. The interval is the {@code response_generation_delay} settings row
     * when it holds a positive number of seconds and
     * {@code scanner.response-generation-delay-seconds} otherwise, resolved once per pass. The first
     * pass runs at the startup instant, and not one interval later — see docs/DECISION_LOG.md
     * DL-047, DL-227, DL-228, DL-251.
     *
     * <p>The method takes no argument, returns nothing and throws nothing: every {@link
     * RuntimeException} raised inside it is recorded and suppressed.
     */
    // Ported from schedule_response_generation() at
    // backend/app/tasks/response_generation.py:L35-50 (faithful port) — see docs/DECISION_LOG.md
    // DL-047.
    // The registered trigger task of config/AsyncSchedulingConfig replaces the `time.sleep(...)` call
    // at :L50, which read `settings.response_generation_interval` while
    // backend/app/core/config.py:L11 declared RESPONSE_GENERATION_DELAY — see
    // docs/DECISION_LOG.md DL-047 and DL-227.
    public void generatePendingResponses() {
        try {
            int attempted = 0;
            int succeeded = 0;
            int unmirrored = 0;
            int skipped = 0;
            int failed = 0;
            int batches = 0;
            int candidateCeiling = maxCandidatesPerPass();
            boolean ceilingReached = false;
            // Keyset cursor over tweets.id; null opens the sweep — DL-248 — see
            // docs/DECISION_LOG.md
            Integer afterId = null;

            while (true) {
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
                    // Per-pass candidate ceiling — DL-282 — see docs/DECISION_LOG.md
                    if (attempted >= candidateCeiling) {
                        ceilingReached = true;
                        break;
                    }
                    String tweetId = String.valueOf(candidate.getId());
                    // The cursor advances before the candidate is handled; a candidate that fails is
                    // not revisited in this pass — DL-248 — see docs/DECISION_LOG.md
                    afterId = candidate.getId();
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

                    } catch (RuntimeException e) {
                        failed++;
                        log.error("Scheduled response generation failed for tweet {}: {}",
                                tweetId, LogSafe.type(e));
                    }
                }

                if (ceilingReached) {
                    break;
                }
                if (batch.size() < CANDIDATE_BATCH_ROWS) {
                    break;
                }
            }

            if (ceilingReached) {
                log.warn("Response generation pass stopped at its ceiling of {} candidate(s); the "
                        + "remaining backlog is left to the following pass", candidateCeiling);
            }

            if (attempted == 0) {
                log.debug("Response generation pass found no tweet awaiting a response");
                return;
            }

            log.info("Response generation pass finished: {} attempted, {} succeeded ({} stored "
                            + "without a Notion mirror), {} skipped, {} failed over {} batch(es)",
                    attempted, succeeded, unmirrored, skipped, failed, batches);
        } catch (RuntimeException e) {
            // Sanitized record: operation and exception class only — DL-084 — see
            // docs/DECISION_LOG.md
            log.error("Response generation pass failed: {}", LogSafe.type(e));
        }
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
     * row was answered elsewhere — see docs/DECISION_LOG.md DL-195. A stored reply always carries
     * content: {@code dto/ResponseDto} rejects a {@code null} value for it (DL-080). A mirror
     * rejection is recorded without failing the candidate, the stored reply is left in place in every
     * case, and the mirror is not re-attempted — see docs/DECISION_LOG.md DL-194.
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
        // dto/ResponseDto rejects a null content — DL-080 — see docs/DECISION_LOG.md
        String content = generated.content();

        // Identifier and length only; the generated text is not recorded — see
        // docs/DECISION_LOG.md DL-052
        log.info("Stored response {} for tweet {} ({} character(s))",
                generated.id(), tweetId, content.length());

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
                    generated.id(), tweetId, LogSafe.type(failure));
            return CandidateOutcome.STORED_UNMIRRORED;
        }
        return CandidateOutcome.STORED_AND_MIRRORED;
    }

    // The mirror outcome is reported separately from the stored reply — DL-253 — see
    // docs/DECISION_LOG.md
    /**
     * What one pass did with one candidate.
     */
    private enum CandidateOutcome {

        /** A reply was stored and mirrored to its Notion page. */
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
