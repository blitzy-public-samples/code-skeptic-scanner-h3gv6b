package com.codeskeptic.scanner.task;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.repository.TweetRepository;
import com.codeskeptic.scanner.service.NotionService;
import com.codeskeptic.scanner.service.ResponseService;
import com.codeskeptic.scanner.util.LogSafe;

import java.time.Instant;
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
 * <p>Pacing has two parts. The tick that calls {@link #generatePendingResponses()} is registered by
 * {@code config/AsyncSchedulingConfig} as a fixed-delay trigger task whose interval falls back to
 * {@code scanner.response-generation-delay-seconds} — see docs/DECISION_LOG.md DL-047 — and runs from
 * the end of one pass to the start of the next. On each tick the
 * effective pacing is resolved by {@link #resolveDelaySeconds()}, which prefers the
 * {@code response_generation_delay} {@code settings} row over that configured value and defers the pass
 * until the row's interval has elapsed — see docs/DECISION_LOG.md DL-197. The scheduling capability is activated by
 * {@code config/AsyncSchedulingConfig}, the single carrier of {@code @EnableScheduling} in this
 * application; this class carries none, declares no thread and submits to no executor. No message
 * broker, queue or task-dispatch infrastructure participates — DL-047.
 *
 * <p>Each candidate is handled independently. A failure is recorded against the candidate's
 * identifier and the pass continues with the next candidate; a failure of the pass itself is
 * recorded and the pass returns normally, leaving the task scheduled. The pass performs no retry,
 * applies no rate limit, caches nothing, and neither caps nor paginates the candidate list.
 *
 * <p>This pass does not own ingestion's replies. It reaches
 * {@link ResponseService#generateResponseIfAbsent(String)}, the one operation both background paths
 * call, so a candidate that {@code task.TweetStreamListener} is answering — or has answered since the
 * candidate query ran — stores nothing and is counted as skipped — see docs/DECISION_LOG.md DL-196.
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
 * @see ResponseService#generateResponseIfAbsent(String)
 * @see NotionService#updateTweetResponse(String, String)
 */
// Fixed-delay intent ported from schedule_response_generation at
// backend/app/tasks/response_generation.py:L35-50, absorbing the task body at :L10-33 (faithful port
// of intent) — see docs/DECISION_LOG.md DL-047
@Component
public class ResponseGenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ResponseGenerationScheduler.class);

    /**
     * Primary key of the {@code settings} row that paces this task. {@code SettingsService} seeds it
     * from {@code scanner.response-generation-delay-seconds} and {@code PUT /settings/{key}} edits it
     * — DL-040, DL-197 — see docs/DECISION_LOG.md.
     */
    private static final String RESPONSE_GENERATION_DELAY_SETTING_KEY = "response_generation_delay";

    private final TweetRepository tweetRepository;

    private final ResponseService responseService;

    private final NotionService notionService;

    /** Data access for {@code settings}; reads the pacing row — DL-197. */
    private final SettingRepository settingRepository;

    /** Bound configuration root; supplies the configured pacing fallback — DL-197. */
    private final ScannerProperties properties;

    /**
     * Instant at which the previous pass finished, or {@code null} before the first pass. It is the
     * reference the effective pacing of {@link #resolveDelaySeconds()} is measured from — DL-197.
     */
    private volatile Instant lastPassCompletedAt;

    /**
     * Binds the five collaborators one pass uses.
     *
     * @param tweetRepository selects the {@code tweets} rows that carry no {@code responses} row;
     *     never {@code null}
     * @param responseService generates and stores one reply per candidate; never {@code null}
     * @param notionService mirrors a stored reply onto its Notion page; never {@code null}
     * @param settingRepository reads the {@value #RESPONSE_GENERATION_DELAY_SETTING_KEY} row that
     *     paces this task; never {@code null}
     * @param properties supplies the configured pacing applied when that row supplies none; never
     *     {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    // Constructor injection replaces the in-function LLMService() at
    // backend/app/tasks/response_generation.py:L19 and NotionService() at :L29, and the
    // get_settings() call at :L37 — see docs/DECISION_LOG.md
    public ResponseGenerationScheduler(TweetRepository tweetRepository,
            ResponseService responseService,
            NotionService notionService,
            SettingRepository settingRepository,
            ScannerProperties properties) {
        this.tweetRepository = Objects.requireNonNull(tweetRepository,
                "tweetRepository must not be null.");
        this.responseService = Objects.requireNonNull(responseService,
                "responseService must not be null.");
        this.notionService = Objects.requireNonNull(notionService,
                "notionService must not be null.");
        this.settingRepository = Objects.requireNonNull(settingRepository,
                "settingRepository must not be null.");
        this.properties = Objects.requireNonNull(properties, "properties must not be null.");
    }

    /**
     * Runs one generation pass over every {@code tweets} row that has no {@code responses} row.
     *
     * <p>The pass selects its candidates, then for each candidate generates and stores a reply and
     * mirrors it to Notion. A candidate another path already answered stores nothing and is counted as
     * skipped. A candidate whose handling raises is recorded and skipped, and the pass continues. The
     * pass closes with a summary of the attempted, succeeded, skipped and failed counts.
     *
     * <p>The first pass runs as soon as the scheduler starts. Each later tick runs a pass only once the
     * delay {@link #resolveDelaySeconds()} reports has elapsed since the previous pass ended; a tick
     * that arrives sooner returns without work and is recorded at {@code DEBUG} — see
     * docs/DECISION_LOG.md DL-047, DL-197.
     *
     * <p>The method takes no argument, returns nothing and throws nothing: every {@link
     * RuntimeException} raised inside it is recorded and suppressed.
     */
    // Ported from schedule_response_generation() at
    // backend/app/tasks/response_generation.py:L35-50 (faithful port) — see docs/DECISION_LOG.md
    // DL-047.
    // The pacing this method is registered with replaces the `time.sleep(...)` call at :L50, which
    // read `settings.response_generation_interval` while backend/app/core/config.py:L11 declared
    // RESPONSE_GENERATION_DELAY — see docs/DECISION_LOG.md DL-040 and DL-047.
    public void generatePendingResponses() {
        long delaySeconds = resolveDelaySeconds();
        Instant previousCompletion = this.lastPassCompletedAt;
        if (previousCompletion != null
                && Instant.now().isBefore(previousCompletion.plusSeconds(delaySeconds))) {
            // Setting row overrides configuration — DL-197 — see docs/DECISION_LOG.md
            log.debug("Response generation pass deferred: setting '{}' asks for {}s and that much has "
                    + "not elapsed since the previous pass", RESPONSE_GENERATION_DELAY_SETTING_KEY,
                    delaySeconds);
            return;
        }

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
                    if (generateAndMirror(tweetId)) {
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
        } finally {
            this.lastPassCompletedAt = Instant.now();

        }
    }

    /**
     * Generates and stores one reply for the supplied identifier, then mirrors it to Notion.
     *
     * <p>Nothing is stored and nothing is mirrored when
     * {@link ResponseService#generateResponseIfAbsent(String)} reports an empty result, which means the
     * row was answered elsewhere — see docs/DECISION_LOG.md DL-196. The mirror step alone is skipped,
     * with a warning, when a stored reply carries no content, and a mirror rejection is recorded
     * without failing the candidate; the stored reply is left in place in every case and the mirror is
     * not re-attempted — see docs/DECISION_LOG.md DL-194.
     *
     * @param tweetId identifier of the {@code tweets} row to reply to; never {@code null} or empty
     * @return {@code true} when this pass stored a reply, {@code false} when the row was answered
     *     elsewhere and nothing was stored
     * @throws RuntimeException as raised by
     *     {@link ResponseService#generateResponseIfAbsent(String)}; the caller records it against
     *     {@code tweetId} and continues with the next candidate

     */
    private boolean generateAndMirror(String tweetId) {
        // Direct in-process call replacing `generate_response.delay(tweet.id)` at
        // backend/app/tasks/response_generation.py:L47 — see docs/DECISION_LOG.md DL-047.
        // The guarded path task.TweetStreamListener also calls — DL-190 — see docs/DECISION_LOG.md.
        // ResponseService owns the LLM call, the stored row and the only
        // ResponseGenerationException — see docs/DECISION_LOG.md
        // The single background generation entry point — DL-196 — see docs/DECISION_LOG.md
        Optional<ResponseDto> result = responseService.generateResponseIfAbsent(tweetId);

        if (result.isEmpty()) {
            log.debug("Tweet {} was answered elsewhere; this pass stores nothing and skips the "
                    + "Notion mirror", tweetId);

            return false;
        }

        ResponseDto generated = result.get();
        String content = generated.content();
        if (content == null) {
            log.warn("Response {} for tweet {} carries no content; the Notion mirror is skipped",
                    generated.id(), tweetId);
            return true;

        }

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

    // Setting row overrides configuration, the precedence DL-040 establishes — DL-197 — see
    // docs/DECISION_LOG.md
    /**
     * Resolves the pacing currently in force, in seconds.
     *
     * <p>The {@code settings} row named {@value #RESPONSE_GENERATION_DELAY_SETTING_KEY} is read first
     * and its value, once surrounding whitespace is discarded, is used when it parses as a positive
     * {@code long}. The configured {@code scanner.response-generation-delay-seconds} is used when that
     * row is absent, holds {@code null}, holds a value that does not parse, or holds a value that is
     * not positive; each of the last two is recorded once at {@code WARN} naming the key without
     * recording the stored value.
     *
     * <p>{@link #generatePendingResponses()} reads this value at the start of every scheduled tick and
     * returns without work until that many seconds have elapsed since the previous pass finished, so an
     * operator's {@code PUT /settings/response_generation_delay} takes effect from the next tick with
     * no redeploy. The tick itself is paced by {@code scanner.response-generation-delay-seconds},
     * which therefore bounds how often a pass can run: a row value at or above the configured cadence
     * is honoured exactly, and a row value below it is honoured only up to that cadence — see
     * docs/DECISION_LOG.md DL-197.
     *
     * @return the effective delay in seconds between the end of one pass and the start of the next;
     *         always positive
     */
    public long resolveDelaySeconds() {
        String stored = settingRepository.findById(RESPONSE_GENERATION_DELAY_SETTING_KEY)
                .map(Setting::getValue)
                .orElse(null);

        if (stored != null) {
            try {
                long parsed = Long.parseLong(stored.strip());
                if (parsed > 0L) {
                    return parsed;
                }
                log.warn("Setting '{}' does not hold a positive number of seconds; applying "
                                + "scanner.response-generation-delay-seconds instead",
                        RESPONSE_GENERATION_DELAY_SETTING_KEY);
            } catch (NumberFormatException notANumber) {
                log.warn("Setting '{}' does not hold a number; applying "
                                + "scanner.response-generation-delay-seconds instead",
                        RESPONSE_GENERATION_DELAY_SETTING_KEY);
            }
        }

        // backend/app/core/config.py:L11 — the configured delay
        return properties.responseGenerationDelaySeconds();
    }
}
