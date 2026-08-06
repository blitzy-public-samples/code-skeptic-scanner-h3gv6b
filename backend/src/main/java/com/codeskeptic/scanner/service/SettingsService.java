package com.codeskeptic.scanner.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.SettingDto;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.service.mapper.SettingMapper;
import com.codeskeptic.scanner.util.LogSafe;
import com.codeskeptic.scanner.util.StreamRuleTerms;

// Net-new (no Python module existed; signatures dictated by backend/app/api/settings.py:L10,L20) —
// see docs/DECISION_LOG.md DL-039, DL-040, DL-043
/**
 * Reads and updates the rows of the {@code settings} table, and seeds its default rows.
 *
 * <p>Three operations are exposed. {@link #getAllSettings()} renders every row.
 * {@link #updateSetting(String, String)} replaces the {@code value} of one row that already exists.
 * {@link #seedDefaultSettings()} inserts each of three default rows that is absent.
 *
 * <p>The first two correspond to the call sites the retired Flask blueprint already declared:
 * {@code SettingsService.get_all_settings()} at {@code backend/app/api/settings.py:L10} and
 * {@code SettingsService.update_setting(key, new_value)} at {@code :L20}. The source invoked both
 * statically on the class; both are instance methods on this bean and this class declares no static
 * method — see docs/DECISION_LOG.md DL-043.
 *
 * <p>{@code GET /settings} renders a JSON array of {@link SettingDto} objects, each carrying the
 * three columns the {@code settings} table declares at {@code backend/app/db/models.py:L42-44} — see
 * docs/DECISION_LOG.md DL-039.
 *
 * <p>Every entity is converted to its wire form inside the transaction that loaded it. No detached
 * entity and no uninitialised proxy leaves this class, and {@code spring.jpa.open-in-view} is
 * {@code false} — see docs/DECISION_LOG.md DL-026.
 *
 * <p>The two client-visible messages are the wire literals of
 * {@code backend/app/api/settings.py:L18} and {@code :L22}, carried by
 * {@link BadRequestException#noValueProvided()} and {@link NotFoundException#settingNotFound()}. No
 * key name, driver text or stack detail is appended to either. This class selects no HTTP status;
 * {@code api.GlobalExceptionHandler} does.
 *
 * <p>The {@code settings} table is reached through {@link SettingRepository} for every read and
 * update, and through {@link EntityManager#persist(Object)} for the seeding insert alone — DL-159.
 * This class declares no JPQL and no native query, so neither of the reserved column names
 * {@code key} and {@code value} is spelled in a statement. It opens no connection to an external
 * system, memoises nothing and declares no operation that publishes to X.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-039, DL-040,
 * DL-043, DL-052, DL-073 and DL-159; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This class is thread-safe. It is a singleton bean, its collaborators are held in final fields
 * and are themselves singletons or thread-safe, and this class holds no other state. Two callers
 * updating the same key concurrently both write and the later write stands; two callers seeding the
 * same key concurrently insert once and neither overwrites the stored row.
 */
@Service
public class SettingsService {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    /**
     * Seeded key whose value bounds the like count a monitored post must reach. The value is seeded
     * from {@code scanner.popularity-threshold}, declared with the default {@code 100} at
     * {@code backend/app/core/config.py:L10} — DL-040 — see docs/DECISION_LOG.md.
     */
    private static final String TWEET_POPULARITY_THRESHOLD_KEY = "tweet_popularity_threshold";

    /** Description stored on the {@value #TWEET_POPULARITY_THRESHOLD_KEY} row. */
    private static final String TWEET_POPULARITY_THRESHOLD_DESCRIPTION =
            "Minimum like count for a monitored post to be processed.";

    /**
     * Seeded key that reports the interval between response-generation sweeps. The value is seeded
     * from {@code scanner.response-generation-delay-seconds}, declared with the default {@code 60} as
     * {@code RESPONSE_GENERATION_DELAY} at {@code backend/app/core/config.py:L11} — DL-040 — see
     * docs/DECISION_LOG.md. The interval in force is resolved before every pass by
     * {@code config.AsyncSchedulingConfig}, which reads this row first and falls back to the configured
     * property; editing this row through {@code PUT /settings/{key}} paces every pass after the one
     * already scheduled — DL-227, DL-228.
     */
    private static final String RESPONSE_GENERATION_DELAY_KEY = "response_generation_delay";

    /** Description stored on the {@value #RESPONSE_GENERATION_DELAY_KEY} row. */
    private static final String RESPONSE_GENERATION_DELAY_DESCRIPTION =
            "Seconds between response-generation sweeps.";

