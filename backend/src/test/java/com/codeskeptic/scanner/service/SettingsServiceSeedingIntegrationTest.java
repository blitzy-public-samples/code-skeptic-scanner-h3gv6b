package com.codeskeptic.scanner.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.SettingDto;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.service.mapper.SettingMapper;

// Net-new (no Python counterpart; backend/app/api/settings.py:L3 imported a service that exists
// nowhere in the retired tree) — DL-040 — see docs/DECISION_LOG.md
/**
 * Exercises {@link SettingsService#seedDefaultSettings()} against a real {@link SettingRepository}
 * over the in-memory database of the {@code test} profile, driven by the real
 * {@link ApplicationReadyEvent}.
 *
 * <p>Nothing here is mocked: the repository writes to H2, the mapper is the production bean, and the
 * seeded values are the ones {@code src/test/resources/application-test.yml} declares. The companion
 * {@code SettingsServiceTest} covers the same method over mocked collaborators; this class covers what
 * a mock cannot show — that the listener is bound to the real event, runs inside a transaction, and
 * writes to a real table.
 *
 * <p>A Spring Boot test context is started through {@code SpringApplication}, which publishes
 * {@link ApplicationReadyEvent} once the context is refreshed. The three rows are therefore already
 * present when the first test method runs. No test-managed transaction wraps a test method — the
 * seeding inserts each row in a transaction of its own (DL-159), which cannot observe state another
 * transaction has not committed — so every write a test performs is committed and the table is
 * restored after each test.
 *
 * <p>The three seeded keys are rows, never schema.
 */
