package com.codeskeptic.scanner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codeskeptic.scanner.config.DatabaseUrlTranslator.TranslatedDatabaseUrl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@link DatabaseUrlTranslator#translate(String)} contract: the {@code jdbc:}
 * pass-through, the supported scheme set, driver-suffix stripping, credential extraction and
 * omission, query-string preservation, and the rejected values.
 *
 * <p>Net-new (no Python counterpart) - see docs/DECISION_LOG.md DL-027.
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

    @Test
    @DisplayName("maps the mariadb scheme onto the mysql jdbc vendor")
    void mapsTheMariadbSchemeOntoMysql() {
        TranslatedDatabaseUrl translated =
                DatabaseUrlTranslator.translate("mariadb://127.0.0.1:3306/scanner");

        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:mysql://127.0.0.1:3306/scanner");
        assertThat(translated.username()).isNull();
        assertThat(translated.password()).isNull();
    }

    @Test
    @DisplayName("maps the h2 scheme onto the h2 jdbc vendor")
    void mapsTheH2SchemeOntoH2() {
        TranslatedDatabaseUrl translated = DatabaseUrlTranslator.translate("h2://localhost/scanner");

        // H2 has no `//` connection mode, so the vendor prefix is `jdbc:h2:tcp://` — DL-071 — see
        // docs/DECISION_LOG.md
        assertThat(translated.jdbcUrl()).isEqualTo("jdbc:h2:tcp://localhost/scanner");
        assertThat(translated.username()).isNull();
        assertThat(translated.password()).isNull();
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
}