    /**
     * Seeded key whose value overrides the terms the filtered stream tracks. It is seeded blank; the
     * source list was empty at {@code backend/app/tasks/tweet_monitoring.py:L53-55} — DL-040, DL-044
     * — see docs/DECISION_LOG.md.
     *
     * <p>A blank value carries no override, so {@code task.TweetStreamClient} composes the rule set
     * from {@code scanner.ingestion.stream-base-keywords} unioned with every {@code ai_tools.name}
     * row. A non-blank value written through {@code PUT /settings/stream_keywords} replaces that
     * composition in full — DL-044.
     */
    private static final String STREAM_KEYWORDS_KEY = "stream_keywords";

    /** Description stored on the {@value #STREAM_KEYWORDS_KEY} row. */
    private static final String STREAM_KEYWORDS_DESCRIPTION =
            "Comma-separated terms overriding the filtered-stream rule set; blank tracks the "
                    + "configured base terms together with every ai_tools row.";

    /**
     * Value seeded onto the {@value #STREAM_KEYWORDS_KEY} row — DL-040, DL-044 — see
     * docs/DECISION_LOG.md.
     */
    private static final String STREAM_KEYWORDS_SEED_VALUE = "";



    /** Data access for the {@code settings} table. */
    private final SettingRepository settingRepository;

    /** Converts a {@link Setting} into its {@link SettingDto} wire form. */
    private final SettingMapper settingMapper;

    /** Source of the three seeded values. */
    private final ScannerProperties properties;

    /** Issues the insert-only write of one seeded row — DL-159 — see docs/DECISION_LOG.md. */
    private final EntityManager entityManager;

    /**
     * Demarcates the transaction one seeded row is inserted in. Its propagation behaviour is
     * {@code REQUIRES_NEW} — DL-159 — see docs/DECISION_LOG.md.
     */
    private final TransactionTemplate insertTransaction;

    /**
     * Creates the bean with its collaborators, replacing the static invocation at
     * {@code backend/app/api/settings.py:L10,L20} — DL-043.
     *
     * @param settingRepository data access for the {@code settings} table, must not be {@code null}
     * @param settingMapper     entity-to-wire converter, must not be {@code null}
     * @param properties        bound configuration supplying the seeded values, must not be
     *                          {@code null}
     * @param entityManager     persistence context the seeding insert is issued through, must not be
     *                          {@code null}
     * @param transactionManager transaction manager the per-key insert transaction is opened on, must
     *                          not be {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    public SettingsService(SettingRepository settingRepository,
            SettingMapper settingMapper,
            ScannerProperties properties,
            EntityManager entityManager,
            PlatformTransactionManager transactionManager) {
        this.settingRepository = Objects.requireNonNull(settingRepository,
                "settingRepository must not be null.");
        this.settingMapper = Objects.requireNonNull(settingMapper, "settingMapper must not be null.");
        this.properties = Objects.requireNonNull(properties, "properties must not be null.");
        this.entityManager = Objects.requireNonNull(entityManager,
                "entityManager must not be null.");
        this.insertTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager,
                "transactionManager must not be null."));
        this.insertTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.insertTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    // Call site backend/app/api/settings.py:L10 — see docs/DECISION_LOG.md DL-039
    /**
     * Returns every row of the {@code settings} table in the order the repository reports.
     *
     * <p>Each element carries the {@code key}, {@code value} and {@code description} of one row
     * unchanged, and a {@code null} column is carried through as a {@code null} component. The rows
     * are converted inside this method's transaction.
     *
     * <p>An empty table yields an empty list, and the list returned is unmodifiable.
     *
     * @return one {@link SettingDto} per row of the {@code settings} table, empty when the table
     *         holds no row, never {@code null}
     */
    @Transactional(readOnly = true)
    public List<SettingDto> getAllSettings() {
        List<SettingDto> settings = settingMapper.toDtoList(settingRepository.findAll());
        log.debug("Rendering {} setting row(s).", settings.size());
        return settings;
    }

