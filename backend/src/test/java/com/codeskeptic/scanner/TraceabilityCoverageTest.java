package com.codeskeptic.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Net-new (no source construct: the retired tree carried no traceability matrix) — see
// docs/DECISION_LOG.md DL-058, DL-216
/**
 * Holds {@code docs/TRACEABILITY_MATRIX.md} to the delivered tree.
 *
 * <p>Rule 1 requires the matrix to map source constructs to target implementations at complete
 * coverage with no gaps. A class added without a row, a class deleted while its row stays, or a row
 * duplicated for one class each break that requirement while leaving the build green — which is how
 * the duplicated controller row and the two absent test rows this class now forbids came to exist.
 *
 * <p>Two properties are asserted: every delivered Java class under {@code src/main/java} and
 * {@code src/test/java} is named by exactly one row, and every path the matrix names as a Java file
 * of this module exists on disk. Together they make the mapping bidirectional in the sense Rule 1
 * requires, and a drift in either direction fails the build.
 *
 * <p>The measured figures in the matrix's test table are checked by reading it, not here.
 * {@code DocumentationConsistencyTest} holds each per-class case count to the executable suite.
 */
@DisplayName("traceability matrix coverage")
class TraceabilityCoverageTest {

    /** The matrix, relative to the module directory. */
    private static final Path MATRIX = Path.of("docs", "TRACEABILITY_MATRIX.md");

    /** Source roots whose classes must each be named by the matrix. */
    private static final List<Path> SOURCE_ROOTS = List.of(
            Path.of("src", "main", "java", "com", "codeskeptic", "scanner"),
            Path.of("src", "test", "java", "com", "codeskeptic", "scanner"));

    /**
     * A Java path the matrix names inside backticks, relative to a package root — for example
     * {@code `api/TweetController.java`} or {@code `ScannerApplicationTests.java`}.
     */
    private static final Pattern NAMED_JAVA_PATH = Pattern.compile(
            "`((?:[A-Za-z0-9_]+/)*[A-Za-z0-9_$]+\\.java)`");

    /** Below this count the walk cannot have reached the delivered tree. */
    private static final int MINIMUM_DELIVERED_CLASSES = 90;

    @Test
    @DisplayName("every delivered class is named by exactly one matrix row")
    void everyDeliveredClassIsNamedByExactlyOneMatrixRow() {
        String matrix = read(MATRIX);
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (String relative : deliveredClasses()) {
            occurrences.put(relative, count(matrix, '`' + relative + '`'));
        }

        assertThat(occurrences).hasSizeGreaterThanOrEqualTo(MINIMUM_DELIVERED_CLASSES);

        List<String> absent = new ArrayList<>();
        List<String> repeated = new ArrayList<>();
        occurrences.forEach((relative, seen) -> {
            if (seen == 0) {
                absent.add(relative);
            } else if (seen > 1) {
                repeated.add(relative + " (" + seen + " rows)");
            }
        });

        assertThat(absent)
                .withFailMessage("These delivered classes are named by no row in %s: %s", MATRIX, absent)
                .isEmpty();
        assertThat(repeated)
                .withFailMessage("These delivered classes are named by more than one row in %s: %s",
                        MATRIX, repeated)
                .isEmpty();
    }

    @Test
    @DisplayName("every Java path the matrix names exists in the delivered tree")
    void everyJavaPathTheMatrixNamesExistsInTheDeliveredTree() {
        List<String> delivered = deliveredClasses();
        List<String> unknown = new ArrayList<>();

        Matcher matcher = NAMED_JAVA_PATH.matcher(read(MATRIX));
        while (matcher.find()) {
            String named = matcher.group(1);
            boolean known = delivered.stream()
                    .anyMatch(relative -> relative.equals(named) || relative.endsWith('/' + named));
            if (!known && !unknown.contains(named)) {
                unknown.add(named);
            }
        }

        assertThat(unknown)
                .withFailMessage("%s names these Java files, which the delivered tree does not hold: %s",
                        MATRIX, unknown)
                .isEmpty();
    }

    /**
     * Lists every delivered class, as a path relative to its package root.
     *
     * @return for example {@code api/TweetController.java}, never empty
     */
    private static List<String> deliveredClasses() {
        List<String> classes = new ArrayList<>();
        for (Path root : SOURCE_ROOTS) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .map(path -> root.relativize(path).toString().replace('\\', '/'))
                        .forEach(classes::add);
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not walk " + root, failure);
            }
        }
        return classes;
    }

    /**
     * Counts non-overlapping occurrences of {@code token} in {@code text}.
     *
     * @param text the text to search
     * @param token the token to count
     * @return the number of occurrences
     */
    private static int count(String text, String token) {
        int seen = 0;
        int from = text.indexOf(token);
        while (from >= 0) {
            seen++;
            from = text.indexOf(token, from + token.length());
        }
        return seen;
    }

    /**
     * Reads a file as UTF-8 text.
     *
     * @param file the file to read
     * @return its contents
     */
    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not read " + file, failure);
        }
    }
}
