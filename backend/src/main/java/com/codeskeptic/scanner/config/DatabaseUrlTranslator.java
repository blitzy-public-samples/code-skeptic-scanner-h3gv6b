package com.codeskeptic.scanner.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Net-new class derived from backend/app/core/config.py:L9 — the opaque DATABASE_URL the retired
// tree read at backend/app/db/database.py:L5 and never interpreted — DL-027, DL-071, DL-072 — see
// docs/DECISION_LOG.md
/**
 * Translates the opaque {@code DATABASE_URL} value, bound to {@code scanner.database-url}, into a
 * JDBC URL together with the username and the password as separate values.
 *
 * <p>Behaviour contract, applied in this order — DL-064, DL-072:
 * <ol>
 *   <li>A {@code null}, empty, whitespace-only or unresolved {@code ${DATABASE_URL}} value raises
 *       {@link IllegalStateException} — DL-186.</li>
 *   <li>A value beginning with the literal lower-case {@code jdbc:} is returned character for
 *       character with a {@code null} username and password: not parsed, not trimmed, scheme matched
 *       case-sensitively, credential material passed through — DL-064.</li>
 *   <li>Any other value is parsed as a {@link URI}: everything from the first {@code '+'} of the
 *       scheme onward is discarded, the remaining scheme is mapped case-insensitively to a vendor, the
 *       user-info component is split on its first {@code ':'}, any recognised credential property is
 *       taken out of the query under either separator, and the result is reassembled as
 *       {@code <jdbc-authority-prefix><host>[:<port>]<path>[?<query>]}.</li>
 *   <li>An unrecognised scheme raises {@link IllegalStateException} naming the supported schemes.</li>
 * </ol>
 *
 * <p>Apart from {@link #isUnset(String)}, which tests a trimmed copy that never reaches the returned
 * URL, the value is consumed exactly as supplied, so a whitespace-padded value is unparseable —
 * DL-186. On the parse path the returned URL carries neither user-info nor any of the nine recognised
 * credential property names; every other property is retained verbatim, in its original order and
 * with its preceding separator, a vendor property naming a driver secret included — DL-072.
 *
 * <p>Scheme map: {@code postgresql} and {@code postgres} onto {@code jdbc:postgresql://},
 * {@code mysql} and {@code mariadb} onto {@code jdbc:mysql://}, {@code h2} onto {@code jdbc:h2://} —
 * one entry per runtime-scope driver — DL-009, DL-028, DL-071. Verified server products are
 * PostgreSQL 16 and MySQL 8.4; a {@code mariadb} value translates onto the MySQL vendor and records a
 * warning naming that unverified combination — DL-187.
 */
public final class DatabaseUrlTranslator {

    /**
     * Shape of a Spring property placeholder that resolved to nothing. Binding leaves such a
     * placeholder in place as literal text when the environment variable behind it is absent — DL-186.
     */
    private static final Pattern UNRESOLVED_PLACEHOLDER =
            Pattern.compile("^\\$\\{.*}$", Pattern.DOTALL);

    private static final Logger LOG = LoggerFactory.getLogger(DatabaseUrlTranslator.class);

    private static final String JDBC_SCHEME_PREFIX = "jdbc:";
    private static final String AUTHORITY_SEPARATOR = "://";
    private static final String PLUS_SIGN = "+";
    private static final String ENCODED_PLUS = "%2B";
    private static final char DRIVER_SUFFIX_MARKER = '+';
    private static final char COLON = ':';
    private static final char AT_SIGN = '@';
    private static final char QUERY_MARKER = '?';
    private static final char FRAGMENT_MARKER = '#';
    private static final char EQUALS_SIGN = '=';
    private static final char IPV6_TERMINATOR = ']';
    private static final int NO_PORT = -1;
    private static final int MAX_PORT = 65535;

    private static final String REDACTED = "***REDACTED***";

    private static final String EXPECTED_FORM = "<scheme>://[username[:password]@]host[:port]/database";

    private static final Map<String, Vendor> VENDOR_BY_SCHEME;