    // Call sites backend/app/api/settings.py:L13-24 — see docs/DECISION_LOG.md
    /**
     * Replaces the {@code value} of the row identified by {@code key} and returns the stored row.
     *
     * <p>The value is validated before the row is read, matching the order of
     * {@code backend/app/api/settings.py:L17-20}. The guard tests for {@code null} alone, as
     * {@code if new_value is None} does at {@code :L17}, so an empty string is stored and returned. No
     * value is trimmed or normalised on the way in — DL-050.
     *
     * <p>{@code key} is the primary key of the {@code settings} table and is never written, and
     * {@code description} is not written either. A key that names no row is reported as absent and no
     * row is created for it.
     *
     * @param key   the primary key of the row to update; a key naming no row, and a {@code null}
     *              key, are both reported as absent
     * @param value the replacement value, stored verbatim; an empty string is accepted
     * @return the stored row, carrying {@code key} and {@code description} unchanged and {@code value}
     *         as supplied
     * @throws BadRequestException when {@code value} is {@code null}, carrying the wire literal of
     *                             {@code backend/app/api/settings.py:L18}
     * <p>An edit of the {@value #STREAM_KEYWORDS_KEY} row that leaves no term the X rule grammar can
     * carry is stored and reported at {@code WARN} naming the key and the term counts; the wire
     * outcome is unchanged and ingestion falls back to the configured base terms — DL-257.
     *
     * @throws NotFoundException   when {@code key} names no row, carrying the wire literal of
     *                             {@code backend/app/api/settings.py:L22}
     */
    @Transactional
    public SettingDto updateSetting(String key, String value) {
        // backend/app/api/settings.py:L17-18 — the guard tests null alone
        if (value == null) {
            log.warn("Rejected the update of setting '{}': the request carried no value.",
                    LogSafe.logSafe(key));
            throw BadRequestException.noValueProvided();
        }

        // backend/app/api/settings.py:L21-22
        // Deviation from the literal call site: a null key is reported as absent, matching the
        // 404 branch of backend/app/api/settings.py:L21-22 — see docs/DECISION_LOG.md DL-048
        Optional<Setting> existing = (key == null)
                ? Optional.empty()
                : settingRepository.findById(key);
        if (existing.isEmpty()) {
            log.warn("Rejected the update of setting '{}': the key names no row.",
                    LogSafe.logSafe(key));
            throw NotFoundException.settingNotFound();
        }

        Setting setting = existing.get();
        setting.setValue(value);
        SettingDto updated = settingMapper.toDto(settingRepository.save(setting));
        log.info("Updated setting '{}'.", LogSafe.logSafe(key));
        reportUnusableStreamKeywords(key, value);
        return updated;
    }

    // The X rule grammar the stored terms must satisfy — DL-257 — see docs/DECISION_LOG.md
    /**
     * Records a {@value #STREAM_KEYWORDS_KEY} edit that leaves no term the X rule grammar can carry.
     *
     * <p>Usability is decided by {@link StreamRuleTerms#isUsable(String)}, the one grammar
     * {@code task/TweetStreamClient} also holds its terms to, so a term counted usable here is never
     * dropped when the rule set is composed — DL-257.
     *
     * <p>Nothing is recorded for any other key, and nothing is recorded when the stored value holds at
     * least one usable term or is blank — a blank value is the seeded "no override" state
     * {@code task/TweetStreamClient} reads, not an unusable one — DL-044.
     *
     * <p>The record names the key and the two counts only. No stored term reaches it — DL-052,
     * DL-197. The stored value is left exactly as the operator supplied it: ingestion falls back to
     * the configured base terms, so an unusable edit withholds nothing that was already working.
     *
     * @param key   the key that was written, possibly {@code null}
     * @param value the value that was stored, never {@code null}
     */
    private void reportUnusableStreamKeywords(String key, String value) {
        if (!STREAM_KEYWORDS_KEY.equals(key) || value.isBlank()) {
            return;
        }
        int supplied = StreamRuleTerms.split(value).size();
        int usable = StreamRuleTerms.countUsable(value);
        if (usable > 0) {
            if (usable < supplied) {
                log.warn("Setting '{}' holds {} term(s) of which {} cannot be carried as an X stream "
                        + "rule; those are dropped when the rule set is next composed",
                        STREAM_KEYWORDS_KEY, supplied, supplied - usable);
            }
            return;
        }
        log.warn("Setting '{}' holds {} term(s) and none can be carried as an X stream rule; "
                + "ingestion falls back to scanner.ingestion.stream-base-keywords. A term may hold "
                + "nothing but letters, digits and '{}', must open and close with a letter or a digit, "
                + "and may not exceed {} character(s)",
                STREAM_KEYWORDS_KEY, supplied, StreamRuleTerms.ADDITIONAL_TERM_CHARACTERS,
                StreamRuleTerms.MAX_TERM_CHARS);
    }


