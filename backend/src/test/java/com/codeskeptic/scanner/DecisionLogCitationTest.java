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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Net-new (no Python counterpart; the retired tree carried no decision log) — DL-216 — see
// docs/DECISION_LOG.md
/**
 * Holds the decision log and every citation of it to one another.
 *
 * <p>Rule 1 makes {@code docs/DECISION_LOG.md} the only place rationale lives, and makes a {@code DL-}
 * identifier written in a source file, a resource, the POM or {@code docs/TRACEABILITY_MATRIX.md} the
 * only route from that artifact to its reasoning. A citation that resolves to no row, a row that
 * carries an empty column, a gap in the identifier sequence and a stated population that disagrees
 * with the delivered one are each a broken route, and each is decidable by reading the files — which
 * is what this class does.
 *
 * <p>Ten properties are asserted:
 *
 * <ol>
 *   <li>the identifiers form the unbroken sequence {@code DL-001} … {@code DL-<n>}, with no gap and no
 *       repeat;</li>
 *   <li>every row carries four non-empty content columns — decision, alternatives, rationale and
 *       risks;</li>
 *   <li>every {@code DL-} identifier cited anywhere under {@code backend/} resolves to one of those
 *       rows;</li>
 *   <li>the population both documents state matches the number of rows actually delivered;</li>
 *   <li>the log's own enumeration of its pointer rows — the rows rewritten as {@code Superseded by}
 *       redirections — names exactly the rows that are pointers, with exactly the successors each one
 *       names, and states their number correctly;</li>
 *   <li>no artifact other than the log itself cites a pointer row — sources, resources, the POM and
 *       the traceability matrix name the entry that owns the subject, not one that redirects to it;</li>
 *   <li>the traceability matrix inventories every delivered Java file exactly once, names no file that
 *       is absent, and its stated case total agrees with its own per-class rows;</li>
 *   <li>the entry that owns the character-column mapping names every column the entities actually
 *       declare a length facet on, and the entry that owns the primary-key bound states its value —
 *       so a facet cannot be added, removed or retuned without the log saying so;</li>
 *   <li>at least one row is cited from outside the log itself, so the routes above are exercised
 *       rather than merely well-formed;</li>
 *   <li>no comment under {@code src} or in the POM carries decision rationale — the reasoning,
 *       alternatives and risks behind a choice live in the log and nowhere else (Rule 1, DL-058).</li>
 * </ol>
 *
 * <p>The fifth property is the one that decays silently. Redirecting a row is how a superseded subject
 * keeps a resolvable identifier, and the log states the resulting set in prose that claims to be
 * complete; nothing but this assertion holds that claim to the rows it describes.
 *
 * <p>The scan is over the working tree rather than the classpath, so it covers resources, the POM and
 * both Markdown documents as well as Java sources. Surefire runs with the module root as its working
 * directory, which is what every path below is resolved against.
 */
@DisplayName("Decision log citations")
class DecisionLogCitationTest {

    /** The log every citation must resolve into. */
    private static final Path DECISION_LOG = Path.of("docs/DECISION_LOG.md");

    /** The bidirectional matrix, which restates the log's population. */
    private static final Path TRACEABILITY_MATRIX = Path.of("docs/TRACEABILITY_MATRIX.md");

    /** Roots scanned for citations. */
    private static final List<Path> SCANNED_ROOTS =
            List.of(Path.of("src"), Path.of("docs"), Path.of("pom.xml"));

    /** File extensions scanned for citations. */
    private static final Set<String> SCANNED_EXTENSIONS =
            Set.of(".java", ".yml", ".yaml", ".xml", ".md");

    /** Shape of one row of the entries table: an identifier and four content columns. */
    private static final Pattern ROW = Pattern.compile(
            "^\\| (DL-\\d{3}) \\|(.*)\\|\\s*$");

    /** Shape of a citation, wherever it appears. */
    private static final Pattern CITATION = Pattern.compile("DL-\\d{3}");

    /** Roots whose comments must carry no rationale: the delivered sources and the POM. */
    private static final List<Path> RATIONALE_SCANNED_ROOTS =
            List.of(Path.of("src"), Path.of("pom.xml"));

