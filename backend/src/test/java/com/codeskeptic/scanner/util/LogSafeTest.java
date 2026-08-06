package com.codeskeptic.scanner.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Net-new (no Python counterpart: the retired backend/ tree declared no logging framework at all)
// — see docs/DECISION_LOG.md
/**
 * Behaviour of {@link LogSafe}, the renderer every log statement passes an externally supplied value
 * through so no request body, stream payload or credential material reaches a log record.
 */
@DisplayName("LogSafe")
class LogSafeTest {

    /** Marker rendered for a {@code null} value. */
    private static final String ABSENT = "absent";

    /** Marker rendered for an empty value. */
    private static final String EMPTY = "empty";

    /** Prefix every correlation token carries, naming the keyed primitive — DL-119. */
    private static final String CORRELATION_PREFIX = "hmac256:";

    /** Longest value {@link LogSafe#logSafe(String)} renders in full. */
    private static final int LOG_VALUE_LIMIT = 64;

    @Nested
    @DisplayName("logSafe")
    class LogSafeRendering {

        @ParameterizedTest(name = "\"{0}\" is rendered unchanged")
        @ValueSource(strings = {"42", "tweet_popularity_threshold", "GET /tweets", "a-b_c.d:e/f",
                "~ the last printable ascii character", " leading and trailing "})
        @DisplayName("renders a value drawn from printable ASCII unchanged")
        void rendersAPrintableAsciiValueUnchanged(String value) {
            assertThat(LogSafe.logSafe(value)).isEqualTo(value);
        }

        @ParameterizedTest(name = "the unsafe characters of {0} are replaced")
        @ValueSource(strings = {"a\rb", "a\nb", "a\tb", "a\u0000b", "caf\u00e9", "line1\r\nWARN forged"})
        @DisplayName("replaces every character outside printable ASCII, so no record can be forged")
        void replacesEveryCharacterOutsidePrintableAscii(String unsafe) {
            String rendered = LogSafe.logSafe(unsafe);

            assertThat(rendered).hasSize(Math.min(unsafe.length(), LOG_VALUE_LIMIT));
            assertThat(rendered).doesNotContain("\r").doesNotContain("\n").doesNotContain("\t")
                    .doesNotContain("\u0000");
            assertThat(rendered.chars()).allMatch(c -> c >= ' ' && c <= '~');
        }

        @Test
        @DisplayName("renders a value at the bound in full and truncates a longer one to the bound")
        void rendersAValueAtTheBoundInFullAndTruncatesALongerOne() {
            String atTheBound = "x".repeat(LOG_VALUE_LIMIT);
            String beyondTheBound = "x".repeat(LOG_VALUE_LIMIT + 1) + "SECRET";

            assertThat(LogSafe.logSafe(atTheBound)).isEqualTo(atTheBound);
            assertThat(LogSafe.logSafe(beyondTheBound)).hasSize(LOG_VALUE_LIMIT)
                    .doesNotContain("SECRET");
        }

        @Test
        @DisplayName("reports an absent value and an empty value by name")
        void reportsAnAbsentValueAndAnEmptyValueByName() {
            assertThat(LogSafe.logSafe(null)).isEqualTo(ABSENT);
            assertThat(LogSafe.logSafe("")).isEqualTo(EMPTY);
        }
    }

    @Nested
    @DisplayName("correlation")
    class Correlation {

        @Test
        @DisplayName("renders the same value as the same token and different values apart")
        void rendersTheSameValueAsTheSameTokenAndDifferentValuesApart() {
            assertThat(LogSafe.correlation("88")).isEqualTo(LogSafe.correlation("88"));
            assertThat(LogSafe.correlation("88")).isNotEqualTo(LogSafe.correlation("89"));
            assertThat(LogSafe.correlation(null)).isEqualTo(ABSENT);
            assertThat(LogSafe.correlation("")).isEqualTo(EMPTY);
        }

