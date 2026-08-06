package com.codeskeptic.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Net-new (no source construct: the retired tree carried no decision log) — see
// docs/DECISION_LOG.md DL-058, DL-099, DL-258
/**
 * Holds every decision pointer in the delivered tree to a row that exists in
 * {@code docs/DECISION_LOG.md}.
 *
 * <p>Rule 1 makes the decision log the single source of truth for rationale and allows a comment to
 * carry a pointer to it. A pointer naming an identifier the log does not define, or naming one whose
 * row was renumbered, silently breaks that contract, and nothing but a reader noticing would catch
 * it. This class turns that into a build failure.
 *
 * <p>Three properties are asserted:
 *
 * <ul>
 *   <li>every {@code DL-<n>} cited anywhere in the scanned set resolves to a row in the log;</li>
 *   <li>the log's own identifiers are unique and contiguous from {@code DL-001}, so a pointer can
 *       never resolve to two rows and a renumbering cannot leave a hole;</li>
 *   <li>the scan actually reached the files it claims to, so the check cannot pass by scanning
 *       nothing.</li>
 * </ul>
 *
 * <p>The scanned set is the Maven module — {@code src}, {@code pom.xml}, {@code docs} and the two
 * ignore files — plus the repository-level operations files under {@code ../.github} and
 * {@code ../infrastructure}, which cite the log too. Tests run with the module directory as the
 * working directory, which is how both paths resolve.
 */
@DisplayName("decision pointer integrity")
class DecisionPointerIntegrityTest {

    /** The log every pointer must resolve into, relative to the module directory. */
    private static final Path DECISION_LOG = Path.of("docs", "DECISION_LOG.md");

    /** A cited decision identifier, in the exact spelling every comment in the tree uses. */
    private static final Pattern CITATION = Pattern.compile("DL-\\d+");

    /** A decision row: the identifier in the first cell of a Markdown table row. */
    private static final Pattern DEFINITION = Pattern.compile("^\\|\\s*(DL-\\d+)\\s*\\|",
            Pattern.MULTILINE);

    /** Paths inside the module that are scanned for citations. */
    private static final List<Path> MODULE_ROOTS = List.of(
            Path.of("src"), Path.of("docs"), Path.of("pom.xml"),
            Path.of(".gitignore"), Path.of(".dockerignore"));

    /** Repository-level operations paths that cite the log; scanned when the checkout provides them. */
    private static final List<Path> REPOSITORY_ROOTS = List.of(
            Path.of("..", ".github"), Path.of("..", "infrastructure"));

    /** File suffixes scanned; every other file is skipped as non-textual. */
    private static final Set<String> SCANNED_SUFFIXES = Set.of(
            ".java", ".yml", ".yaml", ".xml", ".md", ".properties", ".sh", ".dockerignore",
            ".gitignore");

    /** Files without a suffix that are scanned by name. */
    private static final Set<String> SCANNED_NAMES = Set.of(
            "Dockerfile.backend", "Dockerfile.frontend", ".gitignore", ".dockerignore");

    /** Below this count the scan cannot have reached the delivered tree. */
    private static final int MINIMUM_SCANNED_FILES = 90;

    @Test
    @DisplayName("every cited decision identifier resolves to a row in the decision log")
    void everyCitedDecisionIdentifierResolvesToARowInTheDecisionLog() {
        Set<String> defined = definedIdentifiers();
        Map<String, Set<Path>> cited = citedIdentifiers();

        Map<String, Set<Path>> unresolved = new LinkedHashMap<>();
        cited.forEach((identifier, sources) -> {
            if (!defined.contains(identifier)) {
                unresolved.put(identifier, sources);
            }
        });

        assertThat(unresolved)
                .withFailMessage("These decision pointers name no row in %s:%n%s",
                        DECISION_LOG, describe(unresolved))
                .isEmpty();
    }