    /**
     * Wordings that mark a comment as arguing for a choice rather than stating what the code does.
     *
     * <p>Each names either an author's preference, a rejected option, a cost or a risk — the four
     * things the log's own columns carry. A comment may name the source construct, the delivered
     * contract and the decision identifier; it may not carry any of these.
     */
    private static final List<String> RATIONALE_MARKERS = List.of(
            "by design", "deliberately", "deliberate", "intentionally", "on purpose",
            "trade-off", "tradeoff", "at the cost of", "we chose", "chosen over",
            "in preference to", "for readability", "would have been", "the alternative",
            "no reason to", "is cheaper", "is safer", "arguably", "preferable");

    /** Openers of a comment line in the scanned file kinds. */
    private static final List<String> COMMENT_OPENERS =
            List.of("//", "*", "/*", "#", "<!--");

    /** Number of content columns every row must populate. */
    private static final int CONTENT_COLUMNS = 4;

    /** Shape of a traceability-matrix row that inventories one delivered Java file. */
    private static final Pattern MATRIX_ROW = Pattern.compile("^\\| `([^`]+\\.java)` \\|.*");

    /** Shape of the {@code Cases} column a per-test-class matrix row carries. */
    private static final Pattern MATRIX_CASES = Pattern.compile("\\| (\\d+) \\| ");

    /**
     * A column mapping that states the capacity-free character type, capturing the column name and
     * the mapped Java type — DL-068, DL-069.
     */
    private static final Pattern CAPACITY_FREE_COLUMN = Pattern.compile(
            "@Column\\(name = \"((?:[^\"\\\\]|\\\\.)*)\", columnDefinition = \"varchar\"\\)"
                    + "\\s*private (\\S+)");

    /** A column mapping that declares nothing but its name, capturing the name and the mapped type. */
    private static final Pattern BARE_COLUMN = Pattern.compile(
            "@Column\\(name = \"((?:[^\"\\\\]|\\\\.)*)\"\\)\\s*private (\\S+)");

    /** A column mapping that declares a width bound, whatever value it carries. */
    private static final Pattern LENGTH_FACET =
            Pattern.compile("@Column\\([^)]*\\blength\\b[^)]*\\)");

    /** The declared types the schema's character columns are mapped as. */
    private static final Set<String> CHARACTER_TYPES = Set.of("String", "List<String>");

    /** The count DL-068 states for the character columns it names. */
    private static final Pattern CHARACTER_COLUMN_COUNT =
            Pattern.compile("Each of the (\\w+) character columns");

    /** Number words the log writes a column count as, over the range that count can occupy. */
    private static final Map<String, Integer> NUMBER_WORDS = Map.of(
            "eight", 8, "nine", 9, "ten", 10, "eleven", 11, "twelve", 12);

    /**
     * Opening of a row that has been rewritten as a redirection, capturing the successors it names.
     *
     * <p>Every such row opens {@code **Superseded by DL-xxx.**}, listing one or more identifiers
     * separated by {@code , } and {@code  and }.
     */
    private static final Pattern SUPERSEDED = Pattern.compile(
            "\\*\\*Superseded by ((?:DL-\\d{3})(?:(?:,| and) DL-\\d{3})*)\\.\\*\\*");

    /** The prose sentence that states how many pointer rows there are and enumerates them. */
    private static final Pattern POINTER_ENUMERATION = Pattern.compile(
            "These (\\d+) rows are pointers of that kind, and this is the complete set: (.*?)\\. This "
                    + "enumeration is asserted");

    @Test
    @DisplayName("numbers its rows as one unbroken sequence with no gap and no repeat")
    void numbersItsRowsAsOneUnbrokenSequence() {
        List<String> identifiers = new ArrayList<>(rows().keySet());

        assertThat(identifiers).as("rows in docs/DECISION_LOG.md").isNotEmpty();
        assertThat(identifiers).doesNotHaveDuplicates();
        for (int index = 0; index < identifiers.size(); index++) {
            assertThat(identifiers.get(index))
                    .as("row %d of the entries table", index + 1)
                    .isEqualTo(String.format("DL-%03d", index + 1));
        }
    }

