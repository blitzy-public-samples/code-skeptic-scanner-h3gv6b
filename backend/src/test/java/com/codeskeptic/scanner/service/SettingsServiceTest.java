package com.codeskeptic.scanner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.SettingDto;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.service.mapper.SettingMapper;
import com.codeskeptic.scanner.util.StreamRuleTerms;

// Net-new (no Python counterpart; backend/app/api/settings.py:L3 imports a service that exists nowhere in the repository) — see docs/DECISION_LOG.md
// Call sites backend/app/api/settings.py:L10,L20 — see docs/DECISION_LOG.md DL-039, DL-040, DL-043
/**
 * Exercises the three operations {@link SettingsService} exposes:
 * {@link SettingsService#getAllSettings()}, {@link SettingsService#updateSetting(String, String)}
 * and {@link SettingsService#seedDefaultSettings()}.
 *
 * <p>Every test drives the service through mocked collaborators — {@link SettingRepository},
 * {@link SettingMapper}, {@link ScannerProperties} and the {@link EntityManager} the seeding insert
 * is issued through — held by a service instance this class constructs directly, together with a
 * transaction manager that runs the insert inline and records the definition it was opened with. No
 * Spring context is started, no database is opened, no configuration file is read and no credential
 * is resolved.
 *
 * <p>Every seeding test supplies its configured values through the {@link ScannerProperties}
 * accessors, using {@link #CONFIGURED_POPULARITY_THRESHOLD},
 * {@link #CONFIGURED_RESPONSE_GENERATION_DELAY}, {@link #CONFIGURED_KEYWORD_ONE} and
 * {@link #CONFIGURED_KEYWORD_TWO}. None of those matches the value declared at
 * {@code backend/app/core/config.py:L10-11} or {@code backend/app/tasks/tweet_monitoring.py:L53}.
 *
 * <p>Each stub is declared by the test that consumes it. The strict-stub checking
 * {@link MockitoExtension} applies holds for every test in this class.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SettingsService")
class SettingsServiceTest {

    // -----------------------------------------------------------------------
    // Seeded keys — backend/app/db/models.py:L42 names the column they occupy
    // -----------------------------------------------------------------------

    /** Key of the seeded row that bounds the like count of a monitored post. */
    private static final String TWEET_POPULARITY_THRESHOLD_KEY = "tweet_popularity_threshold";

    /** Key of the seeded row that spaces the response-generation sweeps. */
    private static final String RESPONSE_GENERATION_DELAY_KEY = "response_generation_delay";

    /** Key of the seeded row that carries the terms the filtered stream tracks. */
    private static final String STREAM_KEYWORDS_KEY = "stream_keywords";

    /** The three seeded keys, in the order the service writes them. */
    private static final List<String> SEEDED_KEYS = List.of(
            TWEET_POPULARITY_THRESHOLD_KEY,
            RESPONSE_GENERATION_DELAY_KEY,
            STREAM_KEYWORDS_KEY);

    // -----------------------------------------------------------------------
    // Configured values supplied through ScannerProperties
    // -----------------------------------------------------------------------

    /**
     * Configured popularity threshold supplied to the service. The value declared at
     * {@code backend/app/core/config.py:L10} is {@value #SOURCE_DECLARED_POPULARITY_THRESHOLD}.
     */
    private static final int CONFIGURED_POPULARITY_THRESHOLD = 4711;

    /**
     * Configured response-generation delay supplied to the service. The value declared at
     * {@code backend/app/core/config.py:L11} is
     * {@value #SOURCE_DECLARED_RESPONSE_GENERATION_DELAY}.
     */
    private static final long CONFIGURED_RESPONSE_GENERATION_DELAY = 9377L;

    /** First configured stream term supplied to the service. */
    private static final String CONFIGURED_KEYWORD_ONE = "sentinel-keyword-alpha";

    /** Second configured stream term supplied to the service. */
    private static final String CONFIGURED_KEYWORD_TWO = "sentinel-keyword-beta";

    /** Rendering of {@link #CONFIGURED_KEYWORD_ONE} and {@link #CONFIGURED_KEYWORD_TWO} as one value. */
    private static final String CONFIGURED_KEYWORDS_VALUE =
            CONFIGURED_KEYWORD_ONE + "," + CONFIGURED_KEYWORD_TWO;

    /** Value declared for {@code TWEET_POPULARITY_THRESHOLD} at {@code backend/app/core/config.py:L10}. */
    private static final String SOURCE_DECLARED_POPULARITY_THRESHOLD = "100";

    /** Value declared for {@code RESPONSE_GENERATION_DELAY} at {@code backend/app/core/config.py:L11}. */
    private static final String SOURCE_DECLARED_RESPONSE_GENERATION_DELAY = "60";

    // -----------------------------------------------------------------------
    // Stored-row fixtures
    // -----------------------------------------------------------------------

    /** Value already stored on the {@link #TWEET_POPULARITY_THRESHOLD_KEY} row. */
    private static final String STORED_VALUE = "250";

    /** Description already stored on the {@link #TWEET_POPULARITY_THRESHOLD_KEY} row. */
    private static final String STORED_DESCRIPTION = "Threshold last set by the operator.";

    /** Value supplied to {@link SettingsService#updateSetting(String, String)}. */
    private static final String REPLACEMENT_VALUE = "512";

    /** Key that names no stored row. */
    private static final String ABSENT_KEY = "no-such-setting";

    /** Message carried by the rejection that stands in for the {@code settings} primary key. */
    private static final String DUPLICATE_KEY_MESSAGE = "settings primary key already taken";

    /** Message carried by the failure that is not an integrity violation. */
    private static final String UNRELATED_FAILURE_MESSAGE = "the connection is closed";

    /** Number of concurrent callers the threaded seeding test starts. */
    private static final int CONCURRENT_SEEDERS = 8;

    /** Bound on how long the threaded seeding test waits for its callers, in seconds. */
    private static final long CONCURRENCY_TIMEOUT_SECONDS = 20L;

    /** Wire literal of {@code backend/app/api/settings.py:L18}. */
    private static final String NO_VALUE_PROVIDED = "No value provided";

    /** Wire literal of {@code backend/app/api/settings.py:L22}. */
    private static final String SETTING_NOT_FOUND = "Setting not found";

    /** Message carried by the duplicate-key failure a concurrent insert produces. */
    private static final String DUPLICATE_KEY = "Duplicate entry for the settings primary key.";

    /** Message carried by a write failure that is not a duplicate key. */
    private static final String WRITE_FAILED = "The settings insert failed.";

    /** Simple names of the types that can contribute schema. */
    /** The only persistence-context operation the seeding uses to write a row. */
    private static final String PERSIST_OPERATION = "persist";

    /** The only persistence-context operation the seeding uses to force the write out. */
    private static final String FLUSH_OPERATION = "flush";

    private static final List<String> SCHEMA_CAPABLE_TYPE_NAMES = List.of(
            "EntityManagerFactory",
            "SessionFactory",
            "Session",
            "DataSource",
            "JdbcTemplate",
            "NamedParameterJdbcTemplate",
            "Connection",
            "Statement",
            "Flyway",
            "Liquibase");

    /** Suffix identifying a Spring Data repository collaborator. */
    private static final String REPOSITORY_TYPE_SUFFIX = "Repository";

    // -----------------------------------------------------------------------
    // Collaborators
    // -----------------------------------------------------------------------

    /** Stubbed data access for the {@code settings} table. */
    @Mock
    private SettingRepository settingRepository;

    /** Stubbed entity-to-wire converter. */
    @Mock
    private SettingMapper settingMapper;

    /** Stubbed configuration root; the {@code scanner.ingestion} group is stubbed on top of it. */
    @Mock
    private ScannerProperties properties;

    /**
     * Stubbed persistence context. The seeding path issues its insert through
     * {@link EntityManager#persist(Object)} and {@link EntityManager#flush()} — DL-159 — see
     * docs/DECISION_LOG.md.
     */
    @Mock
    private EntityManager entityManager;

    /** Definition of every transaction the seeding insert opened, oldest first. */
    private final List<TransactionDefinition> openedTransactions = new CopyOnWriteArrayList<>();

    /** Unit under test, holding the mocked collaborators. */
    private SettingsService service;

    @BeforeEach
    void createService() {
        service = new SettingsService(settingRepository, settingMapper, properties, entityManager,
                inlineTransactionManager());
    }

    // -----------------------------------------------------------------------
    // Declared surface — backend/app/api/settings.py:L10,L20;
    // frontend/src/schema/setting.ts:L3-7
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("returns the settings as a list of setting objects")
    void returnsTheSettingsAsAListOfSettingObjects() throws NoSuchMethodException {
        Method reader = SettingsService.class.getMethod("getAllSettings");

        assertThat(reader.getReturnType()).isEqualTo(List.class);
        assertThat(reader.getGenericReturnType().getTypeName())
                .isEqualTo("java.util.List<com.codeskeptic.scanner.dto.SettingDto>");
    }

    @Test
    @DisplayName("renders a setting as an object carrying a key a value and a description")
    void rendersASettingAsAnObjectCarryingAKeyAValueAndADescription() {
        List<RecordComponent> components = List.of(SettingDto.class.getRecordComponents());

        assertThat(components).hasSize(3);
        assertThat(components).extracting(RecordComponent::getName)
                .containsExactly("key", "value", "description");
        assertThat(components).extracting(RecordComponent::getType).containsOnly(String.class);
    }

    @Test
    @DisplayName("exposes reading every setting as an instance method")
    void exposesReadingEverySettingAsAnInstanceMethod() throws NoSuchMethodException {
        Method reader = SettingsService.class.getMethod("getAllSettings");

        assertThat(Modifier.isStatic(reader.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(reader.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("exposes updating a setting as an instance method")
    void exposesUpdatingASettingAsAnInstanceMethod() throws NoSuchMethodException {
        Method updater = SettingsService.class.getMethod("updateSetting", String.class, String.class);

        assertThat(Modifier.isStatic(updater.getModifiers())).isFalse();
        assertThat(Modifier.isPublic(updater.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("declares no static method")
    void declaresNoStaticMethod() {
        List<Method> declared = List.of(SettingsService.class.getDeclaredMethods());

        assertThat(declared)
                .filteredOn(method -> !method.isSynthetic())
                .isNotEmpty()
                .allSatisfy(method -> assertThat(Modifier.isStatic(method.getModifiers())).isFalse());
    }

    @Test
    @DisplayName("takes its repository mapper configuration persistence context and transaction "
            + "manager through its only constructor")
    void takesItsCollaboratorsThroughItsOnlyConstructor() {
        List<Constructor<?>> constructors = List.of(SettingsService.class.getDeclaredConstructors());

        assertThat(constructors).hasSize(1);
        assertThat(constructors.get(0).getParameterTypes())
                .containsExactly(SettingRepository.class, SettingMapper.class,
                        ScannerProperties.class, EntityManager.class,
                        PlatformTransactionManager.class);
    }

    @Test
    @DisplayName("resolves no configuration value through an annotated field parameter or method")
    void resolvesNoConfigurationValueThroughAnAnnotatedFieldParameterOrMethod() {
        for (Field field : SettingsService.class.getDeclaredFields()) {
            assertThat(field.isAnnotationPresent(Value.class)).isFalse();
        }
        for (Constructor<?> constructor : SettingsService.class.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                assertThat(parameter.isAnnotationPresent(Value.class)).isFalse();
            }
        }
        for (Method method : SettingsService.class.getDeclaredMethods()) {
            assertThat(method.isAnnotationPresent(Value.class)).isFalse();
        }
    }

    @Test
    @DisplayName("holds no data access collaborator other than the setting repository")
    void holdsNoDataAccessCollaboratorOtherThanTheSettingRepository() {
        assertThat(declaredCollaboratorTypes())
                .filteredOn(type -> type.getSimpleName().endsWith(REPOSITORY_TYPE_SUFFIX))
                .isNotEmpty()
                .containsOnly(SettingRepository.class);
    }

    @Test
    @DisplayName("holds no collaborator that can contribute a table column or index")
    void holdsNoCollaboratorThatCanContributeATableColumnOrIndex() {
        assertThat(declaredCollaboratorTypes())
                .extracting(Class::getSimpleName)
                .doesNotContainAnyElementsOf(SCHEMA_CAPABLE_TYPE_NAMES);
    }

    // Rows are the only thing the seeding contributes — DL-040, DL-159 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("reaches the persistence context for the insert alone and issues no statement of "
            + "its own")
    void reachesThePersistenceContextForTheInsertAloneAndIssuesNoStatementOfItsOwn() {
        stubConfiguredSeedValues();
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        assertThat(mockingDetails(entityManager).getInvocations())
                .extracting(invocation -> invocation.getMethod().getName())
                .as("persistence-context operations the seeding performed")
                .isNotEmpty()
                .containsOnly(PERSIST_OPERATION, FLUSH_OPERATION);
    }

    // -----------------------------------------------------------------------
    // getAllSettings()
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("returns every stored setting with its key its value and its description")
    void returnsEveryStoredSettingWithItsKeyItsValueAndItsDescription() {
        List<Setting> rows = List.of(
                new Setting(TWEET_POPULARITY_THRESHOLD_KEY, STORED_VALUE, STORED_DESCRIPTION),
                new Setting(RESPONSE_GENERATION_DELAY_KEY, "90", "Sweep spacing."),
                new Setting(STREAM_KEYWORDS_KEY, CONFIGURED_KEYWORDS_VALUE, "Tracked terms."));
        when(settingRepository.findAll()).thenReturn(rows);
        stubMapperToConvertEveryRow();

        List<SettingDto> rendered = service.getAllSettings();

        assertThat(rendered).containsExactly(
                new SettingDto(TWEET_POPULARITY_THRESHOLD_KEY, STORED_VALUE, STORED_DESCRIPTION),
                new SettingDto(RESPONSE_GENERATION_DELAY_KEY, "90", "Sweep spacing."),
                new SettingDto(STREAM_KEYWORDS_KEY, CONFIGURED_KEYWORDS_VALUE, "Tracked terms."));
    }

    @Test
    @DisplayName("returns a key a value and a description for every stored setting")
    void returnsAKeyAValueAndADescriptionForEveryStoredSetting() {
        when(settingRepository.findAll()).thenReturn(threeStoredRows());
        stubMapperToConvertEveryRow();

        List<SettingDto> rendered = service.getAllSettings();

        assertThat(rendered).hasSize(3).allSatisfy(setting -> {
            assertThat(setting.key()).isNotNull().isNotEmpty();
            assertThat(setting.value()).isNotNull();
            assertThat(setting.description()).isNotNull().isNotEmpty();
        });
    }

    @Test
    @DisplayName("returns the settings in the order the repository reports them")
    void returnsTheSettingsInTheOrderTheRepositoryReportsThem() {
        when(settingRepository.findAll()).thenReturn(threeStoredRows());
        stubMapperToConvertEveryRow();

        List<SettingDto> rendered = service.getAllSettings();

        assertThat(rendered).extracting(SettingDto::key).containsExactlyElementsOf(SEEDED_KEYS);
    }

    @Test
    @DisplayName("returns an empty list when no setting is stored")
    void returnsAnEmptyListWhenNoSettingIsStored() {
        when(settingRepository.findAll()).thenReturn(List.of());
        stubMapperToConvertEveryRow();

        List<SettingDto> rendered = service.getAllSettings();

        assertThat(rendered).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("carries a stored setting that has no value or description through unchanged")
    void carriesAStoredSettingThatHasNoValueOrDescriptionThroughUnchanged() {
        when(settingRepository.findAll())
                .thenReturn(List.of(new Setting(TWEET_POPULARITY_THRESHOLD_KEY, null, null)));
        stubMapperToConvertEveryRow();

        List<SettingDto> rendered = service.getAllSettings();

        assertThat(rendered)
                .containsExactly(new SettingDto(TWEET_POPULARITY_THRESHOLD_KEY, null, null));
    }

    @Test
    @DisplayName("reads the setting rows once and reads no configured value")
    void readsTheSettingRowsOnceAndReadsNoConfiguredValue() {
        when(settingRepository.findAll()).thenReturn(threeStoredRows());
        stubMapperToConvertEveryRow();

        service.getAllSettings();

        verify(settingRepository).findAll();
        verifyNoMoreInteractions(settingRepository);
        verifyNoInteractions(properties);
    }

    @Test
    @DisplayName("renders the rows it read through the mapper")
    void rendersTheRowsItReadThroughTheMapper() {
        List<Setting> rows = threeStoredRows();
        when(settingRepository.findAll()).thenReturn(rows);
        stubMapperToConvertEveryRow();

        service.getAllSettings();

        verify(settingMapper).toDtoList(same(rows));
        verifyNoMoreInteractions(settingMapper);
    }

    // -----------------------------------------------------------------------
    // updateSetting(String, String) — a null value
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("rejects a null value")
    void rejectsANullValue() {
        assertThatThrownBy(() -> service.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(NO_VALUE_PROVIDED);
    }

    @Test
    @DisplayName("reads no row and writes no row when the value is null")
    void readsNoRowAndWritesNoRowWhenTheValueIsNull() {
        assertThatThrownBy(() -> service.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, null))
                .isInstanceOf(BadRequestException.class);

        verifyNoInteractions(settingRepository);
        verifyNoInteractions(settingMapper);
        verifyNoInteractions(properties);
    }

    @Test
    @DisplayName("rejects a null value for a key that names no row")
    void rejectsANullValueForAKeyThatNamesNoRow() {
        assertThatThrownBy(() -> service.updateSetting(ABSENT_KEY, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(NO_VALUE_PROVIDED);

        verifyNoInteractions(settingRepository);
    }

    // -----------------------------------------------------------------------
    // updateSetting(String, String) — values that are not null
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("accepts the value false")
    void acceptsTheValueFalse() {
        Setting stored = stubStoredRow(TWEET_POPULARITY_THRESHOLD_KEY);

        SettingDto updated = service.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, "false");

        assertThat(updated.value()).isEqualTo("false");
        assertThat(stored.getValue()).isEqualTo("false");
        assertThat(savedRow().getValue()).isEqualTo("false");
    }

    @Test
    @DisplayName("accepts the value zero")
    void acceptsTheValueZero() {
        Setting stored = stubStoredRow(TWEET_POPULARITY_THRESHOLD_KEY);

        SettingDto updated = service.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, "0");

        assertThat(updated.value()).isEqualTo("0");
        assertThat(stored.getValue()).isEqualTo("0");
        assertThat(savedRow().getValue()).isEqualTo("0");
    }

    @Test
    @DisplayName("accepts an empty value")
    void acceptsAnEmptyValue() {
        Setting stored = stubStoredRow(TWEET_POPULARITY_THRESHOLD_KEY);

        SettingDto updated = service.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, "");

        assertThat(updated.value()).isEmpty();
        assertThat(stored.getValue()).isEmpty();
        assertThat(savedRow().getValue()).isEmpty();
    }

    @Test
    @DisplayName("accepts a value of whitespace only")
    void acceptsAValueOfWhitespaceOnly() {
        Setting stored = stubStoredRow(TWEET_POPULARITY_THRESHOLD_KEY);

        SettingDto updated = service.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, "   ");

        assertThat(updated.value()).isEqualTo("   ");
        assertThat(stored.getValue()).isEqualTo("   ");
    }

    @Test
    @DisplayName("stores a value without trimming it")
    void storesAValueWithoutTrimmingIt() {
        Setting stored = stubStoredRow(TWEET_POPULARITY_THRESHOLD_KEY);

        SettingDto updated = service.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, "  512  ");

        assertThat(updated.value()).isEqualTo("  512  ");
        assertThat(stored.getValue()).isEqualTo("  512  ");
    }

    @Test
    @DisplayName("accepts a value that is not a number for a numeric setting")
    void acceptsAValueThatIsNotANumberForANumericSetting() {
        Setting stored = stubStoredRow(TWEET_POPULARITY_THRESHOLD_KEY);

        SettingDto updated = service.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, "not-a-number");

        assertThat(updated.value()).isEqualTo("not-a-number");
        assertThat(stored.getValue()).isEqualTo("not-a-number");
    }

    @Test
    @DisplayName("accepts a value of ten thousand characters")
    void acceptsAValueOfTenThousandCharacters() {
        String longValue = "v".repeat(10_000);
        Setting stored = stubStoredRow(STREAM_KEYWORDS_KEY);

        SettingDto updated = service.updateSetting(STREAM_KEYWORDS_KEY, longValue);

        assertThat(updated.value()).isEqualTo(longValue);
        assertThat(stored.getValue()).hasSize(10_000);
    }

    // -----------------------------------------------------------------------
    // updateSetting(String, String) — a key that names no row
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("reports a key that names no row as absent")
    void reportsAKeyThatNamesNoRowAsAbsent() {
        when(settingRepository.findById(ABSENT_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSetting(ABSENT_KEY, REPLACEMENT_VALUE))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(SETTING_NOT_FOUND);
    }

    @Test
    @DisplayName("writes no row for a key that names no row")
    void writesNoRowForAKeyThatNamesNoRow() {
        when(settingRepository.findById(ABSENT_KEY)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateSetting(ABSENT_KEY, REPLACEMENT_VALUE))
                .isInstanceOf(NotFoundException.class);

        verify(settingRepository, never()).save(any(Setting.class));
        verifyNoInteractions(settingMapper);
    }

    @Test
    @DisplayName("reports a null key as absent")
    void reportsANullKeyAsAbsent() {
        assertThatThrownBy(() -> service.updateSetting(null, REPLACEMENT_VALUE))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(SETTING_NOT_FOUND);

        verifyNoInteractions(settingRepository);
        verifyNoInteractions(settingMapper);
    }

    // -----------------------------------------------------------------------
    // updateSetting(String, String) — a stored row
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("returns the row carrying the new value")
    void returnsTheRowCarryingTheNewValue() {
        stubStoredRow(TWEET_POPULARITY_THRESHOLD_KEY);

        SettingDto updated = service.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, REPLACEMENT_VALUE);

        assertThat(updated).isEqualTo(new SettingDto(
                TWEET_POPULARITY_THRESHOLD_KEY, REPLACEMENT_VALUE, STORED_DESCRIPTION));
    }

    @Test
    @DisplayName("leaves the description of the updated row unchanged")
    void leavesTheDescriptionOfTheUpdatedRowUnchanged() {
        Setting stored = stubStoredRow(TWEET_POPULARITY_THRESHOLD_KEY);

        SettingDto updated = service.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, REPLACEMENT_VALUE);

        assertThat(updated.description()).isEqualTo(STORED_DESCRIPTION);
        assertThat(stored.getDescription()).isEqualTo(STORED_DESCRIPTION);
        assertThat(savedRow().getDescription()).isEqualTo(STORED_DESCRIPTION);
    }

    @Test
    @DisplayName("leaves the key of the updated row unchanged")
    void leavesTheKeyOfTheUpdatedRowUnchanged() {
        Setting stored = stubStoredRow(TWEET_POPULARITY_THRESHOLD_KEY);

        SettingDto updated = service.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, REPLACEMENT_VALUE);

        assertThat(updated.key()).isEqualTo(TWEET_POPULARITY_THRESHOLD_KEY);
        assertThat(stored.getKey()).isEqualTo(TWEET_POPULARITY_THRESHOLD_KEY);
        assertThat(savedRow().getKey()).isEqualTo(TWEET_POPULARITY_THRESHOLD_KEY);
    }

    @Test
    @DisplayName("reads the row by its key and writes back the row it read")
    void readsTheRowByItsKeyAndWritesBackTheRowItRead() {
        Setting stored = stubStoredRow(TWEET_POPULARITY_THRESHOLD_KEY);

        service.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, REPLACEMENT_VALUE);

        verify(settingRepository).findById(TWEET_POPULARITY_THRESHOLD_KEY);
        verify(settingRepository).save(same(stored));
        verifyNoMoreInteractions(settingRepository);
        verifyNoInteractions(properties);
    }

    @Test
    @DisplayName("reports an updated value on a subsequent read")
    void reportsAnUpdatedValueOnASubsequentRead() {
        Setting stored = stubStoredRow(TWEET_POPULARITY_THRESHOLD_KEY);
        when(settingRepository.findAll()).thenReturn(List.of(stored));
        stubMapperToConvertEveryRow();

        service.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, REPLACEMENT_VALUE);
        List<SettingDto> rendered = service.getAllSettings();

        assertThat(rendered).containsExactly(new SettingDto(
                TWEET_POPULARITY_THRESHOLD_KEY, REPLACEMENT_VALUE, STORED_DESCRIPTION));
    }

    // -----------------------------------------------------------------------
    // seedDefaultSettings() — the rows written
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("seeds three settings whose keys are the threshold the delay and the keywords")
    void seedsThreeSettingsWhoseKeysAreTheThresholdTheDelayAndTheKeywords() {
        stubConfiguredSeedValues();
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        assertThat(flushedRows(3)).extracting(Setting::getKey)
                .containsExactlyElementsOf(SEEDED_KEYS);
    }

    @Test
    @DisplayName("seeds a description with every setting it writes")
    void seedsADescriptionWithEverySettingItWrites() {
        stubConfiguredSeedValues();
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        assertThat(flushedRows(3)).allSatisfy(row ->
                assertThat(row.getDescription()).isNotNull().isNotBlank());
    }

    @Test
    @DisplayName("seeds the threshold with the configured popularity threshold")
    void seedsTheThresholdWithTheConfiguredPopularityThreshold() {
        stubConfiguredSeedValues();
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        Setting seeded = seededRow(flushedRows(3), TWEET_POPULARITY_THRESHOLD_KEY);
        assertThat(seeded.getValue()).isEqualTo(String.valueOf(CONFIGURED_POPULARITY_THRESHOLD));
        assertThat(seeded.getValue()).isNotEqualTo(SOURCE_DECLARED_POPULARITY_THRESHOLD);
        verify(properties).popularityThreshold();
    }

    @Test
    @DisplayName("seeds the delay with the configured response generation delay")
    void seedsTheDelayWithTheConfiguredResponseGenerationDelay() {
        stubConfiguredSeedValues();
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        Setting seeded = seededRow(flushedRows(3), RESPONSE_GENERATION_DELAY_KEY);
        assertThat(seeded.getValue()).isEqualTo(String.valueOf(CONFIGURED_RESPONSE_GENERATION_DELAY));
        assertThat(seeded.getValue()).isNotEqualTo(SOURCE_DECLARED_RESPONSE_GENERATION_DELAY);
        verify(properties).responseGenerationDelaySeconds();
    }

    @Test
    @DisplayName("seeds a blank keywords override so the composed rule set applies")
    void seedsABlankKeywordsOverrideSoTheComposedRuleSetApplies() {
        stubConfiguredSeedValues();
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        Setting seeded = seededRow(flushedRows(3), STREAM_KEYWORDS_KEY);
        assertThat(seeded.getValue()).isEmpty();
        assertThat(seeded.getDescription()).isNotBlank();
    }

    @Test
    @DisplayName("reads no configured stream term while seeding the keywords override")
    void readsNoConfiguredStreamTermWhileSeedingTheKeywordsOverride() {
        stubConfiguredSeedValues();
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        assertThat(seededRow(flushedRows(3), STREAM_KEYWORDS_KEY).getValue()).isEmpty();
        verify(properties, never()).ingestion();
    }

    // -----------------------------------------------------------------------
    // seedDefaultSettings() — insert if absent
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("checks every key for presence before writing it")
    void checksEveryKeyForPresenceBeforeWritingIt() {
        stubConfiguredSeedValues();
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        verify(settingRepository).existsById(TWEET_POPULARITY_THRESHOLD_KEY);
        verify(settingRepository).existsById(RESPONSE_GENERATION_DELAY_KEY);
        verify(settingRepository).existsById(STREAM_KEYWORDS_KEY);
    }

    @Test
    @DisplayName("writes no setting whose key is already present")
    void writesNoSettingWhoseKeyIsAlreadyPresent() {
        stubConfiguredSeedValues();
        when(settingRepository.existsById(anyString())).thenReturn(true);

        service.seedDefaultSettings();

        verify(entityManager, never()).persist(any());
        verify(settingRepository, never()).saveAndFlush(any(Setting.class));
        verify(settingRepository, never()).save(any(Setting.class));
        verify(settingRepository, times(3)).existsById(anyString());
        verifyNoMoreInteractions(settingRepository);
        verifyNoInteractions(settingMapper);
        verifyNoInteractions(entityManager);
    }

    @Test
    @DisplayName("writes only the settings whose keys are absent")
    void writesOnlyTheSettingsWhoseKeysAreAbsent() {
        stubConfiguredSeedValues();
        when(settingRepository.existsById(TWEET_POPULARITY_THRESHOLD_KEY)).thenReturn(true);
        when(settingRepository.existsById(RESPONSE_GENERATION_DELAY_KEY)).thenReturn(false);
        when(settingRepository.existsById(STREAM_KEYWORDS_KEY)).thenReturn(false);

        service.seedDefaultSettings();

        assertThat(flushedRows(2)).extracting(Setting::getKey)
                .containsExactly(RESPONSE_GENERATION_DELAY_KEY, STREAM_KEYWORDS_KEY)
                .doesNotContain(TWEET_POPULARITY_THRESHOLD_KEY);
    }

    // -----------------------------------------------------------------------
    // seedDefaultSettings() — a second seeding
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("holds three settings after seeding twice")
    void holdsThreeSettingsAfterSeedingTwice() {
        List<Setting> storedRows = new ArrayList<>();
        stubConfiguredSeedValues();
        stubRepositoryBackedBy(storedRows);

        service.seedDefaultSettings();
        service.seedDefaultSettings();

        assertThat(storedRows).hasSize(3);
        assertThat(storedRows).extracting(Setting::getKey).containsExactlyElementsOf(SEEDED_KEYS);
        verify(entityManager, times(3)).persist(any(Setting.class));
        verify(settingRepository, times(6)).existsById(anyString());
    }

    @Test
    @DisplayName("writes nothing on a second seeding of the same data")
    void writesNothingOnASecondSeedingOfTheSameData() {
        List<Setting> storedRows = new ArrayList<>();
        stubConfiguredSeedValues();
        stubRepositoryBackedBy(storedRows);

        service.seedDefaultSettings();
        List<Setting> afterFirstSeeding = List.copyOf(storedRows);
        service.seedDefaultSettings();

        assertThat(storedRows).hasSize(3);
        assertThat(storedRows).zipSatisfy(afterFirstSeeding,
                (row, rowAfterFirstSeeding) -> assertThat(row).isSameAs(rowAfterFirstSeeding));
        assertThat(storedRows).extracting(Setting::getValue).containsExactly(
                String.valueOf(CONFIGURED_POPULARITY_THRESHOLD),
                String.valueOf(CONFIGURED_RESPONSE_GENERATION_DELAY),
                "");
    }

    @Test
    @DisplayName("leaves an already stored value and description unchanged on a second seeding")
    void leavesAnAlreadyStoredValueAndDescriptionUnchangedOnASecondSeeding() {
        Setting storedRow =
                new Setting(TWEET_POPULARITY_THRESHOLD_KEY, STORED_VALUE, STORED_DESCRIPTION);
        List<Setting> storedRows = new ArrayList<>(List.of(storedRow));
        stubConfiguredSeedValues();
        stubRepositoryBackedBy(storedRows);

        service.seedDefaultSettings();
        service.seedDefaultSettings();

        assertThat(storedRow.getValue()).isEqualTo(STORED_VALUE);
        assertThat(storedRow.getDescription()).isEqualTo(STORED_DESCRIPTION);
        assertThat(storedRows).hasSize(3);
        assertThat(flushedRows(2)).extracting(Setting::getKey)
                .containsExactly(RESPONSE_GENERATION_DELAY_KEY, STREAM_KEYWORDS_KEY)
                .doesNotContain(TWEET_POPULARITY_THRESHOLD_KEY);
    }

    // -----------------------------------------------------------------------
    // seedDefaultSettings() — a key taken concurrently — DL-159
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("inserts each seeded row and never merges, so no statement it issues can update a "
            + "stored row")
    void insertsEachSeededRowAndNeverMerges() {
        stubConfiguredSeedValues();
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        verify(entityManager, times(3)).persist(any(Setting.class));
        verify(entityManager, times(3)).flush();
        verify(entityManager, never()).merge(any());
        verify(settingRepository, never()).saveAndFlush(any(Setting.class));
        verify(settingRepository, never()).save(any(Setting.class));
    }

    @Test
    @DisplayName("opens a transaction of its own for each inserted row")
    void opensATransactionOfItsOwnForEachInsertedRow() {
        stubConfiguredSeedValues();
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        assertThat(openedTransactions).as("transactions opened for the three inserts").hasSize(3);
        assertThat(openedTransactions).allSatisfy(definition ->
                assertThat(definition.getPropagationBehavior())
                        .as("propagation behaviour of the insert transaction")
                        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
    }

    @Test
    @DisplayName("declares no transaction on the seeding entry point and one on each client "
            + "operation")
    void declaresNoTransactionOnTheSeedingEntryPoint() throws NoSuchMethodException {
        Method seeding = SettingsService.class.getDeclaredMethod("seedDefaultSettings");
        Method reading = SettingsService.class.getDeclaredMethod("getAllSettings");
        Method updating = SettingsService.class
                .getDeclaredMethod("updateSetting", String.class, String.class);

        assertThat(seeding.isAnnotationPresent(Transactional.class))
                .as("seedDefaultSettings declares a transaction").isFalse();
        assertThat(SettingsService.class.isAnnotationPresent(Transactional.class))
                .as("the class declares a transaction").isFalse();
        assertThat(reading.isAnnotationPresent(Transactional.class))
                .as("getAllSettings declares a transaction").isTrue();
        assertThat(updating.isAnnotationPresent(Transactional.class))
                .as("updateSetting declares a transaction").isTrue();
    }

    @Test
    @DisplayName("tolerates a key taken between the presence check and the write")
    void toleratesAKeyTakenBetweenThePresenceCheckAndTheWrite() {
        stubConfiguredSeedValues();
        when(settingRepository.existsById(TWEET_POPULARITY_THRESHOLD_KEY)).thenReturn(false, true);
        when(settingRepository.existsById(RESPONSE_GENERATION_DELAY_KEY)).thenReturn(false);
        when(settingRepository.existsById(STREAM_KEYWORDS_KEY)).thenReturn(false);
        stubWriteRejectedFor(TWEET_POPULARITY_THRESHOLD_KEY);

        service.seedDefaultSettings();

        verify(settingRepository, times(2)).existsById(TWEET_POPULARITY_THRESHOLD_KEY);
    }

    @Test
    @DisplayName("seeds the remaining keys after one key is taken concurrently")
    void seedsTheRemainingKeysAfterOneKeyIsTakenConcurrently() {
        stubConfiguredSeedValues();
        when(settingRepository.existsById(TWEET_POPULARITY_THRESHOLD_KEY)).thenReturn(false, true);
        when(settingRepository.existsById(RESPONSE_GENERATION_DELAY_KEY)).thenReturn(false);
        when(settingRepository.existsById(STREAM_KEYWORDS_KEY)).thenReturn(false);
        stubWriteRejectedFor(TWEET_POPULARITY_THRESHOLD_KEY);

        service.seedDefaultSettings();

        assertThat(flushedRows(3)).extracting(Setting::getKey)
                .containsExactlyElementsOf(SEEDED_KEYS);
    }

    @Test
    @DisplayName("rethrows the rejection when the key is still absent after the write fails")
    void rethrowsTheRejectionWhenTheKeyIsStillAbsentAfterTheWriteFails() {
        when(properties.popularityThreshold()).thenReturn(CONFIGURED_POPULARITY_THRESHOLD);
        when(settingRepository.existsById(TWEET_POPULARITY_THRESHOLD_KEY)).thenReturn(false, false);
        doThrow(new DataIntegrityViolationException(DUPLICATE_KEY_MESSAGE))
                .when(entityManager).persist(any(Setting.class));

        assertThatThrownBy(service::seedDefaultSettings)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(DUPLICATE_KEY_MESSAGE);

        verify(settingRepository, times(2)).existsById(TWEET_POPULARITY_THRESHOLD_KEY);
        verify(settingRepository, never()).existsById(RESPONSE_GENERATION_DELAY_KEY);
    }

    @Test
    @DisplayName("propagates a write failure that is not an integrity violation without rechecking "
            + "the key")
    void propagatesAWriteFailureThatIsNotAnIntegrityViolation() {
        when(properties.popularityThreshold()).thenReturn(CONFIGURED_POPULARITY_THRESHOLD);
        when(settingRepository.existsById(TWEET_POPULARITY_THRESHOLD_KEY)).thenReturn(false);
        doThrow(new IllegalStateException(UNRELATED_FAILURE_MESSAGE))
                .when(entityManager).persist(any(Setting.class));

        assertThatThrownBy(service::seedDefaultSettings)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(UNRELATED_FAILURE_MESSAGE);

        verify(settingRepository, times(1)).existsById(TWEET_POPULARITY_THRESHOLD_KEY);
    }

    @Test
    @DisplayName("stores each default row exactly once when several callers seed the same database "
            + "at the same time")
    void storesEachDefaultRowExactlyOnceUnderConcurrentSeeding() throws InterruptedException {
        Map<String, Setting> table = new ConcurrentHashMap<>();
        AtomicInteger rejections = new AtomicInteger();
        List<Throwable> raised = new CopyOnWriteArrayList<>();

        stubConfiguredSeedValues();
        when(settingRepository.existsById(anyString()))
                .thenAnswer(invocation -> table.containsKey((String) invocation.getArgument(0)));
        // persist always inserts, so a key already stored is rejected by the primary key and the
        // stored row is left untouched — the behaviour DL-159 relies on.
        doAnswer(invocation -> {
            Setting written = invocation.getArgument(0);
            if (table.putIfAbsent(written.getKey(), written) != null) {
                rejections.incrementAndGet();
                throw new PersistenceException(DUPLICATE_KEY_MESSAGE);
            }
            return null;
        }).when(entityManager).persist(any(Setting.class));

        runConcurrently(service::seedDefaultSettings, raised);

        assertThat(raised).as("failures raised by the concurrent callers").isEmpty();
        assertThat(table.keySet()).as("stored keys")
                .containsExactlyInAnyOrderElementsOf(SEEDED_KEYS);
        assertThat(table.get(TWEET_POPULARITY_THRESHOLD_KEY).getValue())
                .as("stored popularity threshold")
                .isEqualTo(String.valueOf(CONFIGURED_POPULARITY_THRESHOLD));
        assertThat(table.get(RESPONSE_GENERATION_DELAY_KEY).getValue())
                .as("stored response generation delay")
                .isEqualTo(String.valueOf(CONFIGURED_RESPONSE_GENERATION_DELAY));
        assertThat(table.get(STREAM_KEYWORDS_KEY).getValue()).as("stored stream keywords")
                .isEmpty();
        assertThat(rejections.get()).as("writes the primary key rejected").isNotNegative();
    }

    @Test
    @DisplayName("reports the stored value of a setting rather than the configured value")
    void reportsTheStoredValueOfASettingRatherThanTheConfiguredValue() {
        Setting storedRow =
                new Setting(TWEET_POPULARITY_THRESHOLD_KEY, STORED_VALUE, STORED_DESCRIPTION);
        List<Setting> storedRows = new ArrayList<>(List.of(storedRow));
        stubConfiguredSeedValues();
        stubRepositoryBackedBy(storedRows);
        when(settingRepository.findAll()).thenReturn(storedRows);
        stubMapperToConvertEveryRow();

        service.seedDefaultSettings();
        List<SettingDto> rendered = service.getAllSettings();

        assertThat(rendered).contains(new SettingDto(
                TWEET_POPULARITY_THRESHOLD_KEY, STORED_VALUE, STORED_DESCRIPTION));
        assertThat(rendered).extracting(SettingDto::value)
                .doesNotContain(String.valueOf(CONFIGURED_POPULARITY_THRESHOLD));
    }

    // -----------------------------------------------------------------------
    // seedDefaultSettings() — concurrent seeding
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("opens one transaction of its own for every seeded key")
    void opensOneTransactionOfItsOwnForEverySeededKey() {
        stubConfiguredSeedValues();
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        assertThat(openedTransactions).hasSize(SEEDED_KEYS.size());
    }

    @Test
    @DisplayName("seeds every key in a new repeatable read transaction")
    void seedsEveryKeyInANewRepeatableReadTransaction() {
        stubConfiguredSeedValues();
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        assertThat(openedTransactions).isNotEmpty().allSatisfy(definition -> {
            assertThat(definition.getPropagationBehavior())
                    .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(definition.getIsolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        });
    }

    @Test
    @DisplayName("treats a key another instance inserted first as already seeded")
    void treatsAKeyAnotherInstanceInsertedFirstAsAlreadySeeded() {
        stubConfiguredSeedValues();
        stubConcurrentInsertOf(TWEET_POPULARITY_THRESHOLD_KEY);

        assertThatCode(() -> service.seedDefaultSettings()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("seeds the remaining keys after another instance inserted the first key")
    void seedsTheRemainingKeysAfterAnotherInstanceInsertedTheFirstKey() {
        stubConfiguredSeedValues();
        stubConcurrentInsertOf(TWEET_POPULARITY_THRESHOLD_KEY);

        service.seedDefaultSettings();

        assertThat(flushedRows(SEEDED_KEYS.size())).extracting(Setting::getKey)
                .containsExactlyElementsOf(SEEDED_KEYS);
    }

    @Test
    @DisplayName("attempts a key another instance inserted first no more than once")
    void attemptsAKeyAnotherInstanceInsertedFirstNoMoreThanOnce() {
        stubConfiguredSeedValues();
        stubConcurrentInsertOf(TWEET_POPULARITY_THRESHOLD_KEY);

        service.seedDefaultSettings();

        assertThat(flushedRows(SEEDED_KEYS.size())).extracting(Setting::getKey)
                .containsOnlyOnce(TWEET_POPULARITY_THRESHOLD_KEY);
    }

    @Test
    @DisplayName("writes no update and no delete when a key was inserted concurrently")
    void writesNoUpdateAndNoDeleteWhenAKeyWasInsertedConcurrently() {
        stubConfiguredSeedValues();
        stubConcurrentInsertOf(TWEET_POPULARITY_THRESHOLD_KEY);

        service.seedDefaultSettings();

        verify(settingRepository, never()).save(any(Setting.class));
        verify(settingRepository, never()).delete(any(Setting.class));
        verify(settingRepository, never()).deleteById(anyString());
        verifyNoInteractions(settingMapper);
    }

    @Test
    @DisplayName("leaves a row inserted concurrently exactly as the other instance stored it")
    void leavesARowInsertedConcurrentlyExactlyAsTheOtherInstanceStoredIt() {
        Setting rowAnotherInstanceInserted =
                new Setting(TWEET_POPULARITY_THRESHOLD_KEY, STORED_VALUE, STORED_DESCRIPTION);
        stubConfiguredSeedValues();
        stubConcurrentInsertOf(TWEET_POPULARITY_THRESHOLD_KEY);

        service.seedDefaultSettings();

        assertThat(rowAnotherInstanceInserted.getValue()).isEqualTo(STORED_VALUE);
        assertThat(rowAnotherInstanceInserted.getDescription()).isEqualTo(STORED_DESCRIPTION);
        verify(settingRepository, never()).save(any(Setting.class));
    }

    @Test
    @DisplayName("propagates a write failure that is not a key another instance inserted first")
    void propagatesAWriteFailureThatIsNotAKeyAnotherInstanceInsertedFirst() {
        // The failure reaches the caller on the first key, so no later key's value is read.
        when(properties.popularityThreshold()).thenReturn(CONFIGURED_POPULARITY_THRESHOLD);
        when(settingRepository.existsById(anyString())).thenReturn(false);
        doThrow(new DataIntegrityViolationException(WRITE_FAILED))
                .when(entityManager).persist(any(Setting.class));

        assertThatThrownBy(() -> service.seedDefaultSettings())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage(WRITE_FAILED);
    }

    // -----------------------------------------------------------------------
    // Fixtures and stubs
    // -----------------------------------------------------------------------

    /**
     * Returns one stored row per seeded key, each carrying a value and a description.
     *
     * @return three rows in the order {@link #SEEDED_KEYS} declares
     */
    private static List<Setting> threeStoredRows() {
        return List.of(
                new Setting(TWEET_POPULARITY_THRESHOLD_KEY, STORED_VALUE, STORED_DESCRIPTION),
                new Setting(RESPONSE_GENERATION_DELAY_KEY, "90", "Sweep spacing."),
                new Setting(STREAM_KEYWORDS_KEY, CONFIGURED_KEYWORDS_VALUE, "Tracked terms."));
    }

    /**
     * Converts a row the way {@link SettingMapper} does, copying all three columns unchanged.
     *
     * @param row the row to convert
     * @return the row's wire form
     */
    private static SettingDto asDto(Setting row) {
        return new SettingDto(row.getKey(), row.getValue(), row.getDescription());
    }

    /**
     * Reports whether any row in {@code rows} carries {@code key}.
     *
     * @param rows the rows to search
     * @param key  the key to look for
     * @return {@code true} when a row carries the key
     */
    private static boolean holdsKey(List<Setting> rows, String key) {
        return rows.stream().anyMatch(row -> key.equals(row.getKey()));
    }

    /**
     * Returns the single row seeded for {@code key}.
     *
     * @param rows the rows the repository was asked to write
     * @param key  the key to select
     * @return the row carrying {@code key}
     */
    private static Setting seededRow(List<Setting> rows, String key) {
        return rows.stream()
                .filter(row -> key.equals(row.getKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row was written for the key " + key + "."));
    }

    /** Stubs the mapper to convert every row of a list. */
    private void stubMapperToConvertEveryRow() {
        when(settingMapper.toDtoList(any())).thenAnswer(invocation -> {
            List<Setting> rows = invocation.getArgument(0);
            return rows.stream().map(SettingsServiceTest::asDto).toList();
        });
    }

    /**
     * Stubs one stored row under {@code key}, together with the write and the conversion that follow
     * a successful update.
     *
     * @param key the key the stored row carries
     * @return the stored row the service reads, writes and converts
     */
    private Setting stubStoredRow(String key) {
        Setting stored = new Setting(key, STORED_VALUE, STORED_DESCRIPTION);
        when(settingRepository.findById(key)).thenReturn(Optional.of(stored));
        when(settingRepository.save(same(stored))).thenReturn(stored);
        when(settingMapper.toDto(same(stored)))
                .thenAnswer(invocation -> asDto(invocation.getArgument(0)));
        return stored;
    }

    /** Stubs every seeded key as absent. */
    private void stubEveryKeyAbsent() {
        when(settingRepository.existsById(anyString())).thenReturn(false);
    }

    /**
     * Stubs the insert to reject {@code rejectedKey} with an integrity violation and to accept every
     * other key.
     *
     * @param rejectedKey the key whose insert is rejected
     */
    /**
     * Stubs the state another instance leaves behind when it inserts {@code key} first: the key reads
     * as absent when presence is first tested and as present afterwards, and the insert of that one
     * key is rejected with an integrity violation.
     *
     * @param key the key another instance inserted first
     */
    private void stubConcurrentInsertOf(String key) {
        AtomicInteger presenceTests = new AtomicInteger();
        when(settingRepository.existsById(anyString())).thenAnswer(invocation ->
                key.equals(invocation.getArgument(0)) && presenceTests.incrementAndGet() > 1);
        doThrow(new DataIntegrityViolationException(DUPLICATE_KEY_MESSAGE)).when(entityManager)
                .persist(argThat(row -> row instanceof Setting stored
                        && key.equals(stored.getKey())));
    }

    private void stubWriteRejectedFor(String rejectedKey) {
        doThrow(new DataIntegrityViolationException(DUPLICATE_KEY_MESSAGE)).when(entityManager)
                .persist(argThat(row -> row instanceof Setting stored
                        && rejectedKey.equals(stored.getKey())));
    }

    /**
     * Runs {@code work} on {@value #CONCURRENT_SEEDERS} threads released together, and collects every
     * failure any of them raises.
     *
     * @param work   the operation each thread performs once
     * @param raised the list every raised failure is added to
     * @throws InterruptedException when the calling thread is interrupted while waiting
     */
    private static void runConcurrently(Runnable work, List<Throwable> raised)
            throws InterruptedException {
        CyclicBarrier released = new CyclicBarrier(CONCURRENT_SEEDERS);
        CountDownLatch finished = new CountDownLatch(CONCURRENT_SEEDERS);
        ExecutorService callers = Executors.newFixedThreadPool(CONCURRENT_SEEDERS);
        try {
            for (int caller = 0; caller < CONCURRENT_SEEDERS; caller++) {
                callers.execute(() -> {
                    try {
                        released.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        work.run();
                    } catch (Throwable failure) {
                        raised.add(failure);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            if (!finished.await(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                raised.add(new AssertionError("The concurrent callers did not finish in time."));
            }
        } finally {
            // The pool is awaited and its termination asserted, so no thread of this test outlives
            // it — DL-273 — see docs/DECISION_LOG.md
            awaitTermination(callers);
        }
    }

    // Net-new: every test executor is awaited and its termination asserted — DL-273 — see
    // docs/DECISION_LOG.md
    /**
     * Shuts the supplied executor down and asserts that it terminates.
     *
     * <p>Termination is awaited for at most {@value #CONCURRENCY_TIMEOUT_SECONDS} seconds. A thread
     * still running at that bound fails the test rather than being left behind for the rest of the
     * build. An interrupt while awaiting is restored on the calling thread and reported, so the
     * interrupt is neither swallowed nor mistaken for a clean termination.
     *
     * @param executor the executor to release
     */
    private static void awaitTermination(ExecutorService executor) {
        executor.shutdownNow();
        try {
            assertThat(executor.awaitTermination(CONCURRENCY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .as("the concurrent-caller pool terminated").isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting the concurrent-caller pool.",
                    interrupted);
        }
    }

    /**
     * Stubs the repository to report presence from {@code storedRows} and to append every written row
     * to it, rejecting a key the list already holds.
     *
     * <p>The rejection stands in for the primary key of the {@code settings} table: a second write of
     * a key already stored answers with {@link DataIntegrityViolationException} and appends no
     * duplicate.
     *
     * @param storedRows the mutable list standing in for the {@code settings} table
     */
    private void stubRepositoryBackedBy(List<Setting> storedRows) {
        when(settingRepository.existsById(anyString()))
                .thenAnswer(invocation -> holdsKey(storedRows, invocation.getArgument(0)));
        doAnswer(invocation -> {
            Setting written = invocation.getArgument(0);
            if (holdsKey(storedRows, written.getKey())) {
                throw new DataIntegrityViolationException(DUPLICATE_KEY_MESSAGE);
            }
            storedRows.add(written);
            return null;
        }).when(entityManager).persist(any(Setting.class));
    }

    /** Stubs the two configured values the seeding reads. */
    private void stubConfiguredSeedValues() {
        when(properties.popularityThreshold()).thenReturn(CONFIGURED_POPULARITY_THRESHOLD);
        when(properties.responseGenerationDelaySeconds())
                .thenReturn(CONFIGURED_RESPONSE_GENERATION_DELAY);
    }

    /**
     * Captures the rows {@link SettingsService#updateSetting(String, String)} asked the repository to
     * write, which it writes with {@code save}.
     *
     * @param expectedInserts the number of inserts expected
     * @return the captured rows in the order they were inserted
     */
    private List<Setting> seededRows(int expectedInserts) {
        ArgumentCaptor<Setting> inserted = ArgumentCaptor.forClass(Setting.class);
        verify(settingRepository, times(expectedInserts)).saveAndFlush(inserted.capture());
        return inserted.getAllValues();
    }

    /**
     * Captures the rows {@link SettingsService#seedDefaultSettings()} inserted, which it inserts with
     * {@link EntityManager#persist(Object)} — DL-159 — see docs/DECISION_LOG.md.
     *
     * @param expectedWrites the number of inserts expected
     * @return the captured rows in the order they were inserted
     */
    private List<Setting> flushedRows(int expectedWrites) {
        ArgumentCaptor<Setting> written = ArgumentCaptor.forClass(Setting.class);
        verify(entityManager, times(expectedWrites)).persist(written.capture());
        return written.getAllValues();
    }

    /**
     * Builds a transaction manager that runs the seeding insert inline and records the definition each
     * insert was opened with, so the per-key transaction is observable without a database.
     *
     * @return the recording transaction manager
     */
    private PlatformTransactionManager inlineTransactionManager() {
        return new PlatformTransactionManager() {

            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                openedTransactions.add(definition);
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // No transaction is started, so there is nothing to commit.
            }

            @Override
            public void rollback(TransactionStatus status) {
                // No transaction is started, so there is nothing to roll back.
            }
        };
    }

    /**
     * Captures the single row {@link SettingsService#updateSetting(String, String)} asked the
     * repository to write.
     *
     * @return the captured row
     */
    private Setting savedRow() {
        ArgumentCaptor<Setting> written = ArgumentCaptor.forClass(Setting.class);
        verify(settingRepository).save(written.capture());
        return written.getValue();
    }


    // -----------------------------------------------------------------------
    // The one shared stream-rule grammar — DL-257 — see docs/DECISION_LOG.md
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("reports a stream_keywords edit holding no term the rule grammar admits")
    void reportsAStreamKeywordsEditHoldingNoTermTheRuleGrammarAdmits() {
        stubStoredRow(STREAM_KEYWORDS_KEY);

        List<String> warnings = warningsFrom(() ->
                service.updateSetting(STREAM_KEYWORDS_KEY, "\"quoted\",-negated, "));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst())
                .contains("holds 3 term(s) and none can be carried")
                .contains("may not exceed 128 character(s)")
                .contains("letters, digits and ' -_.''")
                .doesNotContain("quoted")
                .doesNotContain("negated");
    }

    @Test
    @DisplayName("reports how many terms of a stream_keywords edit the rule grammar drops")
    void reportsHowManyTermsOfAStreamKeywordsEditTheRuleGrammarDrops() {
        stubStoredRow(STREAM_KEYWORDS_KEY);

        List<String> warnings = warningsFrom(() ->
                service.updateSetting(STREAM_KEYWORDS_KEY, "GPT-4,from:someone,AI coding tool"));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst())
                .contains("holds 3 term(s) of which 1 cannot be carried")
                .doesNotContain("someone");
    }

    @Test
    @DisplayName("counts a term the shared grammar refuses as unusable, whatever its length")
    void countsATermTheSharedGrammarRefusesAsUnusableWhateverItsLength() {
        stubStoredRow(STREAM_KEYWORDS_KEY);
        String pastTheTermBound = "a".repeat(StreamRuleTerms.MAX_TERM_CHARS + 1);

        List<String> warnings = warningsFrom(() ->
                service.updateSetting(STREAM_KEYWORDS_KEY, pastTheTermBound));

        assertThat(StreamRuleTerms.isUsable(pastTheTermBound)).isFalse();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("holds 1 term(s) and none can be carried");
    }

    @Test
    @DisplayName("reports nothing for an edit every term of which the rule grammar admits")
    void reportsNothingForAnEditEveryTermOfWhichTheRuleGrammarAdmits() {
        stubStoredRow(STREAM_KEYWORDS_KEY);

        assertThat(warningsFrom(() ->
                service.updateSetting(STREAM_KEYWORDS_KEY, "GPT-4, AI coding tool ,Copilot")))
                .isEmpty();
    }

    @Test
    @DisplayName("reports nothing for the blank value the seeding writes")
    void reportsNothingForTheBlankValueTheSeedingWrites() {
        stubStoredRow(STREAM_KEYWORDS_KEY);

        assertThat(warningsFrom(() -> service.updateSetting(STREAM_KEYWORDS_KEY, "   "))).isEmpty();
    }

    @Test
    @DisplayName("reports nothing for a key other than stream_keywords")
    void reportsNothingForAKeyOtherThanStreamKeywords() {
        stubStoredRow(TWEET_POPULARITY_THRESHOLD_KEY);

        assertThat(warningsFrom(() ->
                service.updateSetting(TWEET_POPULARITY_THRESHOLD_KEY, "\"quoted\",-negated")))
                .isEmpty();
    }

    /**
     * Runs {@code work} with an appender attached to this service's logger.
     *
     * @param work the call to record
     * @return every {@code WARN} message the call emitted, in the order recorded
     */
    private static List<String> warningsFrom(Runnable work) {
        Logger logger = (Logger) LoggerFactory.getLogger(SettingsService.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            work.run();
        } finally {
            logger.detachAppender(captured);
        }
        return captured.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }


    // -----------------------------------------------------------------------
    // Log-injection guard — DL-149 — see docs/DECISION_LOG.md
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("renders a newline-bearing setting key with its control characters replaced, forging "
            + "no log record")
    void rendersANewlineBearingSettingKeyAsACorrelationToken() {
        String forged = "auto_response\nFORGED record injected by the caller";
        when(settingRepository.findById(forged)).thenReturn(Optional.empty());

        Logger logger = (Logger) LoggerFactory.getLogger(SettingsService.class);
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        Level restored = logger.getLevel();
        logger.setLevel(Level.TRACE);
        try {
            assertThatThrownBy(() -> service.updateSetting(forged, "true"))
                    .isInstanceOf(NotFoundException.class);
        } finally {
            logger.setLevel(restored);
            logger.detachAppender(captured);
        }

        assertThat(captured.list).as("records the rejection emitted").isNotEmpty();
        assertThat(captured.list).allSatisfy(event -> {
            String rendered = event.getFormattedMessage();
            assertThat(rendered).as("rendered record").doesNotContain("\n").doesNotContain("\r");
            assertThat(rendered).as("rendered record")
                    .doesNotContain("\nFORGED")
                    .doesNotContain("\rFORGED");
            assertThat(rendered.chars()).as("rendered record")
                    .allMatch(character -> character >= ' ' && character <= '~');
        });
    }

    /**
     * Collects the declared field types and constructor parameter types of {@link SettingsService},
     * excluding synthetic fields.
     *
     * @return every type the class holds or accepts
     */
    private static List<Class<?>> declaredCollaboratorTypes() {
        List<Class<?>> types = new ArrayList<>();
        for (Field field : SettingsService.class.getDeclaredFields()) {
            if (!field.isSynthetic()) {
                types.add(field.getType());
            }
        }
        for (Constructor<?> constructor : SettingsService.class.getDeclaredConstructors()) {
            types.addAll(List.of(constructor.getParameterTypes()));
        }
        return types;
    }

    /**
     * A {@link PlatformTransactionManager} that records the definition of every transaction opened
     * against it and counts the commits and rollbacks it is asked for. It opens no connection and
     * reaches no database; the callback the template runs executes inline.
     */
    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        /** One entry per transaction opened, in the order they were opened. */
        private final List<TransactionDefinition> openedTransactions = new ArrayList<>();

        /** Number of transactions committed. */
        private int commits;

        /** Number of transactions rolled back. */
        private int rollbacks;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            openedTransactions.add(definition);
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            commits++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rollbacks++;
        }

        /**
         * Returns the definition of every transaction opened against this manager.
         *
         * @return one entry per transaction, in the order they were opened
         */
        private List<TransactionDefinition> definitions() {
            return List.copyOf(openedTransactions);
        }

        /**
         * Returns how many transactions were committed.
         *
         * @return the commit count
         */
        private int commits() {
            return commits;
        }

        /**
         * Returns how many transactions were rolled back.
         *
         * @return the rollback count
         */
        private int rollbacks() {
            return rollbacks;
        }
    }
}