        // The token is keyed with a per-process secret, so it is not the plain digest — DL-119 —
        // see docs/DECISION_LOG.md
        @ParameterizedTest(name = "the token for \"{0}\" is not its unkeyed digest")
        @ValueSource(strings = {"1", "42", "4711", "admin", "true", "tweet_popularity_threshold"})
        @DisplayName("renders a token no unkeyed digest of the value can be compared against")
        void rendersATokenNoUnkeyedDigestOfTheValueCanBeComparedAgainst(String value)
                throws Exception {

            String unkeyed = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    0, 8);

            assertThat(LogSafe.correlation(value)).isNotEqualTo("sha256:" + unkeyed)
                    .doesNotContain(unkeyed);
        }

        @Test
        @DisplayName("keys every token with one secret, so the whole set moves together")
        void keysEveryTokenWithOneSecretSoTheWholeSetMovesTogether() {
            // Within one process a token is stable, so two records naming one value join up.
            assertThat(LogSafe.correlation("4711")).isEqualTo(LogSafe.correlation("4711"));
            assertThat(LogSafe.correlation("4711")).isNotEqualTo(LogSafe.correlation("4712"));
            assertThat(LogSafe.correlation("4711")).matches(CORRELATION_PREFIX + "[0-9a-f]{16}");
        }

        @Test
        @DisplayName("never echoes the value it correlates")
        void neverEchoesTheValueItCorrelates() {
            assertThat(LogSafe.correlation("reviewer@example.invalid"))
                    .startsWith(CORRELATION_PREFIX)
                    .doesNotContain("reviewer")
                    .doesNotContain("example.invalid");
            assertThat(LogSafe.correlation("2026-99-99T99:99:99Z")).doesNotContain("99");
        }

        @ParameterizedTest(name = "a token for {0} carries only hexadecimal digits")
        @ValueSource(strings = {"{\"value\":\"secret\"}", "Bearer abc.def.ghi", "a\r\nb"})
        @DisplayName("renders a fixed-width hexadecimal token whatever the value carried")
        void rendersAFixedWidthHexadecimalToken(String value) {
            String token = LogSafe.correlation(value);

            assertThat(token).matches(CORRELATION_PREFIX + "[0-9a-f]{16}");
        }