    @Test
    @DisplayName("populates all four content columns of every row")
    void populatesAllFourContentColumnsOfEveryRow() {
        rows().forEach((identifier, columns) -> {
            assertThat(columns)
                    .as("content columns of %s", identifier)
                    .hasSize(CONTENT_COLUMNS);
            assertThat(columns)
                    .as("populated content columns of %s", identifier)
                    .allSatisfy(column -> assertThat(column.strip()).isNotEmpty());
        });
    }

    @Test
    @DisplayName("resolves every citation made anywhere in the module to a row of the log")
    void resolvesEveryCitationMadeAnywhereInTheModule() {
        Set<String> declared = rows().keySet();
        Map<String, Set<String>> unresolved = new LinkedHashMap<>();

        for (Path file : scannedFiles()) {
            for (String citation : citationsIn(file)) {
                if (!declared.contains(citation)) {
                    unresolved.computeIfAbsent(citation, key -> new LinkedHashSet<>())
                            .add(file.toString());
                }
            }
        }

        assertThat(unresolved).as("citations resolving to no row of docs/DECISION_LOG.md").isEmpty();
    }

    @Test
    @DisplayName("states the delivered population in both documents that restate it")
    void statesTheDeliveredPopulationInBothDocumentsThatRestateIt() {
        Set<String> declared = rows().keySet();
        int delivered = declared.size();
        String highest = String.format("DL-%03d", delivered);

        String log = read(DECISION_LOG);
        assertThat(log)
                .as("population state of docs/DECISION_LOG.md")
                .contains("`DL-001` to `" + highest + "`")
                .contains("(" + delivered + " rows)");
        assertThat(log)
                .as("implementation-phase range of docs/DECISION_LOG.md")
                .contains("`DL-061` … `" + highest + "`");

        assertThat(read(TRACEABILITY_MATRIX))
                .as("population restated by docs/TRACEABILITY_MATRIX.md")
                .contains("`DL-001` … `" + highest + "`")
                .contains("(" + delivered + " rows)");
    }

    @Test
    @DisplayName("enumerates its pointer rows exactly as the rows themselves redirect")
    void enumeratesItsPointerRowsExactlyAsTheRowsThemselvesRedirect() {
        Map<String, List<String>> redirected = redirections();
        Matcher stated = POINTER_ENUMERATION.matcher(collapse(read(DECISION_LOG)));

        assertThat(stated.find())
                .as("sentence of docs/DECISION_LOG.md enumerating its pointer rows")
                .isTrue();
        assertThat(Integer.parseInt(stated.group(1)))
                .as("number of pointer rows docs/DECISION_LOG.md states")
                .isEqualTo(redirected.size());
        assertThat(enumerated(stated.group(2)))
                .as("pointer rows docs/DECISION_LOG.md enumerates, against the rows that redirect")
                .isEqualTo(redirected);
    }

    @Test
    @DisplayName("is cited only through the row that owns each subject, never through a pointer")
    void isCitedOnlyThroughTheRowThatOwnsEachSubject() {
        Set<String> pointers = redirections().keySet();
        Map<String, Set<String>> throughAPointer = new LinkedHashMap<>();

        for (Path file : scannedFiles()) {
            if (file.equals(DECISION_LOG)) {
                continue;
            }
            Set<String> cited = new TreeSet<>(citationsIn(file));
            cited.retainAll(pointers);
            if (!cited.isEmpty()) {
                throughAPointer.put(file.toString(), cited);
            }
        }

        assertThat(throughAPointer)
                .as("citations reaching a redirection instead of the row that owns the subject")
                .isEmpty();
    }

