package com.codeskeptic.scanner.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

// Net-new (no source construct: backend/app/tasks/tweet_monitoring.py:L53-55 left the keyword set
// undefined and validated nothing) — DL-257 — see docs/DECISION_LOG.md
/**
 * Exercises the one X filtered-stream rule grammar both callers hold their terms to.
 *
 * <p>The contract asserted here is the one {@code task/TweetStreamClient} applies before a term
 * mutates the registered rule set and the one {@code service/SettingsService} applies when it counts
 * the usable terms of a {@code stream_keywords} edit, so a term reported usable in one place cannot be
 * refused in the other — DL-257.
 *
 * <p>Also asserts that no method here reproduces a term in a way a log record could carry: the class
 * writes no log event, and the fingerprint a caller records for a rendered expression is
 * reproducible from the term alone — DL-275.
 */
@DisplayName("StreamRuleTerms")
class StreamRuleTermsTest {

    /** A term of the greatest accepted length. */
    private static final String LONGEST_ACCEPTED = "a".repeat(StreamRuleTerms.MAX_TERM_CHARS);

    /** A term one character beyond the greatest accepted length. */
    private static final String FIRST_REFUSED = "a".repeat(StreamRuleTerms.MAX_TERM_CHARS + 1);

    @Nested
    @DisplayName("isUsable(String)")
    class Usability {

        @ParameterizedTest
        @ValueSource(strings = {
                "GPT4", "gpt4", "Copilot", "AI coding tool", "AI code assistant",
                "AI generated code", "GPT-4", "code_assistant", "node.js", "don't", "a", "9",
                "AI  coding   tool", "a-b_c.d'e f"})
        @DisplayName("admits a term drawn from letters, digits and the additional characters")
        void admitsATermDrawnFromLettersDigitsAndTheAdditionalCharacters(String term) {
            assertThat(StreamRuleTerms.isUsable(term)).as(term).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "caf\u00e9", "\u041a\u043e\u0434", "\u4ee3\u7801", "\u05e7\u05d5\u05d3",
                "na\u00efve tool", "\u0660\u0661"})
        @DisplayName("admits a letter or digit of any script, because the test is Unicode-aware")
        void admitsALetterOrDigitOfAnyScript(String term) {
            assertThat(StreamRuleTerms.isUsable(term)).as(term).isTrue();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n", " \r\n "})
        @DisplayName("refuses an absent, empty or wholly blank term")
        void refusesAnAbsentEmptyOrWhollyBlankTerm(String term) {
            assertThat(StreamRuleTerms.isUsable(term)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "\"quoted\"", "back\\slash", "(group)", "from:someone", "is:retweet", "#hashtag",
                "@mention", "tool:name", "a\u0000b", "a\nb", "a\rb", "emoji \uD83D\uDE00",
                "50%", "a+b", "a=b", "a/b", "a,b", "a;b", "a!b", "a?b", "a*b", "a[b]", "a{b}",
                "a|b", "a$b", "a&b", "a<b", "a>b", "a~b", "a^b", "a\u2028b", "a\u2029b",
                "a\u0085b", "a\u200eb"})
        @DisplayName("refuses a term carrying a character the grammar does not admit")
        void refusesATermCarryingACharacterTheGrammarDoesNotAdmit(String term) {
            assertThat(StreamRuleTerms.isUsable(term)).as(term).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "-negated", "negated-", "_leading", "trailing_", ".dotted", "dotted.",
                "'quoted", "quoted'", "-", "_", ".", "'", "--"})
        @DisplayName("refuses a term opening or closing with an additional character")
        void refusesATermOpeningOrClosingWithAnAdditionalCharacter(String term) {
            assertThat(StreamRuleTerms.isUsable(term)).as(term).isFalse();
        }

        @Test
        @DisplayName("admits a term of the greatest accepted length and refuses the next one")
        void admitsATermOfTheGreatestAcceptedLengthAndRefusesTheNextOne() {
            assertThat(StreamRuleTerms.MAX_TERM_CHARS).isEqualTo(128);
            assertThat(StreamRuleTerms.isUsable(LONGEST_ACCEPTED)).isTrue();
            assertThat(StreamRuleTerms.isUsable(FIRST_REFUSED)).isFalse();
        }

        @Test
        @DisplayName("measures the length after trimming, so surrounding whitespace is not counted")
        void measuresTheLengthAfterTrimmingSoSurroundingWhitespaceIsNotCounted() {
            assertThat(StreamRuleTerms.isUsable("   " + LONGEST_ACCEPTED + "   ")).isTrue();
            assertThat(StreamRuleTerms.isUsable("   " + FIRST_REFUSED + "   ")).isFalse();
        }

