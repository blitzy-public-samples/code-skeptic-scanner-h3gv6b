package com.codeskeptic.scanner.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Value;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.SettingDto;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.repository.SettingRepository;
import com.codeskeptic.scanner.service.mapper.SettingMapper;

// Net-new (no Python counterpart; backend/app/api/settings.py:L3 imports a service that exists nowhere in the repository) — see docs/DECISION_LOG.md
// Call sites backend/app/api/settings.py:L10,L20 — see docs/DECISION_LOG.md DL-039, DL-040, DL-043
/**
 * Exercises the three operations {@link SettingsService} exposes:
 * {@link SettingsService#getAllSettings()}, {@link SettingsService#updateSetting(String, String)}
 * and {@link SettingsService#seedDefaultSettings()}.
 *
 * <p>Every test drives the service through three mocked collaborators — {@link SettingRepository},
 * {@link SettingMapper} and {@link ScannerProperties} — held by a service instance this class
 * constructs directly. No Spring context is started, no database is opened, no configuration file is
 * read and no credential is resolved.
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

    /** Wire literal of {@code backend/app/api/settings.py:L18}. */
    private static final String NO_VALUE_PROVIDED = "No value provided";

    /** Wire literal of {@code backend/app/api/settings.py:L22}. */
    private static final String SETTING_NOT_FOUND = "Setting not found";

    /** Simple names of the types that can contribute schema. */
    private static final List<String> SCHEMA_CAPABLE_TYPE_NAMES = List.of(
            "EntityManager",
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

    /** Unit under test, holding the three mocked collaborators. */
    private SettingsService service;

    @BeforeEach
    void createService() {
        service = new SettingsService(settingRepository, settingMapper, properties);
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
    @DisplayName("takes its repository mapper and configuration through its only constructor")
    void takesItsRepositoryMapperAndConfigurationThroughItsOnlyConstructor() {
        List<Constructor<?>> constructors = List.of(SettingsService.class.getDeclaredConstructors());

        assertThat(constructors).hasSize(1);
        assertThat(constructors.get(0).getParameterTypes())
                .containsExactly(SettingRepository.class, SettingMapper.class, ScannerProperties.class);
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

        assertThat(savedRows(3)).extracting(Setting::getKey)
                .containsExactlyElementsOf(SEEDED_KEYS);
    }

    @Test
    @DisplayName("seeds a description with every setting it writes")
    void seedsADescriptionWithEverySettingItWrites() {
        stubConfiguredSeedValues();
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        assertThat(savedRows(3)).allSatisfy(row ->
                assertThat(row.getDescription()).isNotNull().isNotBlank());
    }

    @Test
    @DisplayName("seeds the threshold with the configured popularity threshold")
    void seedsTheThresholdWithTheConfiguredPopularityThreshold() {
        stubConfiguredSeedValues();
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        Setting seeded = seededRow(savedRows(3), TWEET_POPULARITY_THRESHOLD_KEY);
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

        Setting seeded = seededRow(savedRows(3), RESPONSE_GENERATION_DELAY_KEY);
        assertThat(seeded.getValue()).isEqualTo(String.valueOf(CONFIGURED_RESPONSE_GENERATION_DELAY));
        assertThat(seeded.getValue()).isNotEqualTo(SOURCE_DECLARED_RESPONSE_GENERATION_DELAY);
        verify(properties).responseGenerationDelaySeconds();
    }

    @Test
    @DisplayName("seeds the keywords with the configured stream terms")
    void seedsTheKeywordsWithTheConfiguredStreamTerms() {
        stubConfiguredSeedValues();
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        Setting seeded = seededRow(savedRows(3), STREAM_KEYWORDS_KEY);
        assertThat(seeded.getValue()).isEqualTo(CONFIGURED_KEYWORDS_VALUE);
        assertThat(seeded.getValue()).contains(CONFIGURED_KEYWORD_ONE, CONFIGURED_KEYWORD_TWO);
        verify(properties).ingestion();
    }

    @Test
    @DisplayName("seeds an empty keywords value when no stream term is configured")
    void seedsAnEmptyKeywordsValueWhenNoStreamTermIsConfigured() {
        when(properties.popularityThreshold()).thenReturn(CONFIGURED_POPULARITY_THRESHOLD);
        when(properties.responseGenerationDelaySeconds()).thenReturn(CONFIGURED_RESPONSE_GENERATION_DELAY);
        when(properties.ingestion()).thenReturn(new ScannerProperties.Ingestion(List.of()));
        stubEveryKeyAbsent();

        service.seedDefaultSettings();

        assertThat(seededRow(savedRows(3), STREAM_KEYWORDS_KEY).getValue()).isEmpty();
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

        verify(settingRepository, never()).save(any(Setting.class));
        verify(settingRepository, times(3)).existsById(anyString());
        verifyNoMoreInteractions(settingRepository);
        verifyNoInteractions(settingMapper);
    }

    @Test
    @DisplayName("writes only the settings whose keys are absent")
    void writesOnlyTheSettingsWhoseKeysAreAbsent() {
        stubConfiguredSeedValues();
        when(settingRepository.existsById(TWEET_POPULARITY_THRESHOLD_KEY)).thenReturn(true);
        when(settingRepository.existsById(RESPONSE_GENERATION_DELAY_KEY)).thenReturn(false);
        when(settingRepository.existsById(STREAM_KEYWORDS_KEY)).thenReturn(false);

        service.seedDefaultSettings();

        assertThat(savedRows(2)).extracting(Setting::getKey)
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
        verify(settingRepository, times(3)).save(any(Setting.class));
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
                CONFIGURED_KEYWORDS_VALUE);
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
        assertThat(savedRows(2)).extracting(Setting::getKey)
                .containsExactly(RESPONSE_GENERATION_DELAY_KEY, STREAM_KEYWORDS_KEY)
                .doesNotContain(TWEET_POPULARITY_THRESHOLD_KEY);
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
     * Stubs the repository to report presence from {@code storedRows} and to append every written row
     * to it.
     *
     * @param storedRows the mutable list standing in for the {@code settings} table
     */
    private void stubRepositoryBackedBy(List<Setting> storedRows) {
        when(settingRepository.existsById(anyString()))
                .thenAnswer(invocation -> holdsKey(storedRows, invocation.getArgument(0)));
        when(settingRepository.save(any(Setting.class))).thenAnswer(invocation -> {
            Setting written = invocation.getArgument(0);
            storedRows.add(written);
            return written;
        });
    }

    /** Stubs the three configured values the seeding reads. */
    private void stubConfiguredSeedValues() {
        when(properties.popularityThreshold()).thenReturn(CONFIGURED_POPULARITY_THRESHOLD);
        when(properties.responseGenerationDelaySeconds())
                .thenReturn(CONFIGURED_RESPONSE_GENERATION_DELAY);
        when(properties.ingestion()).thenReturn(new ScannerProperties.Ingestion(
                List.of(CONFIGURED_KEYWORD_ONE, CONFIGURED_KEYWORD_TWO)));
    }

    /**
     * Captures the rows the repository was asked to write.
     *
     * @param expectedWrites the number of writes expected
     * @return the captured rows in the order they were written
     */
    private List<Setting> savedRows(int expectedWrites) {
        ArgumentCaptor<Setting> written = ArgumentCaptor.forClass(Setting.class);
        verify(settingRepository, times(expectedWrites)).save(written.capture());
        return written.getAllValues();
    }

    /**
     * Captures the single row the repository was asked to write.
     *
     * @return the captured row
     */
    private Setting savedRow() {
        return savedRows(1).get(0);
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
}