    @Test
    @DisplayName("inventories every delivered Java file exactly once, with a case total matching its rows")
    void inventoriesEveryDeliveredJavaFileExactlyOnce() {
        List<String> delivered = new ArrayList<>();
        collectJavaFiles(Path.of("src/main/java/com/codeskeptic/scanner"), delivered);
        int mainFiles = delivered.size();
        collectJavaFiles(Path.of("src/test/java/com/codeskeptic/scanner"), delivered);
        int testFiles = delivered.size() - mainFiles;

        Map<String, Integer> rowsPerFile = new LinkedHashMap<>();
        int statedCaseTotal = 0;
        Matcher total = Pattern.compile("sum to the (\\d+) cases").matcher(read(TRACEABILITY_MATRIX));
        if (total.find()) {
            statedCaseTotal = Integer.parseInt(total.group(1));
        }

        int summedCases = 0;
        int testRows = 0;
        for (String line : read(TRACEABILITY_MATRIX).split("\n")) {
            Matcher row = MATRIX_ROW.matcher(line);
            if (!row.matches()) {
                continue;
            }
            rowsPerFile.merge(row.group(1), 1, Integer::sum);
            Matcher cases = MATRIX_CASES.matcher(line);
            if (cases.find()) {
                summedCases += Integer.parseInt(cases.group(1));
                testRows++;
            }
        }

        assertThat(rowsPerFile.entrySet().stream().filter(e -> e.getValue() > 1).toList())
                .as("files docs/TRACEABILITY_MATRIX.md inventories more than once")
                .isEmpty();
        assertThat(rowsPerFile.keySet())
                .as("files docs/TRACEABILITY_MATRIX.md inventories, against the delivered tree")
                .containsExactlyInAnyOrderElementsOf(delivered);
        assertThat(testRows)
                .as("per-class test rows of docs/TRACEABILITY_MATRIX.md, against the delivered test classes")
                .isEqualTo(testFiles);
        assertThat(summedCases)
                .as("case total docs/TRACEABILITY_MATRIX.md states, against the sum of its own rows")
                .isEqualTo(statedCaseTotal);
        assertThat(read(TRACEABILITY_MATRIX))
                .as("class counts the coverage summary of docs/TRACEABILITY_MATRIX.md restates")
                .contains(summaryRow("Delivered main Java classes (\u00a72.2\u2013\u00a72.6)", mainFiles))
                .contains(summaryRow("Delivered test Java classes (\u00a72.7)", testFiles));
    }

    /**
     * Renders one coverage-summary row, whose three populated cells all carry the delivered count.
     *
     * @param set the name of the coverage set, exactly as the summary writes it
     * @param count the count its required, covered and delivered cells must each carry
     * @return the row as the summary table writes it
     */
    private static String summaryRow(String set, int count) {
        return "| %s | %d | %d | %d |".formatted(set, count, count, count);
    }

    @Test
    @DisplayName("states the character-column mapping the entities declare, column by column")
    void statesTheCharacterColumnMappingTheEntitiesDeclare() {
        Map<String, List<String>> rows = rows();
        String mappingRule = rows.get("DL-068").get(0);
        String primaryKeyMapping = rows.get("DL-069").get(0);

        List<String> characterColumns = new ArrayList<>();
        List<String> bareCharacterColumns = new ArrayList<>();
        List<String> faceted = new ArrayList<>();
        for (Path entity : entitySources()) {
            String source = read(entity);
            Matcher capacityFree = CAPACITY_FREE_COLUMN.matcher(source);
            while (capacityFree.find()) {
                if (CHARACTER_TYPES.contains(capacityFree.group(2))) {
                    characterColumns.add(capacityFree.group(1).replace("\\\"", ""));
                }
            }
            Matcher bare = BARE_COLUMN.matcher(source);
            while (bare.find()) {
                if (CHARACTER_TYPES.contains(bare.group(2))) {
                    bareCharacterColumns.add(entity.getFileName() + ": " + bare.group(1));
                }
            }
            Matcher declared = LENGTH_FACET.matcher(source);
            while (declared.find()) {
                faceted.add(entity.getFileName() + ": " + declared.group());
            }
        }

        assertThat(faceted)
                .as("column mappings declaring a width bound, which the data-model boundary forbids")
                .isEmpty();
        assertThat(bareCharacterColumns)
                .as("character columns left on the provider's invented capacity")
                .isEmpty();
        assertThat(characterColumns)
                .as("character columns the entities map")
                .isNotEmpty();
        assertThat(characterColumns)
                .as("columns DL-068 must name, against those the entities map")
                .allSatisfy(column -> assertThat(mappingRule)
                        .as("DL-068 naming the %s column", column)
                        .contains(column));
        assertThat(mappingRule)
                .as("mapping DL-068 states")
                .contains("`columnDefinition = \"varchar\"`")
                .contains("no `length`");
        Matcher stated = CHARACTER_COLUMN_COUNT.matcher(mappingRule);
        assertThat(stated.find()).as("count DL-068 states for the character columns").isTrue();
        assertThat(NUMBER_WORDS.get(stated.group(1)))
                .as("count DL-068 states, against the character columns the entities map")
                .isEqualTo(characterColumns.size());
        assertThat(primaryKeyMapping)
                .as("mapping DL-069 states for the settings primary key")
                .contains("declares no `length`")
                .contains("`columnDefinition = \"varchar\"`");
    }

