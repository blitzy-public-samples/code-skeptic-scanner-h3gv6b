package com.codeskeptic.scanner.task;

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
import org.springframework.stereotype.Component;

/**
 * Periodic pass that generates a stored reply for every {@code tweets} row that has none.
 *
 * <p>One pass selects the {@code tweets} rows with no associated {@code responses} row and, for each
 * of them, calls {@link ResponseService#generateResponseIfAbsent(String)} to generate and store a
 * reply and
 * then mirrors that reply onto the matching Notion page.
 *
 * <p>Pacing lives entirely in {@code config/AsyncSchedulingConfig}, which registers the tick that
 * calls {@link #generatePendingResponses()} as a fixed-delay trigger task: the interval is measured
 * from the end of one pass to the start of the next, is resolved once per pass from the
 * {@code response_generation_delay} {@code settings} row, and falls back to
 * {@code scanner.response-generation-delay-seconds} when that row supplies no positive value — see
 * docs/DECISION_LOG.md DL-047, DL-197, DL-227. This class reads no pacing value and defers no pass: a
 * tick runs a pass. The scheduling capability is activated by {@code config/AsyncSchedulingConfig},
 * the single carrier of {@code @EnableScheduling} in this application; this class carries none,
 * declares no thread and submits to no executor. No message broker, queue or task-dispatch
 * infrastructure participates — DL-047.
 *
 * <p>Each candidate is handled independently. A failure is recorded against the candidate's
 * identifier and the pass continues with the next candidate; a failure of the pass itself is
 * recorded and the pass returns normally, leaving the task scheduled. The pass performs no retry,
 * applies no rate limit, caches nothing, and neither caps nor paginates the candidate list.
 *
 * <p>This pass does not own ingestion's replies. It reaches
 * {@link ResponseService#generateResponseIfAbsent(String)}, the one operation both background paths
 * call, so a candidate that {@code task.TweetStreamListener} is answering — or has answered since the
 * candidate query ran — stores nothing and is counted as skipped — see docs/DECISION_LOG.md DL-195.
 *
 * <p>The relational database is the system of record and Notion is a secondary mirror. A failed
 * mirror leaves the already stored reply in place; no compensation or re-generation is performed.
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
 * @see ResponseService#generateResponseIfAbsent(Tweet)
 * @see NotionService#updateTweetResponse(String, String)
 */
