package com.codeskeptic.scanner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeskeptic.scanner.config.DatabaseUrlTranslator.TranslatedDatabaseUrl;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exercises the {@link DatabaseUrlTranslator#translate(String)} contract: the credential-free
 * {@code jdbc:} pass-through and the rejection of a credential-bearing one, the supported scheme set,
 * driver-suffix stripping, credential extraction from the user-info component and from the query
 * string, percent-escape decoding, query-string preservation, host and port validation, and the
 * rejected values.
 *
 * <p>Net-new (no Python counterpart) - see docs/DECISION_LOG.md DL-027, DL-071, DL-072 and DL-187.
 */
class DatabaseUrlTranslatorTest {

    @Test
    @DisplayName("translates a postgresql url and omits the credentials from the reassembled url")
    void translatesAPostgresqlUrl() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("postgresql://scanner:s3cret@db.internal:5432/codeskeptic");

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:postgresql://db.internal:5432/codeskeptic");
        assertThat(translated.username()).isEqualTo("scanner");
        assertThat(translated.password()).isEqualTo("s3cret");
        assertThat(translated.jdbcUrl()).doesNotContain("scanner:s3cret", "s3cret", "@");
    }

    @Test
    @DisplayName("strips the driver suffix and preserves the query string")
    void stripsTheDriverSuffixAndPreservesTheQueryString() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("postgresql+psycopg2://u:p@localhost/scanner?sslmode=require");

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost/scanner?sslmode=require");
        assertThat(translated.jdbcUrl()).doesNotContain("psycopg2");
        assertThat(translated.jdbcUrl()).contains("?sslmode=require");
        assertThat(translated.username()).isEqualTo("u");
        assertThat(translated.password()).isEqualTo("p");
        assertThat(translated.jdbcUrl()).doesNotContain("u:p", "@");
    }

    @Test
    @DisplayName("translates a mysql url and omits the credentials from the reassembled url")
    void translatesAMysqlUrl() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("mysql+pymysql://root:root@127.0.0.1:3306/scanner");

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:mysql://127.0.0.1:3306/scanner");
        assertThat(translated.username()).isEqualTo("root");
        assertThat(translated.password()).isEqualTo("root");
        assertThat(translated.jdbcUrl()).doesNotContain("root:root", "root", "@", "pymysql");
    }

    @Test
    @DisplayName("returns a value that already begins with jdbc: unchanged and reports no credentials")
    void returnsAJdbcValueUnchanged() {
        TranslatedDatabaseUrl translated = DatabaseUrlTranslator.translate("jdbc:postgresql://host/db");

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:postgresql://host/db");
        assertThat(translated.username()).isNull();
        assertThat(translated.password()).isNull();
    }

    @Test
    @DisplayName("maps the postgres scheme onto the postgresql jdbc vendor")
    void mapsThePostgresSchemeOntoPostgresql() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("postgres://db.internal:5432/codeskeptic");

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:postgresql://db.internal:5432/codeskeptic");
        assertThat(translated.username()).isNull();
        assertThat(translated.password()).isNull();
    }

    // The mariadb scheme resolves onto the MySQL JDBC vendor, as AAP 0.6.5.1 maps it — DL-187 — see
    // docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "mariadb://127.0.0.1:3306/scanner",
        "mariadb+mariadbconnector://127.0.0.1:3306/scanner",
        "MariaDB://127.0.0.1:3306/scanner",
    })
    @DisplayName("maps the mariadb scheme onto the mysql jdbc vendor")
    void mapsTheMariadbSchemeOntoMysql(String databaseUrl) {
        TranslatedDatabaseUrl translated = DatabaseUrlTranslator.translate(databaseUrl);

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:mysql://127.0.0.1:3306/scanner");
        assertThat(translated.jdbcUrl()).doesNotContain("mariadb");
    }

    @Test
    @DisplayName("takes the credentials out of a mariadb url and keeps them out of the jdbc url")
    void takesTheCredentialsOutOfAMariadbUrl() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("mariadb://scanner:scanner@127.0.0.1:3306/scanner");

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:mysql://127.0.0.1:3306/scanner");
        assertThat(translated.username()).isEqualTo("scanner");
        assertThat(translated.password()).isEqualTo("scanner");
        assertThat(translated.jdbcUrl()).doesNotContain("scanner:scanner", "@");
    }

    @Test
    @DisplayName("preserves the query string of a mariadb url")
    void preservesTheQueryStringOfAMariadbUrl() {
        assertThat(DatabaseUrlTranslator.translate("mariadb://host/db?useSSL=true").jdbcUrl())
                .isEqualTo("jdbc:mysql://host/db?useSSL=true");
    }

    @Test
    @DisplayName("advertises the mariadb scheme in the supported set")
    void advertisesTheMariadbSchemeInTheSupportedSet() {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate("oracle://h/db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supported schemes are")
                .hasMessageContaining("mariadb");
    }

    @Test
    @DisplayName("maps the h2 scheme onto the h2 jdbc vendor in its tcp connection mode")
    void mapsTheH2SchemeOntoH2() {
        TranslatedDatabaseUrl translated = DatabaseUrlTranslator.translate("h2://localhost/scanner");

        // The vendor prefix for the h2 scheme is `jdbc:h2:tcp://` — DL-071 — see docs/DECISION_LOG.md
        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:h2:tcp://localhost/scanner");
        assertThat(translated.username()).isNull();
        assertThat(translated.password()).isNull();
    }

    @Test
    @DisplayName("carries the h2 host port and database into the tcp url")
    void carriesTheH2HostPortAndDatabaseIntoTheTcpUrl() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("h2://db.internal:9092/codeskeptic");

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:h2:tcp://db.internal:9092/codeskeptic");
        assertThat(translated.jdbcUrl()).doesNotContain("jdbc:h2://");
    }

    @Test
    @DisplayName("rejects an unsupported scheme and names the supported schemes")
    void rejectsAnUnsupportedScheme() {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate("oracle://h/db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("postgresql")
                .hasMessageContaining("mysql");
    }

    @Test
    @DisplayName("rejects an empty value")
    void rejectsAnEmptyValue() {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate(""))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("rejects a null value")
    void rejectsANullValue() {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("rejects a whitespace-only value")
    void rejectsAWhitespaceOnlyValue() {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate("   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("rejects an unresolved DATABASE_URL placeholder as an unset value")
    void rejectsAnUnresolvedPlaceholder() {
        // Configuration binding leaves the placeholder in place as literal text when the environment
        // variable is absent — DL-186 — see docs/DECISION_LOG.md
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate("${DATABASE_URL}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DATABASE_URL must be set: no database URL was supplied.");
    }

    @Test
    @DisplayName("rejects an unresolved placeholder that carries a nested default")
    void rejectsAnUnresolvedPlaceholderCarryingANestedDefault() {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate("${DATABASE_URL:${DB_URL}}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DATABASE_URL must be set: no database URL was supplied.");
    }

    @Test
    @DisplayName("translates a value that merely contains a dollar-brace sequence")
    void translatesAValueThatMerelyContainsADollarBraceSequence() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("postgresql://db.internal:5432/codeskeptic$%7Bx%7D");

        assertThat(translated.jdbcUrl())
                .isEqualTo("jdbc:postgresql://db.internal:5432/codeskeptic$%7Bx%7D");
    }

    // -----------------------------------------------------------------------
    // The jdbc: pass-through is credential-free: a value carrying credential material is rejected,
    // never altered — DL-072 — see docs/DECISION_LOG.md
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("passes through a credential-free jdbc url unchanged")
    void passesThroughACredentialFreeJdbcUrlUnchanged() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("jdbc:postgresql://host:5432/db?sslmode=require");

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:postgresql://host:5432/db?sslmode=require");
        assertThat(translated.username()).isNull();
        assertThat(translated.password()).isNull();
    }

    @Test
    @DisplayName("passes through the test profile h2 jdbc url unchanged")
    void passesThroughTheTestProfileH2JdbcUrlUnchanged() {
        String testProfileUrl =
                "jdbc:h2:mem:scanner_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";

        TranslatedDatabaseUrl translated = DatabaseUrlTranslator.translate(testProfileUrl);

        assertThat(translated.jdbcUrl()).isEqualTo(testProfileUrl);
        assertThat(translated.username()).isNull();
        assertThat(translated.password()).isNull();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "jdbc:postgresql://u:p@host/db",
        "jdbc:mysql://host/db?user=root",
        "jdbc:postgresql://host/db?password=s3cret",
        "jdbc:mysql://host/db;user=root",
        "jdbc:mysql://host/db;password=s3cret",
        "jdbc:postgresql://host/db?USER=root",
        "jdbc:postgresql://host/db?sslmode=require&password=s3cret",
        "jdbc:postgresql://u:p@host/db?password=s3cret",
    })
    @DisplayName("rejects a jdbc url that carries credential material")
    void rejectsAJdbcUrlCarryingCredentialMaterial(String databaseUrl) {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate(databaseUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not appear in the JDBC URL");
    }

    @Test
    @DisplayName("names the credential-free form when it rejects a credential-bearing jdbc url")
    void namesTheCredentialFreeFormWhenItRejectsACredentialBearingJdbcUrl() {
        assertThatThrownBy(
                () -> DatabaseUrlTranslator.translate("jdbc:postgresql://u:p@host/db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("<scheme>://[username[:password]@]host[:port]/database");
    }

    @Test
    @DisplayName("keeps a jdbc url whose property merely looks like a credential property")
    void keepsAJdbcUrlWhosePropertyMerelyLooksLikeACredentialProperty() {
        TranslatedDatabaseUrl translated = DatabaseUrlTranslator
                .translate("jdbc:postgresql://host/db?userTimezone=UTC&passwordAuthentication=true");

        assertThat(translated.jdbcUrl())
                .isEqualTo("jdbc:postgresql://host/db?userTimezone=UTC&passwordAuthentication=true");
    }

    @Test
    @DisplayName("returns no jdbc url that carries credential material on either path")
    void returnsNoJdbcUrlThatCarriesCredentialMaterialOnEitherPath() {
        assertThat(DatabaseUrlTranslator.translate("jdbc:postgresql://host/db").jdbcUrl())
                .doesNotContain("@", "user=", "password=");
        assertThat(DatabaseUrlTranslator
                .translate("postgresql://u:p@host/db?user=other&password=another").jdbcUrl())
                .doesNotContain("@", "user=", "password=");
    }

    // The redaction contract of TranslatedDatabaseUrl#toString() — DL-072 — see
    // docs/DECISION_LOG.md
    @Test
    @DisplayName("never renders a passed-through jdbc url in its own string form")
    void neverRendersAPassedThroughJdbcUrlInItsOwnStringForm() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("jdbc:postgresql://host/db");

        assertThat(translated.toString())
                .doesNotContain("host")
                .doesNotContain("jdbc:postgresql://host/db")
                .isEqualTo("TranslatedDatabaseUrl[jdbcUrl=" + "***REDACTED***"
                        + ", username=***REDACTED***, password=***REDACTED***]");
    }

    // -----------------------------------------------------------------------
    // Credentials carried in the query string
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("extracts the credentials from the query string and retains the remaining properties")
    void extractsCredentialsFromTheQueryStringAndRetainsTheRemainingProperties() {
        TranslatedDatabaseUrl translated = DatabaseUrlTranslator
                .translate("mysql://host/db?user=root&password=secret&useSSL=true");

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:mysql://host/db?useSSL=true");
        assertThat(translated.username()).isEqualTo("root");
        assertThat(translated.password()).isEqualTo("secret");
        assertThat(translated.jdbcUrl()).doesNotContain("user=", "password=", "secret");
    }

    @Test
    @DisplayName("takes the user-info credentials in preference to the query-string ones")
    void takesTheUserInfoCredentialsInPreferenceToTheQueryStringOnes() {
        TranslatedDatabaseUrl translated = DatabaseUrlTranslator
                .translate("postgresql://u:p@host/db?user=other&password=another");

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:postgresql://host/db");
        assertThat(translated.username()).isEqualTo("u");
        assertThat(translated.password()).isEqualTo("p");
        assertThat(translated.jdbcUrl()).doesNotContain("other", "another");
    }

    @Test
    @DisplayName("keeps the first credential property when the same name is repeated in another case")
    void keepsTheFirstCredentialPropertyWhenTheNameIsRepeated() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("mysql://host/db?password=a&PASSWORD=b");

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:mysql://host/db");
        assertThat(translated.password()).isEqualTo("a");
    }

    // -----------------------------------------------------------------------
    // The user-info component
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("decodes the percent-escapes of the user-info component")
    void decodesThePercentEscapesOfTheUserInfoComponent() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("postgresql://us%40er:p%40ss@host/db");

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:postgresql://host/db");
        assertThat(translated.username()).isEqualTo("us@er");
        assertThat(translated.password()).isEqualTo("p@ss");
    }

    @Test
    @DisplayName("carries a plus sign in the user-info component through as a plus sign")
    void carriesAPlusSignInTheUserInfoComponentThroughLiterally() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("postgresql://u:a+b@host/db");

        assertThat(translated.password()).isEqualTo("a+b");
    }

    @Test
    @DisplayName("rejects a malformed percent-escape")
    void rejectsAMalformedPercentEscape() {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate("postgresql://u:p%ZZ@host/db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a parseable URL");
    }

    @Test
    @DisplayName("reports no password when the user-info component carries only a username")
    void reportsNoPasswordWhenTheUserInfoCarriesOnlyAUsername() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("postgresql://scanner@host/db");

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:postgresql://host/db");
        assertThat(translated.username()).isEqualTo("scanner");
        assertThat(translated.password()).isNull();
    }

    @Test
    @DisplayName("reports an empty password when the user-info component ends with the separator")
    void reportsAnEmptyPasswordWhenTheUserInfoEndsWithTheSeparator() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("postgresql://scanner:@host/db");

        assertThat(translated.username()).isEqualTo("scanner");
        assertThat(translated.password()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Scheme, host and port
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("accepts a scheme written in upper case")
    void acceptsASchemeWrittenInUpperCase() {
        assertThat(DatabaseUrlTranslator.translate("POSTGRESQL://host/db").jdbcUrl())
                .isEqualTo("jdbc:postgresql://host/db");
    }

    @Test
    @DisplayName("rejects a url that declares no host")
    void rejectsAUrlThatDeclaresNoHost() {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate("postgresql:///db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declares no host");
    }

    @Test
    @DisplayName("rejects a url that declares no scheme and names the supported schemes")
    void rejectsAUrlThatDeclaresNoScheme() {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate("//host/db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("declares no scheme")
                .hasMessageContaining("postgresql")
                .hasMessageContaining("h2");
    }

    @Test
    @DisplayName("rejects a non-numeric port")
    void rejectsANonNumericPort() {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate("postgresql://host:abc/db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-numeric port");
    }

    @Test
    @DisplayName("rejects a port above the highest valid port number")
    void rejectsAPortAboveTheHighestValidPortNumber() {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate("postgresql://host:99999/db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out-of-range port")
                .hasMessageContaining("65535");
    }

    @Test
    @DisplayName("accepts an ipv6 host and keeps its brackets")
    void acceptsAnIpv6HostAndKeepsItsBrackets() {
        assertThat(DatabaseUrlTranslator.translate("postgresql://[::1]:5432/db").jdbcUrl())
                .isEqualTo("jdbc:postgresql://[::1]:5432/db");
    }

    @Test
    @DisplayName("accepts a host carrying an underscore")
    void acceptsAHostCarryingAnUnderscore() {
        assertThat(DatabaseUrlTranslator.translate("postgresql://my_host:5432/db").jdbcUrl())
                .isEqualTo("jdbc:postgresql://my_host:5432/db");
    }

    @Test
    @DisplayName("omits the port and the path when the url declares neither")
    void omitsThePortAndThePathWhenTheUrlDeclaresNeither() {
        TranslatedDatabaseUrl translated = DatabaseUrlTranslator.translate("postgresql://host");

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:postgresql://host");
        assertThat(translated.username()).isNull();
        assertThat(translated.password()).isNull();
    }

    @Test
    @DisplayName("rejects a url that cannot be parsed")
    void rejectsAUrlThatCannotBeParsed() {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate("postgresql://ho st/db"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a parseable URL");
    }

    // -----------------------------------------------------------------------
    // The reassembled query and fragment
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("appends no question mark when the query is empty")
    void appendsNoQuestionMarkWhenTheQueryIsEmpty() {
        assertThat(DatabaseUrlTranslator.translate("postgresql://host/db?").jdbcUrl())
                .isEqualTo("jdbc:postgresql://host/db");
    }

    @Test
    @DisplayName("carries no fragment into the reassembled url")
    void carriesNoFragmentIntoTheReassembledUrl() {
        assertThat(DatabaseUrlTranslator.translate("postgresql://host/db#frag").jdbcUrl())
                .isEqualTo("jdbc:postgresql://host/db");
    }

    // -----------------------------------------------------------------------
    // One property-separator grammar: '&' and ';' both extract — DL-072
    // -----------------------------------------------------------------------

    // A ';' separated credential property is extracted, exactly as an '&' separated one is — DL-072 —
    // see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', value = {
        "postgresql://host/db?sslmode=require;user=admin        | jdbc:postgresql://host/db?sslmode=require        | admin | ",
        "postgresql://host/db?sslmode=require;password=s3cret   | jdbc:postgresql://host/db?sslmode=require        |       | s3cret",
        "postgresql://host/db?sslmode=require;PASSWD=s3cret     | jdbc:postgresql://host/db?sslmode=require        |       | s3cret",
        "postgresql://host/db?user=admin;password=s3cret        | jdbc:postgresql://host/db                       | admin | s3cret",
        "mysql://host/db?useSSL=true;user=root                  | jdbc:mysql://host/db?useSSL=true                | root  | ",
        "h2://host:9092/db?MODE=PostgreSQL;pwd=s3cret           | jdbc:h2:tcp://host:9092/db?MODE=PostgreSQL       |       | s3cret",
        "postgresql://host/db?user=admin&password=s3cret        | jdbc:postgresql://host/db                       | admin | s3cret",
        "postgresql://host/db?sslmode=require&user=admin;pwd=x  | jdbc:postgresql://host/db?sslmode=require        | admin | x",
    })
    @DisplayName("extracts a credential property separated by a semicolon or an ampersand and leaves "
            + "neither in the jdbc url")
    void extractsACredentialPropertyUnderEitherSeparator(String databaseUrl, String expectedUrl,
            String expectedUsername, String expectedPassword) {
        TranslatedDatabaseUrl translated = DatabaseUrlTranslator.translate(databaseUrl.strip());

        assertThat(translated.jdbcUrl()).isEqualTo(expectedUrl.strip());
        assertThat(translated.username())
                .isEqualTo(expectedUsername == null ? null : expectedUsername.strip());
        assertThat(translated.password())
                .isEqualTo(expectedPassword == null ? null : expectedPassword.strip());
    }

    // A retained property keeps the separator that preceded it, so an H2 property list is not
    // rewritten — DL-072 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("retains semicolon-separated properties that name no credential with their own "
            + "separators")
    void retainsSemicolonSeparatedPropertiesThatNameNoCredential() {
        assertThat(DatabaseUrlTranslator
                        .translate("h2://host:9092/db?MODE=PostgreSQL;DB_CLOSE_DELAY=-1").jdbcUrl())
                .isEqualTo("jdbc:h2:tcp://host:9092/db?MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        assertThat(DatabaseUrlTranslator
                        .translate("postgresql://host/db?a=1&b=2;c=3&d=4").jdbcUrl())
                .isEqualTo("jdbc:postgresql://host/db?a=1&b=2;c=3&d=4");
    }

    // Removing the first property does not promote its separator into the leading position — DL-072 —
    // see docs/DECISION_LOG.md
    @Test
    @DisplayName("opens the retained query with no separator when the first property was a credential")
    void opensTheRetainedQueryWithNoSeparatorWhenTheFirstPropertyWasACredential() {
        assertThat(DatabaseUrlTranslator
                        .translate("h2://host:9092/db?user=sa;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
                        .jdbcUrl())
                .isEqualTo("jdbc:h2:tcp://host:9092/db?MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        assertThat(DatabaseUrlTranslator
                        .translate("postgresql://host/db?password=s3cret&sslmode=require").jdbcUrl())
                .isEqualTo("jdbc:postgresql://host/db?sslmode=require");
    }

    // A credential property never swallows the properties after it — DL-072 — see docs/DECISION_LOG.md
    @Test
    @DisplayName("takes only its own value from a credential property, never the properties after it")
    void takesOnlyItsOwnValueFromACredentialProperty() {
        TranslatedDatabaseUrl translated = DatabaseUrlTranslator
                .translate("postgresql://host/db?user=admin;sslmode=require;password=s3cret");

        assertThat(translated.username()).isEqualTo("admin");
        assertThat(translated.password()).isEqualTo("s3cret");
        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:postgresql://host/db?sslmode=require");
    }

    // A ';' separated credential property is rejected on the jdbc: pass-through path, where nothing is
    // extracted — DL-072 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "jdbc:h2:tcp://host:9092/db?MODE=PostgreSQL;user=sa",
        "jdbc:h2:tcp://host:9092/db;user=sa",
        "jdbc:postgresql://host/db?sslmode=require;password=s3cret",
    })
    @DisplayName("rejects a semicolon-separated credential property on the jdbc pass-through path")
    void rejectsASemicolonSeparatedCredentialPropertyOnThePassThroughPath(String databaseUrl) {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate(databaseUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a username or a password must not appear in the JDBC URL");
    }

    // -----------------------------------------------------------------------
    // Padding: classification trims, translation does not — DL-072, DL-186
    // -----------------------------------------------------------------------

    // ConfiguredValues.isUnset trims before classifying, so a padded placeholder is still unset —
    // DL-186 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {
        " ${DATABASE_URL}",
        "${DATABASE_URL} ",
        "  ${DATABASE_URL}  ",
        "\t${DATABASE_URL}\n",
        " ${DATABASE_URL:${DB_URL}} ",
    })
    @DisplayName("rejects a padded unresolved placeholder as an unset value")
    void rejectsAPaddedUnresolvedPlaceholder(String databaseUrl) {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate(databaseUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATABASE_URL must be set");
    }

    // Nothing is trimmed on the way into a returned URL, so a padded URL is simply unparseable —
    // DL-072 — see docs/DECISION_LOG.md
    @ParameterizedTest(name = "[{index}] \"{0}\"")
    @ValueSource(strings = {
        " postgresql://host/db",
        "postgresql://host/db ",
        "  postgresql://host/db  ",
        " jdbc:postgresql://host/db",
    })
    @DisplayName("rejects a padded url rather than trimming it into a translatable value")
    void rejectsAPaddedUrlRatherThanTrimmingIt(String databaseUrl) {
        assertThatThrownBy(() -> DatabaseUrlTranslator.translate(databaseUrl))
                .isInstanceOf(IllegalStateException.class);
    }

    // -----------------------------------------------------------------------
    // The returned record
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("redacts the url the username and the password in its string form")
    void redactsEveryComponentInItsStringForm() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("postgresql://scanner:s3cret@db.internal:5432/codeskeptic");

        assertThat(translated).hasToString(
                "TranslatedDatabaseUrl[jdbcUrl=***REDACTED***, username=***REDACTED***, password=***REDACTED***]");
        assertThat(translated.toString()).doesNotContain("s3cret", "scanner", "db.internal", "codeskeptic");
    }

    @Test
    @DisplayName("rejects a blank jdbc url")
    void rejectsABlankJdbcUrl() {
        assertThatThrownBy(() -> new TranslatedDatabaseUrl("   ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TranslatedDatabaseUrl(null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("declares only a private constructor")
    void declaresOnlyAPrivateConstructor() {
        Constructor<?>[] constructors = DatabaseUrlTranslator.class.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
    }
}