    // Net-new (no Python counterpart) — DL-040 — see docs/DECISION_LOG.md
    /**
     * Inserts each of the three default rows of the {@code settings} table that is absent.
     *
     * <p>The three keys are {@value #TWEET_POPULARITY_THRESHOLD_KEY},
     * {@value #RESPONSE_GENERATION_DELAY_KEY} and {@value #STREAM_KEYWORDS_KEY}. Each carries a
     * {@code description}. The first two carry a {@code value} rendered from configuration —
     * {@code scanner.popularity-threshold} and {@code scanner.response-generation-delay-seconds} —
     * and each is read back in place of that configuration default on every use, by
     * {@code service.TwitterService} and {@code config.AsyncSchedulingConfig} respectively — DL-040,
     * DL-227. The third carries the blank value {@value #STREAM_KEYWORDS_SEED_VALUE}, which
     * {@code task.TweetStreamClient} reads as "no override" — DL-044.
     *
     * <p>A key this operation observes as present is left exactly as it stands: its {@code value} and
     * its {@code description} are both untouched, whatever they hold and however they came to hold
     * it. A repeated call against unchanged data writes nothing.
     *
     * <p>Rows are the only thing added. No column, table or index is contributed here; the schema is
     * the four tables the source declared.
     *
     * <p>This method runs on {@link ApplicationReadyEvent}, after the context is refreshed, and is
     * also directly invocable. It declares no transaction — DL-159 — see docs/DECISION_LOG.md. Each
     * key is written in a transaction of its own; a rejected write is rolled back on its own and the
     * remaining keys are still attempted. A key taken concurrently, by a second instance or by a second
     * caller, is absorbed per key by {@link #seedIfAbsent(String, String, String)}.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException when a write is rejected and the
     *                                                                key it carried is still absent
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedDefaultSettings() {
        seedIfAbsent(TWEET_POPULARITY_THRESHOLD_KEY,
                Integer.toString(properties.popularityThreshold()),
                TWEET_POPULARITY_THRESHOLD_DESCRIPTION);
        seedIfAbsent(RESPONSE_GENERATION_DELAY_KEY,
                Long.toString(properties.responseGenerationDelaySeconds()),
                RESPONSE_GENERATION_DELAY_DESCRIPTION);
        // A blank seed leaves the override inert, so the composed rule set applies — DL-044 — see
        // docs/DECISION_LOG.md
        seedIfAbsent(STREAM_KEYWORDS_KEY,
                STREAM_KEYWORDS_SEED_VALUE,
                STREAM_KEYWORDS_DESCRIPTION);
    }

    /**
     * Writes one default row when its key is absent, and writes nothing when the key is present.
     *
     * <p>The write is an insert and only an insert: the row is handed to
     * {@link jakarta.persistence.EntityManager#persist(Object)} and flushed, so the statement issued
     * is always {@code insert into settings}. No merge is performed and no {@code update} statement is
     * reachable from this method — see docs/DECISION_LOG.md DL-159.
     *
     * <p>The insert runs in a transaction of its own, opened by {@link #insertTransaction} with
     * {@code PROPAGATION_REQUIRES_NEW}. A rejected insert rolls back that transaction alone, leaving
     * any transaction the caller holds usable and the remaining keys still writable.
     *
     * <p>{@code existsById} is consulted first and loads no row; the primary key is what refuses a
     * duplicate — see docs/DECISION_LOG.md DL-159. Two concurrent outcomes are possible for one key
     * and neither writes over a stored row:
     *
     * <ul>
     *   <li>the key is present when {@code existsById} runs — nothing is written;
     *   <li>the key is taken after {@code existsById} and before the insert reaches the database —
     *       the insert is rejected by the primary key, the rejection is caught, presence is
     *       re-established and the row the other writer stored is left exactly as it stands, both its
     *       {@code value} and its {@code description}.
     * </ul>
     *
     * <p>A rejection raised while the key is still absent afterwards is rethrown, wrapped in a
     * {@link DataIntegrityViolationException} when the persistence provider reported it as a
     * {@link PersistenceException}.
     *
     * <p>An insert is logged once at {@code INFO} and names the key; a key already present, and a key
     * taken concurrently, are logged at {@code DEBUG}. No stored value is written to the log.
     *
     * <p>The presence check and the insert are two statements, so another instance may insert the
     * same key in between. That outcome surfaces as a {@link DataIntegrityViolationException} on the
     * insert, which is caught here: the row is re-read, the value the other instance stored is left
     * in place, and nothing is rethrown. One row losing that race neither writes over the winning
     * row nor stops the remaining rows from being seeded.
     *
     * @param key         the primary key of the default row
     * @param value       the value to store when the row is written
     * @param description the description to store when the row is written
     * @throws DataIntegrityViolationException when the insert is rejected and the key is still absent
     */
    // Insert-only seeding of one key — DL-159 — see docs/DECISION_LOG.md
    private void seedIfAbsent(String key, String value, String description) {
        if (settingRepository.existsById(key)) {
            log.debug("Default setting '{}' is already present and is left unchanged.", key);
            return;
        }
        try {
            insertTransaction.executeWithoutResult(status -> {
                entityManager.persist(new Setting(key, value, description));
                entityManager.flush();
            });
            log.info("Seeded default setting '{}'.", key);
        } catch (DataIntegrityViolationException | PersistenceException keyTaken) {
            if (!settingRepository.existsById(key)) {
                throw (keyTaken instanceof DataIntegrityViolationException rejected)
                        ? rejected
                        : new DataIntegrityViolationException(
                                "The insert of setting '" + key + "' was rejected.", keyTaken);
            }
            log.debug("Default setting '{}' was stored concurrently and is left unchanged.", key);
        }
    }

}
