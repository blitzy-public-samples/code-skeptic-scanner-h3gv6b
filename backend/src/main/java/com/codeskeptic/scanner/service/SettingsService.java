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
import org.springframework.data.domain.Sort;
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

// Net-new (no Python module existed; signatures dictated by backend/app/api/settings.py:L10,L20) —
// see docs/DECISION_LOG.md DL-039, DL-040, DL-043
/**
 * Reads and updates the rows of the {@code settings} table, and seeds its default rows.
 *
 * <p>{@link #getAllSettings()} renders every row as a {@link SettingDto} carrying the three columns
 * the table declares at {@code backend/app/db/models.py:L42-44}, ordered by {@code key} ascending —
 * DL-039.
 * {@link #updateSetting(String, String)} replaces the {@code value} of any row that already exists.
 * No key is reserved: every row is rendered and every stored row is writable — DL-284. Both answer
 * the call sites {@code backend/app/api/settings.py:L10} and {@code :L20} declared statically on the
 * class; both are instance methods here and this class declares no static method — DL-043.
 *
 * <p>Every entity is converted to its wire form inside the transaction that loaded it. No detached
 * entity and no uninitialised proxy leaves this class, and {@code spring.jpa.open-in-view} is
 * {@code false} — DL-026.
 *
 * <p>The two client-visible messages are the wire literals of
 * {@code backend/app/api/settings.py:L18} and {@code :L22}, carried by
 * {@link BadRequestException#noValueProvided()} and {@link NotFoundException#settingNotFound()}. No
 * key name, driver text or stack detail is appended to either. This class selects no HTTP status;
 * {@code api.GlobalExceptionHandler} does.
 *
 * <p>Reads and updates go through {@link SettingRepository}; the seeding insert alone goes through
 * {@link EntityManager#persist(Object)} — DL-159. This class declares no JPQL and no native query, so
 * neither of the reserved column names {@code key} and {@code value} is spelled in a statement. It
 * opens no connection to an external system, memoises nothing and declares no operation that
 * publishes to X.
 *
 * <p>This class is thread-safe: a singleton bean holding its collaborators in final fields and no
 * other state. Two callers updating the same key concurrently both write and the later write stands;
 * two callers seeding the same key concurrently insert once and neither overwrites the stored row.
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

    private static final String TWEET_POPULARITY_THRESHOLD_DESCRIPTION =
            "Minimum like count for a monitored post to be processed.";

    /**
     * Seeded key that reports the interval between response-generation sweeps. The value is seeded
     * from {@code scanner.response-generation-delay-seconds}, declared with the default {@code 60} as
     * {@code RESPONSE_GENERATION_DELAY} at {@code backend/app/core/config.py:L11} — DL-040 — see
     * docs/DECISION_LOG.md.
     *
     * <p>The row reports the interval; it does not set it. Pacing is declared on
     * {@code task.ResponseGenerationScheduler.generatePendingResponses()} as
     * {@code @Scheduled(fixedDelayString = "${scanner.response-generation-delay-seconds}")}, which the
     * framework resolves once when it registers the task, so an edit written through
     * {@code PUT /settings/{key}} changes what {@code GET /settings} reports and takes effect on the
     * interval at the next restart — DL-227.
     */
    private static final String RESPONSE_GENERATION_DELAY_KEY = "response_generation_delay";

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

    private static final String STREAM_KEYWORDS_DESCRIPTION =
            "Comma-separated terms overriding the filtered-stream rule set; blank tracks the "
                    + "configured base terms together with every ai_tools row.";

    /**
     * Value seeded onto the {@value #STREAM_KEYWORDS_KEY} row — DL-040, DL-044 — see
     * docs/DECISION_LOG.md.
     */
    private static final String STREAM_KEYWORDS_SEED_VALUE = "";

    /**
     * Order every row of the {@code settings} table is read in: {@code settings.key} ascending —
     * DL-039 — see docs/DECISION_LOG.md.
     *
     * <p>The sort names the {@code key} property of {@link Setting}, so the persistence provider
     * renders the quoted column name of DL-061 and this class still declares no statement of its own.
     */
    private static final Sort TABLE_ORDER = Sort.by(Sort.Direction.ASC, "key");

    private final SettingRepository settingRepository;

    private final SettingMapper settingMapper;

    private final ScannerProperties properties;

    /** Issues the insert-only write of one seeded row — DL-159 — see docs/DECISION_LOG.md. */
    private final EntityManager entityManager;

    /**
     * Demarcates the transaction one seeded row is inserted in. Its propagation behaviour is
     * {@code REQUIRES_NEW} — DL-159 — see docs/DECISION_LOG.md.
     */
    private final TransactionTemplate insertTransaction;

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
    // The order is requested here rather than left to the database — DL-039 — see
    // docs/DECISION_LOG.md
    /**
     * Returns every row of the {@code settings} table, ordered by {@code key} ascending.
     *
     * <p>The order is requested of the database through {@link #BY_KEY} rather than left to
     * whatever sequence an unordered scan happens to return, so the same table renders the same
     * array on every vendor and on every call. Because {@code key} is the primary key of the table
     * the order is total, and no two elements can compare equal.
     *
     * <p>Each element carries the {@code key}, {@code value} and {@code description} of one row
     * unchanged, and a {@code null} column is carried through as a {@code null} component. The rows
     * are converted inside this method's transaction.
     *
     * <p>The order is the one {@link #TABLE_ORDER} declares and is the same on every read and on
     * every vendor: it does not depend on the order rows were inserted in, and an
     * {@link #updateSetting(String, String)} call does not move the row it writes — DL-039.
     *
     * <p>An empty table yields an empty list, and the list returned is unmodifiable.
     *
     * @return one {@link SettingDto} per row of the {@code settings} table, ordered by {@code key}
     *         ascending, empty when the table holds no row, never {@code null}
     */
    @Transactional(readOnly = true)
    public List<SettingDto> getAllSettings() {
        List<SettingDto> settings = settingMapper.toDtoList(settingRepository.findAll(TABLE_ORDER));
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
     * <p>{@code key} and {@code description} are never written. A key that names no row is reported as
     * absent and no row is created for it.
     *
     * <p>An edit of the {@value #STREAM_KEYWORDS_KEY} row that leaves no term the X rule grammar can
     * carry is stored and reported at {@code WARN} naming the key and the term counts; the wire
     * outcome is unchanged and ingestion falls back to the configured base terms — DL-257.
     *
     * @param key   the primary key of the row to update; a key naming no row, and a {@code null}
     *              key, are both reported as absent
     * @param value the replacement value, stored verbatim; an empty string is accepted
     * @return the stored row, carrying {@code key} and {@code description} unchanged and {@code value}
     *         as supplied
     * @throws BadRequestException when {@code value} is {@code null}, carrying the wire literal of
     *                             {@code backend/app/api/settings.py:L18}
     * @throws NotFoundException   when {@code key} names no row, carrying the wire literal of
     *                             {@code backend/app/api/settings.py:L22}
     */
    @Transactional
    public SettingDto updateSetting(String key, String value) {
        // backend/app/api/settings.py:L17-18 — the guard tests null alone
        if (value == null) {
            log.warn("Rejected a setting update: the request carried no value.");
            throw BadRequestException.noValueProvided();
        }

        // backend/app/api/settings.py:L21-22
        // Deviation from the literal call site: a null key is reported as absent, matching the
        // 404 branch of backend/app/api/settings.py:L21-22 — see docs/DECISION_LOG.md DL-048
        Optional<Setting> existing =
                (key == null) ? Optional.empty() : settingRepository.findById(key);
        if (existing.isEmpty()) {
            log.warn("Rejected a setting update: the key names no row.");
            throw NotFoundException.settingNotFound();
        }

        Setting setting = existing.get();
        setting.setValue(value);
        SettingDto updated = settingMapper.toDto(settingRepository.save(setting));
        log.info("Updated one setting row.");
        return updated;
    }

    // Net-new (no Python counterpart) — DL-040 — see docs/DECISION_LOG.md
    /**
     * Inserts each of the three default rows of the {@code settings} table that is absent.
     *
     * <p>The keys are {@value #TWEET_POPULARITY_THRESHOLD_KEY},
     * {@value #RESPONSE_GENERATION_DELAY_KEY} and {@value #STREAM_KEYWORDS_KEY}. The first two carry a
     * {@code value} rendered from {@code scanner.popularity-threshold} and
     * {@code scanner.response-generation-delay-seconds}. The first is read back in place of that
     * configuration default on every use, by {@code service.TwitterService} — DL-040. The second
     * reports the interval and does not set it: {@code task.ResponseGenerationScheduler} declares its
     * pacing on the method and the framework resolves that value once when it registers the task —
     * DL-227. The third carries the blank value {@value #STREAM_KEYWORDS_SEED_VALUE}, which
     * {@code task.TweetStreamClient} reads as "no override" — DL-044.
     *
     * <p>A key observed as present is left exactly as it stands, {@code value} and
     * {@code description} both. Rows are the only thing added: no column, table or index is
     * contributed here.
     *
     * <p>This method runs on {@link ApplicationReadyEvent} and is also directly invocable. It declares
     * no transaction — DL-159. Each key is written in a transaction of its own, so a rejected write is
     * rolled back alone and the remaining keys are still attempted.
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
     * reachable from this method — DL-159.
     *
     * <p>The insert runs in a transaction of its own, opened by {@link #insertTransaction} with
     * {@code PROPAGATION_REQUIRES_NEW}. A rejected insert rolls back that transaction alone, leaving
     * any transaction the caller holds usable and the remaining keys still writable.
     *
     * <p>{@code existsById} is consulted first and loads no row; the primary key is what refuses a
     * duplicate — DL-159. The check and the insert are two statements, so a key taken in between is
     * refused by the primary key: that rejection is caught, presence is re-established and the row the
     * other writer stored is left exactly as it stands, both its {@code value} and its
     * {@code description}. A rejection raised while the key is still absent afterwards is rethrown,
     * wrapped in a {@link DataIntegrityViolationException} when the provider reported it as a
     * {@link PersistenceException}.
     *
     * <p>An insert is logged once at {@code INFO} naming the key; a key already present, and a key
     * taken concurrently, are logged at {@code DEBUG}. No stored value is written to the log.
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