        @Test
        @DisplayName("admits the configured base terms this application ships")
        void admitsTheConfiguredBaseTermsThisApplicationShips() {
            List<String> baseTerms = List.of(
                    "AI coding tool", "AI code assistant", "AI generated code", "GPT-4");

            assertThat(baseTerms).allMatch(StreamRuleTerms::isUsable);
        }
    }

    @Nested
    @DisplayName("expressionOf(String)")
    class Rendering {

        @ParameterizedTest(name = "\"{0}\" renders as {1}")
        @CsvSource({
                "GPT-4,GPT-4",
                "Copilot,Copilot",
                "code_assistant,code_assistant"})
        @DisplayName("carries a single-word term unchanged")
        void carriesASingleWordTermUnchanged(String term, String expected) {
            assertThat(StreamRuleTerms.expressionOf(term)).isEqualTo(expected);
        }

        @Test
        @DisplayName("wraps a term holding whitespace in double quotes")
        void wrapsATermHoldingWhitespaceInDoubleQuotes() {
            assertThat(StreamRuleTerms.expressionOf("AI coding tool"))
                    .isEqualTo("\"AI coding tool\"");
        }

        @Test
        @DisplayName("adds no escape, because no admitted term carries a character needing one")
        void addsNoEscapeBecauseNoAdmittedTermCarriesACharacterNeedingOne() {
            String rendered = StreamRuleTerms.expressionOf("AI code assistant");

            assertThat(rendered).doesNotContain("\\");
            assertThat(rendered.chars().filter(character -> character == '"').count()).isEqualTo(2L);
        }

        @Test
        @DisplayName("bounds the rendered expression at the term bound plus the two quotes")
        void boundsTheRenderedExpressionAtTheTermBoundPlusTheTwoQuotes() {
            String phrase = "a".repeat(StreamRuleTerms.MAX_TERM_CHARS - 2) + " b";

            assertThat(StreamRuleTerms.isUsable(phrase)).isTrue();
            assertThat(StreamRuleTerms.expressionOf(phrase))
                    .hasSize(StreamRuleTerms.MAX_TERM_CHARS + 2);
        }
    }

    @Nested
    @DisplayName("split(String) and countUsable(String)")
    class Splitting {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("reads an absent or blank value as no terms at all")
        void readsAnAbsentOrBlankValueAsNoTermsAtAll(String value) {
            assertThat(StreamRuleTerms.split(value)).isEmpty();
            assertThat(StreamRuleTerms.countUsable(value)).isZero();
        }

        @Test
        @DisplayName("splits on the delimiter and keeps every segment, blank ones included")
        void splitsOnTheDelimiterAndKeepsEverySegmentBlankOnesIncluded() {
            assertThat(StreamRuleTerms.TERM_DELIMITER).isEqualTo(",");
            assertThat(StreamRuleTerms.split("GPT-4, Copilot , ,AI coding tool"))
                    .containsExactly("GPT-4", " Copilot ", " ", "AI coding tool");
        }

        @Test
        @DisplayName("counts only the segments the grammar admits")
        void countsOnlyTheSegmentsTheGrammarAdmits() {
            String value = "GPT-4,\"quoted\", Copilot , ,-negated," + FIRST_REFUSED;

            assertThat(StreamRuleTerms.split(value)).hasSize(6);
            assertThat(StreamRuleTerms.countUsable(value)).isEqualTo(2);
        }

        @Test
        @DisplayName("agrees with the per-term decision for every segment it splits")
        void agreesWithThePerTermDecisionForEverySegmentItSplits() {
            String value = "GPT-4,\"quoted\", Copilot , ,-negated,node.js,a\nb," + LONGEST_ACCEPTED;
            List<String> segments = StreamRuleTerms.split(value);

            long usableSegments = segments.stream().filter(StreamRuleTerms::isUsable).count();

            assertThat(StreamRuleTerms.countUsable(value)).isEqualTo((int) usableSegments);
        }

        @Test
        @DisplayName("splits into no more than the segment bound, leaving the tail in the last part")
        void splitsIntoNoMoreThanTheSegmentBoundLeavingTheTailInTheLastPart() {
            String value = String.join(StreamRuleTerms.TERM_DELIMITER,
                    Collections.nCopies(StreamRuleTerms.MAX_SEGMENTS + 10, "GPT4"));

            List<String> segments = StreamRuleTerms.split(value);

            assertThat(StreamRuleTerms.MAX_SEGMENTS).isEqualTo(512);
            assertThat(segments).hasSize(StreamRuleTerms.MAX_SEGMENTS);
            assertThat(segments.getLast()).contains(StreamRuleTerms.TERM_DELIMITER);
            assertThat(StreamRuleTerms.isUsable(segments.getLast())).isFalse();
            assertThat(StreamRuleTerms.countUsable(value))
                    .isEqualTo(StreamRuleTerms.MAX_SEGMENTS - 1);
        }

        @Test
        @DisplayName("counts a value holding no admitted segment as none")
        void countsAValueHoldingNoAdmittedSegmentAsNone() {
            assertThat(StreamRuleTerms.countUsable("\"a\",\\b,-c, , ")).isZero();
        }
    }

    @Nested
    @DisplayName("class shape")
    class ClassShape {

        @Test
        @DisplayName("publishes only the four members both callers share")
        void publishesOnlyTheFourMembersBothCallersShare() {
            List<String> methods = Arrays.stream(StreamRuleTerms.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .map(Method::getName)
                    .sorted()
                    .toList();

            assertThat(methods)
                    .containsExactly("countUsable", "expressionOf", "isUsable", "split");
        }

        @Test
        @DisplayName("cannot be instantiated")
        void cannotBeInstantiated() throws ReflectiveOperationException {
            Constructor<StreamRuleTerms> constructor =
                    StreamRuleTerms.class.getDeclaredConstructor();
            constructor.setAccessible(true);

            assertThatThrownBy(constructor::newInstance)
                    .isInstanceOf(InvocationTargetException.class)
                    .cause()
                    .isInstanceOf(AssertionError.class);
        }

        @Test
        @DisplayName("is final and holds only static members")
        void isFinalAndHoldsOnlyStaticMembers() {
            assertThat(Modifier.isFinal(StreamRuleTerms.class.getModifiers())).isTrue();
            assertThat(Arrays.stream(StreamRuleTerms.class.getDeclaredMethods()))
                    .allMatch(method -> Modifier.isStatic(method.getModifiers()));
            assertThat(Arrays.stream(StreamRuleTerms.class.getDeclaredFields()))
                    .allMatch(field -> Modifier.isStatic(field.getModifiers()));
        }
    }
}
