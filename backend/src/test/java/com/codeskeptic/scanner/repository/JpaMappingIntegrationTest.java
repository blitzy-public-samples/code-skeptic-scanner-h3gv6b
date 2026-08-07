package com.codeskeptic.scanner.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.dto.PaginatedResponsesDto;
import com.codeskeptic.scanner.dto.PaginatedTweetsDto;
import com.codeskeptic.scanner.dto.ResponseDto;
import com.codeskeptic.scanner.dto.TweetDto;
import com.codeskeptic.scanner.dto.UpdateResponseRequest;
import com.codeskeptic.scanner.entity.AiTool;
import com.codeskeptic.scanner.entity.Response;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.entity.Tweet;
import com.codeskeptic.scanner.exception.BadRequestException;
import com.codeskeptic.scanner.exception.NotFoundException;
import com.codeskeptic.scanner.repository.ResponseRepository.ResponseRow;
import com.codeskeptic.scanner.service.LlmService;
import com.codeskeptic.scanner.service.ResponseService;
import com.codeskeptic.scanner.service.SentimentAnalysisService;
import com.codeskeptic.scanner.service.TwitterService;
import com.codeskeptic.scanner.service.mapper.ResponseMapper;
import com.codeskeptic.scanner.service.mapper.TweetMapper;
import com.codeskeptic.scanner.util.DelimitedStringListConverter;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.jdbc.connections.internal.UserSuppliedConnectionProviderImpl;
import org.hibernate.stat.Statistics;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration test over the JPA mappings of {@link Tweet}, {@link Response}, {@link AiTool} and
 * {@link Setting}, executed against an in-memory database whose schema is created from the entity
 * annotations by {@code spring.jpa.hibernate.ddl-auto} — see docs/DECISION_LOG.md DL-026.
 *
 * <p>Table, column, constraint and key facts are read from live {@link DatabaseMetaData} on a
 * connection taken from the injected {@link DataSource}. Row values are read through the
 * {@link jakarta.persistence.EntityManager} of the enclosing transaction.
 *
 * <p>The assertions cover:
 *
 * <ul>
 *   <li>a schema of exactly four base tables — {@code tweets}, {@code responses}, {@code ai_tools}
 *       and {@code settings} — with no {@code app_users} table;
 *   <li>exactly the twenty declared physical columns across those tables, nine / five / three /
 *       three, each reported in its declared type family;
 *   <li>sixteen non-primary-key columns that accept null values, no unique constraint beyond each
 *       primary key, no declared column length on any mapped field, and no table-level unique
 *       constraint and no index on any entity;
 *   <li>{@code responses.tweet_id} as the schema's only foreign key, and a reloaded {@code tweets}
 *       row exposing its {@code responses} rows in ascending identifier order with the inverse
 *       association resolved;
 *   <li>{@code media} and {@code ai_tools_mentioned} as single delimited character columns, with no
 *       join table and no foreign key to {@code ai_tools}, over the four write and read cases of
 *       {@link com.codeskeptic.scanner.util.DelimitedStringListConverter};
 *   <li>{@code settings} carrying the physical columns {@code key} and {@code value}, with
 *       {@code key} as its sole, ungenerated primary key;
 *   <li>the declared and inherited operations of the four repositories.
 * </ul>
 */
// Verifies the port of backend/app/db/models.py (faithful port) — see docs/DECISION_LOG.md
// The per-call create_engine and sessionmaker at backend/app/db/database.py:L5-13 are replaced by
// the Spring Data repositories exercised here — see docs/DECISION_LOG.md
@DataJpaTest
@ActiveProfiles("test")
class JpaMappingIntegrationTest {
    // backend/app/db/models.py:L8,L21,L33,L40
    private static final String TWEETS_TABLE = "tweets";
    private static final String RESPONSES_TABLE = "responses";
    private static final String AI_TOOLS_TABLE = "ai_tools";
    private static final String SETTINGS_TABLE = "settings";

    private static final Set<String> MAPPED_TABLES =
            Set.of(TWEETS_TABLE, RESPONSES_TABLE, AI_TOOLS_TABLE, SETTINGS_TABLE);

    // backend/app/db/models.py:L10-18
    private static final List<String> TWEETS_COLUMNS = List.of(
            "id", "content", "like_count", "created_at", "doubt_rating", "media", "quoted_tweet_id",
            "user_id", "ai_tools_mentioned");

    // backend/app/db/models.py:L23-27
    private static final List<String> RESPONSES_COLUMNS =
            List.of("id", "content", "generated_at", "is_approved", "tweet_id");

    // backend/app/db/models.py:L35-37
    private static final List<String> AI_TOOLS_COLUMNS = List.of("id", "name", "description");

    // backend/app/db/models.py:L42-44
    private static final List<String> SETTINGS_COLUMNS = List.of("key", "value", "description");

    // backend/app/db/models.py:L10,L23,L35,L42 declare the primary keys; every remaining column is
    // listed here.
    private static final Map<String, List<String>> NON_PRIMARY_KEY_COLUMNS = Map.of(
            TWEETS_TABLE, List.of("content", "like_count", "created_at", "doubt_rating", "media",
                    "quoted_tweet_id", "user_id", "ai_tools_mentioned"),
            RESPONSES_TABLE, List.of("content", "generated_at", "is_approved", "tweet_id"),
            AI_TOOLS_TABLE, List.of("name", "description"),
            SETTINGS_TABLE, List.of("value", "description"));

    private static final int NON_PRIMARY_KEY_COLUMN_COUNT = 16;
    private static final int MAPPED_COLUMN_COUNT = 20;

    private static final int COLUMN_ANNOTATED_FIELD_COUNT = 19;

    private static final int JOIN_COLUMN_ANNOTATED_FIELD_COUNT = 1;

    /**
     * Value {@code jakarta.persistence.Column#length()} carries when a mapping declares no length.
     * Every mapped column leaves the facet undeclared, so every mapping reports this value — DL-068 —
     * see docs/DECISION_LOG.md.
     */
    private static final int UNDECLARED_LENGTH_FACET = 255;

    /**
     * JDBC type code the ten wide character columns state, so each dialect renders its widest
     * character type that needs no declared capacity — DL-068 — see docs/DECISION_LOG.md.
     */
    private static final int WIDE_CHARACTER_JDBC_TYPE_CODE = SqlTypes.LONGVARCHAR;

    /**
     * Capacity H2 2.3 and PostgreSQL 16 report for a column stating
     * {@link #WIDE_CHARACTER_JDBC_TYPE_CODE}. It is the vendor's own widest capacity-free character
     * rendering, not a capacity this mapping states — DL-068 — see docs/DECISION_LOG.md.
     */
    private static final int WIDE_CHARACTER_CAPACITY = 32_600;

    /**
     * Capacity the {@code settings} primary key is rendered with on every supported vendor, so the
     * column is indexable on all three — DL-069 — see docs/DECISION_LOG.md.
     */
    private static final int PRIMARY_KEY_CAPACITY = 255;

    /**
     * A value length beyond the capacity an undeclared length renders, used to store a value the
     * withdrawn mapping would have rejected — DL-068 — see docs/DECISION_LOG.md.
     */
    private static final int BEYOND_UNDECLARED_LENGTH_FACET = 1_000;

    /**
     * Every character column the source declares as a bare {@code Column(String)} —
     * backend/app/db/models.py:L11,L15-18 (tweets), :L24 (responses), :L36-37 (ai_tools) and
     * :L42-44 (settings). All eleven are mapped without a length facet — DL-068 — see
     * docs/DECISION_LOG.md.
     */
    private static final Map<String, List<String>> SOURCE_CHARACTER_COLUMNS = Map.of(
            TWEETS_TABLE,
            List.of("content", "media", "quoted_tweet_id", "user_id", "ai_tools_mentioned"),
            RESPONSES_TABLE, List.of("content"),
            AI_TOOLS_TABLE, List.of("name", "description"),
            SETTINGS_TABLE, List.of("key", "value", "description"));

    /**
     * The ten character columns that state {@link #WIDE_CHARACTER_JDBC_TYPE_CODE}. It is
     * {@link #SOURCE_CHARACTER_COLUMNS} without the {@code settings} primary key — DL-068, DL-069 —
     * see docs/DECISION_LOG.md.
     */
    private static final Map<String, List<String>> WIDE_CHARACTER_COLUMNS = Map.of(
            TWEETS_TABLE,
            List.of("content", "media", "quoted_tweet_id", "user_id", "ai_tools_mentioned"),
            RESPONSES_TABLE, List.of("content"),
            AI_TOOLS_TABLE, List.of("name", "description"),
            SETTINGS_TABLE, List.of("value", "description"));

    private static final int WIDE_CHARACTER_COLUMN_COUNT = 10;

    /**
     * Vendor character types that hold a large object and not a string. No generated statement
     * may declare one: Hibernate's own type system treats such a column as not string-like,
     * and MySQL cannot index one without a prefix length — DL-068, DL-069 — see
     * docs/DECISION_LOG.md.
     */
    private static final List<String> LARGE_OBJECT_CHARACTER_TYPES =
            List.of("clob", "longtext", "mediumtext", "tinytext", "character large object", " oid");

    /**
     * Vendor character type names that require a declared capacity before MySQL accepts them. A
     * generated MySQL statement naming one of these without a parenthesised length is not valid
     * MySQL DDL — DL-068 — see docs/DECISION_LOG.md.
     */
    private static final List<String> CAPACITY_REQUIRING_TYPES = List.of("varchar", "char");

    /** Count of the character columns backend/app/db/models.py declares as {@code Column(String)}. */
    private static final int SOURCE_CHARACTER_COLUMN_COUNT = 11;

    /** The {@code settings} primary-key column — DL-061 — see docs/DECISION_LOG.md. */
    private static final String PRIMARY_KEY_CHARACTER_COLUMN = "key";

    private static final List<Class<?>> MAPPED_ENTITIES =
            List.of(Tweet.class, Response.class, AiTool.class, Setting.class);

    private static final String PUBLIC_SCHEMA = "PUBLIC";
    private static final Set<String> BASE_TABLE_TYPES = Set.of("TABLE", "BASE TABLE");

    private static final String COLUMN_NAME = "COLUMN_NAME";
    private static final String COLUMN_SIZE = "COLUMN_SIZE";
    private static final String DATA_TYPE = "DATA_TYPE";
    private static final String TYPE_NAME = "TYPE_NAME";
    private static final String NULLABLE = "NULLABLE";
    private static final String IS_NULLABLE = "IS_NULLABLE";
    private static final String IS_AUTOINCREMENT = "IS_AUTOINCREMENT";

    /**
     * The type family of the three surrogate keys and the one foreign key, all four of which
     * backend/app/db/models.py:L10,L23,L27,L35 declares {@code Integer}. A 64-bit identifier column
     * fails this expectation.
     */
    private static final Set<Integer> IDENTIFIER_TYPES = Set.of(Types.INTEGER);

    private static final Set<Integer> INTEGER_TYPES = Set.of(Types.INTEGER);

    private static final Set<Integer> TIMESTAMP_TYPES = Set.of(Types.TIMESTAMP);

    private static final Set<Integer> FLOATING_POINT_TYPES =
            Set.of(Types.DOUBLE, Types.FLOAT, Types.REAL);

    private static final Set<Integer> BOOLEAN_TYPES = Set.of(Types.BOOLEAN, Types.BIT);

    private static final Set<Integer> CHARACTER_TYPES = Set.of(Types.VARCHAR,
            Types.LONGVARCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB);

    private static final Map<String, Map<String, Set<Integer>>> MAPPED_COLUMN_TYPES = Map.of(
            TWEETS_TABLE, Map.of(
                    "id", IDENTIFIER_TYPES,
                    "content", CHARACTER_TYPES,
                    "like_count", INTEGER_TYPES,
                    "created_at", TIMESTAMP_TYPES,
                    "doubt_rating", FLOATING_POINT_TYPES,
                    "media", CHARACTER_TYPES,
                    "quoted_tweet_id", CHARACTER_TYPES,
                    "user_id", CHARACTER_TYPES,
                    "ai_tools_mentioned", CHARACTER_TYPES),
            RESPONSES_TABLE, Map.of(
                    "id", IDENTIFIER_TYPES,
                    "content", CHARACTER_TYPES,
                    "generated_at", TIMESTAMP_TYPES,
                    "is_approved", BOOLEAN_TYPES,
                    "tweet_id", IDENTIFIER_TYPES),
            AI_TOOLS_TABLE, Map.of(
                    "id", IDENTIFIER_TYPES,
                    "name", CHARACTER_TYPES,
                    "description", CHARACTER_TYPES),
            SETTINGS_TABLE, Map.of(
                    "key", CHARACTER_TYPES,
                    "value", CHARACTER_TYPES,
                    "description", CHARACTER_TYPES));

    private static final String SCHEMA_GENERATION_SCRIPTS_ACTION =
            "jakarta.persistence.schema-generation.scripts.action";