    // Scheme-to-vendor map of AAP 0.6.5.1; mariadb resolves onto the MySQL vendor — DL-187. One entry
    // per runtime-scope driver, H2 included — DL-009, DL-071 — see docs/DECISION_LOG.md
    static {
        final Map<String, Vendor> vendors = new LinkedHashMap<>();
        vendors.put("postgresql", Vendor.POSTGRESQL);
        vendors.put("postgres", Vendor.POSTGRESQL);
        vendors.put("mysql", Vendor.MYSQL);
        vendors.put("mariadb", Vendor.MYSQL);
        vendors.put("h2", Vendor.H2);
        VENDOR_BY_SCHEME = Collections.unmodifiableMap(vendors);
    }

    private static final String SUPPORTED_SCHEMES = String.join(", ", VENDOR_BY_SCHEME.keySet());

    /**
     * Scheme whose translation is accompanied by a warning: it resolves onto a vendor whose driver
     * this service ships, against a server product the service is not verified against — DL-187 —
     * see docs/DECISION_LOG.md.
     */
    private static final String UNVERIFIED_SERVER_SCHEME = "mariadb";

    /**
     * The JDBC property names treated as credential material wherever they appear in a URL. Matched
     * case-insensitively against the text before a property's {@code '='} — DL-072 — see
     * docs/DECISION_LOG.md.
     */
    private static final Set<String> CREDENTIAL_PROPERTY_NAMES = Set.of(
            "user", "username", "uid", "password", "passwd", "pwd",
            "password1", "password2", "password3");

    /**
     * The primary credential names whose value fills the <em>username</em> half of the pair. Every
     * other member of {@link #CREDENTIAL_PROPERTY_NAMES} fills the password half — DL-072.
     */
    private static final Set<String> IDENTITY_PROPERTY_NAMES = Set.of("user", "username", "uid");

    private static final Pattern PROPERTY_SEPARATOR = Pattern.compile("[?&;]");

    /**
     * Separates properties inside the query component of a parsed, non-JDBC URL. It is the same set
     * {@link #PROPERTY_SEPARATOR} detects with, minus the {@code '?'} that opens the component, so
     * extraction and detection read one grammar — DL-072 — see docs/DECISION_LOG.md.
     */
    private static final Pattern QUERY_PROPERTY_SEPARATOR = Pattern.compile("[&;]");

    /**
     * A supported JDBC vendor and the exact URL prefix its driver requires ahead of the authority.
     *
     * <p>One constant per driver the executable jar carries — DL-028, DL-071 — see
     * docs/DECISION_LOG.md.
     */
    private enum Vendor {

        POSTGRESQL("postgresql", "jdbc:postgresql://"),
        MYSQL("mysql", "jdbc:mysql://"),
        H2("h2", "jdbc:h2://");

        private final String token;
        private final String jdbcAuthorityPrefix;

        Vendor(String token, String jdbcAuthorityPrefix) {
            this.token = token;
            this.jdbcAuthorityPrefix = jdbcAuthorityPrefix;
        }

            String token() {
            return token;
        }

            String jdbcAuthorityPrefix() {
            return jdbcAuthorityPrefix;
        }
    }

    private DatabaseUrlTranslator() {
    }