    @Test
    @DisplayName("cites at least one row from the tree it governs")
    void citesAtLeastOneRowFromTheTreeItGoverns() {
        Set<String> cited = new TreeSet<>();
        for (Path file : scannedFiles()) {
            if (!file.equals(DECISION_LOG)) {
                cited.addAll(citationsIn(file));
            }
        }

        assertThat(cited).as("identifiers cited outside the log itself").isNotEmpty();
        assertThat(rows().keySet()).as("declared rows").containsAll(cited);
    }

    @Test
    @DisplayName("carries no decision rationale in any comment of the delivered sources or the POM")
    void carriesNoDecisionRationaleInAnyCommentOfTheDeliveredSourcesOrThePom() {
        Map<String, String> offending = new TreeMap<>();
        for (Path file : rationaleScannedFiles()) {
            List<String> lines = read(file).lines().toList();
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index).strip();
                if (!isComment(line)) {
                    continue;
                }
                String lowered = line.toLowerCase(Locale.ROOT);
                String marker = RATIONALE_MARKERS.stream()
                        .filter(lowered::contains)
                        .findFirst()
                        .orElse(null);
                if (marker != null) {
                    offending.put(file + ":" + (index + 1) + " [" + marker + "]", line);
                }
            }
        }

        assertThat(offending)
                .as("comments carrying rationale, which docs/DECISION_LOG.md owns (Rule 1, DL-058)")
                .isEmpty();
    }

    /**
     * Lists every file whose comments are held to the no-rationale rule.
     *
     * @return the delivered sources and the POM, in a stable order
     */
    private static List<Path> rationaleScannedFiles() {
        List<Path> files = new ArrayList<>();
        for (Path root : RATIONALE_SCANNED_ROOTS) {
            if (!Files.exists(root)) {
                continue;
            }
            if (Files.isRegularFile(root)) {
                files.add(root);
                continue;
            }
            try (Stream<Path> walked = Files.walk(root)) {
                walked.filter(Files::isRegularFile)
                        .filter(DecisionLogCitationTest::isScanned)
                        .sorted()
                        .forEach(files::add);
            } catch (IOException ex) {
                throw new UncheckedIOException("Could not walk " + root, ex);
            }
        }
        return files;
    }

    /**
     * Reports whether a stripped line opens a comment in any of the scanned file kinds.
     *
     * @param strippedLine the line with leading and trailing whitespace removed
     * @return {@code true} when the line is a comment line
     */
    private static boolean isComment(String strippedLine) {
        return COMMENT_OPENERS.stream().anyMatch(strippedLine::startsWith);
    }

    /**
     * Lists the entity sources, which are the only classes that map a column.
     *
     * @return the entity source files, in a stable order
     */
    private static List<Path> entitySources() {
        List<String> names = new ArrayList<>();
        Path root = Path.of("src/main/java/com/codeskeptic/scanner/entity");
        collectJavaFiles(root, names);
        return names.stream().map(root::resolve).toList();
    }

    /**
     * Collects every Java file beneath a package root, named as the matrix names it.
     *
     * @param root the package root to walk
     * @param into the collection each path is added to, relative to {@code root}
     */
    private static void collectJavaFiles(Path root, List<String> into) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .forEach(into::add);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to walk " + root, ex);
        }
    }

    /**
     * Reads the rows that have been rewritten as redirections, with the successors each one names.
     *
     * @return one entry per redirected row, holding the identifiers that now own its subject
     */
    private static Map<String, List<String>> redirections() {
        Map<String, List<String>> redirected = new LinkedHashMap<>();
        rows().forEach((identifier, columns) -> {
            Matcher matcher = SUPERSEDED.matcher(String.join("|", columns));
            if (matcher.find()) {
                redirected.put(identifier, identifiers(matcher.group(1)));
            }
        });
        return redirected;
    }

    /**
     * Reads the log's prose enumeration of its pointer rows into the same shape {@link #redirections()}
     * produces, so the two are directly comparable.
     *
     * <p>The enumeration is a {@code ; }-separated list of groups, each naming one or more redirected
     * rows, then {@code →}, then the successors they share. A group is expanded to one entry per
     * redirected row it names.
     *
     * @param enumeration the text between {@code complete set:} and the sentence that follows it
     * @return one entry per enumerated row, holding the successors the enumeration gives it
     */
    private static Map<String, List<String>> enumerated(String enumeration) {
        Map<String, List<String>> stated = new LinkedHashMap<>();
        for (String group : enumeration.split(";")) {
            String[] sides = group.split("→");
            if (sides.length != 2) {
                continue;
            }
            List<String> successors = identifiers(sides[1]);
            for (String redirected : identifiers(sides[0])) {
                stated.put(redirected, successors);
            }
        }
        return stated;
    }

    /**
     * Lists the identifiers a fragment names, in the order it names them.
     *
     * @param fragment any text that may contain identifiers
     * @return the identifiers found, in order, without repeats
     */
    private static List<String> identifiers(String fragment) {
        List<String> found = new ArrayList<>();
        Matcher matcher = CITATION.matcher(fragment);
        while (matcher.find()) {
            if (!found.contains(matcher.group())) {
                found.add(matcher.group());
            }
        }
        return found;
    }

    /**
     * Joins wrapped prose into one line, so a sentence that spans several lines can be matched whole.
     *
     * @param text the text to collapse
     * @return the text with every run of whitespace reduced to a single space
     */
    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ");
    }

    /**
     * Reads the entries table, keyed by identifier in the order the rows are written.
     *
     * @return one entry per row, holding that row's four content columns
     */
    private static Map<String, List<String>> rows() {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (String line : read(DECISION_LOG).split("\n")) {
            Matcher matcher = ROW.matcher(line);
            if (matcher.matches()) {
                rows.put(matcher.group(1), List.of(matcher.group(2).split("\\|", -1)));
            }
        }
        return rows;
    }

    /**
     * Lists every file a citation may appear in.
     *
     * @return the scanned files, in a stable order
     */
    private static List<Path> scannedFiles() {
        List<Path> files = new ArrayList<>();
        for (Path root : SCANNED_ROOTS) {
            if (!Files.exists(root)) {
                continue;
            }
            if (Files.isRegularFile(root)) {
                files.add(root);
                continue;
            }
            try (Stream<Path> walked = Files.walk(root)) {
                walked.filter(Files::isRegularFile)
                        .filter(DecisionLogCitationTest::isScanned)
                        .sorted()
                        .forEach(files::add);
            } catch (IOException ex) {
                throw new UncheckedIOException("Could not walk " + root, ex);
            }
        }
        return files;
    }

    /**
     * Reports whether a file's name carries one of the scanned extensions.
     *
     * @param file the candidate file
     * @return {@code true} when the file is scanned for citations
     */
    private static boolean isScanned(Path file) {
        String name = file.getFileName().toString();
        return SCANNED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    /**
     * Collects the citations one file makes.
     *
     * @param file the file to read
     * @return the distinct identifiers the file names
     */
    private static Set<String> citationsIn(Path file) {
        Set<String> citations = new LinkedHashSet<>();
        Matcher matcher = CITATION.matcher(read(file));
        while (matcher.find()) {
            citations.add(matcher.group());
        }
        return citations;
    }

    /**
     * Reads one file as UTF-8 text.
     *
     * @param file the file to read
     * @return its whole content
     */
    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read " + file, ex);
        }
    }
}