        @Test
        @DisplayName("accepts any object, not only text")
        void acceptsAnyObjectNotOnlyText() {
            assertThat(LogSafe.correlation(88)).isEqualTo(LogSafe.correlation("88"));
            assertThat(LogSafe.correlation(List.of("a", "b"))).startsWith(CORRELATION_PREFIX);
        }
    }

    @Nested
    @DisplayName("token")
    class ProviderToken {

        @ParameterizedTest(name = "the provider field \"{0}\" is carried literally")
        @ValueSource(strings = {"validation_error", "object_not_found", "insufficient_quota",
                "invalid_request_error", "model_not_found", "reasoning_effort", "temperature",
                "messages[0].content", "req-9zk", "8f0a5c1e-3b7d-4a21-9c66-0d1e2f3a4b5c"})
        @DisplayName("carries a provider-shaped field literally")
        void carriesAProviderShapedFieldLiterally(String field) {
            assertThat(LogSafe.token(field)).isEqualTo(field);
        }

        @ParameterizedTest(name = "the provider field \"{0}\" is reported absent")
        @ValueSource(strings = {"Validation Error\ninjected", "bad id\nforged",
                "Media is not a property that exists.", "rejected\r\nERROR forged", "a b",
                "caf\u00e9", "quote\"d", "semi;colon", "{\"code\":\"x\"}"})
        @DisplayName("reports a field that fails the shape check as absent, echoing nothing of it")
        void reportsAFieldThatFailsTheShapeCheckAsAbsent(String field) {
            assertThat(LogSafe.token(field)).isEqualTo(ABSENT);
        }

        @Test
        @DisplayName("removes surrounding whitespace before checking the shape")
        void removesSurroundingWhitespaceBeforeCheckingTheShape() {
            assertThat(LogSafe.token("  req-9zk  ")).isEqualTo("req-9zk");
            assertThat(LogSafe.token("\treq-9zk\n")).isEqualTo("req-9zk");
        }

        @Test
        @DisplayName("reports a field at the bound literally and a longer one as absent")
        void reportsAFieldAtTheBoundLiterallyAndALongerOneAsAbsent() {
            String atTheBound = "c".repeat(LOG_VALUE_LIMIT);

            assertThat(LogSafe.token(atTheBound)).isEqualTo(atTheBound);
            assertThat(LogSafe.token("c".repeat(LOG_VALUE_LIMIT + 1))).isEqualTo(ABSENT);
        }

        @Test
        @DisplayName("reports an absent, an empty and a blank field by name")
        void reportsAnAbsentAnEmptyAndABlankFieldByName() {
            assertThat(LogSafe.token(null)).isEqualTo(ABSENT);
            assertThat(LogSafe.token("")).isEqualTo(ABSENT);
            assertThat(LogSafe.token("   ")).isEqualTo(ABSENT);
        }
    }

    @Nested
    @DisplayName("type")
    class FailureType {

        @Test
        @DisplayName("names the failure's class and never its message")
        void namesTheFailuresClassAndNeverItsMessage() {
            IllegalStateException failure = new IllegalStateException("password=hunter2");

            assertThat(LogSafe.type(failure)).isEqualTo("IllegalStateException")
                    .doesNotContain("hunter2");
        }

        @Test
        @DisplayName("reports an absent failure by name")
        void reportsAnAbsentFailureByName() {
            assertThat(LogSafe.type(null)).isEqualTo(ABSENT);
        }
    }

    // Bounded failure metadata — DL-197 — see docs/DECISION_LOG.md
    @Nested
    @DisplayName("typeChain")
    class TypeChainRendering {

        @Test
        @DisplayName("names each type of a cause chain and no message of any of them")
        void namesEachTypeOfACauseChainAndNoMessageOfAnyOfThem() {
            Throwable failure = new IllegalStateException("outer password=hunter2",
                    new RuntimeException("middle jdbc:postgresql://db/x",
                            new IllegalArgumentException("inner HUNTER2")));

            assertThat(LogSafe.typeChain(failure))
                    .isEqualTo("IllegalStateException <- RuntimeException <- IllegalArgumentException")
                    .doesNotContain("hunter2")
                    .doesNotContain("jdbc")
                    .doesNotContain("HUNTER2");
        }

        @Test
        @DisplayName("bounds a chain at five links and marks that it continues")
        void boundsAChainAtFiveLinksAndMarksThatItContinues() {
            Throwable failure = new IllegalStateException("l1", new IllegalStateException("l2",
                    new IllegalStateException("l3", new IllegalStateException("l4",
                            new IllegalStateException("l5", new IllegalStateException("l6"))))));

            String rendered = LogSafe.typeChain(failure);

            assertThat(rendered.split(" <- ")).hasSize(6);
            assertThat(rendered).endsWith(" <- ...");
        }

        @Test
        @DisplayName("stops at a cause that refers back into the chain")
        void stopsAtACauseThatRefersBackIntoTheChain() {
            IllegalStateException outer = new IllegalStateException("outer");
            RuntimeException inner = new RuntimeException("inner", outer);
            outer.initCause(inner);

            assertThat(LogSafe.typeChain(outer)).endsWith("...").hasSizeLessThan(120);
        }

        @Test
        @DisplayName("reports an absent failure chain by name")
        void reportsAnAbsentFailureChainByName() {
            assertThat(LogSafe.typeChain(null)).isEqualTo(ABSENT);
        }
    }

    // Bounded failure metadata — DL-197 — see docs/DECISION_LOG.md
    @Nested
    @DisplayName("originFrame")
    class OriginFrameRendering {

        @Test
        @DisplayName("names this application's own frame, its method and its line")
        void namesThisApplicationsOwnFrameItsMethodAndItsLine() {
            assertThat(LogSafe.originFrame(new IllegalStateException("raised here")))
                    .startsWith("LogSafeTest")
                    .contains(".namesThisApplicationsOwnFrameItsMethodAndItsLine:")
                    .matches("[A-Za-z0-9$.]+:[0-9]+");
        }

        @Test
        @DisplayName("names the topmost frame when the stack holds none of this application's")
        void namesTheTopmostFrameWhenTheStackHoldsNoneOfThisApplications() {
            IllegalStateException failure = new IllegalStateException("no application frame");
            failure.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("org.example.provider.Driver", "connect", "Driver.java", 42)});

            assertThat(LogSafe.originFrame(failure)).isEqualTo("Driver.connect:42");
        }

        @Test
        @DisplayName("reports an absent failure and an empty stack by name")
        void reportsAnAbsentFailureAndAnEmptyStackByName() {
            IllegalStateException empty = new IllegalStateException("no frames");
            empty.setStackTrace(new StackTraceElement[0]);

            assertThat(LogSafe.originFrame(null)).isEqualTo(ABSENT);
            assertThat(LogSafe.originFrame(empty)).isEqualTo(ABSENT);
        }
    }

    // Sanitized diagnostic detail — DL-197 — see docs/DECISION_LOG.md
    @Nested
    @DisplayName("failureDetail")
    class FailureDetailRendering {

        @Test
        @DisplayName("guards every message it carries and forges no record boundary")
        void guardsEveryMessageItCarriesAndForgesNoRecordBoundary() {
            Throwable failure = new IllegalStateException("first\r\nforged",
                    new RuntimeException("second\u0000line"));

            String rendered = LogSafe.failureDetail(failure);

            assertThat(rendered).contains("IllegalStateException[first??forged]")
                    .contains("RuntimeException[second?line]")
                    .doesNotContain("\r")
                    .doesNotContain("\n");
            assertThat(rendered.chars())
                    .allMatch(character -> character >= ' ' && character <= '~');
        }

        @Test
        @DisplayName("bounds a long message at the detail bound")
        void boundsALongMessageAtTheDetailBound() {
            String longMessage = "x".repeat(300) + "SECRET";

            assertThat(LogSafe.failureDetail(new IllegalStateException(longMessage)))
                    .doesNotContain("SECRET")
                    .contains("x".repeat(256));
        }

        @Test
        @DisplayName("bounds the frames it renders and marks that they continue")
        void boundsTheFramesItRendersAndMarksThatTheyContinue() {
            IllegalStateException failure = new IllegalStateException("deep stack");
            StackTraceElement[] frames = new StackTraceElement[25];
            for (int index = 0; index < frames.length; index++) {
                frames[index] = new StackTraceElement("org.example.Deep", "call" + index,
                        "Deep.java", index + 1);
            }
            failure.setStackTrace(frames);

            String rendered = LogSafe.failureDetail(failure);

            assertThat(rendered).contains("Deep.call0:1").contains("Deep.call9:10")
                    .doesNotContain("Deep.call10:11")
                    .endsWith(", ...");
        }

        @Test
        @DisplayName("reports an absent failure by name and an absent message as absent")
        void reportsAnAbsentFailureByNameAndAnAbsentMessageAsAbsent() {
            assertThat(LogSafe.failureDetail(null)).isEqualTo(ABSENT);
            assertThat(LogSafe.failureDetail(new IllegalStateException()))
                    .startsWith("IllegalStateException[absent]");
        }
    }

    @Test
    @DisplayName("renders no control character and no line break for any unsafe value")
    void rendersNoControlCharacterForAnyUnsafeValue() {
        String unsafe = "a\r\nb\tc\u0000d\u001b[31m";

        Throwable hostile = new IllegalStateException(unsafe, new RuntimeException(unsafe));
        for (String rendered : List.of(LogSafe.logSafe(unsafe), LogSafe.correlation(unsafe),
                LogSafe.token(unsafe), LogSafe.typeChain(hostile), LogSafe.originFrame(hostile),
                LogSafe.failureDetail(hostile))) {
            assertThat(rendered.chars()).allMatch(character -> character >= ' ' && character <= '~');
        }
    }

    @Test
    @DisplayName("declares only static members behind a single private constructor")
    void declaresOnlyStaticMembersBehindASinglePrivateConstructor() throws Exception {
        Constructor<?>[] constructors = LogSafe.class.getDeclaredConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(Modifier.isPrivate(constructors[0].getModifiers())).isTrue();
        assertThat(Modifier.isFinal(LogSafe.class.getModifiers())).isTrue();

        constructors[0].setAccessible(true);
        assertThatCode(constructors[0]::newInstance).doesNotThrowAnyException();
    }
}