    /**
     * The outcome of a translation.
     *
     * <p>{@code username} and {@code password} carry credential material.
     * {@link TranslatedDatabaseUrl#toString()} redacts all three components, and {@code jdbcUrl}
     * contains no recognised credential material — DL-072.
     *
     * @param jdbcUrl  the JDBC URL, never {@code null}, never blank, and never carrying a username
     *                 or a password in its user-info component or in a property
     * @param username the username taken from the user-info component or from a recognised
     *                 credential property, or {@code null} when the value carried none
     * @param password the password taken from the user-info component or from a recognised
     *                 credential property, or {@code null} when the value carried none
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
         * <p>The same text is returned for every instance. No JDBC URL, host, database name, query
         * parameter, username or password reaches diagnostic output through this method.
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
     *                    value beginning with the literal {@code jdbc:} is passed through unchanged
     * @return the translated JDBC URL and the credentials taken from the value, never {@code null}
     * @throws IllegalStateException if the value is {@code null}, empty, whitespace-only or an
     *                               unresolved {@code ${DATABASE_URL}} placeholder; or, on the parse
     *                               path, if it cannot be parsed as a URL, which includes a value
     *                               padded with leading or trailing whitespace; if it declares no
     *                               scheme or no host; if its port is not an integer in
     *                               {@code 0..}{@value #MAX_PORT}; or if its scheme is not one of the
     *                               supported schemes
     */
    public static TranslatedDatabaseUrl translate(String databaseUrl) {
        if (isUnset(databaseUrl)) {
            LOG.error("DATABASE_URL is not set; there is no database URL to translate.");
            throw new IllegalStateException("DATABASE_URL must be set: no database URL was supplied.");
        }

        if (databaseUrl.startsWith(JDBC_SCHEME_PREFIX)) {
            // AAP 0.6.5.1: pass through unchanged anything already beginning with jdbc: — DL-064,
            // DL-072 — see docs/DECISION_LOG.md
            LOG.info("DATABASE_URL already holds a JDBC URL; it is used exactly as supplied and no "
                    + "credentials are extracted from it.");
            return new TranslatedDatabaseUrl(databaseUrl, null, null);
        }

        final URI uri = parseUri(databaseUrl);
        final Vendor vendor = resolveVendor(uri.getScheme());

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

        final UserInfo userInfoCredentials = splitUserInfo(rawUserInfo);
        final String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        final Query query = partitionQuery(uri.getRawQuery(), userInfoCredentials);

        final StringBuilder jdbcUrl = new StringBuilder(vendor.jdbcAuthorityPrefix()).append(host);
        if (port != NO_PORT) {
            jdbcUrl.append(COLON).append(port);
        }
        jdbcUrl.append(path);

        if (!query.retained().isEmpty()) {
            jdbcUrl.append(QUERY_MARKER);
            for (int index = 0; index < query.retained().size(); index++) {
                final Property retained = query.retained().get(index);
                if (index > 0) {
                    jdbcUrl.append(retained.separator());
                }
                jdbcUrl.append(retained.text());
            }
        }

        final String assembled = jdbcUrl.toString();

        // Logging baseline - see docs/DECISION_LOG.md DL-052. The record names the resolved vendor
        // only; host, port, database path, query and user-info are omitted.
        LOG.info("Translated DATABASE_URL to a JDBC URL for vendor '{}'", vendor.token());
        return new TranslatedDatabaseUrl(assembled, query.credentials().username(), query.credentials().password());
    }

    /**
     * Reads the name of a {@code name=value} property token.
     *
     * @param token one property segment, never {@code null}
     * @return the text before the first {@code '='}, stripped; the whole stripped token when it
     *     carries no {@code '='}
     */
    private static String propertyName(String token) {
        final int equals = token.indexOf(EQUALS_SIGN);
        return (equals < 0 ? token : token.substring(0, equals)).strip();
    }

    /**
     * Splits a raw query component into the properties the reassembled URL retains and the
     * credentials taken out of it.
     *
     * <p>Properties are separated on {@code '&'} and on {@code ';'} alike, so a credential property is
     * extracted under either separator and neither separator can carry one into the reassembled URL —
     * DL-072 — see docs/DECISION_LOG.md.
     *
     * <p>A property whose name is recognised by {@link #CREDENTIAL_PROPERTY_NAMES} is removed from the
     * query and, when the user-info component did not already supply that half of the credentials, its
     * percent-decoded value becomes the username or the password. Every other property is retained
     * verbatim, in its original order, and with the separator that preceded it in the supplied value;
     * a retained property that becomes the first one carries no separator.
     *
     * @param rawQuery      the raw query component, or {@code null} when the value declared none
     * @param fromUserInfo  the credentials already taken from the user-info component
     * @return the retained properties and the resolved credentials, never {@code null}
     */
    private static Query partitionQuery(String rawQuery, UserInfo fromUserInfo) {
        String username = fromUserInfo.username();
        String password = fromUserInfo.password();

        if (rawQuery == null || rawQuery.isEmpty()) {
            return new Query(List.of(), new UserInfo(username, password));
        }

        final List<Property> retained = new ArrayList<>();
        for (Property property : splitProperties(rawQuery)) {
            final String token = property.text();
            if (token.isEmpty()) {
                continue;
            }
            final int equals = token.indexOf(EQUALS_SIGN);
            final String name = (equals < 0 ? token : token.substring(0, equals)).strip().toLowerCase(Locale.ROOT);
            if (!CREDENTIAL_PROPERTY_NAMES.contains(name)) {
                retained.add(property);
                continue;
            }
            final String value = equals < 0 ? "" : decodeUriComponent(token.substring(equals + 1));
            if (IDENTITY_PROPERTY_NAMES.contains(name)) {
                if (username == null) {
                    username = value;
                }
            } else if (password == null) {
                password = value;
            }
        }
        return new Query(Collections.unmodifiableList(retained), new UserInfo(username, password));
    }