    private static final String SCHEMA_GENERATION_SCRIPTS_CREATE_TARGET =
            "jakarta.persistence.schema-generation.scripts.create-target";

    private static final Path GENERATED_SCRIPT_DIRECTORY = Path.of("target", "generated-schema");

    /**
     * The physical column type each supported vendor must generate for every one of the twenty
     * mapped columns, stated as the fragment the {@code create table} statement must contain.
     *
     * <p>The expectations are the physical shape of the source declarations at
     * backend/app/db/models.py:L10-18, :L23-28, :L35-37 and :L42-44: a 32-bit column for every
     * {@code Column(Integer)} including the {@code responses.tweet_id} foreign key, a zone-free
     * timestamp for every {@code Column(DateTime)}, a 53-bit binary floating-point column for
     * {@code Column(Float)}, a boolean column for {@code Column(Boolean)}, and — for every
     * {@code Column(String)} but the {@code settings} primary key — the widest character type the
     * dialect renders without a declared capacity: {@code text} on MySQL and
     * {@code varchar(32600)} on PostgreSQL and H2. The {@code settings} primary key is rendered
     * {@code varchar(255)} on all three: MySQL cannot index a {@code text} column without a
     * prefix length.
     *
     * <p>DL-068, DL-069, DL-166 — see docs/DECISION_LOG.md.
     */
    private static final Map<String, Map<String, List<String>>> EXPECTED_GENERATED_COLUMN_TYPES =
            Map.of(
                    "org.hibernate.dialect.H2Dialect", Map.of(
                            TWEETS_TABLE, List.of("id integer", "content varchar(32600)",
                                    "like_count integer", "created_at timestamp(6)",
                                    "doubt_rating float(53)", "media varchar(32600)",
                                    "quoted_tweet_id varchar(32600)", "user_id varchar(32600)",
                                    "ai_tools_mentioned varchar(32600)"),
                            RESPONSES_TABLE, List.of("id integer", "content varchar(32600)",
                                    "generated_at timestamp(6)", "is_approved boolean",
                                    "tweet_id integer"),
                            AI_TOOLS_TABLE,
                            List.of("id integer", "name varchar(32600)",
                                    "description varchar(32600)"),
                            SETTINGS_TABLE, List.of("\"key\" varchar(255)",
                                    "\"value\" varchar(32600)", "description varchar(32600)")),
                    "org.hibernate.dialect.PostgreSQLDialect", Map.of(
                            TWEETS_TABLE, List.of("id integer", "content varchar(32600)",
                                    "like_count integer", "created_at timestamp(6)",
                                    "doubt_rating float(53)", "media varchar(32600)",
                                    "quoted_tweet_id varchar(32600)", "user_id varchar(32600)",
                                    "ai_tools_mentioned varchar(32600)"),
                            RESPONSES_TABLE, List.of("id integer", "content varchar(32600)",
                                    "generated_at timestamp(6)", "is_approved boolean",
                                    "tweet_id integer"),
                            AI_TOOLS_TABLE,
                            List.of("id integer", "name varchar(32600)",
                                    "description varchar(32600)"),
                            SETTINGS_TABLE, List.of("\"key\" varchar(255)",
                                    "\"value\" varchar(32600)", "description varchar(32600)")),
                    "org.hibernate.dialect.MySQLDialect", Map.of(
                            TWEETS_TABLE, List.of("id integer", "content text",
                                    "like_count integer", "created_at datetime(6)",
                                    "doubt_rating float(53)", "media text",
                                    "quoted_tweet_id text", "user_id text",
                                    "ai_tools_mentioned text"),
                            RESPONSES_TABLE, List.of("id integer", "content text",
                                    "generated_at datetime(6)", "is_approved bit",
                                    "tweet_id integer"),
                            AI_TOOLS_TABLE,
                            List.of("id integer", "name text", "description text"),
                            SETTINGS_TABLE, List.of("`key` varchar(255)",
                                    "`value` text", "description text")));

    private static final String DELIMITER = ",";
    private static final double TOLERANCE = 1.0e-9;

    private static final int BACKLOG_ROW_COUNT = 55;

    /**
     * Row bound of one candidate batch in that assertion. It divides {@link #BACKLOG_ROW_COUNT} into
     * more than one batch, so the drain is asserted across a batch boundary — DL-248.
     */
    private static final int BACKLOG_BATCH_ROWS = 20;

    /** Rows stored for the whole-table page read of the listing assertions — DL-217. */
    private static final int LISTING_ROW_COUNT = 7;

    /** Longest {@link #awaitTermination(ExecutorService)} waits for a test pool — DL-273. */
    private static final long EXECUTOR_TERMINATION_SECONDS = 5L;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private TweetRepository tweetRepository;

    @Autowired
    private ResponseRepository responseRepository;

    @Autowired
    private AiToolRepository aiToolRepository;

    @Autowired
    private SettingRepository settingRepository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // Ported from backend/app/db/models.py:L8,L21,L33,L40 (faithful port) — see
    // docs/DECISION_LOG.md
    // The absence of an app_users table is asserted here — DL-020 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("the schema holds exactly the four mapped tables and no app_users table")
    void schemaHoldsExactlyTheFourMappedTables() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            Set<String> storedTables = readStoredTableNames(metaData).keySet();

            assertThat(storedTables)
                    .as("base tables of the %s schema", PUBLIC_SCHEMA)
                    .containsExactlyInAnyOrderElementsOf(normaliseAll(MAPPED_TABLES));
            assertThat(storedTables).as("app_users table").doesNotContain(normalise("app_users"));