// Fixed-delay intent ported from schedule_response_generation at
// backend/app/tasks/response_generation.py:L35-50, absorbing the task body at :L10-33 (faithful port
// of intent) — see docs/DECISION_LOG.md DL-047
@Component
public class ResponseGenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ResponseGenerationScheduler.class);

    private final TweetRepository tweetRepository;

    private final ResponseService responseService;

    private final NotionService notionService;

    /**
     * Binds the three collaborators one pass uses.
     *
     * @param tweetRepository selects the {@code tweets} rows that carry no {@code responses} row;
     *     never {@code null}
     * @param responseService generates and stores one reply per candidate; never {@code null}
     * @param notionService mirrors a stored reply onto its Notion page; never {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    // Constructor injection replaces the in-function LLMService() at
    // backend/app/tasks/response_generation.py:L19 and NotionService() at :L29; the get_settings()
    // call at :L37 is replaced by the pacing config/AsyncSchedulingConfig resolves — DL-227 — see
    // docs/DECISION_LOG.md
    public ResponseGenerationScheduler(TweetRepository tweetRepository,
            ResponseService responseService,
            NotionService notionService) {
        this.tweetRepository = Objects.requireNonNull(tweetRepository,
                "tweetRepository must not be null.");
        this.responseService = Objects.requireNonNull(responseService,
                "responseService must not be null.");
        this.notionService = Objects.requireNonNull(notionService,
                "notionService must not be null.");
    }

    /**
     * Runs one generation pass over every {@code tweets} row that has no {@code responses} row.
     *
     * <p>The pass selects its candidates, then for each candidate generates and stores a reply and
     * mirrors it to Notion. A candidate another path already answered stores nothing and is counted as
     * skipped. A candidate whose handling raises is recorded and skipped, and the pass continues. The
     * pass closes with a summary of the attempted, succeeded, skipped and failed counts.
     *
     * <p>Every tick runs a pass. When a pass runs is decided in one place only, by the trigger
     * {@code config/AsyncSchedulingConfig} registers, which measures the interval in force from the
     * completion of the previous pass — see docs/DECISION_LOG.md DL-047, DL-197, DL-227.
     *
     * <p>The method takes no argument, returns nothing and throws nothing: every {@link
     * RuntimeException} raised inside it is recorded and suppressed.
     */
    // Ported from schedule_response_generation() at
    // backend/app/tasks/response_generation.py:L35-50 (faithful port) — see docs/DECISION_LOG.md
    // DL-047.
    // The pacing this method is registered with replaces the `time.sleep(...)` call at :L50, which
    // read `settings.response_generation_interval` while backend/app/core/config.py:L11 declared
    // RESPONSE_GENERATION_DELAY — see docs/DECISION_LOG.md DL-040, DL-047 and DL-227.
    public void generatePendingResponses() {
        try {
            List<Tweet> candidates = tweetRepository.findByResponsesIsEmpty();

            if (candidates.isEmpty()) {
                log.debug("Response generation pass found no tweet awaiting a response");
                return;
            }

            log.info("Response generation pass started for {} tweet(s) awaiting a response",
                    candidates.size());

            int succeeded = 0;
            int skipped = 0;
            int failed = 0;

            for (Tweet candidate : candidates) {
                String tweetId = String.valueOf(candidate.getId());
                try {
                    if (generateAndMirror(candidate)) {
                        succeeded++;
                    } else {
                        skipped++;
                    }

                } catch (RuntimeException e) {
                    failed++;
                    log.error("Scheduled response generation failed for tweet {}: {}",
                            tweetId, LogSafe.type(e));
                }
            }

            log.info("Response generation pass finished: {} attempted, {} succeeded, {} skipped, "
                            + "{} failed",
                    candidates.size(), succeeded, skipped, failed);
        } catch (RuntimeException e) {
            // Sanitized record: operation and exception class only — DL-084 — see
            // docs/DECISION_LOG.md
            log.error("Response generation pass failed: {}", LogSafe.type(e));
        }
    }

    /**
     * Generates and stores one reply for the supplied candidate row, then mirrors it to Notion.
     *
     * <p>The row is handed on as the candidate query selected it, so the generator reads no row of its
     * own — see docs/DECISION_LOG.md DL-226. Nothing is stored and nothing is mirrored when
     * {@link ResponseService#generateResponseIfAbsent(Tweet)} reports an empty result, which means the
     * row was answered elsewhere — see docs/DECISION_LOG.md DL-195. A stored reply always carries
     * content: {@code dto/ResponseDto} rejects a {@code null} value for it (DL-080). A mirror
     * rejection is recorded without failing the candidate, the stored reply is left in place in every
     * case, and the mirror is not re-attempted — see docs/DECISION_LOG.md DL-194.
     *
     * @param candidate the {@code tweets} row to reply to, carrying its assigned identifier; never
     *     {@code null}
     * @return {@code true} when this pass stored a reply, {@code false} when the row was answered
     *     elsewhere and nothing was stored
     * @throws RuntimeException as raised by
     *     {@link ResponseService#generateResponseIfAbsent(Tweet)}; the caller records it against the
     *     candidate's identifier and continues with the next candidate
     */
    private boolean generateAndMirror(Tweet candidate) {
        String tweetId = String.valueOf(candidate.getId());
        // Direct in-process call replacing `generate_response.delay(tweet.id)` at
        // backend/app/tasks/response_generation.py:L47 — see docs/DECISION_LOG.md DL-047.
        // The guarded path task.TweetStreamListener also calls — DL-195 — see docs/DECISION_LOG.md.
        // ResponseService owns the LLM call, the stored row and the only
        // ResponseGenerationException — see docs/DECISION_LOG.md
        // The single background generation entry point — DL-195 — see docs/DECISION_LOG.md
        // The already-selected row is handed on rather than read again — DL-226 — see
        // docs/DECISION_LOG.md
        Optional<ResponseDto> result = responseService.generateResponseIfAbsent(candidate);

        if (result.isEmpty()) {
            log.debug("Tweet {} was answered elsewhere; this pass stores nothing and skips the "
                    + "Notion mirror", tweetId);

            return false;
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
        // The mirror is not re-attempted — see docs/DECISION_LOG.md DL-194
        try {
            notionService.updateTweetResponse(tweetId, content);
        } catch (RuntimeException failure) {
            // service/NotionService owns the failure record — see docs/DECISION_LOG.md DL-197
            log.debug("Mirroring response {} for tweet {} to the Notion database failed with {}",
                    generated.id(), tweetId, LogSafe.type(failure));
        }
        return true;
    }
}