    /**
     * Splits a raw query component into its properties, keeping the separator that preceded each one.
     *
     * <p>The first property carries the empty separator: {@code '?'} precedes it, not a property
     * separator. Every later property carries the {@code '&'} or {@code ';'} that separated it from
     * the property before it in the supplied value — DL-072 — see docs/DECISION_LOG.md.
     *
     * @param rawQuery the raw query component, never {@code null} and never empty
     * @return the properties in their original order, never {@code null}
     */
    private static List<Property> splitProperties(String rawQuery) {
        final List<Property> properties = new ArrayList<>();
        final Matcher separators = QUERY_PROPERTY_SEPARATOR.matcher(rawQuery);
        String separator = "";
        int from = 0;
        while (separators.find()) {
            properties.add(new Property(separator, rawQuery.substring(from, separators.start())));
            separator = separators.group();
            from = separators.end();
        }
        properties.add(new Property(separator, rawQuery.substring(from)));
        return properties;
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
     * <p>{@value #UNVERIFIED_SERVER_SCHEME} resolves onto the MySQL vendor and records a warning
     * naming the unverified server product — DL-187 — see docs/DECISION_LOG.md.
     *
     * @throws IllegalStateException if the scheme is absent or unsupported
     */
    private static Vendor resolveVendor(String rawScheme) {
        if (rawScheme == null || rawScheme.isBlank()) {
            LOG.error("DATABASE_URL declares no scheme. Supported schemes: {}.", SUPPORTED_SCHEMES);
            throw new IllegalStateException("DATABASE_URL declares no scheme: expected " + EXPECTED_FORM
                    + " with one of the supported schemes " + SUPPORTED_SCHEMES + ".");
        }

        final int driverSuffix = rawScheme.indexOf(DRIVER_SUFFIX_MARKER);
        final String scheme = (driverSuffix < 0 ? rawScheme : rawScheme.substring(0, driverSuffix))
                .toLowerCase(Locale.ROOT);

        if (UNVERIFIED_SERVER_SCHEME.equals(scheme)) {
            LOG.warn("DATABASE_URL declares the '{}' scheme, which translates onto the MySQL vendor "
                    + "served by com.mysql:mysql-connector-j. A MariaDB server is not one of the "
                    + "server products this service is verified against; dialect resolution can fail "
                    + "after the connection pool is created. See backend/docs/DECISION_LOG.md DL-187.",
                    UNVERIFIED_SERVER_SCHEME);
        }

        final Vendor vendor = VENDOR_BY_SCHEME.get(scheme);
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
     * separately; either part may be absent. An encoded {@code ':'} inside a username or password is
     * not a separator — see docs/DECISION_LOG.md DL-072.
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

    /**
     * The outcome of partitioning a query component: the properties the reassembled URL keeps, in
     * their original order and raw form, and the credentials resolved from the user-info component
     * and from any recognised credential property.
     */
    private record Query(List<Property> retained, UserInfo credentials) {
    }

    /**
     * One property of a query component together with the separator that preceded it in the supplied
     * value. The first property of a component carries the empty separator — DL-072.
     */
    private record Property(String separator, String text) {
    }

    /** The parts of an authority component; {@code port} is {@value #NO_PORT} when absent. */
    private record Authority(String userInfo, String host, int port) {
    }

    /**
     * Reports whether a bound configuration value carries no usable configuration.
     *
     * <p>A {@code null} value, a blank value and an unresolved {@code ${...}} placeholder are all
     * treated as unset. Configuration binding leaves an unresolved placeholder in place as literal
     * text when the environment variable behind it is absent, so the bound value is neither
     * {@code null} nor blank — DL-186.
     *
     * @param value the bound value, possibly {@code null}
     * @return {@code true} when the value is {@code null}, blank, or an unresolved placeholder
     */
    private static boolean isUnset(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return UNRESOLVED_PLACEHOLDER.matcher(value.trim()).matches();
    }

}
