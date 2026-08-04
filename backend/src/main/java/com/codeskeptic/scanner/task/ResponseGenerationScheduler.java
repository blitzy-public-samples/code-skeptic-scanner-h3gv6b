package com.codeskeptic.scanner.task;

import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.NotionService;
import com.codeskeptic.scanner.service.ResponseService;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic pass that generates a stored reply for every {@code tweets} row that has none.
 *
 * <p>One pass selects the {@code tweets} rows with no associated {@code responses} row and, for each
 * of them, calls {@link ResponseService#generateResponse(String)} to generate and store a reply and
 * then mirrors that reply onto the matching Notion page.
 *
 * <p>Pacing is declared on {@link #generatePendingResponses()} as a fixed delay read from
 * {@code scanner.response-generation-delay-seconds} — see docs/DECISION_LOG.md DL-047. The interval
 * runs from the end of one pass to the start of the next. The scheduling capability is activated by
 * {@code config/AsyncSchedulingConfig}, the single carrier of {@code @EnableScheduling} in this
 * application; this class carries none, declares no thread and submits to no executor. No message
 * broker, queue or task-dispatch infrastructure participates — DL-047.
 *
 * <p>Each candidate is handled independently. A failure is recorded against the candidate's
 * identifier and the pass continues with the next candidate; a failure of the pass itself is
 * recorded and the pass returns normally, leaving the task scheduled. The pass performs no retry,
 * applies no rate limit, caches nothing, and neither caps nor paginates the candidate list.
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
 * <p>Only {@link Tweet#getId()} is read from a selected row. {@link Tweet#getResponses()} is lazy
 * and {@code spring.jpa.open-in-view} is {@code false}; the collection is never traversed here.
 *
 * @see ResponseService#generateResponse(String)
 * @see NotionService#updateTweetResponse(String, String)
 */
// Ported from backend/app/tasks/response_generation.py:L35-50, absorbing the body of the Celery task
// generate_response at :L10-33 (faithful port) — see docs/DECISION_LOG.md DL-047.
// Replaces the broker-less Celery application at :L8, the `while True` loop at :L41 and the
// `time.sleep(...)` call at :L50 — see docs/DECISION_LOG.md DL-047.
@Component
public class ResponseGenerationScheduler {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
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
     */
    // Constructor injection replaces the in-function LLMService() at
    // backend/app/tasks/response_generation.py:L19 and NotionService() at :L29 — see
    // docs/DECISION_LOG.md
    public ResponseGenerationScheduler(TweetRepository tweetRepository,
            ResponseService responseService,
            NotionService notionService) {
        this.tweetRepository = tweetRepository;
        this.responseService = responseService;
        this.notionService = notionService;
    }

    /**
     * Runs one generation pass over every {@code tweets} row that has no {@code responses} row.
     *
     * <p>The pass selects its candidates, then for each candidate generates and stores a reply and
     * mirrors it to Notion. A candidate whose handling raises is recorded and skipped, and the pass
     * continues. The pass closes with a summary of the attempted, succeeded and failed counts.
     *
     * <p>The first pass runs as soon as the scheduler starts; each later pass starts once the
     * configured delay has elapsed since the previous pass ended — see docs/DECISION_LOG.md DL-047.
     *
     * <p>The method takes no argument, returns nothing and throws nothing: every {@link
     * RuntimeException} raised inside it is recorded and suppressed.
     */
    // Ported from schedule_response_generation() at
    // backend/app/tasks/response_generation.py:L35-50 (faithful port) — see docs/DECISION_LOG.md
    // DL-047.
    // The `fixedDelayString` expression below resolves the property that carries the value the
    // source read as `settings.response_generation_interval` at :L50, declared as
    // RESPONSE_GENERATION_DELAY at backend/app/core/config.py:L11 — see docs/DECISION_LOG.md DL-047.
    @Scheduled(fixedDelayString = "${scanner.response-generation-delay-seconds}",
            timeUnit = TimeUnit.SECONDS)
    public void generatePendingResponses() {
        try {
            // Replaces `Tweet.query.filter(Tweet.response == None).all()` at
            // backend/app/tasks/response_generation.py:L43 — see docs/DECISION_LOG.md
            List<Tweet> candidates = tweetRepository.findByResponsesIsEmpty();

            if (candidates.isEmpty()) {
                log.debug("Response generation pass found no tweet awaiting a response");
                return;
            }

            log.info("Response generation pass started for {} tweet(s) awaiting a response",
                    candidates.size());

            int succeeded = 0;
            int failed = 0;

            for (Tweet candidate : candidates) {
                // Only the identifier is read from the selected row.
                String tweetId = String.valueOf(candidate.getId());
                try {
                    generateAndMirror(tweetId);
                    succeeded++;
                } catch (RuntimeException e) {
                    failed++;
                    // Sanitized record: operation, identifier and exception class only — DL-084 —
                    // see docs/DECISION_LOG.md
                    log.error("Scheduled response generation failed for tweet {}: {}",
                            tweetId, e.getClass().getSimpleName());
                }
            }

            log.info("Response generation pass finished: {} attempted, {} succeeded, {} failed",
                    candidates.size(), succeeded, failed);
        } catch (RuntimeException e) {
            // Sanitized record: operation and exception class only — DL-084 — see
            // docs/DECISION_LOG.md
            log.error("Response generation pass failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Generates and stores one reply for the supplied identifier, then mirrors it to Notion.
     *
     * <p>The mirror step is skipped, with a warning, when generation yields no reply or a reply that
     * carries no content. The stored reply is left in place either way.
     *
     * @param tweetId identifier of the {@code tweets} row to reply to; never {@code null} or empty
     * @throws RuntimeException as raised by {@link ResponseService#generateResponse(String)} or
     *     {@link NotionService#updateTweetResponse(String, String)}; the caller records it against
     *     {@code tweetId} and continues with the next candidate
     */
    private void generateAndMirror(String tweetId) {
        // Direct in-process call replacing `generate_response.delay(tweet.id)` at
        // backend/app/tasks/response_generation.py:L47 — see docs/DECISION_LOG.md DL-047.
        // ResponseService owns the LLM call, the stored row and the only
        // ResponseGenerationException — see docs/DECISION_LOG.md
        ResponseDto generated = responseService.generateResponse(tweetId);

        if (generated == null) {
            log.warn("Generation for tweet {} yielded no response; the Notion mirror is skipped",
                    tweetId);
            return;
        }

        String content = generated.content();
        if (content == null) {
            log.warn("Response {} for tweet {} carries no content; the Notion mirror is skipped",
                    generated.id(), tweetId);
            return;
        }

        // Identifier and length only; the generated text is not recorded — DL-052 — see
        // docs/DECISION_LOG.md
        log.info("Stored response {} for tweet {} ({} character(s))",
                generated.id(), tweetId, content.length());

        // Closes the call to the absent NotionService.update_tweet_response at
        // backend/app/tasks/response_generation.py:L30, which the documented step "Update Notion
        // database with response" names (documentation/Code Structure.md) — see
        // docs/DECISION_LOG.md
        notionService.updateTweetResponse(tweetId, content);
    }
}
