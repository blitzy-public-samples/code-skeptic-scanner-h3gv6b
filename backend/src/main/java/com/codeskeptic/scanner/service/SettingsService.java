package com.codeskeptic.scanner.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>Every entity is converted to its wire form inside the transaction that loaded it, so neither a
 * detached entity nor an uninitialised proxy leaves this class; {@code spring.jpa.open-in-view} is
 * {@code false}.
 *
 * <p>The set of client-visible messages this class can produce is closed: it is the two wire literals
 * of {@code backend/app/api/settings.py:L18} and {@code :L22}, carried by
 * {@link BadRequestException#noValueProvided()} and {@link NotFoundException#settingNotFound()}.
 * Neither literal is minted here and no key name, driver text or stack detail is appended to either.
 * This class selects no HTTP status; {@code api.GlobalExceptionHandler} does.
 *
 * <p>The {@code settings} table is reached through {@link SettingRepository} only. This class
 * declares no JPQL and no native query, so neither of the reserved column names {@code key} and
 * {@code value} is spelled in a statement. It opens no connection to an external system, memoises
 * nothing and declares no operation that publishes to X.
 *
 * <p>Decisions covering this file are recorded in {@code docs/DECISION_LOG.md} DL-039, DL-040,
 * DL-043, DL-052 and DL-073; construct-level provenance is recorded in
 * {@code docs/TRACEABILITY_MATRIX.md}.
 *
 * <p>This is a singleton bean. Its three collaborators are held in final fields and are themselves
 * singletons, and this class holds no other state, so every member declared here is safe for
 * concurrent use. Two callers updating the same key concurrently both write and the later write
 * stands.
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
     * Seeded key whose value spaces the response-generation sweeps. The value is seeded from
     * {@code scanner.response-generation-delay-seconds}, declared with the default {@code 60} as
     * {@code RESPONSE_GENERATION_DELAY} at {@code backend/app/core/config.py:L11} — DL-040 — see
     * docs/DECISION_LOG.md.
     */
    private static final String RESPONSE_GENERATION_DELAY_KEY = "response_generation_delay";

    /** Description stored on the {@value #RESPONSE_GENERATION_DELAY_KEY} row. */
    private static final String RESPONSE_GENERATION_DELAY_DESCRIPTION =
            "Seconds between response-generation sweeps.";

    /**
     * Seeded key whose value carries the terms the filtered stream tracks. The value is seeded from
     * {@code scanner.ingestion.stream-base-keywords}; the source list was empty at
     * {@code backend/app/tasks/tweet_monitoring.py:L53-55} — DL-040, DL-044 — see
     * docs/DECISION_LOG.md.
     */
    private static final String STREAM_KEYWORDS_KEY = "stream_keywords";

    /** Description stored on the {@value #STREAM_KEYWORDS_KEY} row. */
    private static final String STREAM_KEYWORDS_DESCRIPTION = "Terms the filtered stream tracks.";

    /**
     * Separator joining the configured base keywords into the single {@code String} the
     * {@value #STREAM_KEYWORDS_KEY} row stores. It is the separator
     * {@code util.DelimitedStringListConverter} reads and writes.
     */
    private static final String KEYWORD_DELIMITER = ",";

    /** Data access for the {@code settings} table. */
    private final SettingRepository settingRepository;

    /** Converts a {@link Setting} into its {@link SettingDto} wire form. */
    private final SettingMapper settingMapper;

    /** Source of the three seeded values. */
    private final ScannerProperties properties;

    /**
     * Creates the bean with its collaborators, replacing the static invocation at
     * {@code backend/app/api/settings.py:L10,L20} — DL-043.
     *
     * @param settingRepository data access for the {@code settings} table, must not be {@code null}
     * @param settingMapper     entity-to-wire converter, must not be {@code null}
     * @param properties        bound configuration supplying the seeded values, must not be
     *                          {@code null}
     * @throws NullPointerException when any argument is {@code null}
     */
    public SettingsService(SettingRepository settingRepository,
            SettingMapper settingMapper,
            ScannerProperties properties) {
        this.settingRepository = Objects.requireNonNull(settingRepository,
                "settingRepository must not be null.");
        this.settingMapper = Objects.requireNonNull(settingMapper, "settingMapper must not be null.");
        this.properties = Objects.requireNonNull(properties, "properties must not be null.");
    }

    // Call site backend/app/api/settings.py:L10 — see docs/DECISION_LOG.md DL-039
    /**
     * Returns every row of the {@code settings} table in the order the repository reports.
     *
     * <p>Each element carries the {@code key}, {@code value} and {@code description} of one row
     * unchanged; no value is trimmed, defaulted or substituted, and a {@code null} column is carried
     * through as a {@code null} component. The rows are converted inside this method's transaction.
     *
     * <p>An empty table yields an empty list. The return value is never {@code null} and the list it
     * returns is unmodifiable.
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
     * {@code if new_value is None} does at {@code :L17}: an empty string is a value, is stored and is
     * returned. No other value is rejected, and no value is trimmed or normalised on the way in.
     *
     * <p>{@code key} identifies an existing row and is never written; it is the primary key of the
     * {@code settings} table. {@code description} is not written either. A key that names no row is
     * reported as absent and no row is created for it, so this operation never inserts.
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
            log.warn("Rejected the update of setting '{}': the request carried no value.", key);
            throw BadRequestException.noValueProvided();
        }

        // backend/app/api/settings.py:L21-22
        // Deviation from the literal call site: a null key is reported as absent — DL-073 — see
        // docs/DECISION_LOG.md
        Optional<Setting> existing = (key == null)
                ? Optional.empty()
                : settingRepository.findById(key);
        if (existing.isEmpty()) {
            log.warn("Rejected the update of setting '{}': the key names no row.", key);
            throw NotFoundException.settingNotFound();
        }

        Setting setting = existing.get();
        setting.setValue(value);
        SettingDto updated = settingMapper.toDto(settingRepository.save(setting));
        log.info("Updated setting '{}'.", key);
        return updated;
    }

    // Net-new (no Python counterpart) — DL-040 — see docs/DECISION_LOG.md
    /**
     * Inserts each of the three default rows of the {@code settings} table that is absent.
     *
     * <p>The three keys are {@value #TWEET_POPULARITY_THRESHOLD_KEY},
     * {@value #RESPONSE_GENERATION_DELAY_KEY} and {@value #STREAM_KEYWORDS_KEY}. Each carries a
     * {@code description} and a {@code value} rendered from configuration:
     * {@code scanner.popularity-threshold}, {@code scanner.response-generation-delay-seconds} and
     * {@code scanner.ingestion.stream-base-keywords} respectively.
     *
     * <p>The operation inserts only. A key already present is left exactly as it stands: its
     * {@code value} and its {@code description} are both untouched, whatever they hold and however
     * they came to hold it. A repeated call against unchanged data writes nothing.
     *
     * <p>Rows are the only thing added. No column, table or index is contributed here; the schema is
     * the four tables the source declared.
     *
     * <p>This method runs on {@link ApplicationReadyEvent}, after the context is refreshed, and
     * participates in a transaction. It is also directly invocable.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedDefaultSettings() {
        seedIfAbsent(TWEET_POPULARITY_THRESHOLD_KEY,
                Integer.toString(properties.popularityThreshold()),
                TWEET_POPULARITY_THRESHOLD_DESCRIPTION);
        seedIfAbsent(RESPONSE_GENERATION_DELAY_KEY,
                Long.toString(properties.responseGenerationDelaySeconds()),
                RESPONSE_GENERATION_DELAY_DESCRIPTION);
        seedIfAbsent(STREAM_KEYWORDS_KEY,
                streamKeywordsSeedValue(),
                STREAM_KEYWORDS_DESCRIPTION);
    }

    /**
     * Inserts one default row when its key is absent, and writes nothing when the key is present.
     *
     * <p>Presence is established with {@code existsById}, which loads no row. An insert is logged
     * once at {@code INFO} and names the key; a key already present is logged at {@code DEBUG}. No
     * stored value is written to the log.
     *
     * @param key         the primary key of the default row
     * @param value       the value to store when the row is inserted
     * @param description the description to store when the row is inserted
     */
    private void seedIfAbsent(String key, String value, String description) {
        if (settingRepository.existsById(key)) {
            log.debug("Default setting '{}' is already present and is left unchanged.", key);
            return;
        }
        settingRepository.save(new Setting(key, value, description));
        log.info("Seeded default setting '{}'.", key);
    }

    /**
     * Renders the configured base keywords as the single delimited {@code String} the
     * {@value #STREAM_KEYWORDS_KEY} row stores.
     *
     * <p>Each term is trimmed and terms that are {@code null} or blank are dropped; the retained
     * terms are joined with {@value #KEYWORD_DELIMITER} in configured order. An absent
     * {@code scanner.ingestion} group, an absent keyword list and a list that retains no term all
     * yield an empty string.
     *
     * @return the joined keywords, or an empty string when no term is configured; never {@code null}
     */
    // Term normalisation and tolerance of an absent group — DL-073 — see docs/DECISION_LOG.md
    private String streamKeywordsSeedValue() {
        ScannerProperties.Ingestion ingestion = properties.ingestion();
        List<String> configured = (ingestion == null) ? List.of() : ingestion.streamBaseKeywords();
        if (configured == null || configured.isEmpty()) {
            return "";
        }
        return configured.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(Predicate.not(String::isEmpty))
                .collect(Collectors.joining(KEYWORD_DELIMITER));
    }
}