            try (ResultSet appUsers = metaData.getTables(null, null, "APP_USERS", null)) {
                assertThat(appUsers.next()).as("any app_users table in any schema").isFalse();
            }
        }
    }

    // Ported from backend/app/db/models.py:L10-18 (faithful port) — see docs/DECISION_LOG.md
    @Test
    @DisplayName("the tweets table exposes exactly its nine physical columns")
    void tweetsTableExposesExactlyItsNineColumns() throws SQLException {
        assertThat(readPhysicalColumns(TWEETS_TABLE))
                .as("physical columns of table %s", TWEETS_TABLE)
                .hasSize(9)
                .containsExactlyInAnyOrderElementsOf(normaliseAll(TWEETS_COLUMNS));
    }

    // Ported from backend/app/db/models.py:L23-27 (faithful port) — see docs/DECISION_LOG.md
    @Test
    @DisplayName("the responses table exposes exactly its five physical columns, tweet_id included")
    void responsesTableExposesExactlyItsFiveColumns() throws SQLException {
        assertThat(readPhysicalColumns(RESPONSES_TABLE))
                .as("physical columns of table %s", RESPONSES_TABLE)
                .hasSize(5)
                .contains(normalise("tweet_id"))
                .containsExactlyInAnyOrderElementsOf(normaliseAll(RESPONSES_COLUMNS));
    }

    // Ported from backend/app/db/models.py:L35-37 (faithful port) — see docs/DECISION_LOG.md
    @Test
    @DisplayName("the ai_tools table exposes exactly its three physical columns")
    void aiToolsTableExposesExactlyItsThreeColumns() throws SQLException {
        assertThat(readPhysicalColumns(AI_TOOLS_TABLE))
                .as("physical columns of table %s", AI_TOOLS_TABLE)
                .hasSize(3)
                .containsExactlyInAnyOrderElementsOf(normaliseAll(AI_TOOLS_COLUMNS));
    }

    // Ported from backend/app/db/models.py:L42-44 (faithful port) — see docs/DECISION_LOG.md
    @Test
    @DisplayName("the settings table exposes exactly its three physical columns")
    void settingsTableExposesExactlyItsThreeColumns() throws SQLException {
        assertThat(readPhysicalColumns(SETTINGS_TABLE))
                .as("physical columns of table %s", SETTINGS_TABLE)
                .hasSize(3)
                .containsExactlyInAnyOrderElementsOf(normaliseAll(SETTINGS_COLUMNS));
    }

    // Ported from backend/app/db/models.py:L10-18,L23-27,L35-37,L42-44 (faithful port) — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("the four mapped tables expose exactly twenty physical columns in total")
    void theFourMappedTablesExposeExactlyTwentyPhysicalColumns() throws SQLException {
        int physicalColumns = 0;
        for (String logicalTable : MAPPED_TABLES) {
            physicalColumns += readPhysicalColumns(logicalTable).size();
        }
        assertThat(physicalColumns).as("physical columns across the four mapped tables")
                .isEqualTo(MAPPED_COLUMN_COUNT);
    }

    // Ported from backend/app/db/models.py:L10-18,L23-27,L35-37,L42-44 (faithful port) — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("every mapped column is reported in the type family of its source declaration")
    void everyMappedColumnIsReportedInItsDeclaredTypeFamily() throws SQLException {
        int inspected = 0;
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            for (Map.Entry<String, Map<String, Set<Integer>>> table
                    : MAPPED_COLUMN_TYPES.entrySet()) {
                String logicalTable = table.getKey();
                String storedTable = storedTableName(metaData, logicalTable);
                Map<String, Map<String, Object>> columns = readColumns(metaData, storedTable);
                for (Map.Entry<String, Set<Integer>> expected : table.getValue().entrySet()) {
                    Map<String, Object> attributes = columns.get(normalise(expected.getKey()));
                    assertThat(attributes)
                            .as("metadata of column %s.%s", logicalTable, expected.getKey())
                            .isNotNull();
                    assertThat(attributes.get(DATA_TYPE))
                            .as("java.sql.Types code of column %s.%s (reported as %s)",
                                    logicalTable, expected.getKey(), attributes.get(TYPE_NAME))
                            .isIn(expected.getValue());
                    inspected++;
                }
            }
        }
        assertThat(inspected).as("columns whose type family was inspected")
                .isEqualTo(MAPPED_COLUMN_COUNT);
    }

    // Ported from backend/app/db/models.py:L10-18,L23-27,L35-37,L42-44 (faithful port) — see
    // docs/DECISION_LOG.md
    // backend/app/db/models.py declares each of those columns as a bare Column(<Type>): none
    // carries nullable=False.
    @Test
    @DisplayName("all sixteen non-primary-key columns accept null values")
    void allNonPrimaryKeyColumnsAcceptNullValues() throws SQLException {
        int inspected = 0;
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            for (Map.Entry<String, List<String>> table : NON_PRIMARY_KEY_COLUMNS.entrySet()) {
                String logicalTable = table.getKey();
                String storedTable = storedTableName(metaData, logicalTable);
                Map<String, Map<String, Object>> columns = readColumns(metaData, storedTable);
                for (String columnName : table.getValue()) {
                    Map<String, Object> attributes = columns.get(normalise(columnName));
                    assertThat(attributes).as("metadata of column %s.%s", logicalTable, columnName)
                            .isNotNull();
                    assertThat(attributes.get(IS_NULLABLE))
                            .as("IS_NULLABLE of column %s.%s", logicalTable, columnName)
                            .isEqualTo("YES");
                    assertThat(attributes.get(NULLABLE))
                            .as("NULLABLE of column %s.%s", logicalTable, columnName)
                            .isEqualTo(DatabaseMetaData.columnNullable);
                    inspected++;
                }
            }
        }
        assertThat(inspected).as("non-primary-key columns whose nullability was inspected")
                .isEqualTo(NON_PRIMARY_KEY_COLUMN_COUNT);
    }

    // The wire form of a row the schema accepts — DL-080 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("a stored responses row carrying a null tweet_id renders that member as null rather "
            + "than dereferencing the association, on its own and inside a list")
    void storedResponseCarryingNoTweetRendersANullTweetId() {
        Response orphan = new Response();
        orphan.setContent("orphan-response");
        orphan.setGeneratedAt(LocalDateTime.of(2026, 8, 5, 12, 0));
        orphan.setIsApproved(Boolean.FALSE);
        Response saved = responseRepository.save(orphan);

        entityManager.flush();
        entityManager.clear();

        Response reloaded = responseRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTweet()).as("association of the stored row").isNull();

        ResponseMapper mapper = new ResponseMapper();
        ResponseDto rendered = mapper.toDto(reloaded);
        assertThat(rendered.id()).as("identifier the wire form carries")
                .isEqualTo(String.valueOf(saved.getId()));
        assertThat(rendered.tweetId()).as("association the wire form carries").isNull();
        assertThat(rendered.content()).as("content the wire form carries")
                .isEqualTo("orphan-response");
        assertThat(mapper.toDtoList(List.of(reloaded)))
                .as("list conversion that holds such a row")
                .singleElement()
                .extracting(ResponseDto::tweetId)
                .isNull();
    }

    // The wire form of a row the schema accepts — DL-080 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("a stored responses row carrying null in every nullable column renders every one of "
            + "those members as null without an unboxing failure")
    void storedResponseCarryingOnlyItsKeyRendersEveryOtherMemberAsNull() {
        Response bare = new Response();
        Response saved = responseRepository.save(bare);

        entityManager.flush();
        entityManager.clear();

        Response reloaded = responseRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getId()).as("identifier of the stored row").isEqualTo(saved.getId());

        ResponseDto rendered = new ResponseMapper().toDto(reloaded);
        assertThat(rendered.id()).isEqualTo(String.valueOf(saved.getId()));
        assertThat(rendered.content()).isNull();
        assertThat(rendered.generatedAt()).isNull();
        assertThat(rendered.isApproved()).isNull();
        assertThat(rendered.tweetId()).isNull();
    }

    // The wire form of a row the schema accepts — DL-080 — see docs/DECISION_LOG.md
    // This is the shape an operator, a migration or an external writer leaves behind, and one such row
    // must not make the page that holds it unanswerable.
    @Test
    @DisplayName("a stored tweets row carrying null in every nullable column renders every one of "
            + "those members as null without an unboxing failure")
    void storedTweetCarryingOnlyItsKeyRendersEveryOtherMemberAsNull() {
        Tweet sparse = new Tweet();
        Tweet saved = tweetRepository.save(sparse);

        entityManager.flush();
        entityManager.clear();

        Tweet reloaded = tweetRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getId()).as("identifier of the stored row").isEqualTo(saved.getId());

        TweetMapper mapper = new TweetMapper();
        TweetDto rendered = mapper.toDto(reloaded);
        assertThat(rendered.id()).isEqualTo(String.valueOf(saved.getId()));
        assertThat(rendered.content()).isNull();
        assertThat(rendered.likeCount()).isNull();
        assertThat(rendered.createdAt()).isNull();
        assertThat(rendered.doubtRating()).isNull();
        assertThat(rendered.quotedTweetId()).isNull();
        assertThat(rendered.userId()).isNull();
        assertThat(rendered.media()).as("list component of an empty column").isEmpty();
        assertThat(rendered.aiToolsMentioned()).as("list component of an empty column").isEmpty();
        assertThat(mapper.toDtoList(List.of(reloaded)))
                .as("list conversion that holds such a row")
                .singleElement()
                .extracting(TweetDto::doubtRating)
                .isNull();
    }

    // Ported from backend/app/db/models.py:L10-18,L23-27,L35-37,L42-44 (faithful port) — see
    // docs/DECISION_LOG.md
    // backend/app/db/models.py declares each of those columns as a bare Column(<Type>): none
    // carries unique=True.
    @Test
    @DisplayName("no mapped table declares a unique constraint beyond its primary key")
    void noMappedTableDeclaresAUniqueConstraintBeyondItsPrimaryKey() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            for (String logicalTable : MAPPED_TABLES) {
                String storedTable = storedTableName(metaData, logicalTable);
                Set<String> primaryKeyColumns =
                        new LinkedHashSet<>(readPrimaryKeyColumns(metaData, storedTable));
                Map<String, Set<String>> uniqueIndexes = readUniqueIndexes(metaData, storedTable);

                assertThat(uniqueIndexes).as("unique indexes of table %s", logicalTable).hasSize(1);
                assertThat(uniqueIndexes.values().iterator().next())
                        .as("columns of the only unique index of table %s", logicalTable)
                        .containsExactlyInAnyOrderElementsOf(primaryKeyColumns);
            }

            List<String> constraints = readTableConstraints(connection);
            for (String logicalTable : MAPPED_TABLES) {
                assertThat(constraints)
                        .as("UNIQUE constraints of table %s", logicalTable)
                        .doesNotContain(normalise(logicalTable) + "|UNIQUE");
            }
        }
    }

    // Ported from backend/app/db/models.py:L10-18,L23-28,L35-37,L42-44 (faithful port) — see
    // docs/DECISION_LOG.md
    // backend/app/db/models.py declares each of those columns as a bare Column(<Type>): none
    // carries nullable=False, unique=True or a length argument, and neither does any mapped field.
    // No field states a columnDefinition; the ten wide character fields state a JDBC type code and
    // nothing else — DL-068, DL-069 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("every mapped entity field leaves nullability, uniqueness, length and column "
            + "definition undeclared")
    void everyMappedEntityFieldLeavesTheColumnDefaultsInPlace() {
        int columnFields = 0;
        int joinColumnFields = 0;
        int characterFields = 0;

        for (Class<?> entityType : MAPPED_ENTITIES) {
            for (Field field : mappedFields(entityType, Column.class)) {
                Column column = field.getAnnotation(Column.class);
                String location = entityType.getSimpleName() + "#" + field.getName();

                assertThat(column.nullable()).as("@Column#nullable of %s", location).isTrue();
                assertThat(column.unique()).as("@Column#unique of %s", location).isFalse();
                assertThat(column.length()).as("@Column#length of %s", location)
                        .isEqualTo(UNDECLARED_LENGTH_FACET);
                assertThat(column.columnDefinition())
                        .as("@Column#columnDefinition of %s", location)
                        .isEmpty();
                if (isCharacterMapped(field)) {
                    characterFields++;
                }
                columnFields++;
            }

            for (Field field : mappedFields(entityType, JoinColumn.class)) {
                JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);
                String location = entityType.getSimpleName() + "#" + field.getName();

                assertThat(joinColumn.nullable()).as("@JoinColumn#nullable of %s", location)
                        .isTrue();
                assertThat(joinColumn.unique()).as("@JoinColumn#unique of %s", location).isFalse();
                joinColumnFields++;
            }
        }

        assertThat(columnFields).as("fields carrying @Column")
                .isEqualTo(COLUMN_ANNOTATED_FIELD_COUNT);
        assertThat(joinColumnFields).as("fields carrying @JoinColumn")
                .isEqualTo(JOIN_COLUMN_ANNOTATED_FIELD_COUNT);
        assertThat(columnFields + joinColumnFields).as("mapped columns across the four entities")
                .isEqualTo(MAPPED_COLUMN_COUNT);
        assertThat(characterFields).as("character-mapped fields across the four entities")
                .isEqualTo(SOURCE_CHARACTER_COLUMN_COUNT);
    }

    // The ten wide character columns state a JDBC type code; the settings primary key and every
    // non-character column state none — DL-068, DL-069 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("the ten wide character columns state the wide character JDBC type code and no "
            + "other mapped field states one")
    void onlyTheWideCharacterColumnsStateAJdbcTypeCode() throws NoSuchFieldException {
        List<String> wideColumnNames = WIDE_CHARACTER_COLUMNS.values().stream()
                .flatMap(List::stream).toList();
        int annotatedFields = 0;

        for (Class<?> entityType : MAPPED_ENTITIES) {
            for (Field field : mappedFields(entityType, Column.class)) {
                String location = entityType.getSimpleName() + "#" + field.getName();
                JdbcTypeCode typeCode = field.getAnnotation(JdbcTypeCode.class);
                String columnName = field.getAnnotation(Column.class).name().replace("\"", "");

                if (wideColumnNames.contains(columnName)) {
                    assertThat(typeCode).as("@JdbcTypeCode of %s", location).isNotNull();
                    assertThat(typeCode.value()).as("@JdbcTypeCode value of %s", location)
                            .isEqualTo(WIDE_CHARACTER_JDBC_TYPE_CODE);
                    annotatedFields++;
                } else {
                    assertThat(typeCode).as("@JdbcTypeCode of %s", location).isNull();
                }
            }
        }

        assertThat(annotatedFields).as("columns stating the wide character JDBC type code")
                .isEqualTo(WIDE_CHARACTER_COLUMN_COUNT);
        assertThat(Setting.class.getDeclaredField("key").getAnnotation(JdbcTypeCode.class))
                .as("@JdbcTypeCode of the settings primary key").isNull();
    }

    private static boolean isCharacterMapped(Field field) {
        return field.getType() == String.class || field.getType() == List.class;
    }

    // Ported from backend/app/db/models.py:L11,L15-18,L24,L36-37,L42-44 (faithful port) — DL-068,
    // DL-069 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("every character column the source declares is generated in the character type "
            + "family, the ten wide ones at the vendor's capacity-free width and the primary key at "
            + "the indexable width")
    void everySourceCharacterColumnIsGeneratedInTheCharacterFamily() throws SQLException {
        List<String> wideColumnNames = WIDE_CHARACTER_COLUMNS.values().stream()
                .flatMap(List::stream).toList();
        int assertedColumns = 0;
        int assertedWideColumns = 0;

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            for (Map.Entry<String, List<String>> table : SOURCE_CHARACTER_COLUMNS.entrySet()) {
                for (String columnName : table.getValue()) {
                    Map<String, Object> attributes =
                            readColumn(metaData, table.getKey(), columnName);

                    assertThat(attributes.get(DATA_TYPE))
                            .as("java.sql.Types code of column %s.%s (reported as %s)",
                                    table.getKey(), columnName, attributes.get(TYPE_NAME))
                            .isIn(CHARACTER_TYPES);
                    if (wideColumnNames.contains(columnName)) {
                        assertThat((int) attributes.get(COLUMN_SIZE))
                                .as("generated capacity of wide column %s.%s (reported as %s)",
                                        table.getKey(), columnName, attributes.get(TYPE_NAME))
                                .isNotEqualTo(UNDECLARED_LENGTH_FACET)
                                .isEqualTo(WIDE_CHARACTER_CAPACITY);
                        assertedWideColumns++;
                    } else {
                        assertThat((int) attributes.get(COLUMN_SIZE))
                                .as("generated capacity of primary-key column %s.%s (reported as %s)",
                                        table.getKey(), columnName, attributes.get(TYPE_NAME))
                                .isEqualTo(PRIMARY_KEY_CAPACITY);
                    }
                    assertedColumns++;
                }
            }
        }

        assertThat(assertedColumns).as("character columns the source declares")
                .isEqualTo(SOURCE_CHARACTER_COLUMN_COUNT);
        assertThat(assertedWideColumns).as("wide character columns")
                .isEqualTo(WIDE_CHARACTER_COLUMN_COUNT);
    }

    // The settings primary key is indexable on every supported vendor and the wide value column
    // holds far more than an undeclared length would have rendered — DL-068, DL-069 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("the settings row stores and reloads a key at the indexable capacity and a value "
            + "past the capacity an undeclared length would have rendered")
    void theSettingsRowStoresAKeyAtCapacityAndAValuePastTheUndeclaredLength() {
        String fullLengthKey = "k".repeat(PRIMARY_KEY_CAPACITY);
        String longValue = "v".repeat(BEYOND_UNDECLARED_LENGTH_FACET);

        settingRepository.save(
                new Setting(fullLengthKey, longValue, "A value past the annotation default"));
        entityManager.flush();
        entityManager.clear();

        Optional<Setting> reloaded = settingRepository.findById(fullLengthKey);
        assertThat(reloaded).as("row stored under a key at the indexable capacity").isPresent();
        assertThat(reloaded.get().getKey()).as("stored key")
                .hasSize(PRIMARY_KEY_CAPACITY).isEqualTo(fullLengthKey);
        assertThat(reloaded.get().getValue()).as("stored value")
                .hasSize(BEYOND_UNDECLARED_LENGTH_FACET).isEqualTo(longValue);
    }

    // Ported from backend/app/db/models.py:L7-8,L20-21,L32-33,L39-40 (faithful port) — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("no mapped entity declares a table-level unique constraint or an index")
    void noMappedEntityDeclaresATableLevelUniqueConstraintOrAnIndex() {
        for (Class<?> entityType : MAPPED_ENTITIES) {
            Table table = entityType.getAnnotation(Table.class);
            assertThat(table).as("@Table of %s", entityType.getSimpleName()).isNotNull();
            assertThat(table.uniqueConstraints())
                    .as("@Table#uniqueConstraints of %s", entityType.getSimpleName()).isEmpty();
            assertThat(table.indexes()).as("@Table#indexes of %s", entityType.getSimpleName())
                    .isEmpty();
        }
    }

    // Ported from backend/app/db/models.py:L27-28,L30 (faithful port) — see docs/DECISION_LOG.md
    @Test
    @DisplayName("responses.tweet_id is the schema's only foreign key and targets tweets.id")
    void responsesTweetIdIsTheSchemasOnlyForeignKey() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            String responsesTable = storedTableName(metaData, RESPONSES_TABLE);
            String tweetsTable = storedTableName(metaData, TWEETS_TABLE);
            String aiToolsTable = storedTableName(metaData, AI_TOOLS_TABLE);
            String settingsTable = storedTableName(metaData, SETTINGS_TABLE);

            assertThat(readImportedKeys(metaData, responsesTable))
                    .as("foreign keys of table %s", RESPONSES_TABLE)
                    .containsExactly(normalise("tweet_id") + "->" + normalise(TWEETS_TABLE) + "."
                            + normalise("id"));
            assertThat(readImportedKeys(metaData, tweetsTable))
                    .as("foreign keys of table %s", TWEETS_TABLE).isEmpty();
            assertThat(readImportedKeys(metaData, aiToolsTable))
                    .as("foreign keys of table %s", AI_TOOLS_TABLE).isEmpty();
            assertThat(readImportedKeys(metaData, settingsTable))
                    .as("foreign keys of table %s", SETTINGS_TABLE).isEmpty();

            assertThat(readExportedKeys(metaData, aiToolsTable))
                    .as("foreign keys referencing table %s", AI_TOOLS_TABLE).isEmpty();
            assertThat(readExportedKeys(metaData, settingsTable))
                    .as("foreign keys referencing table %s", SETTINGS_TABLE).isEmpty();
            assertThat(readExportedKeys(metaData, tweetsTable))
                    .as("foreign keys referencing table %s", TWEETS_TABLE)
                    .containsExactly(normalise(RESPONSES_TABLE) + "." + normalise("tweet_id"));

            assertThat(readTableConstraints(connection))
                    .as("FOREIGN KEY constraints of the %s schema", PUBLIC_SCHEMA)
                    .containsOnlyOnce(normalise(RESPONSES_TABLE) + "|FOREIGN KEY")
                    .filteredOn(constraint -> constraint.endsWith("|FOREIGN KEY"))
                    .hasSize(1);
        }
    }

    // Ported from backend/app/db/models.py:L30 (faithful port) — see docs/DECISION_LOG.md
    @Test
    @DisplayName("a reloaded tweet exposes its responses in ascending identifier order with the "
            + "inverse association resolved")
    void reloadedTweetExposesItsResponsesInAscendingIdentifierOrder() {
        Tweet tweet = saveTweet(LocalDateTime.of(2026, 1, 1, 12, 0), 6.5, 120);
        List<Integer> savedResponseIds = new ArrayList<>();
        savedResponseIds.add(saveResponse(tweet, "First drafted reply", Boolean.FALSE).getId());
        savedResponseIds.add(saveResponse(tweet, "Second drafted reply", Boolean.TRUE).getId());
        savedResponseIds.add(saveResponse(tweet, "Third drafted reply", null).getId());

        entityManager.flush();
        entityManager.clear();

        List<Integer> ascendingResponseIds = savedResponseIds.stream().sorted().toList();
        Optional<Tweet> reloaded = tweetRepository.findById(tweet.getId());
        assertThat(reloaded).as("reloaded tweets row").isPresent();

        List<Response> responses = reloaded.get().getResponses();
        assertThat(responses).as("responses of the reloaded tweet").hasSize(3);

        List<Integer> loadedResponseIds = responses.stream().map(Response::getId).toList();
        assertThat(loadedResponseIds).as("identifier order of the responses collection")
                .containsExactlyElementsOf(ascendingResponseIds);
        assertThat(loadedResponseIds)
                .as("responses collection ordered by ascending identifier").isSorted();
        for (int position = 1; position < loadedResponseIds.size(); position++) {
            assertThat(loadedResponseIds.get(position))
                    .as("identifier at position %d of the responses collection", position)
                    .isGreaterThan(loadedResponseIds.get(position - 1));
        }

        for (Response response : responses) {
            assertThat(response.getTweet()).as("inverse association of response %s",
                    response.getId()).isNotNull();
            assertThat(response.getTweet().getId())
                    .as("parent identifier of response %s", response.getId())
                    .isEqualTo(tweet.getId());
        }
    }

    // Ported from backend/app/db/models.py:L15,L18,L33 (faithful port) — DL-024 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("media and ai_tools_mentioned are character columns with no join table and no "
            + "foreign key to ai_tools")
    void delimitedColumnsAreCharacterColumnsWithNoJoinTableAndNoForeignKey() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            for (String columnName : List.of("media", "ai_tools_mentioned")) {
                Map<String, Object> attributes = readColumn(metaData, TWEETS_TABLE, columnName);
                assertThat(attributes.get(DATA_TYPE))
                        .as("java.sql.Types code of column %s.%s (reported as %s)", TWEETS_TABLE,
                                columnName, attributes.get(TYPE_NAME))
                        .isIn(CHARACTER_TYPES);
                assertThat(normalise((String) attributes.get(TYPE_NAME)))
                        .as("type name of column %s.%s", TWEETS_TABLE, columnName)
                        .doesNotContain("ARRAY");
            }

            Set<String> storedTables = readStoredTableNames(metaData).keySet();
            assertThat(storedTables)
                    .filteredOn(tableName -> tableName.contains(normalise("ai_tool")))
                    .as("tables named after the ai_tools catalogue")
                    .containsExactly(normalise(AI_TOOLS_TABLE));

            assertThat(readExportedKeys(metaData, storedTableName(metaData, AI_TOOLS_TABLE)))
                    .as("foreign keys referencing table %s", AI_TOOLS_TABLE).isEmpty();
        }
    }

    // Ported from backend/app/db/models.py:L15,L18 (faithful port) — DL-024 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("a multi-valued list is stored as one delimited column value and round-trips in "
            + "order")
    void multiValuedListIsStoredAsOneDelimitedColumnValue() {
        List<String> media = List.of("https://pbs.example/media/1.png", "https://pbs.example/media/2.png");
        List<String> aiTools = List.of("Copilot", "Cursor", "Codeium");

        Tweet tweet = new Tweet();
        tweet.setContent("Three tools, two images");
        tweet.setMedia(media);
        tweet.setAiToolsMentioned(aiTools);
        Tweet saved = tweetRepository.save(tweet);

        Object[] rawColumns = readDelimitedColumns(saved.getId());

        assertThat(rawColumns[0]).as("raw media column value").isInstanceOf(String.class);
        assertThat(rawColumns[1]).as("raw ai_tools_mentioned column value")
                .isInstanceOf(String.class);
        assertThat((String) rawColumns[0]).as("raw media column value")
                .isEqualTo(String.join(DELIMITER, media)).contains(DELIMITER);
        assertThat((String) rawColumns[1]).as("raw ai_tools_mentioned column value")
                .isEqualTo(String.join(DELIMITER, aiTools)).contains(DELIMITER);

        entityManager.clear();

        Optional<Tweet> reloaded = tweetRepository.findById(saved.getId());
        assertThat(reloaded).as("reloaded tweets row").isPresent();
        assertThat(reloaded.get().getMedia()).as("media attribute").containsExactlyElementsOf(media);
        assertThat(reloaded.get().getAiToolsMentioned()).as("ai_tools_mentioned attribute")
                .containsExactlyElementsOf(aiTools);
    }

    // Ported from backend/app/db/models.py:L15,L18 (faithful port) — DL-024 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("a single-element list is stored without a delimiter and round-trips as one element")
    void singleElementListIsStoredWithoutADelimiter() {
        Tweet tweet = new Tweet();
        tweet.setContent("One tool, one image");
        tweet.setMedia(List.of("https://pbs.example/media/only.png"));
        tweet.setAiToolsMentioned(List.of("Copilot"));
        Tweet saved = tweetRepository.save(tweet);

        Object[] rawColumns = readDelimitedColumns(saved.getId());

        assertThat((String) rawColumns[0]).as("raw media column value")
                .isEqualTo("https://pbs.example/media/only.png").doesNotContain(DELIMITER);
        assertThat((String) rawColumns[1]).as("raw ai_tools_mentioned column value")
                .isEqualTo("Copilot").doesNotContain(DELIMITER);

        entityManager.clear();

        Optional<Tweet> reloaded = tweetRepository.findById(saved.getId());
        assertThat(reloaded).as("reloaded tweets row").isPresent();
        assertThat(reloaded.get().getMedia()).as("media attribute")
                .containsExactly("https://pbs.example/media/only.png");
        assertThat(reloaded.get().getAiToolsMentioned()).as("ai_tools_mentioned attribute")
                .containsExactly("Copilot");
    }

    // Ported from backend/app/db/models.py:L15,L18 (faithful port) — DL-024 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("an empty list is stored as a null column value and reads back as an empty list")
    void emptyListIsStoredAsANullColumnValue() {
        Tweet tweet = new Tweet();
        tweet.setContent("No media and no tools");
        tweet.setMedia(List.of());
        tweet.setAiToolsMentioned(List.of());
        Tweet saved = tweetRepository.save(tweet);

        Object[] rawColumns = readDelimitedColumns(saved.getId());

        assertThat(rawColumns[0]).as("raw media column value").isNull();
        assertThat(rawColumns[1]).as("raw ai_tools_mentioned column value").isNull();

        entityManager.clear();

        Optional<Tweet> reloaded = tweetRepository.findById(saved.getId());
        assertThat(reloaded).as("reloaded tweets row").isPresent();
        assertThat(reloaded.get().getMedia()).as("media attribute").isNotNull().isEmpty();
        assertThat(reloaded.get().getAiToolsMentioned()).as("ai_tools_mentioned attribute")
                .isNotNull().isEmpty();
    }

    // Ported from backend/app/db/models.py:L15,L18 (faithful port) — DL-024 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("a null list is stored as a null column value and reads back as an empty list")
    void nullListIsStoredAsANullColumnValue() {
        Tweet tweet = new Tweet();
        tweet.setContent("Unset media and unset tools");
        tweet.setMedia(null);
        tweet.setAiToolsMentioned(null);
        Tweet saved = tweetRepository.save(tweet);

        Object[] rawColumns = readDelimitedColumns(saved.getId());

        assertThat(rawColumns[0]).as("raw media column value").isNull();
        assertThat(rawColumns[1]).as("raw ai_tools_mentioned column value").isNull();

        entityManager.clear();

        Optional<Tweet> reloaded = tweetRepository.findById(saved.getId());
        assertThat(reloaded).as("reloaded tweets row").isPresent();
        assertThat(reloaded.get().getMedia()).as("media attribute").isNotNull().isEmpty();
        assertThat(reloaded.get().getAiToolsMentioned()).as("ai_tools_mentioned attribute")
                .isNotNull().isEmpty();
    }

    // Ported from backend/app/db/models.py:L15,L18 (faithful port) — DL-164 — see
    // docs/DECISION_LOG.md
    // The codec gives no character a special meaning: a comma inside an element separates it and a
    // backslash is stored literally. Both are asserted on the stored column text and on the reloaded
    // attribute.
    @Test
    @DisplayName("a delimiter inside an element separates it and a backslash is stored literally")
    void aDelimiterInsideAnElementSeparatesItAndABackslashIsStoredLiterally() {
        Tweet tweet = new Tweet();
        tweet.setContent("An element carrying a delimiter and one carrying a backslash");
        tweet.setMedia(List.of("https://pbs.example/a,b.png"));
        tweet.setAiToolsMentioned(List.of("Copi\\lot", "Cursor\\"));
        Tweet saved = tweetRepository.save(tweet);

        Object[] rawColumns = readDelimitedColumns(saved.getId());

        assertThat((String) rawColumns[0]).as("raw media column value")
                .isEqualTo("https://pbs.example/a,b.png");
        assertThat((String) rawColumns[1]).as("raw ai_tools_mentioned column value")
                .isEqualTo("Copi\\lot,Cursor\\");

        entityManager.clear();

        Optional<Tweet> reloaded = tweetRepository.findById(saved.getId());
        assertThat(reloaded).as("reloaded tweets row").isPresent();
        assertThat(reloaded.get().getMedia()).as("media attribute")
                .containsExactly("https://pbs.example/a", "b.png");
        assertThat(reloaded.get().getAiToolsMentioned()).as("ai_tools_mentioned attribute")
                .containsExactly("Copi\\lot", "Cursor\\");
    }

    // Ported from backend/app/db/models.py:L15,L18 (faithful port) — DL-164 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("surrounding whitespace is discarded on the way in and interior whitespace is kept")
    void surroundingWhitespaceIsDiscardedAndInteriorWhitespaceIsKept() {
        Tweet tweet = new Tweet();
        tweet.setContent("Elements carrying surrounding and interior whitespace");
        tweet.setMedia(List.of("  https://pbs.example/one.png  ", "\thttps://pbs.example/two.png\n"));
        tweet.setAiToolsMentioned(List.of("  Copilot  ", "Cursor  Editor", "   "));
        Tweet saved = tweetRepository.save(tweet);

        Object[] rawColumns = readDelimitedColumns(saved.getId());

        assertThat((String) rawColumns[0]).as("raw media column value")
                .isEqualTo("https://pbs.example/one.png,https://pbs.example/two.png");
        assertThat((String) rawColumns[1]).as("raw ai_tools_mentioned column value")
                .isEqualTo("Copilot,Cursor  Editor");

        entityManager.clear();

        Optional<Tweet> reloaded = tweetRepository.findById(saved.getId());
        assertThat(reloaded).as("reloaded tweets row").isPresent();
        assertThat(reloaded.get().getMedia()).as("media attribute")
                .containsExactly("https://pbs.example/one.png", "https://pbs.example/two.png");
        assertThat(reloaded.get().getAiToolsMentioned()).as("ai_tools_mentioned attribute")
                .containsExactly("Copilot", "Cursor  Editor");
    }

    // Ported from backend/app/db/models.py:L15,L18 (faithful port) — DL-164 — see
    // docs/DECISION_LOG.md
    // A value written by a hand edit is read by the same codec as a value this converter wrote.
    @Test
    @DisplayName("a hand-written column value is read by the same codec, backslashes and repeated "
            + "delimiters included")
    void aHandWrittenColumnValueIsReadByTheSameCodec() {
        Tweet tweet = new Tweet();
        tweet.setContent("A row whose delimited columns are written as raw text");
        Tweet saved = tweetRepository.save(tweet);
        entityManager.flush();

        writeDelimitedColumns(saved.getId(), " a , b ,,c\\d, ,e\\",
                "Copilot,,  Cursor  ,C:\\tools\\codeium,");
        entityManager.clear();

        Object[] rawColumns = readDelimitedColumns(saved.getId());
        assertThat((String) rawColumns[0]).as("raw media column value")
                .isEqualTo(" a , b ,,c\\d, ,e\\");
        assertThat((String) rawColumns[1]).as("raw ai_tools_mentioned column value")
                .isEqualTo("Copilot,,  Cursor  ,C:\\tools\\codeium,");

        Optional<Tweet> reloaded = tweetRepository.findById(saved.getId());
        assertThat(reloaded).as("reloaded tweets row").isPresent();
        assertThat(reloaded.get().getMedia()).as("media attribute")
                .containsExactly("a", "b", "c\\d", "e\\");
        assertThat(reloaded.get().getAiToolsMentioned()).as("ai_tools_mentioned attribute")
                .containsExactly("Copilot", "Cursor", "C:\\tools\\codeium");
    }

    // Ported from backend/app/db/models.py:L15,L18 (faithful port) — DL-164 — see
    // docs/DECISION_LOG.md
    // The column codec and the Notion mirror are the same code; the mirror calls the two static
    // members asserted here.
    @Test
    @DisplayName("the column codec is the one authorized codec and round-trips every delimiter-free "
            + "element")
    void theColumnCodecIsTheOneAuthorizedCodec() {
        List<String> elements = List.of("Copilot", "C:\\tools\\codeium", "\"Cursor\"",
                "Café — assistant", "🤖 helper", "Cursor  Editor");

        String delimited = DelimitedStringListConverter.encode(elements);

        assertThat(delimited).as("encoded value")
                .isEqualTo(String.join(DELIMITER, elements));
        assertThat(DelimitedStringListConverter.decode(delimited)).as("decoded value")
                .containsExactlyElementsOf(elements);
        assertThat(new DelimitedStringListConverter().convertToDatabaseColumn(elements))
                .as("column value written through the converter").isEqualTo(delimited);
        assertThat(new DelimitedStringListConverter().convertToEntityAttribute(delimited))
                .as("attribute read through the converter").containsExactlyElementsOf(elements);
        assertThat(DelimitedStringListConverter.encode(null)).as("encoded null list").isNull();
        assertThat(DelimitedStringListConverter.encode(List.of(" ", ""))).as("encoded blank list")
                .isNull();
        assertThat(DelimitedStringListConverter.decode(null)).as("decoded null value")
                .isNotNull().isEmpty();
        assertThat(DelimitedStringListConverter.decode(" , ,")).as("decoded blank tokens")
                .isNotNull().isEmpty();
    }

    // Ported from backend/app/db/models.py:L42-44 (faithful port) — DL-061 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("the settings table carries the physical columns key and value and returns a "
            + "stored row through them")
    void settingsTableCarriesThePhysicalColumnsKeyAndValue() throws SQLException {
        Setting saved = settingRepository.save(
                new Setting("stream_keywords", "AI coding tool,GPT-4", "Streaming rule terms"));
        entityManager.flush();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            assertThat((String) readColumn(metaData, SETTINGS_TABLE, "key").get(COLUMN_NAME))
                    .as("physical name of the settings primary-key column")
                    .isEqualToIgnoringCase("key");
            assertThat((String) readColumn(metaData, SETTINGS_TABLE, "value").get(COLUMN_NAME))
                    .as("physical name of the settings payload column")
                    .isEqualToIgnoringCase("value");
        }

        Object[] storedRow = (Object[]) entityManager.getEntityManager()
                .createNativeQuery("select \"key\", cast(\"value\" as varchar), "
                        + "cast(description as varchar) from settings where \"key\" = :settingKey")
                .setParameter("settingKey", saved.getKey())
                .getSingleResult();

        assertThat(storedRow[0]).as("stored settings key").isEqualTo("stream_keywords");
        assertThat(storedRow[1]).as("stored settings value").isEqualTo("AI coding tool,GPT-4");
        assertThat(storedRow[2]).as("stored settings description").isEqualTo("Streaming rule terms");
    }

    // Ported from backend/app/db/models.py:L42 (faithful port) — see docs/DECISION_LOG.md
    @Test
    @DisplayName("the settings key column is the sole primary key, is not generated, and retrieves "
            + "the row it was assigned")
    void settingsKeyColumnIsTheSoleUngeneratedPrimaryKey() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String storedTable = storedTableName(metaData, SETTINGS_TABLE);

            assertThat(readPrimaryKeyColumns(metaData, storedTable))
                    .as("primary-key columns of table %s", SETTINGS_TABLE)
                    .containsExactly(normalise("key"));
            assertThat(readColumn(metaData, SETTINGS_TABLE, "key").get(IS_AUTOINCREMENT))
                    .as("IS_AUTOINCREMENT of column %s.key", SETTINGS_TABLE).isEqualTo("NO");
        }

        settingRepository.save(new Setting("tweet_popularity_threshold", "100",
                "Minimum like count for ingestion"));
        settingRepository.save(new Setting("response_generation_delay", "60",
                "Seconds between response generation runs"));
        entityManager.flush();
        entityManager.clear();

        Optional<Setting> found = settingRepository.findById("tweet_popularity_threshold");
        assertThat(found).as("settings row retrieved by its assigned key").isPresent();
        assertThat(found.get().getKey()).as("key of the retrieved settings row")
                .isEqualTo("tweet_popularity_threshold");
        assertThat(found.get().getValue()).as("value of the retrieved settings row").isEqualTo("100");
        assertThat(found.get().getDescription()).as("description of the retrieved settings row")
                .isEqualTo("Minimum like count for ingestion");

        assertThat(settingRepository.existsById("tweet_popularity_threshold"))
                .as("presence of an assigned key").isTrue();
        assertThat(settingRepository.existsById("absent_setting"))
                .as("presence of a key that was never assigned").isFalse();
        assertThat(settingRepository.findById("absent_setting"))
                .as("settings row for a key that was never assigned").isEmpty();
        assertThat(settingRepository.findAll()).extracting(Setting::getKey)
                .as("keys of every stored settings row")
                .containsExactlyInAnyOrder("tweet_popularity_threshold", "response_generation_delay");
        assertThat(settingRepository.count()).as("stored settings row count").isEqualTo(2L);
    }

    // The order GET /settings renders, asked of the database on the quoted key column — DL-039,
    // DL-061 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("the settings table is readable ordered by its quoted key column, ascending")
    void settingsTableIsReadableOrderedByItsQuotedKeyColumn() {
        settingRepository.save(new Setting("tweet_popularity_threshold", "100",
                "Minimum like count for ingestion"));
        settingRepository.save(new Setting("stream_keywords", "AI coding tool",
                "Streaming rule terms"));
        settingRepository.save(new Setting("response_generation_delay", "60",
                "Seconds between response generation runs"));
        entityManager.flush();
        entityManager.clear();

        assertThat(settingRepository.findAll(Sort.by(Sort.Direction.ASC, "key")))
                .extracting(Setting::getKey)
                .as("keys of every stored settings row, ordered ascending")
                .containsExactly("response_generation_delay", "stream_keywords",
                        "tweet_popularity_threshold");

        Setting relocated = settingRepository.findById("response_generation_delay").orElseThrow();
        relocated.setValue("90");
        settingRepository.save(relocated);
        entityManager.flush();
        entityManager.clear();

        assertThat(settingRepository.findAll(Sort.by(Sort.Direction.ASC, "key")))
                .extracting(Setting::getKey)
                .as("keys of every stored settings row after a value was rewritten")
                .containsExactly("response_generation_delay", "stream_keywords",
                        "tweet_popularity_threshold");
    }

    // Ported from backend/app/db/models.py:L10,L23,L35 (faithful port) — DL-049 and DL-070 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("the three surrogate identifiers are unset before the insert and populated after it")
    void surrogateIdentifiersArePopulatedOnInsert() {
        Tweet tweet = new Tweet();
        tweet.setContent("A post awaiting its identifier");
        assertThat(tweet.getId()).as("tweets identifier before the insert").isNull();
        Tweet savedTweet = tweetRepository.save(tweet);
        assertThat(savedTweet.getId()).as("tweets identifier after the insert").isNotNull();

        Response response = new Response();
        response.setContent("A reply awaiting its identifier");
        response.setTweet(savedTweet);
        assertThat(response.getId()).as("responses identifier before the insert").isNull();
        Response savedResponse = responseRepository.save(response);
        assertThat(savedResponse.getId()).as("responses identifier after the insert").isNotNull();

        AiTool aiTool = new AiTool("Copilot", "Inline code completion");
        assertThat(aiTool.getId()).as("ai_tools identifier before the insert").isNull();
        AiTool savedAiTool = aiToolRepository.save(aiTool);
        assertThat(savedAiTool.getId()).as("ai_tools identifier after the insert").isNotNull();

        entityManager.flush();
    }

    // Ported from backend/app/tasks/response_generation.py:L43, whose expression ended in `.all()`
    // (faithful port of the predicate) — DL-248 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("consecutive keyset batches cover every unanswered tweet exactly once and bound "
            + "the rows one statement returns")
    void consecutiveKeysetBatchesCoverEveryUnansweredTweetExactlyOnce() {
        Tweet answered = saveTweet(LocalDateTime.of(2026, 1, 1, 9, 0), 7.0, 250);
        saveResponse(answered, "Already drafted", Boolean.FALSE);

        List<Integer> unansweredIds = new ArrayList<>();
        for (int row = 0; row < BACKLOG_ROW_COUNT; row++) {
            unansweredIds.add(saveTweet(LocalDateTime.of(2026, 1, 2, 9, 0).plusMinutes(row),
                    8.0, 300 + row).getId());
        }

        entityManager.flush();
        entityManager.clear();

        Pageable batchRequest =
                PageRequest.of(0, BACKLOG_BATCH_ROWS, Sort.by(Sort.Direction.ASC, "id"));
        List<Integer> drained = new ArrayList<>();
        List<Integer> batchSizes = new ArrayList<>();
        Integer afterId = null;
        while (true) {
            List<Tweet> batch = tweetRepository.findUnansweredBatchAfter(afterId, batchRequest);
            if (batch.isEmpty()) {
                break;
            }
            batchSizes.add(batch.size());
            for (Tweet candidate : batch) {
                drained.add(candidate.getId());
                afterId = candidate.getId();
            }
            if (batch.size() < BACKLOG_BATCH_ROWS) {
                break;
            }
        }

        assertThat(batchSizes).as("rows each batch returned")
                .isNotEmpty()
                .allMatch(size -> size <= BACKLOG_BATCH_ROWS)
                .hasSizeGreaterThan(1);
        assertThat(drained).as("every tweets row carrying no response, drained in batches")
                .containsExactlyElementsOf(unansweredIds)
                .doesNotHaveDuplicates()
                .doesNotContain(answered.getId());
        assertThat(drained).as("the drain order").isSorted();
    }

    // A page size larger than the table renders the same envelope as one that matches it — DL-217 —
    // see docs/DECISION_LOG.md
    @Test
    @DisplayName("both listings render the same envelope whether the page size matches the table or "
            + "exceeds it")
    void bothListingsRenderTheSameEnvelopeWhateverThePageSize() {
        List<Tweet> stored = new ArrayList<>();
        for (int row = 0; row < LISTING_ROW_COUNT; row++) {
            Tweet tweet = saveTweet(LocalDateTime.of(2026, 1, 7, 9, 0).plusMinutes(row),
                    4.0 + row, 500 + row);
            stored.add(tweet);
            saveResponse(tweet, "Reply " + row, row % 2 == 0 ? Boolean.TRUE : Boolean.FALSE);
        }
        entityManager.flush();
        entityManager.clear();

        TwitterService tweets = new TwitterService(tweetRepository, settingRepository,
                propertiesWithoutOverrides(), new TweetMapper(), mock(SentimentAnalysisService.class));
        ResponseService responses = responseService(mock(LlmService.class));

        PaginatedTweetsDto tweetsAtExactSize = tweets.getPaginatedTweets(1, LISTING_ROW_COUNT);
        PaginatedTweetsDto tweetsAtLargeSize = tweets.getPaginatedTweets(1, Integer.MAX_VALUE);
        PaginatedResponsesDto responsesAtExactSize =
                responses.getPaginatedResponses(1, LISTING_ROW_COUNT);
        PaginatedResponsesDto responsesAtLargeSize = responses.getPaginatedResponses(1,
                Integer.MAX_VALUE);

        assertThat(tweetsAtLargeSize.tweets()).as("tweet rows the large page rendered")
                .containsExactlyElementsOf(tweetsAtExactSize.tweets())
                .hasSize(LISTING_ROW_COUNT);
        assertThat(tweetsAtLargeSize.pagination().total()).as("tweet total the large page reported")
                .isEqualTo(tweetsAtExactSize.pagination().total())
                .isEqualTo(LISTING_ROW_COUNT);
        assertThat(tweetsAtLargeSize.pagination().totalPages())
                .as("tweet total_pages the large page reported").isEqualTo(1);
        assertThat(tweetsAtLargeSize.pagination().perPage())
                .as("per_page the large tweet page restated").isEqualTo(Integer.MAX_VALUE);
        assertThat(responsesAtLargeSize.pagination().perPage())
                .as("per_page the large response page restated").isEqualTo(Integer.MAX_VALUE);
        assertThat(tweetsAtLargeSize.tweets()).extracting(TweetDto::id)
                .as("tweet identifiers in ascending order")
                .containsExactlyElementsOf(stored.stream().map(row -> String.valueOf(row.getId()))
                        .toList());

        assertThat(responsesAtLargeSize.responses()).as("response rows the large page rendered")
                .containsExactlyElementsOf(responsesAtExactSize.responses())
                .hasSize(LISTING_ROW_COUNT);
        assertThat(responsesAtLargeSize.pagination().total())
                .as("response total the large page reported")
                .isEqualTo(responsesAtExactSize.pagination().total())
                .isEqualTo(LISTING_ROW_COUNT);
        assertThat(responsesAtLargeSize.pagination().totalPages())
                .as("response total_pages the large page reported").isEqualTo(1);
    }

    // A projected page row reads no tweets row — DL-245 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("a projected response page carries the parent identifier without loading a tweets "
            + "row")
    void aProjectedResponsePageCarriesTheParentIdentifierWithoutLoadingATweetsRow() {
        Tweet parent = saveTweet(LocalDateTime.of(2026, 1, 6, 12, 0), 5.5, 150);
        Response stored = saveResponse(parent, "Chunked reply", Boolean.TRUE);
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = statistics();
        statistics.clear();

        List<ResponseRow> rows = responseRepository.findAllRows(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "id"))).getContent();

        assertThat(rows).as("rows the page covered").hasSize(1);
        assertThat(rows.get(0).getId()).as("identifier of the projected row")
                .isEqualTo(stored.getId());
        assertThat(rows.get(0).getTweetId()).as("parent identifier of the projected row")
                .isEqualTo(parent.getId());
        assertThat(statistics.getEntityLoadCount())
                .as("entities the page read loaded").isZero();
    }

    // The opening batch of a sweep takes the first matching rows — DL-248 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("the opening batch of a sweep applies no cursor and a cursor past the last row "
            + "returns nothing")
    void theOpeningBatchAppliesNoCursorAndACursorPastTheLastRowReturnsNothing() {
        Tweet first = saveTweet(LocalDateTime.of(2026, 1, 2, 9, 0), 8.0, 300);
        Tweet second = saveTweet(LocalDateTime.of(2026, 1, 2, 9, 5), 8.5, 310);
        entityManager.flush();
        entityManager.clear();

        Pageable batchRequest =
                PageRequest.of(0, BACKLOG_BATCH_ROWS, Sort.by(Sort.Direction.ASC, "id"));

        assertThat(tweetRepository.findUnansweredBatchAfter(null, batchRequest))
                .as("the opening batch").extracting(Tweet::getId)
                .containsExactly(first.getId(), second.getId());
        assertThat(tweetRepository.findUnansweredBatchAfter(first.getId(), batchRequest))
                .as("the batch past the first row").extracting(Tweet::getId)
                .containsExactly(second.getId());
        assertThat(tweetRepository.findUnansweredBatchAfter(second.getId(), batchRequest))
                .as("the batch past the last row").isEmpty();
    }

    // The update write boundary and wire conversion share one real persistence transaction — DL-082,
    // DL-244.
    @Test
    @DisplayName("the response service clears a column for an explicit null and maps a valid update through the real repository")
    void responseServiceClearsAColumnForAnExplicitNullAndMapsAValidUpdate() {
        Tweet tweet = saveTweet(LocalDateTime.of(2026, 1, 4, 9, 0), 6.0, 180);
        Response response = saveResponse(tweet, "Draft awaiting review", Boolean.FALSE);
        entityManager.flush();
        entityManager.clear();
        ResponseService service = responseService(mock(LlmService.class));
        String responseId = String.valueOf(response.getId());

        // An explicit JSON null binds without error and reaches the service as a write of null —
        // DL-244
        UpdateResponseRequest emptyingContent =
                new UpdateResponseRequest(NullNode.getInstance(), null);
        assertThat(emptyingContent.writesContent()).isTrue();
        assertThat(emptyingContent.contentValue()).isNull();

        // The column is nullable and the wire record carries an empty one through as JSON null, so
        // the write is stored rather than refused — DL-080, DL-244
        ResponseDto cleared = service.updateResponse(responseId, emptyingContent);
        assertThat(cleared.id()).isEqualTo(responseId);
        assertThat(cleared.content()).isNull();
        assertThat(cleared.isApproved()).isFalse();

        entityManager.flush();
        entityManager.clear();
        Response emptied = responseRepository.findById(response.getId()).orElseThrow();
        assertThat(emptied.getContent()).as("the stored column after the clearing write").isNull();
        assertThat(emptied.getIsApproved()).isFalse();

        // A body carrying neither updatable member still reaches the transcribed 400 literal —
        // DL-082
        assertThatThrownBy(() -> service.updateResponse(responseId,
                new UpdateResponseRequest(null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Update data is required");

        entityManager.clear();
        Response unchanged = responseRepository.findById(response.getId()).orElseThrow();
        assertThat(unchanged.getContent()).as("the column a rejected body leaves alone").isNull();
        assertThat(unchanged.getIsApproved()).isFalse();

        ResponseDto updated = service.updateResponse(responseId,
                new UpdateResponseRequest(TextNode.valueOf("Reviewed draft"), BooleanNode.TRUE));

        assertThat(updated.id()).isEqualTo(responseId);
        assertThat(updated.content()).isEqualTo("Reviewed draft");
        assertThat(updated.isApproved()).isTrue();
        assertThat(updated.generatedAt()).isEqualTo(response.getGeneratedAt());
        assertThat(updated.tweetId()).isEqualTo(String.valueOf(tweet.getId()));
    }

    // Two service instances have distinct in-process claims; the parent-row lock is the shared guard
    // that serialises their final existence checks — DL-195.
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("two response service instances racing on one tweet store exactly one response row")
    void twoResponseServiceInstancesRacingOnOneTweetStoreExactlyOneResponseRow() throws Exception {
        responseRepository.deleteAll();
        tweetRepository.deleteAll();
        Tweet tweet = saveTweet(LocalDateTime.of(2026, 1, 5, 9, 0), 8.0, 300);
        String tweetId = String.valueOf(tweet.getId());
        CyclicBarrier bothModelsAnswered = new CyclicBarrier(2);
        LlmService firstGenerator = mock(LlmService.class);
        LlmService secondGenerator = mock(LlmService.class);
        when(firstGenerator.generateResponse(any(TweetDto.class))).thenAnswer(invocation -> {
            bothModelsAnswered.await(5, TimeUnit.SECONDS);
            return "First generated draft";
        });
        when(secondGenerator.generateResponse(any(TweetDto.class))).thenAnswer(invocation -> {
            bothModelsAnswered.await(5, TimeUnit.SECONDS);
            return "Second generated draft";
        });
        ResponseService firstService = responseService(firstGenerator);
        ResponseService secondService = responseService(secondGenerator);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Optional<ResponseDto>> first =
                    executor.submit(() -> firstService.generateResponseIfAbsent(tweetId));
            Future<Optional<ResponseDto>> second =
                    executor.submit(() -> secondService.generateResponseIfAbsent(tweetId));

            Optional<ResponseDto> firstResult = first.get(15, TimeUnit.SECONDS);
            Optional<ResponseDto> secondResult = second.get(15, TimeUnit.SECONDS);

            assertThat(firstResult.isPresent() ^ secondResult.isPresent()).isTrue();
            assertThat(responseRepository.count()).isEqualTo(1L);
            Response stored = responseRepository.findAll().getFirst();
            assertThat(stored.getTweet().getId()).isEqualTo(tweet.getId());
            assertThat(stored.getContent())
                    .isIn("First generated draft", "Second generated draft");
            assertThat(stored.getIsApproved()).isFalse();
        } finally {
            // The pool is awaited and its termination asserted, so neither generator thread outlives
            // this test — DL-273 — see docs/DECISION_LOG.md
            awaitTermination(executor);
            responseRepository.deleteAll();
            tweetRepository.deleteAll();
        }
    }

    // Net-new: every test executor is awaited and its termination asserted — DL-273 — see
    // docs/DECISION_LOG.md
    private static void awaitTermination(ExecutorService executor) {
        executor.shutdownNow();
        try {
            assertThat(executor.awaitTermination(EXECUTOR_TERMINATION_SECONDS, TimeUnit.SECONDS))
                    .as("the concurrent-generator pool terminated").isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting the concurrent-generator pool.",
                    interrupted);
        }
    }

    // Net-new (no Python counterpart: the AnalyticsService imported at
    // backend/app/api/analytics.py:L3 did not exist; the column is
    // backend/app/db/models.py:L26) — DL-041 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("the responses aggregate counts every row and only the rows whose approval flag "
            + "is true")
    void theApprovalCountsCountsEveryRowAndOnlyTheApprovedRows() {
        Tweet tweet = saveTweet(LocalDateTime.of(2026, 1, 1, 11, 0), 5.0, 140);
        saveResponse(tweet, "Approved reply one", Boolean.TRUE);
        saveResponse(tweet, "Approved reply two", Boolean.TRUE);
        saveResponse(tweet, "Rejected reply", Boolean.FALSE);
        saveResponse(tweet, "Undecided reply", null);

        entityManager.flush();

        ResponseRepository.ApprovalCounts aggregate = responseRepository.findApprovalCounts();

        assertThat(aggregate).as("responses aggregate").isNotNull();
        assertThat(aggregate.getResponseCount()).as("stored responses row count").isEqualTo(4L);
        assertThat(aggregate.getApprovedResponseCount()).as("approved responses row count")
                .isEqualTo(2L);
    }

    // Net-new (no Python counterpart: the AnalyticsService imported at
    // backend/app/api/analytics.py:L3 did not exist; the column is
    // backend/app/db/models.py:L26) — DL-041, DL-180 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("the responses aggregate reports zero for both counts over an empty table")
    void theApprovalCountsReportsZeroForBothCountsOverAnEmptyTable() {
        ResponseRepository.ApprovalCounts aggregate = responseRepository.findApprovalCounts();

        assertThat(aggregate).as("responses aggregate").isNotNull();
        assertThat(aggregate.getResponseCount()).as("stored responses row count").isZero();
        assertThat(aggregate.getApprovedResponseCount()).as("approved responses row count").isZero();
    }

    // Net-new (no Python counterpart: the AnalyticsService imported at
    // backend/app/api/analytics.py:L3 did not exist; the columns are
    // backend/app/db/models.py:L12,L14) — DL-041 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("the tweets aggregate reports zero rows and no mean when no tweet is stored")
    void theTweetAggregateReportsZeroRowsAndNoMeanWhenNoTweetIsStored() {
        TweetRepository.TweetAggregate aggregate = tweetRepository.findAggregates();

        assertThat(aggregate).as("tweets aggregate").isNotNull();
        assertThat(aggregate.getTweetCount()).as("stored tweets row count").isZero();
        assertThat(aggregate.getAverageDoubtRating())
                .as("average doubt rating over an empty table").isNull();
        assertThat(aggregate.getAverageLikeCount())
                .as("average like count over an empty table").isNull();
    }

    // Net-new (no Python counterpart: the AnalyticsService imported at
    // backend/app/api/analytics.py:L3 did not exist; the columns are
    // backend/app/db/models.py:L12,L14) — DL-041 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("the tweets aggregate computes the row count and both means in one statement")
    void theTweetAggregateComputesTheRowCountAndBothMeansInOneStatement() {
        saveTweet(LocalDateTime.of(2026, 1, 1, 9, 0), 2.5, 4);
        saveTweet(LocalDateTime.of(2026, 1, 1, 12, 0), 7.5, 10);

        entityManager.flush();
        entityManager.clear();
        statistics().clear();

        TweetRepository.TweetAggregate aggregate = tweetRepository.findAggregates();

        assertThat(aggregate).as("tweets aggregate").isNotNull();
        assertThat(aggregate.getTweetCount()).as("stored tweets row count").isEqualTo(2L);
        assertThat(aggregate.getAverageDoubtRating()).as("average doubt rating")
                .isNotNull().isCloseTo(5.0, within(TOLERANCE));
        assertThat(aggregate.getAverageLikeCount()).as("average like count")
                .isNotNull().isCloseTo(7.0, within(TOLERANCE));
        assertThat(statistics().getPrepareStatementCount())
                .as("statements issued for the three tweets metrics").isEqualTo(1L);
    }

    // Net-new (no Python counterpart: the AnalyticsService imported at
    // backend/app/api/analytics.py:L3 did not exist and get_trends() at :L13-14 took no argument;
    // the columns are backend/app/db/models.py:L12-14) — DL-042 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("findDailyTrendsBetween buckets only the tweets inside the closed window by "
            + "calendar day and populates every projection accessor")
    void findDailyTrendsBetweenBucketsTheTweetsInsideTheClosedWindowByCalendarDay() {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDateTime cutoff = yesterday.atStartOfDay();
        LocalDateTime until = today.atTime(23, 0);

        saveTweet(yesterday.atTime(9, 0), 2.0, 3);
        saveTweet(today.atTime(12, 0), 4.0, 5);
        saveTweet(today.atTime(18, 0), 6.0, 7);
        Tweet beforeCutoff = saveTweet(today.minusDays(4).atTime(12, 0), 9.0, 1000);
        Tweet afterWindow = saveTweet(today.plusDays(3).atTime(12, 0), 1.0, 2000);

        entityManager.flush();
        entityManager.clear();

        List<TweetRepository.DailyTrend> trends =
                tweetRepository.findDailyTrendsBetween(cutoff, until);

        assertThat(trends).as("day buckets inside the closed window").hasSize(2);
        assertThat(trends).extracting(TweetRepository.DailyTrend::getBucketDate)
                .as("bucket days in ascending order").containsExactly(yesterday, today);
        assertThat(trends).extracting(TweetRepository.DailyTrend::getBucketDate)
                .as("day of the tweet stored before the cutoff")
                .doesNotContain(beforeCutoff.getCreatedAt().toLocalDate());
        assertThat(trends).extracting(TweetRepository.DailyTrend::getBucketDate)
                .as("day of the tweet stamped after the closing bound")
                .doesNotContain(afterWindow.getCreatedAt().toLocalDate());

        TweetRepository.DailyTrend yesterdayBucket = trends.get(0);
        assertThat(yesterdayBucket.getTweetCount()).as("row count of the %s bucket", yesterday)
                .isEqualTo(1L);
        assertThat(yesterdayBucket.getAverageDoubtRating())
                .as("average doubt rating of the %s bucket", yesterday)
                .isNotNull().isCloseTo(2.0, within(TOLERANCE));
        assertThat(yesterdayBucket.getTotalLikes()).as("summed like count of the %s bucket",
                yesterday).isEqualTo(3L);

        TweetRepository.DailyTrend todayBucket = trends.get(1);
        assertThat(todayBucket.getTweetCount()).as("row count of the %s bucket", today)
                .isEqualTo(2L);
        assertThat(todayBucket.getAverageDoubtRating())
                .as("average doubt rating of the %s bucket", today)
                .isNotNull().isCloseTo(5.0, within(TOLERANCE));
        assertThat(todayBucket.getTotalLikes()).as("summed like count of the %s bucket", today)
                .isEqualTo(12L);

        for (TweetRepository.DailyTrend bucket : trends) {
            assertThat(bucket.getBucketDate()).as("bucket day").isNotNull();
            assertThat(bucket.getTweetCount()).as("bucket row count").isNotNull();
            assertThat(bucket.getAverageDoubtRating()).as("bucket average doubt rating").isNotNull();
            assertThat(bucket.getTotalLikes()).as("bucket summed like count").isNotNull();
        }
    }

    // Net-new (no Python counterpart) — DL-245, DL-249 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("a page of responses carries the parent identifier without loading a tweets row or "
            + "issuing a per-row statement")
    void aPageOfResponsesCarriesTheParentIdentifierWithoutLoadingATweetsRow() {
        Tweet first = saveTweet(LocalDateTime.of(2026, 1, 1, 12, 0), 6.5, 120);
        Tweet second = saveTweet(LocalDateTime.of(2026, 1, 2, 12, 0), 7.5, 220);
        Tweet third = saveTweet(LocalDateTime.of(2026, 1, 3, 12, 0), 8.5, 320);
        saveResponse(first, "First drafted reply", Boolean.FALSE);
        saveResponse(second, "Second drafted reply", Boolean.TRUE);
        saveResponse(third, "Third drafted reply", null);
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManager.getEntityManager()
                .getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        Page<ResponseRow> page = responseRepository
                .findAllRows(PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "id")));
        List<Integer> parentIds = page.getContent().stream()
                .map(ResponseRow::getTweetId)
                .toList();

        assertThat(page.getTotalElements()).as("rows the page reports").isEqualTo(3L);
        assertThat(parentIds).as("parent identifier of every row on the page")
                .containsExactly(first.getId(), second.getId(), third.getId());
        assertThat(page.getContent()).extracting(ResponseRow::getContent)
                .as("content of every row on the page")
                .containsExactly("First drafted reply", "Second drafted reply",
                        "Third drafted reply");
        assertThat(page.getContent()).extracting(ResponseRow::getIsApproved)
                .as("approval flag of every row on the page")
                .containsExactly(Boolean.FALSE, Boolean.TRUE, null);
        assertThat(statistics.getPrepareStatementCount())
                .as("statements issued to render one page of responses")
                .isLessThanOrEqualTo(2L);
        assertThat(statistics.getEntityLoadCount())
                .as("entities the page read loaded")
                .isZero();
    }

    // The projection maps onto the five wire members — DL-245 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("the response mapper renders a projected page row as the five wire members")
    void theResponseMapperRendersAProjectedPageRowAsTheFiveWireMembers() {
        Tweet tweet = saveTweet(LocalDateTime.of(2026, 1, 5, 12, 0), 4.5, 90);
        Response stored = saveResponse(tweet, "Projected reply", Boolean.TRUE);
        entityManager.flush();
        entityManager.clear();

        Page<ResponseRow> page = responseRepository.findAllRows(PageRequest.of(0, 10));
        assertThat(page.getContent()).hasSize(1);

        ResponseDto rendered = new ResponseMapper().toDto(page.getContent().getFirst());

        assertThat(rendered.id()).isEqualTo(String.valueOf(stored.getId()));
        assertThat(rendered.content()).isEqualTo("Projected reply");
        assertThat(rendered.generatedAt()).isEqualTo(stored.getGeneratedAt());
        assertThat(rendered.isApproved()).isTrue();
        assertThat(rendered.tweetId()).isEqualTo(String.valueOf(tweet.getId()));
    }

    // Ported from backend/app/db/models.py:L32-37 (faithful port) — see docs/DECISION_LOG.md
    @Test
    @DisplayName("ai_tools rows are stored, listed and counted through the inherited repository "
            + "surface")
    void aiToolRowsAreStoredListedAndCountedThroughTheInheritedSurface() {
        aiToolRepository.save(new AiTool("Copilot", "Inline code completion"));
        aiToolRepository.save(new AiTool("Cursor", "Editor with an integrated assistant"));

        entityManager.flush();
        entityManager.clear();

        assertThat(aiToolRepository.count()).as("stored ai_tools row count").isEqualTo(2L);
        assertThat(aiToolRepository.findAll()).extracting(AiTool::getName)
                .as("names of every stored ai_tools row")
                .containsExactlyInAnyOrder("Copilot", "Cursor");
        assertThat(aiToolRepository.findAll()).extracting(AiTool::getDescription)
                .as("descriptions of every stored ai_tools row")
                .containsExactlyInAnyOrder("Inline code completion",
                        "Editor with an integrated assistant");
        assertThat(aiToolRepository.findAll()).extracting(AiTool::getId)
                .as("identifiers of every stored ai_tools row").doesNotContainNull();
    }

    // Ported from backend/app/db/models.py:L10-18,L23-28,L35-37,L42-44 (faithful port) — DL-166 —
    // see docs/DECISION_LOG.md
    // The expectations below are the physical types of the source declarations, stated per vendor;
    // the generated statements are produced from the four entity classes with no database contacted.
    @Test
    @DisplayName("the generated schema carries the source column types on every supported vendor")
    void theGeneratedSchemaCarriesTheSourceColumnTypesOnEverySupportedVendor() {
        for (Map.Entry<String, Map<String, List<String>>> vendor
                : EXPECTED_GENERATED_COLUMN_TYPES.entrySet()) {
            Map<String, String> statements = generateCreateStatements(vendor.getKey());

            assertThat(statements.keySet()).as("tables generated for %s", vendor.getKey())
                    .containsExactlyInAnyOrderElementsOf(MAPPED_TABLES);

            for (Map.Entry<String, List<String>> table : vendor.getValue().entrySet()) {
                String statement = statements.get(table.getKey());
                for (String columnFragment : table.getValue()) {
                    assertThat(statement)
                            .as("create statement of table %s on %s", table.getKey(),
                                    vendor.getKey())
                            .contains(columnFragment);
                }
            }
        }
    }

    // Ported from backend/app/db/models.py:L10,L23,L27,L35 (faithful port) — DL-138, DL-070,
    // DL-166 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("no generated statement declares a 64-bit identifier column on any supported "
            + "vendor")
    void noGeneratedStatementDeclaresA64BitIdentifierColumn() {
        for (String dialect : EXPECTED_GENERATED_COLUMN_TYPES.keySet()) {
            Map<String, String> statements = generateCreateStatements(dialect);

            for (Map.Entry<String, String> table : statements.entrySet()) {
                assertThat(table.getValue())
                        .as("create statement of table %s on %s", table.getKey(), dialect)
                        .doesNotContain("bigint")
                        .doesNotContain("int8")
                        .doesNotContain("serial");
            }
        }
    }

    // No generated character column is a large object, and every capacity-requiring type carries a
    // capacity, so each statement is valid DDL on the vendor it was generated for — DL-068, DL-069,
    // DL-166 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("no generated statement declares a large-object character type on any supported "
            + "vendor")
    void noGeneratedStatementDeclaresALargeObjectCharacterType() {
        for (String dialect : EXPECTED_GENERATED_COLUMN_TYPES.keySet()) {
            Map<String, String> statements = generateCreateStatements(dialect);

            for (Map.Entry<String, String> table : statements.entrySet()) {
                for (String largeObjectType : LARGE_OBJECT_CHARACTER_TYPES) {
                    assertThat(table.getValue())
                            .as("create statement of table %s on %s", table.getKey(), dialect)
                            .doesNotContain(largeObjectType);
                }
            }
        }
    }

    // MySQL rejects a character type that needs a capacity and does not carry one, which is what
    // made an earlier mapping unable to bootstrap this schema — DL-068 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("every generated character type that requires a capacity carries one on every "
            + "supported vendor")
    void everyCapacityRequiringGeneratedTypeCarriesACapacity() {
        for (String dialect : EXPECTED_GENERATED_COLUMN_TYPES.keySet()) {
            Map<String, String> statements = generateCreateStatements(dialect);

            for (Map.Entry<String, String> table : statements.entrySet()) {
                for (String typeName : CAPACITY_REQUIRING_TYPES) {
                    assertThat(uncapacitatedOccurrences(table.getValue(), typeName))
                            .as("occurrences of %s without a capacity in table %s on %s",
                                    typeName, table.getKey(), dialect)
                            .isZero();
                }
            }
        }
    }

    // MySQL cannot index a text column without a prefix length, so no column named in a primary key
    // clause may be generated as one — DL-069 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("every generated primary-key column is declared in an indexable type on every "
            + "supported vendor")
    void everyGeneratedPrimaryKeyColumnIsIndexable() {
        for (String dialect : EXPECTED_GENERATED_COLUMN_TYPES.keySet()) {
            Map<String, String> statements = generateCreateStatements(dialect);

            for (Map.Entry<String, String> table : statements.entrySet()) {
                String statement = table.getValue();
                for (String keyColumn : primaryKeyColumns(statement)) {
                    String declaration = columnDeclaration(statement, keyColumn);
                    assertThat(declaration)
                            .as("declaration of primary-key column %s in table %s on %s",
                                    keyColumn, table.getKey(), dialect)
                            .isNotBlank()
                            .doesNotContain("text")
                            .doesNotContain("blob")
                            .doesNotContain("clob");
                    assertThat(uncapacitatedOccurrences(declaration, "varchar"))
                            .as("uncapacitated varchar in primary-key column %s of table %s on %s",
                                    keyColumn, table.getKey(), dialect)
                            .isZero();
                }
            }
        }
    }

    // Net-new (no Python counterpart) — DL-068 — see docs/DECISION_LOG.md
    private static int uncapacitatedOccurrences(String statement, String typeName) {
        int occurrences = 0;
        int from = statement.indexOf(typeName);
        while (from >= 0) {
            int after = from + typeName.length();
            boolean wholeWord = from == 0 || !Character.isLetterOrDigit(statement.charAt(from - 1));
            if (wholeWord && (after >= statement.length() || statement.charAt(after) != '(')) {
                occurrences++;
            }
            from = statement.indexOf(typeName, after);
        }
        return occurrences;
    }

    // Net-new (no Python counterpart) — DL-069 — see docs/DECISION_LOG.md
    private static List<String> primaryKeyColumns(String statement) {
        int clause = statement.indexOf("primary key (");
        if (clause < 0) {
            return List.of();
        }
        int open = statement.indexOf('(', clause);
        int close = statement.indexOf(')', open);
        return Arrays.stream(statement.substring(open + 1, close).split(DELIMITER))
                .map(name -> name.replace("\"", "").replace("`", "").trim())
                .filter(name -> !name.isEmpty())
                .toList();
    }

    // Net-new (no Python counterpart) — DL-069 — see docs/DECISION_LOG.md
    private static String columnDeclaration(String statement, String columnName) {
        int open = statement.indexOf('(');
        String body = statement.substring(open + 1);
        for (String declaration : body.split(DELIMITER)) {
            String trimmed = declaration.replace("\"", "").replace("`", "").trim();
            if (trimmed.startsWith(columnName + " ")) {
                return trimmed;
            }
        }
        return "";
    }

    // Net-new (no Python counterpart) — DL-166 — see docs/DECISION_LOG.md
    private static Map<String, String> generateCreateStatements(String dialect) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put(AvailableSettings.DIALECT, dialect);
        settings.put(AvailableSettings.ALLOW_METADATA_ON_BOOT, "false");
        settings.put(AvailableSettings.CONNECTION_PROVIDER,
                new UserSuppliedConnectionProviderImpl());
        settings.put(SCHEMA_GENERATION_SCRIPTS_ACTION, "create");

        Path script;
        try {
            Files.createDirectories(GENERATED_SCRIPT_DIRECTORY);
            script = Files.createTempFile(GENERATED_SCRIPT_DIRECTORY, "schema-", ".sql");
        } catch (IOException e) {
            throw new AssertionError("The generated schema script could not be created.", e);
        }
        settings.put(SCHEMA_GENERATION_SCRIPTS_CREATE_TARGET, script.toAbsolutePath().toString());

        List<String> lines;
        try {
            MetadataSources sources =
                    new MetadataSources(new StandardServiceRegistryBuilder()
                            .applySettings(settings).build());
            MAPPED_ENTITIES.forEach(sources::addAnnotatedClass);
            sources.buildMetadata().buildSessionFactory().close();
            lines = Files.readAllLines(script);
        } catch (IOException e) {
            throw new AssertionError("The generated schema script could not be read.", e);
        }

        Map<String, String> statements = new LinkedHashMap<>();
        for (String line : lines) {
            String statement = line.toLowerCase(Locale.ROOT);
            for (String logicalTable : MAPPED_TABLES) {
                if (statement.startsWith("create table " + logicalTable + " ")) {
                    statements.put(logicalTable, statement);
                }
            }
        }
        return statements;
    }

    private Set<String> readPhysicalColumns(String logicalTable) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            return readColumns(metaData, storedTableName(metaData, logicalTable)).keySet();
        }
    }

    private static String normalise(String identifier) {
        return identifier == null ? null : identifier.toUpperCase(Locale.ROOT);
    }

    /**
     * Returns the normalised physical column name a mapping declares, with any quoting removed.
     *
     * <p>{@code Setting#key} declares the reserved word as a quoted identifier — DL-061 — see
     * docs/DECISION_LOG.md.
     *
     * @param column the column mapping to read
     * @return the uppercased column name, free of quote characters
     */
    private static String unquotedColumnName(Column column) {
        return normalise(column.name()).replace("\"", "").replace("`", "");
    }

    private static Set<String> normaliseAll(Iterable<String> identifiers) {
        Set<String> normalised = new LinkedHashSet<>();
        for (String identifier : identifiers) {
            normalised.add(normalise(identifier));
        }
        return normalised;
    }

    private static Map<String, String> readStoredTableNames(DatabaseMetaData metaData)
            throws SQLException {
        Map<String, String> storedNames = new LinkedHashMap<>();
        try (ResultSet tables = metaData.getTables(null, PUBLIC_SCHEMA, null, null)) {
            while (tables.next()) {
                String schema = tables.getString("TABLE_SCHEM");
                String tableType = tables.getString("TABLE_TYPE");
                if (PUBLIC_SCHEMA.equalsIgnoreCase(schema)
                        && BASE_TABLE_TYPES.contains(normalise(tableType))) {
                    String tableName = tables.getString("TABLE_NAME");
                    storedNames.put(normalise(tableName), tableName);
                }
            }
        }
        return storedNames;
    }

    private static String storedTableName(DatabaseMetaData metaData, String logicalName)
            throws SQLException {
        String storedName = readStoredTableNames(metaData).get(normalise(logicalName));
        assertThat(storedName).as("stored name of table %s", logicalName).isNotNull();
        return storedName;
    }

    private static Map<String, Map<String, Object>> readColumns(DatabaseMetaData metaData,
            String storedTable) throws SQLException {
        Map<String, Map<String, Object>> columns = new LinkedHashMap<>();
        try (ResultSet columnRows = metaData.getColumns(null, PUBLIC_SCHEMA, storedTable, null)) {
            while (columnRows.next()) {
                Map<String, Object> attributes = new LinkedHashMap<>();
                String columnName = columnRows.getString(COLUMN_NAME);
                attributes.put(COLUMN_NAME, columnName);
                attributes.put(COLUMN_SIZE, columnRows.getInt(COLUMN_SIZE));
                attributes.put(DATA_TYPE, columnRows.getInt(DATA_TYPE));
                attributes.put(TYPE_NAME, columnRows.getString(TYPE_NAME));
                attributes.put(NULLABLE, columnRows.getInt(NULLABLE));
                attributes.put(IS_NULLABLE, columnRows.getString(IS_NULLABLE));
                attributes.put(IS_AUTOINCREMENT, columnRows.getString(IS_AUTOINCREMENT));
                columns.put(normalise(columnName), attributes);
            }
        }
        return columns;
    }

    private static Map<String, Object> readColumn(DatabaseMetaData metaData, String logicalTable,
            String columnName) throws SQLException {
        String storedTable = storedTableName(metaData, logicalTable);
        Map<String, Object> attributes =
                readColumns(metaData, storedTable).get(normalise(columnName));
        assertThat(attributes).as("metadata of column %s.%s", logicalTable, columnName).isNotNull();
        return attributes;
    }

    private static List<String> readPrimaryKeyColumns(DatabaseMetaData metaData, String storedTable)
            throws SQLException {
        List<String> primaryKeyColumns = new ArrayList<>();
        try (ResultSet keys = metaData.getPrimaryKeys(null, PUBLIC_SCHEMA, storedTable)) {
            while (keys.next()) {
                primaryKeyColumns.add(normalise(keys.getString(COLUMN_NAME)));
            }
        }
        return primaryKeyColumns;
    }

    private static List<String> readImportedKeys(DatabaseMetaData metaData, String storedTable)
            throws SQLException {
        List<String> importedKeys = new ArrayList<>();
        try (ResultSet keys = metaData.getImportedKeys(null, PUBLIC_SCHEMA, storedTable)) {
            while (keys.next()) {
                importedKeys.add(normalise(keys.getString("FKCOLUMN_NAME")) + "->"
                        + normalise(keys.getString("PKTABLE_NAME")) + "."
                        + normalise(keys.getString("PKCOLUMN_NAME")));
            }
        }
        return importedKeys;
    }

    private static List<String> readExportedKeys(DatabaseMetaData metaData, String storedTable)
            throws SQLException {
        List<String> exportedKeys = new ArrayList<>();
        try (ResultSet keys = metaData.getExportedKeys(null, PUBLIC_SCHEMA, storedTable)) {
            while (keys.next()) {
                exportedKeys.add(normalise(keys.getString("FKTABLE_NAME")) + "."
                        + normalise(keys.getString("FKCOLUMN_NAME")));
            }
        }
        return exportedKeys;
    }

    private static Map<String, Set<String>> readUniqueIndexes(DatabaseMetaData metaData,
            String storedTable) throws SQLException {
        Map<String, Set<String>> uniqueIndexes = new LinkedHashMap<>();
        try (ResultSet indexes = metaData.getIndexInfo(null, PUBLIC_SCHEMA, storedTable, true,
                false)) {
            while (indexes.next()) {
                String indexName = normalise(indexes.getString("INDEX_NAME"));
                String columnName = normalise(indexes.getString(COLUMN_NAME));
                if (indexName != null && columnName != null) {
                    uniqueIndexes.computeIfAbsent(indexName, name -> new LinkedHashSet<>())
                            .add(columnName);
                }
            }
        }
        return uniqueIndexes;
    }

    private static List<String> readTableConstraints(Connection connection) throws SQLException {
        List<String> constraints = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select table_name, constraint_type from information_schema.table_constraints"
                        + " where table_schema = ?")) {
            statement.setString(1, PUBLIC_SCHEMA);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    constraints.add(
                            normalise(rows.getString(1)) + "|" + normalise(rows.getString(2)));
                }
            }
        }
        return constraints;
    }

    private static List<Field> mappedFields(Class<?> entityType,
            Class<? extends java.lang.annotation.Annotation> annotation) {
        List<Field> fields = new ArrayList<>();
        for (Field field : entityType.getDeclaredFields()) {
            if (field.isAnnotationPresent(annotation)) {
                fields.add(field);
            }
        }
        return fields;
    }

    private static ScannerProperties propertiesWithoutOverrides() {
        return new ScannerProperties(null, 100, 0L, null, null, null, null, null, null, null, null);
    }

    private ResponseService responseService(LlmService generator) {
        return new ResponseService(responseRepository, tweetRepository, generator,
                new ResponseMapper(), new TweetMapper(), new TransactionTemplate(transactionManager));
    }

    private Statistics statistics() {
        Statistics statistics = entityManager.getEntityManager()
                .getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    private Tweet saveTweet(LocalDateTime createdAt, Double doubtRating, Integer likeCount) {
        Tweet tweet = new Tweet();
        tweet.setContent("A skeptical post about AI coding tools");
        tweet.setCreatedAt(createdAt);
        tweet.setDoubtRating(doubtRating);
        tweet.setLikeCount(likeCount);
        tweet.setUserId("2244994945");
        return tweetRepository.save(tweet);
    }

    private Response saveResponse(Tweet tweet, String content, Boolean isApproved) {
        Response response = new Response();
        response.setContent(content);
        response.setGeneratedAt(LocalDateTime.of(2026, 1, 1, 12, 0));
        response.setIsApproved(isApproved);
        response.setTweet(tweet);
        return responseRepository.save(response);
    }

    private Object[] readDelimitedColumns(Integer tweetId) {
        entityManager.flush();
        Object[] rawColumns = (Object[]) entityManager.getEntityManager()
                .createNativeQuery("select media, ai_tools_mentioned from tweets where id = :tweetId")
                .setParameter("tweetId", tweetId)
                .getSingleResult();
        return new Object[] {characterValueText(rawColumns[0]), characterValueText(rawColumns[1])};
    }

    private void writeDelimitedColumns(Integer tweetId, String media, String aiToolsMentioned) {
        entityManager.getEntityManager()
                .createNativeQuery("update tweets set media = :media, "
                        + "ai_tools_mentioned = :aiToolsMentioned where id = :tweetId")
                .setParameter("media", media)
                .setParameter("aiToolsMentioned", aiToolsMentioned)
                .setParameter("tweetId", tweetId)
                .executeUpdate();
        entityManager.flush();
    }

    /**
     * Reads a character column value as text.
     *
     * <p>The two delimited columns are mapped as unbounded character storage rather than as a
     * large object — DL-068, DL-166 — so the driver reports them as a {@link String}. Any other
     * handle type fails this method, so the surrounding assertions describe the stored text and
     * never a driver handle.
     *
     * @param columnValue the raw value the driver reported; may be {@code null}
     * @return the stored text, or {@code null} when the column holds SQL null
     */
    private static String characterValueText(Object columnValue) {
        if (columnValue == null) {
            return null;
        }
        if (columnValue instanceof String text) {
            return text;
        }
        throw new AssertionError("A character column reported an unexpected handle type: "
                + columnValue.getClass().getName());
    }
}
