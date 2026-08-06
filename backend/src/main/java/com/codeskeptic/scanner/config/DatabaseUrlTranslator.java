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

import com.codeskeptic.scanner.util.ConfiguredValues;

/**
 * Translates the opaque {@code DATABASE_URL} value, bound to {@code scanner.database-url}, into a
 * JDBC URL together with the username and the password as separate values.
 *
 * <p>Existing lower-case {@code jdbc:} values pass through after credential checks. SQLAlchemy-style
 * PostgreSQL, MySQL and MariaDB URLs are parsed into vendor JDBC URLs; user-info and recognised
 * credential query properties are returned separately. Unsupported, malformed, unresolved or
 * credential-bearing JDBC values fail with {@link IllegalStateException} — DL-072 — see
 * docs/DECISION_LOG.md.
 *
 * <p>The value is consumed exactly as supplied: no character of it is trimmed, case-folded or
 * otherwise normalised on the way into a returned URL. A value padded with leading or trailing
 * whitespace is not a parseable URL and raises {@link IllegalStateException}. Classification is the
 * one place trimmed text is used: {@link ConfiguredValues#isUnset(String)} trims before testing for
 * blankness and for the unresolved-placeholder shape, so a padded {@code ${DATABASE_URL}} is
 * recognised as unset — DL-186. That trimmed copy is used for the test alone and never reaches the
 * returned URL.
 *
 * <p>Behaviour contract, applied in this order:
 * <ol>
 *   <li>A {@code null}, empty or whitespace-only value raises {@link IllegalStateException}. So does
 *       an unresolved {@code ${DATABASE_URL}} placeholder, the literal text that binds when the
 *       environment variable is absent — DL-186 — see docs/DECISION_LOG.md.</li>
 *   <li>A value beginning with the literal lower-case {@code jdbc:} is returned exactly as
 *       supplied, character for character, with a {@code null} username and a {@code null}
 *       password. It is not parsed, rewritten, trimmed or stripped, and its scheme is matched
 *       case-sensitively. Such a value carrying credential material is rejected; it is never
 *       altered — DL-072 — see docs/DECISION_LOG.md.</li>
 *   <li>Any other value is parsed as a {@link URI}. Everything from the first {@code '+'} of the
 *       scheme onward is discarded, the remaining scheme is mapped case-insensitively to a JDBC
 *       vendor, the user-info component is split on its first {@code ':'} into the username and the
 *       password, any recognised credential property is taken out of the query under either property
 *       separator, and the URL is
 *       reassembled as {@code <jdbc-authority-prefix><host>[:<port>]<path>[?<query>]}. The
 *       {@code :<port>} segment is present only when the value declares a port, the
 *       {@code ?<query>} segment only when at least one property is retained, and neither the
 *       user-info component nor any credential property appears in the reassembled URL.</li>
 *   <li>An unrecognised scheme raises {@link IllegalStateException} naming the supported
 *       schemes.</li>
 * </ol>
 *
 * <p>{@link TranslatedDatabaseUrl#jdbcUrl()} never carries a username, a password or any other
 * secret-bearing property, on either path — DL-072 — see docs/DECISION_LOG.md. Two property
 * classes are recognised, both case-insensitively:
 * <ul>
 *   <li>A <b>primary credential</b> property, named {@code user}, {@code username}, {@code uid},
 *       {@code password}, {@code passwd}, {@code pwd}, {@code password1}, {@code password2} or
 *       {@code password3}. Its name is matched exactly. On the parse path its value fills whichever
 *       half of the credential pair the user-info component left unset, and the property is removed
 *       from the URL.</li>
 *   <li>A <b>secondary secret</b> property, whose name carries one of the tokens
 *       {@code password}, {@code passwd}, {@code pwd}, {@code passphrase}, {@code secret},
 *       {@code credential} or {@code token} anywhere within it — {@code sslpassword},
 *       {@code trustCertificateKeyStorePassword} and {@code xdevapi.ssl-truststore-password} among
 *       them. There is nowhere to bind such a value to, so the whole URL is <em>rejected</em>, with a
 *       message naming the offending property names and no value of any of them.</li>
 * </ul>
 *
 * <p>Extraction and detection read one grammar — DL-072:
 * <ul>
 *   <li><b>Extraction</b>, on the parse path, splits the query component on {@code '&'} and
 *       {@code ';'} alike. A credential property is removed under either separator and fills whichever
 *       credential the user-info component left unset. Every other property is retained verbatim, in
 *       its original order, and with the separator that preceded it in the supplied value; a retained
 *       property that becomes the first one carries no separator. A query whose every property is a
 *       credential leaves no {@code ?} segment at all.</li>
 *   <li><b>Detection</b> splits on {@code '?'}, {@code '&'} and {@code ';'} alike and runs over the
 *       reassembled URL, so it is a check on the outcome and not a second grammar. On the parse
 *       path extraction has already removed every credential property, so detection finds none. On
 *       the {@code jdbc:} pass-through path nothing is extracted, so a value carrying a credential
 *       property under any of the three separators is <em>rejected</em> and never altered.</li>
 * </ul>
 *
 * <p>Supported schemes and the JDBC authority prefix each maps to: {@code postgresql} and
 * {@code postgres} map to {@code jdbc:postgresql://}; {@code mysql} and {@code mariadb} map to
 * {@code jdbc:mysql://} — DL-187 — see docs/DECISION_LOG.md. The set matches the runtime-scope JDBC
 * drivers declared in backend/pom.xml, which are exactly {@code org.postgresql:postgresql} and
 * {@code com.mysql:mysql-connector-j}. There is no {@code h2} scheme: {@code com.h2database:h2} is a
 * {@code test}-scope coordinate and is absent from the executable jar, so H2 is reachable only as a
 * literal {@code jdbc:h2:} value on the pass-through path, which is what
 * src/test/resources/application-test.yml supplies — DL-071, DL-242 — see docs/DECISION_LOG.md.
 *
 * <p>Server products this service is verified against: PostgreSQL 16 and MySQL 8.4. A
 * {@code mariadb} value translates onto the MySQL vendor and a warning naming that unverified
 * combination is recorded whenever the scheme is declared — DL-187 — see docs/DECISION_LOG.md.
 *
 * <p>Examples, in which {@code USERNAME} and {@code PASSWORD} stand for the configured credentials:
 * <pre>{@code
 * var translated = DatabaseUrlTranslator.translate(
 *         "postgresql://USERNAME:PASSWORD@db.internal:5432/codeskeptic");
 * translated.jdbcUrl();   // jdbc:postgresql://db.internal:5432/codeskeptic
 * translated.username();  // USERNAME
 * translated.password();  // PASSWORD
 *
 * DatabaseUrlTranslator.translate("mariadb://db.internal:3306/codeskeptic")
 *         .jdbcUrl();     // jdbc:mysql://db.internal:3306/codeskeptic
 *
 * DatabaseUrlTranslator.translate("jdbc:h2:mem:scanner_test")
 *         .jdbcUrl();     // jdbc:h2:mem:scanner_test, passed through unchanged
 * }</pre>
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
    private static final char FRAGMENT_MARKER = '#';
    private static final char EQUALS_SIGN = '=';
    private static final char IPV6_TERMINATOR = ']';
    private static final int NO_PORT = -1;
    private static final int MAX_PORT = 65535;

    /** Rendered by {@link TranslatedDatabaseUrl#toString()} in place of each of its values. */
    private static final String REDACTED = "***REDACTED***";

    /** The grammar named by every failure message. */
    private static final String EXPECTED_FORM = "<scheme>://[username[:password]@]host[:port]/database";

    /**
     * Maps a URL scheme, lower-cased and with any {@code +driver} suffix removed, to the JDBC
     * vendor it resolves to.
     */
    private static final Map<String, Vendor> VENDOR_BY_SCHEME;

    // Scheme-to-vendor map of AAP 0.6.5.1; mariadb resolves onto the MySQL vendor — DL-187 — see
    // docs/DECISION_LOG.md
    // The map holds one entry per runtime-scope driver; H2 is test-scope and has no scheme — DL-071,
    // DL-242 — see docs/DECISION_LOG.md
    static {
        final Map<String, Vendor> vendors = new LinkedHashMap<>();
        vendors.put("postgresql", Vendor.POSTGRESQL);
        vendors.put("postgres", Vendor.POSTGRESQL);
        vendors.put("mysql", Vendor.MYSQL);
        vendors.put("mariadb", Vendor.MYSQL);
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

    /**
     * Tokens whose presence anywhere in a property name makes that property secret-bearing. Each is
     * matched case-insensitively as a substring of the text before a property's {@code '='}, so a
     * vendor property naming a secret under any of these tokens is caught — DL-072 — see
     * docs/DECISION_LOG.md.
     */
    private static final Set<String> SECRET_NAME_TOKENS = Set.of(
            "password", "passwd", "pwd", "passphrase", "secret", "credential", "token");

    /** Separates properties inside the query or property section of a URL. */
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
        MYSQL("mysql", "jdbc:mysql://");

        private final String token;
        private final String jdbcAuthorityPrefix;

        Vendor(String token, String jdbcAuthorityPrefix) {
            this.token = token;
            this.jdbcAuthorityPrefix = jdbcAuthorityPrefix;
        }

        /** Returns the vendor token named in log records and failure messages. */
        String token() {
            return token;
        }

        /** Returns everything the reassembled URL carries before the host. */
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
     *                               {@code 0..}{@value #MAX_PORT}; if its scheme is not one of the
     *                               supported schemes; or if credential material remains inside the
     *                               assembled JDBC URL
     */
    public static TranslatedDatabaseUrl translate(String databaseUrl) {
        if (ConfiguredValues.isUnset(databaseUrl)) {
            LOG.error("DATABASE_URL is not set; there is no database URL to translate.");
            throw new IllegalStateException("DATABASE_URL must be set: no database URL was supplied.");
        }

        if (databaseUrl.startsWith(JDBC_SCHEME_PREFIX)) {
            // The invariant holds on this path too: detect, then reject; never alter — DL-072 — see
            // docs/DECISION_LOG.md
            rejectCredentialMaterial(databaseUrl, "DATABASE_URL already holds a JDBC URL that "
                    + "carries credential material");
            // The same invariant covers a secret this service cannot bind anywhere — DL-072 — see
            // docs/DECISION_LOG.md
            rejectSecretBearingProperties(databaseUrl);
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
        rejectCredentialMaterial(assembled, "DATABASE_URL carries credential material that cannot be "
                + "separated from its JDBC URL");
        // A retained property whose name carries a secret token is refused; it never reaches the
        // driver — DL-072 — see docs/DECISION_LOG.md
        rejectSecretBearingProperties(assembled);

        // Logging baseline - see docs/DECISION_LOG.md DL-052. The record names the resolved vendor
        // only; host, port, database path, query and user-info are omitted.
        LOG.info("Translated DATABASE_URL to a JDBC URL for vendor '{}'", vendor.token());
        return new TranslatedDatabaseUrl(assembled, query.credentials().username(), query.credentials().password());
    }

    /**
     * Rejects a URL that carries credential material. {@link TranslatedDatabaseUrl#jdbcUrl()} never
     * holds a username or a password.
     *
     * @param url     the URL to test
     * @param summary the leading clause of the failure message
     * @throws IllegalStateException if {@link #carriesCredentialMaterial(String)} holds for the URL
     */
    private static void rejectCredentialMaterial(String url, String summary) {
        if (!carriesCredentialMaterial(url)) {
            return;
        }
        LOG.error("{}. Supply the credentials through the user-info component of a non-JDBC "
                + "DATABASE_URL instead.", summary);
        throw new IllegalStateException(summary + ": a username or a password must not appear in the "
                + "JDBC URL. Supply them through the user-info component of " + EXPECTED_FORM + " so "
                + "they are held apart from the URL.");
    }

    // Secondary secrets carried by a URL property — DL-072 — see docs/DECISION_LOG.md
    /**
     * Rejects a URL carrying a property whose name declares it to hold a secret this service cannot
     * separate from the URL.
     *
     * <p>Only properties are inspected: every {@code ?}, {@code &} and {@code ;} separated segment
     * after the first, which is the same grammar {@link #carriesCredentialMaterial(String)} reads. A
     * primary credential property is not reported here — on the parse path it has already been
     * extracted, and on the pass-through path
     * {@link #rejectCredentialMaterial(String, String)} has already refused it.
     *
     * <p>The failure names the offending property names, in the order they appear and as they were
     * written, and never any part of a value. A deployment carrying such a property removes it from
     * {@code DATABASE_URL}: this service binds the pool from
     * {@code scanner.datasource.pool.*}, whose eight members carry geometry and timing only, so there
     * is no property to move a driver secret to — DL-270, DL-271.
     *
     * @param url the URL to inspect, either a passed-through value or a reassembled one
     * @throws IllegalStateException if any property name carries a token of
     *     {@link #SECRET_NAME_TOKENS}
     */
    private static void rejectSecretBearingProperties(String url) {
        final List<String> offending = new ArrayList<>();
        final String[] tokens = PROPERTY_SEPARATOR.split(url, -1);
        for (int i = 1; i < tokens.length; i++) {
            final String name = propertyName(tokens[i]);
            if (name.isEmpty() || CREDENTIAL_PROPERTY_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (carriesSecretToken(name)) {
                offending.add(name);
            }
        }
        if (offending.isEmpty()) {
            return;
        }

        final String named = String.join(", ", offending);
        LOG.error("DATABASE_URL carries {} secret-bearing propert{}: {}. No value is reproduced.",
                offending.size(), offending.size() == 1 ? "y" : "ies", named);
        throw new IllegalStateException("DATABASE_URL carries secret-bearing propert"
                + (offending.size() == 1 ? "y " : "ies ") + named + ": a driver secret must not "
                + "appear in the URL, and this service has no property to bind one to. Remove "
                + (offending.size() == 1 ? "it" : "them") + " from DATABASE_URL. No value is "
                + "reproduced here.");
    }

    /**
     * Reports whether a property name carries a token that makes it secret-bearing.
     *
     * @param name the property name as written, never {@code null}
     * @return {@code true} when the lower-cased name holds any member of
     *     {@link #SECRET_NAME_TOKENS} as a substring
     */
    private static boolean carriesSecretToken(String name) {
        final String normalised = name.toLowerCase(Locale.ROOT);
        for (String token : SECRET_NAME_TOKENS) {
            if (normalised.contains(token)) {
                return true;
            }
        }
        return false;
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
     * Reports whether a URL carries credential material, in either of the two places a JDBC URL can
     * hold it: the user-info component of an authority, and a recognised credential property.
     *
     * <p>The property scan covers every {@code ?}, {@code &} and {@code ;} separated property of the
     * whole URL, which is a query string and the semicolon-separated property list some drivers
     * accept. The text before a property's {@code '='} is compared, lower-cased, against
     * {@link #CREDENTIAL_PROPERTY_NAMES}.
     *
     * @param url the URL to inspect; never {@code null}
     * @return {@code true} when the URL carries a username or a password
     */
    private static boolean carriesCredentialMaterial(String url) {
        final int authorityStart = url.indexOf(AUTHORITY_SEPARATOR);
        if (authorityStart >= 0) {
            final int from = authorityStart + AUTHORITY_SEPARATOR.length();
            int end = url.length();
            for (int i = from; i < url.length(); i++) {
                final char c = url.charAt(i);
                if (c == '/' || c == QUERY_MARKER || c == FRAGMENT_MARKER) {
                    end = i;
                    break;
                }
            }
            if (url.lastIndexOf(AT_SIGN, end - 1) >= from) {
                return true;
            }
        }

        final String[] tokens = PROPERTY_SEPARATOR.split(url, -1);
        for (int i = 1; i < tokens.length; i++) {
            if (isCredentialProperty(tokens[i])) {
                return true;
            }
        }
        return false;
    }

    /** Reports whether a {@code name=value} token names a primary credential property. */
    private static boolean isCredentialProperty(String token) {
        return CREDENTIAL_PROPERTY_NAMES.contains(propertyName(token).toLowerCase(Locale.ROOT));
    }

    /**
     * Splits a raw query component into the properties the reassembled URL retains and the
     * credentials taken out of it.
     *
     * <p>Properties are separated on {@code '&'} and on {@code ';'} alike, which is the grammar
     * {@link #rejectCredentialMaterial(String, String)} detects with, so a credential property is
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
     * not a separator — see docs/DECISION_LOG.md DL-071.
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
}