    @Test
    @DisplayName("the decision log numbers its rows uniquely and contiguously from one")
    void theDecisionLogNumbersItsRowsUniquelyAndContiguouslyFromOne() {
        List<String> ordered = new ArrayList<>();
        Matcher matcher = DEFINITION.matcher(read(DECISION_LOG));
        while (matcher.find()) {
            ordered.add(matcher.group(1));
        }

        assertThat(ordered).isNotEmpty();
        assertThat(ordered).doesNotHaveDuplicates();

        List<String> expected = new ArrayList<>(ordered.size());
        for (int number = 1; number <= ordered.size(); number++) {
            expected.add(String.format(Locale.ROOT, "DL-%03d", number));
        }
        assertThat(ordered)
                .withFailMessage("The log's identifiers are not contiguous from DL-001; "
                        + "%d rows are present and the sequence first differs at %s",
                        ordered.size(), firstDifference(ordered, expected))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("the scan reaches the delivered tree rather than passing on an empty set")
    void theScanReachesTheDeliveredTreeRatherThanPassingOnAnEmptySet() {
        List<Path> scanned = scannedFiles();

        assertThat(scanned).hasSizeGreaterThanOrEqualTo(MINIMUM_SCANNED_FILES);
        assertThat(scanned).anySatisfy(path ->
                assertThat(path.toString()).endsWith("ScannerApplication.java"));
        assertThat(scanned).anySatisfy(path ->
                assertThat(path.toString()).endsWith("application.yml"));
        assertThat(scanned).anySatisfy(path -> assertThat(path.toString()).endsWith("pom.xml"));
        assertThat(citedIdentifiers()).isNotEmpty();
    }

    /**
     * Reads the identifiers the decision log defines.
     *
     * @return every identifier appearing in the first cell of a table row, never empty
     */
    private static Set<String> definedIdentifiers() {
        Set<String> defined = new TreeSet<>();
        Matcher matcher = DEFINITION.matcher(read(DECISION_LOG));
        while (matcher.find()) {
            defined.add(matcher.group(1));
        }
        return defined;
    }

    /**
     * Reads every identifier cited across the scanned set, with the files that cite it.
     *
     * <p>The log itself is scanned as well: a row may cite another row, and such a citation is held
     * to the same requirement.
     *
     * @return citation to citing files, in first-seen order, never empty
     */
    private static Map<String, Set<Path>> citedIdentifiers() {
        Map<String, Set<Path>> cited = new LinkedHashMap<>();
        for (Path file : scannedFiles()) {
            Matcher matcher = CITATION.matcher(read(file));
            while (matcher.find()) {
                cited.computeIfAbsent(matcher.group(), key -> new LinkedHashSet<>()).add(file);
            }
        }
        return cited;
    }

    /**
     * Collects the textual files under the scanned roots.
     *
     * @return every scanned file, in walk order
     */
    private static List<Path> scannedFiles() {
        List<Path> files = new ArrayList<>();
        Stream.concat(MODULE_ROOTS.stream(), REPOSITORY_ROOTS.stream()).forEach(root -> {
            if (!Files.exists(root)) {
                return;
            }
            if (Files.isRegularFile(root)) {
                if (isScanned(root)) {
                    files.add(root);
                }
                return;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(DecisionPointerIntegrityTest::isScanned)
                        .forEach(files::add);
            } catch (IOException failure) {
                throw new UncheckedIOException("Could not walk " + root, failure);
            }
        });
        return files;
    }

    /**
     * Reports whether a file is textual enough to scan.
     *
     * @param file the candidate
     * @return {@code true} when the file's name or suffix is in the scanned set
     */
    private static boolean isScanned(Path file) {
        String name = file.getFileName().toString();
        if (SCANNED_NAMES.contains(name)) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SCANNED_SUFFIXES.contains(name.substring(dot));
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

    /**
     * Renders unresolved citations, one identifier per line with the files that cite it.
     *
     * @param unresolved the citations that resolved to no row
     * @return a multi-line description
     */
    private static String describe(Map<String, Set<Path>> unresolved) {
        StringBuilder description = new StringBuilder();
        unresolved.forEach((identifier, sources) ->
                description.append("  ").append(identifier).append(" cited by ")
                        .append(sources).append(System.lineSeparator()));
        return description.toString();
    }

    /**
     * Names the first position at which two identifier sequences differ.
     *
     * @param observed the sequence read from the log
     * @param expected the contiguous sequence of the same length
     * @return a description of the first difference, or {@code "no difference"}
     */
    private static String firstDifference(List<String> observed, List<String> expected) {
        for (int index = 0; index < Math.min(observed.size(), expected.size()); index++) {
            if (!observed.get(index).equals(expected.get(index))) {
                return "position " + index + ": expected " + expected.get(index) + ", read "
                        + observed.get(index);
            }
        }
        return "no difference";
    }
}