@DataJpaTest
@ActiveProfiles("test")
@EnableConfigurationProperties(ScannerProperties.class)
@Import({SettingsService.class, SettingMapper.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("SettingsService seeding on ApplicationReadyEvent")
class SettingsServiceSeedingIntegrationTest {

    /** Primary key of the popularity-threshold row. */
    private static final String TWEET_POPULARITY_THRESHOLD_KEY = "tweet_popularity_threshold";

    /** Primary key of the sweep-spacing row. */
    private static final String RESPONSE_GENERATION_DELAY_KEY = "response_generation_delay";

    /** Primary key of the tracked-terms row. */
    private static final String STREAM_KEYWORDS_KEY = "stream_keywords";

    /** The three keys the seeding writes. */
    private static final List<String> SEEDED_KEYS = List.of(TWEET_POPULARITY_THRESHOLD_KEY,
            RESPONSE_GENERATION_DELAY_KEY, STREAM_KEYWORDS_KEY);

    /** Value an operator write puts in a seeded row before a later event is published. */
    private static final String OPERATOR_VALUE = "4242";

    /** Description an operator write puts in a seeded row before a later event is published. */
    private static final String OPERATOR_DESCRIPTION = "Edited through PUT /settings/{key}.";

    /**
     * Generated character capacity of {@code settings.key}. The mapping declares no {@code length},
     * so the column carries the undeclared-length capacity — DL-069 — see docs/DECISION_LOG.md.
     */
    private static final int SETTINGS_KEY_LENGTH = 255;

    /** Value the tracked-terms row is seeded with — DL-044 — see docs/DECISION_LOG.md. */
    private static final String STREAM_KEYWORDS_SEED_VALUE = "";

    /** Name of the presence-check operation a stale read is simulated on. */
    private static final String EXISTS_BY_ID = "existsById";

    /** Unit under test, imported as a bean so its event listener is registered. */
    @Autowired
    private SettingsService settingsService;

    /** Real data access over the in-memory database. */
    @Autowired
    private SettingRepository settingRepository;

    /** Bound configuration of the {@code test} profile, supplying the seeded values. */
    @Autowired
    private ScannerProperties properties;

    /** Publisher a further real {@link ApplicationReadyEvent} is handed to. */
    @Autowired
    private ApplicationEventPublisher eventPublisher;

    /** This test's own context, carried by every event this class publishes. */
    @Autowired
    private ConfigurableApplicationContext applicationContext;

    /** Persistence context a second service instance is built on for the race test. */
    @Autowired
    private EntityManager entityManager;

    /** Transaction manager a second service instance is built on for the race test. */
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Restores the table to its seeded state after each test. No test-managed transaction wraps a
     * test method, so every write a test performs is committed and has to be undone here.
     */
    @AfterEach
    void restoreTheSeededTable() {
        settingRepository.deleteAll();
        settingsService.seedDefaultSettings();
    }

    @Test
    @DisplayName("has seeded the three default rows by the time the context is ready")
    void hasSeededTheThreeDefaultRowsByTheTimeTheContextIsReady() {
        assertThat(settingRepository.findAll()).extracting(Setting::getKey)
                .containsExactlyInAnyOrderElementsOf(SEEDED_KEYS);
        assertThat(settingRepository.count()).isEqualTo(SEEDED_KEYS.size());
    }

    @Test
    @DisplayName("carries the configured value and a description in every seeded row")
    void carriesTheConfiguredValueAndADescriptionInEverySeededRow() {
        Setting threshold = row(TWEET_POPULARITY_THRESHOLD_KEY);
        Setting delay = row(RESPONSE_GENERATION_DELAY_KEY);
        Setting keywords = row(STREAM_KEYWORDS_KEY);

        assertThat(threshold.getValue())
                .isEqualTo(Integer.toString(properties.popularityThreshold()));
        assertThat(delay.getValue())
                .isEqualTo(Long.toString(properties.responseGenerationDelaySeconds()));
        assertThat(keywords.getValue()).isEqualTo(STREAM_KEYWORDS_SEED_VALUE);
        assertThat(List.of(threshold, delay, keywords))
                .allSatisfy(seeded -> assertThat(seeded.getDescription()).isNotBlank());
    }

    @Test
    @DisplayName("adds nothing and changes nothing when the event is published again")
    void addsNothingAndChangesNothingWhenTheEventIsPublishedAgain() {
        List<Setting> before = snapshot();

        publishApplicationReadyEvent();
        publishApplicationReadyEvent();

        assertThat(snapshot()).containsExactlyInAnyOrderElementsOf(before);
        assertThat(settingRepository.count()).isEqualTo(SEEDED_KEYS.size());
    }

    @Test
    @DisplayName("adds nothing and changes nothing when the method is invoked directly again")
    void addsNothingAndChangesNothingWhenTheMethodIsInvokedDirectlyAgain() {
        List<Setting> before = snapshot();

        settingsService.seedDefaultSettings();
        settingsService.seedDefaultSettings();

        assertThat(snapshot()).containsExactlyInAnyOrderElementsOf(before);
        assertThat(settingRepository.count()).isEqualTo(SEEDED_KEYS.size());
    }

    @Test
    @DisplayName("leaves an operator-edited row exactly as it stands on a later event")
    void leavesAnOperatorEditedRowExactlyAsItStandsOnALaterEvent() {
        settingsService.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, OPERATOR_VALUE);
        Setting edited = row(TWEET_POPULARITY_THRESHOLD_KEY);
        edited.setDescription(OPERATOR_DESCRIPTION);
        settingRepository.saveAndFlush(edited);

        publishApplicationReadyEvent();

        Setting afterTheEvent = row(TWEET_POPULARITY_THRESHOLD_KEY);
        assertThat(afterTheEvent.getValue()).isEqualTo(OPERATOR_VALUE);
        assertThat(afterTheEvent.getDescription()).isEqualTo(OPERATOR_DESCRIPTION);
        assertThat(settingRepository.count()).isEqualTo(SEEDED_KEYS.size());
    }

    // A key taken between the presence check and the insert — DL-159 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("leaves a row inserted after the presence check exactly as it stands, value and "
            + "description alike")
    void leavesARowInsertedAfterThePresenceCheckExactlyAsItStands() {
        settingsService.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, OPERATOR_VALUE);
        Setting operatorRow = row(TWEET_POPULARITY_THRESHOLD_KEY);
        operatorRow.setDescription(OPERATOR_DESCRIPTION);
        settingRepository.saveAndFlush(operatorRow);

        SettingsService seedingOnAStalePresenceRead = new SettingsService(
                repositoryReportingKeyAbsent(TWEET_POPULARITY_THRESHOLD_KEY), new SettingMapper(),
                properties, entityManager, transactionManager);

        seedingOnAStalePresenceRead.seedDefaultSettings();

        Setting afterTheSeeding = row(TWEET_POPULARITY_THRESHOLD_KEY);
        assertThat(afterTheSeeding.getValue()).as("value of the operator-owned row")
                .isEqualTo(OPERATOR_VALUE);
        assertThat(afterTheSeeding.getDescription()).as("description of the operator-owned row")
                .isEqualTo(OPERATOR_DESCRIPTION);
        assertThat(settingRepository.count()).as("stored row count")
                .isEqualTo(SEEDED_KEYS.size());
    }

    // A key taken between the presence check and the insert, on a key nothing else holds — DL-159
    @Test
    @DisplayName("still inserts a genuinely absent row when another key reports a stale presence")
    void stillInsertsAGenuinelyAbsentRowWhenAnotherKeyReportsAStalePresence() {
        settingRepository.deleteById(STREAM_KEYWORDS_KEY);

        SettingsService seedingOnAStalePresenceRead = new SettingsService(
                repositoryReportingKeyAbsent(TWEET_POPULARITY_THRESHOLD_KEY), new SettingMapper(),
                properties, entityManager, transactionManager);

        seedingOnAStalePresenceRead.seedDefaultSettings();

        assertThat(settingRepository.findAll()).extracting(Setting::getKey)
                .as("stored keys after the seeding")
                .containsExactlyInAnyOrderElementsOf(SEEDED_KEYS);
        assertThat(row(STREAM_KEYWORDS_KEY).getValue()).as("re-inserted tracked terms")
                .isEqualTo(STREAM_KEYWORDS_SEED_VALUE);
    }

    @Test
    @DisplayName("writes only the row that is absent and leaves the two present ones untouched")
    void writesOnlyTheRowThatIsAbsentAndLeavesTheTwoPresentOnesUntouched() {
        settingRepository.deleteById(STREAM_KEYWORDS_KEY);
        Setting presentBefore = detached(row(TWEET_POPULARITY_THRESHOLD_KEY));

        publishApplicationReadyEvent();

        assertThat(settingRepository.count()).isEqualTo(SEEDED_KEYS.size());
        Setting reinserted = row(STREAM_KEYWORDS_KEY);
        assertThat(reinserted.getValue()).isEqualTo(STREAM_KEYWORDS_SEED_VALUE);
        assertThat(reinserted.getDescription()).isNotBlank();
        Setting presentAfter = row(TWEET_POPULARITY_THRESHOLD_KEY);
        assertThat(presentAfter.getValue()).isEqualTo(presentBefore.getValue());
        assertThat(presentAfter.getDescription()).isEqualTo(presentBefore.getDescription());
    }

    @Test
    @DisplayName("re-seeds every row when the table has been emptied")
    void reSeedsEveryRowWhenTheTableHasBeenEmptied() {
        settingRepository.deleteAll();
        assertThat(settingRepository.count()).isZero();

        publishApplicationReadyEvent();

        assertThat(settingRepository.findAll()).extracting(Setting::getKey)
                .containsExactlyInAnyOrderElementsOf(SEEDED_KEYS);
    }

    @Test
    @DisplayName("renders every seeded row through the production mapper on a later read")
    void rendersEverySeededRowThroughTheProductionMapperOnALaterRead() {
        List<SettingDto> rendered = settingsService.getAllSettings();

        assertThat(rendered).hasSize(SEEDED_KEYS.size());
        assertThat(rendered).extracting(SettingDto::key)
                .containsExactlyInAnyOrderElementsOf(SEEDED_KEYS);
        assertThat(rendered).allSatisfy(setting -> {
            assertThat(setting.value()).isNotNull();
            assertThat(setting.description()).isNotBlank();
        });
    }

    @Test
    @DisplayName("contributes rows only, each carrying a key within the declared column width")
    void contributesRowsOnlyEachCarryingAKeyWithinTheDeclaredColumnWidth() {
        assertThat(settingRepository.findAll()).allSatisfy(seeded -> {
            assertThat(seeded.getKey()).isIn(SEEDED_KEYS);
            assertThat(seeded.getKey().length()).isLessThanOrEqualTo(SETTINGS_KEY_LENGTH);
        });
    }

    /**
     * Wraps the real repository; the first presence check of one stored key reports it absent and
     * every later check reports the database state.
     *
     * <p>This is the interleaving a second instance produces: it checks presence, another writer
     * commits the same key, its own insert is then rejected by the actual primary key of the
     * {@code settings} table — not by a stub — and its re-check sees the row the other writer stored.
     * Every operation other than that first check reaches the real repository and the real database.
     *
     * @param absentKey the key whose first presence check reports absent
     * @return the wrapping repository
     */
    private SettingRepository repositoryReportingKeyAbsent(String absentKey) {
        AtomicBoolean firstCheck = new AtomicBoolean(true);
        return (SettingRepository) Proxy.newProxyInstance(
                SettingRepository.class.getClassLoader(),
                new Class<?>[] {SettingRepository.class},
                (proxy, method, arguments) -> {
                    if (EXISTS_BY_ID.equals(method.getName()) && arguments != null
                            && arguments.length == 1 && absentKey.equals(arguments[0])
                            && firstCheck.compareAndSet(true, false)) {
                        return Boolean.FALSE;
                    }
                    try {
                        return method.invoke(settingRepository, arguments);
                    } catch (InvocationTargetException delegated) {
                        throw delegated.getCause();
                    }
                });
    }

    /**
     * Publishes a real {@link ApplicationReadyEvent} carrying this test's own context, which is what
     * reaches the listener on {@link SettingsService#seedDefaultSettings()}.
     */
    private void publishApplicationReadyEvent() {
        eventPublisher.publishEvent(new ApplicationReadyEvent(new SpringApplication(),
                new String[0], applicationContext, Duration.ZERO));
    }

    /**
     * Reads one stored row by its primary key.
     *
     * @param key the primary key of the row
     * @return the stored row
     */
    private Setting row(String key) {
        Optional<Setting> stored = settingRepository.findById(key);
        assertThat(stored).as("stored row for the key %s", key).isPresent();
        return stored.get();
    }

    /**
     * Reads every stored row as values that no later write can change.
     *
     * @return one detached copy per stored row
     */
    private List<Setting> snapshot() {
        return settingRepository.findAll().stream()
                .map(SettingsServiceSeedingIntegrationTest::detached)
                .toList();
    }

    /**
     * Copies a row's three columns into an instance the persistence context does not manage.
     *
     * @param managed the row to copy
     * @return the copy
     */
    private static Setting detached(Setting managed) {
        return new Setting(managed.getKey(), managed.getValue(), managed.getDescription());
    }
}
