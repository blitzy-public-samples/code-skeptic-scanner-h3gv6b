package com.codeskeptic.scanner.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Translates the opaque {@code DATABASE_URL} value, bound to {@code scanner.database-url}, into a
 * JDBC URL together with the username and the password as separate values.
 *
 * <p>Net-new: no Python counterpart. The retired source declared {@code DATABASE_URL} at
 * backend/app/core/config.py:L9 and passed it verbatim to
 * {@code create_engine(settings.DATABASE_URL)} at backend/app/db/database.py:L7 - see
 * docs/DECISION_LOG.md DL-027.
 *
 * <p>Every value is consumed exactly as supplied. No value is trimmed, case-folded or otherwise
 * normalised at any point. A value padded with leading or trailing whitespace is not a parseable
 * URL and raises {@link IllegalStateException}.
 *
 * <p>Behaviour contract, applied in this order:
 * <ol>
 *   <li>A {@code null}, empty or whitespace-only value raises {@link IllegalStateException}.</li>
 *   <li>A value beginning with the literal lower-case {@code jdbc:} is returned exactly as
 *       supplied, character for character, with a {@code null} username and a {@code null}
 *       password. It is not parsed, normalised, trimmed or stripped, and its scheme is matched
 *       case-sensitively.</li>
 *   <li>Any other value is parsed as a {@link URI}. Everything from the first {@code '+'} of the
 *       scheme onward is discarded, the remaining scheme is mapped case-insensitively to a JDBC
 *       vendor token, the user-info component is split on its first {@code ':'} into the username
 *       and the password, and the URL is reassembled as
 *       {@code jdbc:<vendor>://<host>[:<port>]<path>[?<query>]}. The {@code :<port>} segment is
 *       present only when the value declares a port, the {@code ?<query>} segment only when the
 *       value declares a query, and the user-info component is omitted from the reassembled
 *       URL.</li>
 *   <li>An unrecognised scheme raises {@link IllegalStateException} naming the supported
 *       schemes.</li>
 * </ol>
 *
 * <p>Supported schemes and the JDBC vendor token each maps to: {@code postgresql} and
 * {@code postgres} map to {@code postgresql}; {@code mysql} and {@code mariadb} map to
 * {@code mysql}; {@code h2} maps to {@code h2}. The set matches the runtime-scope JDBC drivers
 * declared in backend/pom.xml: {@code org.postgresql:postgresql},
 * {@code com.mysql:mysql-connector-j} and {@code com.h2database:h2}.
 *
 * <p>Example, in which {@code USERNAME} and {@code PASSWORD} stand for the configured credentials:
 * <pre>{@code
 * var translated = DatabaseUrlTranslator.translate(
 *         "postgresql://USERNAME:PASSWORD@db.internal:5432/codeskeptic");
 * translated.jdbcUrl();   // jdbc:postgresql://db.internal:5432/codeskeptic
 * translated.username();  // USERNAME
 * translated.password();  // PASSWORD
 * }</pre>
 *
 * <p>Every member is static, the type holds no state and is not instantiable, and translation
 * mutates nothing. This type is safe for concurrent use.
 */
public final class DatabaseUrlTranslator {

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseUrlTranslator.class);

    private static final String JDBC_SCHEME_PREFIX = "jdbc:";
    private static final String AUTHORITY_SEPARATOR = "://";
    private static final String PLUS_SIGN = "+";
    private static final String ENCODED_PLUS = "%2B";
    private static final char DRIVER_SUFFIX_MARKER = '+';
    private static final char COLON = ':';
    private static final char AT_SIGN = '@';
    private static final char QUERY_MARKER = '?';
    private static final char IPV6_TERMINATOR = ']';
    private static final int NO_PORT = -1;
    private static final int MAX_PORT = 65535;

    /** Rendered by {@link TranslatedDatabaseUrl#toString()} in place of each of its values. */
    private static final String REDACTED = "***REDACTED***";

    /** The grammar named by every failure message. */
    private static final String EXPECTED_FORM = "<scheme>://[username[:password]@]host[:port]/database";

    /**
     * Maps a URL scheme, lower-cased and with any {@code +driver} suffix removed, to the JDBC
     * vendor token used in the reassembled URL.
     */
    private static final Map<String, String> JDBC_VENDOR_BY_SCHEME;

    static {
        final Map<String, String> vendors = new LinkedHashMap<>();
        vendors.put("postgresql", "postgresql");
        vendors.put("postgres", "postgresql");
        vendors.put("mysql", "mysql");
        vendors.put("mariadb", "mysql");
        vendors.put("h2", "h2");
        JDBC_VENDOR_BY_SCHEME = Collections.unmodifiableMap(vendors);
    }

    private static final String SUPPORTED_SCHEMES = String.join(", ", JDBC_VENDOR_BY_SCHEME.keySet());

    private DatabaseUrlTranslator() {
    }

    /**
     * The outcome of a translation.
     *
     * <p>Every component can carry credential material: a value that arrived as a JDBC URL is
     * passed through as supplied, and such a URL may embed credentials in its user-info component
     * or in a query-string property. {@link TranslatedDatabaseUrl#toString()} renders all three
     * components as the same fixed marker and reproduces none of them.
     *
     * @param jdbcUrl  the JDBC URL, never {@code null}, never blank and never carrying the
     *                 user-info component of the value it was translated from
     * @param username the username taken from the user-info component, or {@code null} when the
     *                 value carried none
     * @param password the password taken from the user-info component, or {@code null} when the
     *                 value carried none
     */
    public record TranslatedDatabaseUrl(String jdbcUrl, String username, String password) {

        /**
         * Rejects an absent or blank JDBC URL.
         *
         * @throws IllegalArgumentException if {@code jdbcUrl} is {@code null} or blank
         */
        public TranslatedDatabaseUrl {
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                throw new IllegalArgumentException("jdbcUrl must not be null or blank.");
            }
        }

        /**
         * Returns a fixed description of this outcome that carries none of its three values.
         *
         * <p>The same text is returned for every instance, so no JDBC URL, host, database name,
         * query parameter, username or password can reach diagnostic output through this method.
         * That holds for a reassembled {@code jdbcUrl} and equally for a value that already began
         * with {@code jdbc:} and was passed through unchanged.
         *
         * @return the fixed text {@code TranslatedDatabaseUrl[jdbcUrl=***REDACTED***,
         *     username=***REDACTED***, password=***REDACTED***]}
         */
        @Override
        public String toString() {
            return "TranslatedDatabaseUrl[jdbcUrl=" + REDACTED
                    + ", username=" + REDACTED
                    + ", password=" + REDACTED
                    + "]";
        }
    }

    /**
     * Translates a {@code DATABASE_URL} value into a JDBC URL and separate credentials.
     *
     * @param databaseUrl the configured {@code DATABASE_URL} value, consumed exactly as supplied; a
     *                    value beginning with the literal {@code jdbc:} is returned unchanged
     * @return the translated JDBC URL and the credentials taken from the value, never {@code null}
     * @throws IllegalStateException if the value is {@code null}, empty or whitespace-only; if it
     *                               cannot be parsed as a URL, which includes a value padded with
     *                               leading or trailing whitespace; if it declares no scheme or no
     *                               host; if its port is not an integer in
     *                               {@code 0..}{@value #MAX_PORT}; or if its scheme is not one of
     *                               the supported schemes
     */
    public static TranslatedDatabaseUrl translate(String databaseUrl) {
        if (databaseUrl == null || databaseUrl.isBlank()) {
            LOG.error("DATABASE_URL is not set; there is no database URL to translate.");
            throw new IllegalStateException("DATABASE_URL must be set: no database URL was supplied.");
        }

        if (databaseUrl.startsWith(JDBC_SCHEME_PREFIX)) {
            LOG.info("DATABASE_URL already holds a JDBC URL; it is used exactly as supplied and no "
                    + "credentials are extracted from it.");
            return new TranslatedDatabaseUrl(databaseUrl, null, null);
        }

        final URI uri = parseUri(databaseUrl);
        final String vendor = resolveVendor(uri.getScheme());

        String host = uri.getHost();
        int port = uri.getPort();
        String rawUserInfo = uri.getRawUserInfo();

        if (host == null || host.isEmpty()) {
            // Registry-based authority: java.net.URI leaves host, port and user-info unset.
            final Authority authority = splitAuthority(uri.getRawAuthority());
            rawUserInfo = authority.userInfo();
            host = authority.host();
            port = authority.port();
        }

        if (host == null || host.isEmpty()) {
            LOG.error("DATABASE_URL declares no host.");
            throw new IllegalStateException("DATABASE_URL declares no host: expected " + EXPECTED_FORM + ".");
        }

        port = validatePort(port);

        final UserInfo credentials = splitUserInfo(rawUserInfo);
        final String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        final String query = uri.getRawQuery();

        final StringBuilder jdbcUrl = new StringBuilder(JDBC_SCHEME_PREFIX)
                .append(vendor)
                .append(AUTHORITY_SEPARATOR)
                .append(host);
        if (port != NO_PORT) {
            jdbcUrl.append(COLON).append(port);
        }
        jdbcUrl.append(path);

        if (query != null && !query.isEmpty()) {
            jdbcUrl.append(QUERY_MARKER).append(query);
        }

        // Logging baseline - see docs/DECISION_LOG.md DL-052. The record names the resolved vendor
        // only; host, port, database path, query and user-info are omitted.
        LOG.info("Translated DATABASE_URL to a JDBC URL for vendor '{}'", vendor);
        return new TranslatedDatabaseUrl(jdbcUrl.toString(), credentials.username(), credentials.password());
    }

    /**
     * Parses the {@code DATABASE_URL} value exactly as supplied.
     *
     * @throws IllegalStateException if the value is not a parseable URI
     */
    private static URI parseUri(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            final String reason = e.getReason() == null ? "malformed URL" : e.getReason();
            LOG.error("DATABASE_URL is not a parseable URL: {}.", reason);
            throw new IllegalStateException("DATABASE_URL is not a parseable URL (" + reason
                    + "): expected " + EXPECTED_FORM + ".");
        }
    }

    /**
     * Discards any {@code +driver} suffix and matches the remaining scheme case-insensitively.
     *
     * @throws IllegalStateException if the scheme is absent or unsupported
     */
    private static String resolveVendor(String rawScheme) {
        if (rawScheme == null || rawScheme.isBlank()) {
            LOG.error("DATABASE_URL declares no scheme. Supported schemes: {}.", SUPPORTED_SCHEMES);
            throw new IllegalStateException("DATABASE_URL declares no scheme: expected " + EXPECTED_FORM
                    + " with one of the supported schemes " + SUPPORTED_SCHEMES + ".");
        }

        final int driverSuffix = rawScheme.indexOf(DRIVER_SUFFIX_MARKER);
        final String scheme = (driverSuffix < 0 ? rawScheme : rawScheme.substring(0, driverSuffix))
                .toLowerCase(Locale.ROOT);

        final String vendor = JDBC_VENDOR_BY_SCHEME.get(scheme);
        if (vendor == null) {
            LOG.error("DATABASE_URL declares the unsupported scheme '{}'. Supported schemes: {}.",
                    scheme, SUPPORTED_SCHEMES);
            throw new IllegalStateException("DATABASE_URL declares the unsupported scheme '" + scheme
                    + "': supported schemes are " + SUPPORTED_SCHEMES + ".");
        }
        return vendor;
    }

    /**
     * Splits a raw user-info component on its first literal {@code ':'} and percent-decodes each half
     * separately; either part may be absent. Splitting before decoding keeps an encoded {@code ':'}
     * inside a username or password out of the separator search.
     */
    private static UserInfo splitUserInfo(String rawUserInfo) {
        if (rawUserInfo == null || rawUserInfo.isEmpty()) {
            return new UserInfo(null, null);
        }
        final int separator = rawUserInfo.indexOf(COLON);
        if (separator < 0) {
            return new UserInfo(decodeUriComponent(rawUserInfo), null);
        }
        return new UserInfo(
                decodeUriComponent(rawUserInfo.substring(0, separator)),
                decodeUriComponent(rawUserInfo.substring(separator + 1)));
    }

    /**
     * Splits a raw authority component into its user-info, host and port parts. Applied only when
     * {@link URI} reported no host, which happens for a registry-based authority.
     *
     * @throws IllegalStateException if the port is not an integer
     */
    private static Authority splitAuthority(String rawAuthority) {
        if (rawAuthority == null || rawAuthority.isBlank()) {
            return new Authority(null, null, NO_PORT);
        }

        String userInfo = null;
        String hostAndPort = rawAuthority;
        final int at = rawAuthority.lastIndexOf(AT_SIGN);
        if (at >= 0) {
            userInfo = rawAuthority.substring(0, at);
            hostAndPort = rawAuthority.substring(at + 1);
        }

        String host = hostAndPort;
        String portToken = null;
        final int separator = hostAndPort.lastIndexOf(COLON);
        // A ':' inside an IPv6 literal is part of the host, not a port separator.
        if (separator > hostAndPort.lastIndexOf(IPV6_TERMINATOR)) {
            host = hostAndPort.substring(0, separator);
            portToken = hostAndPort.substring(separator + 1);
        }

        final String presentUserInfo = (userInfo == null || userInfo.isEmpty()) ? null : userInfo;
        return new Authority(presentUserInfo, host, parsePort(portToken));
    }

    /**
     * Parses a port token, yielding {@value #NO_PORT} when the authority declared none.
     *
     * @throws IllegalStateException if the token is not an integer
     */
    private static int parsePort(String portToken) {
        if (portToken == null || portToken.isEmpty()) {
            return NO_PORT;
        }
        try {
            return Integer.parseInt(portToken);
        } catch (NumberFormatException e) {
            LOG.error("DATABASE_URL declares a non-numeric port.");
            throw new IllegalStateException("DATABASE_URL declares a non-numeric port: expected an "
                    + "integer between 0 and " + MAX_PORT + ".");
        }
    }

    /**
     * Checks a resolved port, however it was resolved, and passes {@value #NO_PORT} through.
     *
     * @throws IllegalStateException if the port is outside {@code 0..}{@value #MAX_PORT}
     */
    private static int validatePort(int port) {
        if (port == NO_PORT) {
            return NO_PORT;
        }
        if (port < 0 || port > MAX_PORT) {
            LOG.error("DATABASE_URL declares the out-of-range port {}.", port);
            throw new IllegalStateException("DATABASE_URL declares the out-of-range port " + port
                    + ": expected an integer between 0 and " + MAX_PORT + ".");
        }
        return port;
    }

    /**
     * Percent-decodes a URI component, passing {@code null} and the empty string through unchanged. A
     * literal {@code '+'} is preserved as {@code '+'} and is not decoded to a space.
     *
     * @throws IllegalStateException if the component carries a malformed percent-escape
     */
    private static String decodeUriComponent(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            return URLDecoder.decode(value.replace(PLUS_SIGN, ENCODED_PLUS), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            LOG.error("DATABASE_URL carries a malformed percent-escape in its user-info component.");
            throw new IllegalStateException(
                    "DATABASE_URL carries a malformed percent-escape in its user-info component.");
        }
    }

    /**
     * The percent-decoded text before and after the first literal {@code ':'} of a raw user-info
     * component; either may be null.
     */
    private record UserInfo(String username, String password) {
    }

    /** The parts of an authority component; {@code port} is {@value #NO_PORT} when absent. */
    private record Authority(String userInfo, String host, int port) {
    }
}
