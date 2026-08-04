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
 * <p>Behaviour contract, applied in this order:
 * <ol>
 *   <li>A {@code null}, empty or whitespace-only value raises {@link IllegalStateException}.</li>
 *   <li>A value already beginning with {@code jdbc:} is returned unchanged, with a {@code null}
 *       username and a {@code null} password. It is not parsed, normalised or stripped.</li>
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

    /** The grammar named by every failure message. */
    private static final String EXPECTED_FORM = "<scheme>://[username[:password]@]host[:port]/database";

    /**
     * Maps a URL scheme, lower-cased and with any {@code +driver} suffix removed, to the JDBC
     * vendor token used in the reassembled URL. Insertion-ordered; {@link #SUPPORTED_SCHEMES} is
     * derived from its key set.
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

    /** Derived from the key set of {@link #JDBC_VENDOR_BY_SCHEME}. */
    private static final String SUPPORTED_SCHEMES = String.join(", ", JDBC_VENDOR_BY_SCHEME.keySet());

    private DatabaseUrlTranslator() {
        // Utility type; every member is static.
    }

    /**
     * The outcome of a translation.
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
         * @throws IllegalArgumentException if {@code jdbcUrl} is {@code null} or blank
         */
        public TranslatedDatabaseUrl {
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                throw new IllegalArgumentException("jdbcUrl must not be null or blank.");
            }
        }

        /**
         * Returns a description of this outcome in which the password is replaced by a fixed
         * marker.
         *
         * @return a description of this outcome carrying no password material
         */
        @Override
        public String toString() {
            return "TranslatedDatabaseUrl[jdbcUrl=" + jdbcUrl
                    + ", username=" + username
                    + ", password=" + (password == null ? "null" : "<redacted>")
                    + "]";
        }
    }

    /**
     * Translates a {@code DATABASE_URL} value into a JDBC URL and separate credentials.
     *
     * @param databaseUrl the configured {@code DATABASE_URL} value; a value already beginning with
     *                    {@code jdbc:} is returned unchanged
     * @return the translated JDBC URL and the credentials taken from the value, never {@code null}
     * @throws IllegalStateException if the value is {@code null}, empty or whitespace-only; if it
     *                               cannot be parsed as a URL; if it declares no scheme or no
     *                               host; if its port is not an integer in
     *                               {@code 0..}{@value #MAX_PORT}; or if its scheme is not one of
     *                               the supported schemes
     */
    public static TranslatedDatabaseUrl translate(String databaseUrl) {
        if (databaseUrl == null || databaseUrl.isBlank()) {
            LOG.error("DATABASE_URL is not set; there is no database URL to translate.");
            throw new IllegalStateException("DATABASE_URL must be set: no database URL was supplied.");
        }

        final String value = databaseUrl.trim();

        if (value.regionMatches(true, 0, JDBC_SCHEME_PREFIX, 0, JDBC_SCHEME_PREFIX.length())) {
            LOG.info("DATABASE_URL already holds a JDBC URL; it is used unchanged and no credentials "
                    + "are extracted from it.");
            return new TranslatedDatabaseUrl(value, null, null);
        }

        final URI uri = parseUri(value);
        final String vendor = resolveVendor(uri.getScheme());

        String host = uri.getHost();
        int port = uri.getPort();
        String userInfo = uri.getUserInfo();

        if (host == null || host.isEmpty()) {
            // java.net.URI leaves host, port and user-info unset for a registry-based authority,
            // which includes any authority whose host name contains '_'.
            final Authority authority = splitAuthority(uri.getRawAuthority());
            userInfo = decodeUriComponent(authority.userInfo());
            host = authority.host();
            port = authority.port();
        }

        if (host == null || host.isEmpty()) {
            LOG.error("DATABASE_URL declares no host.");
            throw new IllegalStateException("DATABASE_URL declares no host: expected " + EXPECTED_FORM + ".");
        }

        port = validatePort(port);

        final UserInfo credentials = splitUserInfo(userInfo);
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

        // Captured before the query segment is appended; the log record below excludes the query.
        final String loggableUrl = jdbcUrl.toString();

        if (query != null && !query.isEmpty()) {
            jdbcUrl.append(QUERY_MARKER).append(query);
        }

        // Logging baseline - see docs/DECISION_LOG.md DL-052.
        LOG.info("Translated DATABASE_URL to JDBC vendor '{}': {}", vendor, loggableUrl);
        return new TranslatedDatabaseUrl(jdbcUrl.toString(), credentials.username(), credentials.password());
    }

    /**
     * Parses a value as a URI.
     *
     * @param value the trimmed {@code DATABASE_URL} value
     * @return the parsed URI
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
     * Maps a URL scheme to a JDBC vendor token, discarding any {@code +driver} suffix and matching
     * case-insensitively.
     *
     * @param rawScheme the scheme exactly as the URI reported it
     * @return the JDBC vendor token
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
     * Splits a user-info component on its first {@code ':'}.
     *
     * @param userInfo the decoded user-info component, or {@code null}
     * @return the username and the password, either or both of which may be {@code null}
     */
    private static UserInfo splitUserInfo(String userInfo) {
        if (userInfo == null || userInfo.isEmpty()) {
            return new UserInfo(null, null);
        }
        final int separator = userInfo.indexOf(COLON);
        if (separator < 0) {
            return new UserInfo(userInfo, null);
        }
        return new UserInfo(userInfo.substring(0, separator), userInfo.substring(separator + 1));
    }

    /**
     * Splits a raw authority component into its user-info, host and port parts.
     *
     * @param rawAuthority the authority exactly as the URI reported it, or {@code null}
     * @return the parts of the authority; the user-info and host are {@code null} when absent and
     *         the port is {@value #NO_PORT} when absent
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
     * Parses a port token.
     *
     * @param portToken the port text, or {@code null} when the authority declared none
     * @return the port, or {@value #NO_PORT} when the authority declared none
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
     * Checks that a resolved port is usable. Applied to the port however it was resolved.
     *
     * @param port the resolved port, or {@value #NO_PORT} when the value declared none
     * @return the port, or {@value #NO_PORT} when the value declared none
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
     * Percent-decodes a URI component.
     *
     * @param value the raw component, or {@code null}
     * @return the decoded component, or {@code null} when the component was {@code null}
     * @throws IllegalStateException if the component carries a malformed percent-escape
     */
    private static String decodeUriComponent(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        try {
            // URLDecoder maps '+' to a space; the literal '+' is escaped before decoding.
            return URLDecoder.decode(value.replace(PLUS_SIGN, ENCODED_PLUS), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            LOG.error("DATABASE_URL carries a malformed percent-escape in its user-info component.");
            throw new IllegalStateException(
                    "DATABASE_URL carries a malformed percent-escape in its user-info component.");
        }
    }

    /**
     * The username and password taken from a user-info component.
     *
     * @param username the text before the first {@code ':'}, or {@code null} when absent
     * @param password the text after the first {@code ':'}, or {@code null} when absent
     */
    private record UserInfo(String username, String password) {
    }

    /**
     * The parts of an authority component.
     *
     * @param userInfo the raw user-info component, or {@code null} when absent
     * @param host     the host, or {@code null} when absent
     * @param port     the port, or {@value #NO_PORT} when absent
     */
    private record Authority(String userInfo, String host, int port) {
    }
}
