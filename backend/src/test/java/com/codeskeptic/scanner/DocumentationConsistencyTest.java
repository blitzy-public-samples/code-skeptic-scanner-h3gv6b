package com.codeskeptic.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.MalformedInputException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.BaseStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Executable consistency contract for the two Rule 1 explainability artifacts.
 *
 * <p>Net-new (no source construct) — DL-216 — see docs/DECISION_LOG.md.
 */
@DisplayName("Documentation consistency")
final class DocumentationConsistencyTest {

    /**
     * Number of test classes the Agent Action Plan's fixed inventory named. It records a plan rather
     * than a delivery, and it is the one figure here that is not derived from the tree.
     */
    private static final int PLANNED_TEST_CLASS_COUNT = 19;

    private static final Path BACKEND_ROOT = locateBackendRoot();
    private static final Path REPOSITORY_ROOT = BACKEND_ROOT.getParent();
    private static final Path DECISION_LOG = BACKEND_ROOT.resolve("docs/DECISION_LOG.md");
    private static final Path TRACEABILITY_MATRIX =
            BACKEND_ROOT.resolve("docs/TRACEABILITY_MATRIX.md");

    private static final Pattern DECISION_REFERENCE = Pattern.compile("DL-(\\d{3})");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");

    private static final Set<String> RETIRED_PYTHON_FILES = Set.of(
            "backend/app/main.py",
            "backend/app/core/config.py",
            "backend/app/core/security.py",
            "backend/app/db/database.py",
            "backend/app/db/models.py",
            "backend/app/schema/tweet.py",
            "backend/app/schema/response.py",
            "backend/app/api/tweets.py",
            "backend/app/api/responses.py",
            "backend/app/api/settings.py",
            "backend/app/api/analytics.py",
            "backend/app/services/twitter_service.py",
            "backend/app/services/sentiment_analysis.py",
            "backend/app/services/notion_service.py",
            "backend/app/services/llm_service.py",
            "backend/app/tasks/tweet_monitoring.py",
            "backend/app/tasks/response_generation.py",
            "backend/tests/test_api.py",
            "backend/tests/test_services.py",
            "backend/tests/test_tasks.py");

    private static final Set<String> OPERATIONS_FILES = Set.of(
            "infrastructure/docker/Dockerfile.backend",
            ".github/workflows/ci.yml",
            ".github/workflows/cd.yml",
            "scripts/deploy.sh");

    @Test
    @DisplayName("keeps decision identifiers contiguous, unique and fully populated")
    void keepsDecisionIdentifiersContiguousUniqueAndFullyPopulated() throws IOException {
        List<DecisionRow> decisions = decisionRows();
        int declared = decisions.size();
        List<Integer> expectedIdentifiers =
                IntStream.rangeClosed(1, declared).boxed().toList();

        assertThat(decisions).isNotEmpty();
        assertThat(decisions.stream().map(DecisionRow::identifier).toList())
                .containsExactlyElementsOf(expectedIdentifiers);
        for (DecisionRow decision : decisions) {
            assertThat(decision.decision()).as("DL-%03d decision", decision.identifier()).isNotBlank();
            assertThat(decision.alternatives())
                    .as("DL-%03d alternatives", decision.identifier())
                    .isNotBlank();
            assertThat(decision.rationale())
                    .as("DL-%03d rationale", decision.identifier())
                    .isNotBlank();
            assertThat(decision.risks()).as("DL-%03d risks", decision.identifier()).isNotBlank();
        }

        assertThat(collapse(Files.readString(DECISION_LOG)))
                .contains("Every identifier from `DL-001` to `DL-%03d`".formatted(declared))
                .contains(words(declared) + " rows, with no gap and no repeat");
    }

    @Test
    @DisplayName("resolves every repository decision citation to a populated row")
    void resolvesEveryRepositoryDecisionCitationToAPopulatedRow() throws IOException {
        Set<Integer> decisions = decisionRows().stream()
                .map(DecisionRow::identifier)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Integer> citations = new LinkedHashSet<>();

        for (Path file : repositoryTextFiles()) {
            Matcher matcher = DECISION_REFERENCE.matcher(readTextIfPossible(file));
            while (matcher.find()) {
                citations.add(Integer.parseInt(matcher.group(1)));
            }
        }

        assertThat(citations).isNotEmpty().isSubsetOf(decisions).containsAll(decisions);
    }

    @Test
    @DisplayName("maps each retired Python file once in the source inventory")
    void mapsEachRetiredPythonFileOnceInTheSourceInventory() throws IOException {
        List<List<String>> rows = tableRows(section(
                Files.readString(TRACEABILITY_MATRIX),
                "### 1.1 Retired Python files",
                "### 1.2 HTTP routes"));
        Map<String, String> statuses = new LinkedHashMap<>();

        for (List<String> row : rows) {
            if (row.size() != 4 || !row.get(0).matches("\\d+")) {
                continue;
            }
            String source = inlineCode(row.get(1));
            assertThat(statuses.put(source, row.get(3))).as(source).isNull();
        }

        assertThat(statuses).hasSize(20);
        assertThat(statuses.keySet()).containsExactlyInAnyOrderElementsOf(RETIRED_PYTHON_FILES);
        assertThat(statuses.values()).containsOnly("Delivered");
    }

    @Test
    @DisplayName("maps every delivered backend file exactly once in the target inventory")
    void mapsEveryDeliveredBackendFileExactlyOnceInTheTargetInventory() throws IOException {
        String matrix = Files.readString(TRACEABILITY_MATRIX);
        Set<String> documentedBuildFiles = targetFiles(
                section(matrix,
                        "### 2.1 Build, configuration and documentation",
                        "### 2.2 Application core, configuration and security"))
                .stream()
                .map(DocumentationConsistencyTest::withoutBackendPrefix)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> documentedMainFiles = targetFiles(
                section(matrix,
                        "### 2.2 Application core, configuration and security",
                        "### 2.7 Tests"))
                .stream()
                .map(path -> "src/main/java/com/codeskeptic/scanner/" + path)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> documentedTestFiles = testRows(matrix).keySet().stream()
                .map(path -> "src/test/java/com/codeskeptic/scanner/" + path)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Set<String> documented = new LinkedHashSet<>();
        documented.addAll(documentedBuildFiles);
        documented.addAll(documentedMainFiles);
        documented.addAll(documentedTestFiles);

        assertThat(documentedBuildFiles).hasSize(resourceAndBuildFileCount());
        assertThat(documentedMainFiles).hasSize(mainClassCount());
        assertThat(documentedTestFiles).hasSize(testClassCount());
        assertThat(documented).containsExactlyInAnyOrderElementsOf(backendFiles());
    }

    @Test
    @DisplayName("maps each edited operations file once outside the backend")
    void mapsEachEditedOperationsFileOnceOutsideTheBackend() throws IOException {
        Set<String> documented = targetFiles(section(
                Files.readString(TRACEABILITY_MATRIX),
                "### 2.9 Operations files edited outside `backend/`",
                "## 3. Targets not delivered at this checkpoint"));

        assertThat(documented).containsExactlyInAnyOrderElementsOf(OPERATIONS_FILES);
        assertThat(documented).allSatisfy(path ->
                assertThat(REPOSITORY_ROOT.resolve(path)).exists().isRegularFile());
    }

    @Test
    @DisplayName("matches every test row and case count to the executable suite")
    void matchesEveryTestRowAndCaseCountToTheExecutableSuite() throws Exception {
        Map<String, Integer> documented = testRows(Files.readString(TRACEABILITY_MATRIX));
        Map<String, Integer> executable = new LinkedHashMap<>();

        for (String path : javaSources(BACKEND_ROOT.resolve(
                "src/test/java/com/codeskeptic/scanner"))) {
            String className = "com.codeskeptic.scanner."
                    + path.substring(0, path.length() - ".java".length()).replace('/', '.');
            executable.put(path, countTestCases(Class.forName(className)));
        }

        int cases = executable.values().stream().mapToInt(Integer::intValue).sum();

        assertThat(documented).containsExactlyInAnyOrderEntriesOf(executable);
        assertThat(documented).hasSize(testClassCount());
        assertThat(documented.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(cases);
        assertThat(collapse(Files.readString(TRACEABILITY_MATRIX)))
                .contains("the %s rows below sum to the %d cases"
                        .formatted(words(executable.size()), cases));
    }

    @Test
    @DisplayName("keeps coverage-summary counts aligned with repository inventories")
    void keepsCoverageSummaryCountsAlignedWithRepositoryInventories() throws IOException {
        Map<String, List<String>> coverage = coverageRows(Files.readString(TRACEABILITY_MATRIX));

        assertThat(coverage.get("Retired Python files (§1.1)"))
                .containsExactly("20", "20", "20 fully", "0");
        String main = String.valueOf(mainClassCount());
        String tests = String.valueOf(testClassCount());
        String resources = String.valueOf(resourceAndBuildFileCount());

        assertThat(coverage.get("Delivered main Java classes (§2.2–§2.6)"))
                .containsExactly(main, main, main, "—");
        assertThat(coverage.get("Delivered test Java classes (§2.7)"))
                .containsExactly(tests, tests, tests, "—");
        assertThat(coverage.get("Delivered resources and build files (§2.1)"))
                .containsExactly(resources, resources, resources, "—");
        assertThat(coverage.get("Operations files edited (§2.9)"))
                .containsExactly("4", "4", "4", "—");
        assertThat(coverage.get("Planned targets (§3)"))
                .containsExactly("0", "—", "—", "0");
    }

    @Test
    @DisplayName("keeps delivery-state prose aligned with the derived counts")
    void keepsDeliveryStateProseAlignedWithTheDerivedCounts() throws Exception {
        String matrix = Files.readString(TRACEABILITY_MATRIX);
        int tests = testClassCount();
        Map<String, String> origins = testOrigins(matrix);
        long netNew = origins.values().stream()
                .filter(origin -> origin.startsWith("*No source construct"))
                .count();
        long carried = tests - netNew;
        int cases = 0;
        for (String path
                : javaSources(BACKEND_ROOT.resolve("src/test/java/com/codeskeptic/scanner"))) {
            cases += countTestCases(Class.forName("com.codeskeptic.scanner."
                    + path.substring(0, path.length() - ".java".length()).replace('/', '.')));
        }

        assertThat(origins).hasSize(tests);
        assertThat(matrix).doesNotContain("| PLANNED |");
        assertThat(collapse(matrix))
                .contains("is %s files under `backend/src/test/java`, which is the plan's %s plus the %s"
                        .formatted(words(tests), words(PLANNED_TEST_CLASS_COUNT),
                                words(tests - PLANNED_TEST_CLASS_COUNT)))
                .contains("%s of the %s delivered test classes carry a row naming a source construct"
                        .formatted(capitalised(words((int) carried)), words(tests)))
                .contains("the remaining %s have no source construct of any kind"
                        .formatted(words((int) netNew)))
                .contains("the %s rows below sum to the %d cases".formatted(words(tests), cases))
                .contains("%s classes running %d cases".formatted(words(tests), cases));
    }

    /**
     * Joins wrapped prose into one line, so a sentence a Markdown paragraph wraps can be matched whole.
     *
     * @param text the document text
     * @return the text with every run of whitespace reduced to a single space
     */
    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ");
    }

    /**
     * Counts the Java classes delivered under {@code src/main/java}.
     *
     * @return the delivered main-class count
     * @throws IOException if the tree cannot be walked
     */
    private static int mainClassCount() throws IOException {
        return javaSources(BACKEND_ROOT.resolve("src/main/java/com/codeskeptic/scanner")).size();
    }

    /**
     * Counts the Java classes delivered under {@code src/test/java}.
     *
     * @return the delivered test-class count
     * @throws IOException if the tree cannot be walked
     */
    private static int testClassCount() throws IOException {
        return javaSources(BACKEND_ROOT.resolve("src/test/java/com/codeskeptic/scanner")).size();
    }

    /**
     * Counts the delivered files under {@code backend/} that are not Java sources.
     *
     * @return the resource, build and documentation file count
     * @throws IOException if the tree cannot be walked
     */
    private static int resourceAndBuildFileCount() throws IOException {
        return backendFiles().size() - mainClassCount() - testClassCount();
    }

    /**
     * Reads the source construct each per-class test row of the matrix names.
     *
     * @param matrix the traceability matrix
     * @return one entry per test row, holding the source-construct cell as written
     */
    private static Map<String, String> testOrigins(String matrix) {
        Map<String, String> origins = new LinkedHashMap<>();
        for (List<String> row : tableRows(section(
                matrix, "### 2.7 Tests", "### 2.9 Operations files edited outside `backend/`"))) {
            if (row.size() != 4) {
                continue;
            }
            Matcher code = INLINE_CODE.matcher(row.get(0));
            if (!code.find() || !row.get(2).matches("\\d+")) {
                continue;
            }
            origins.put(code.group(1), row.get(1));
        }
        return origins;
    }

    /**
     * Renders a count as the English words the two documents write it in.
     *
     * @param count the count to render, from zero to nine hundred and ninety-nine
     * @return for example {@code forty-four} or {@code two hundred and seventy-four}
     */
    private static String words(int count) {
        String[] units = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
                "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
                "seventeen", "eighteen", "nineteen"};
        String[] tens = {"", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty",
                "ninety"};
        if (count < 0 || count > 999) {
            throw new IllegalArgumentException("Unsupported count: " + count);
        }
        if (count < 20) {
            return units[count];
        }
        if (count < 100) {
            return count % 10 == 0 ? tens[count / 10] : tens[count / 10] + '-' + units[count % 10];
        }
        String hundreds = units[count / 100] + " hundred";
        return count % 100 == 0 ? hundreds : hundreds + " and " + words(count % 100);
    }

    /**
     * Capitalises the first letter of a rendered count, for a sentence that opens with it.
     *
     * @param rendered the rendered count
     * @return the same text with its first letter in upper case
     */
    private static String capitalised(String rendered) {
        return Character.toUpperCase(rendered.charAt(0)) + rendered.substring(1);
    }

    private static List<DecisionRow> decisionRows() throws IOException {
        List<DecisionRow> decisions = new ArrayList<>();
        for (String line : Files.readAllLines(DECISION_LOG)) {
            if (!line.startsWith("| DL-")) {
                continue;
            }
            String[] cells = line.split("\\|", -1);
            assertThat(cells).as(line).hasSize(7);
            Matcher identifier = DECISION_REFERENCE.matcher(cells[1]);
            assertThat(identifier.find()).as(line).isTrue();
            decisions.add(new DecisionRow(
                    Integer.parseInt(identifier.group(1)),
                    cells[2].strip(),
                    cells[3].strip(),
                    cells[4].strip(),
                    cells[5].strip()));
        }
        return decisions;
    }

    private static Set<Path> repositoryTextFiles() throws IOException {
        Set<Path> files = new LinkedHashSet<>();
        Files.walkFileTree(REPOSITORY_ROOT, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                Path name = directory.getFileName();
                if (name != null && Set.of(".git", "target", "node_modules")
                        .contains(name.toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile()) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static String readTextIfPossible(Path file) throws IOException {
        try {
            return Files.readString(file);
        } catch (MalformedInputException ignored) {
            return "";
        }
    }

    private static Set<String> backendFiles() throws IOException {
        Set<String> files = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.walk(BACKEND_ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(BACKEND_ROOT.resolve("target")))
                    .map(BACKEND_ROOT::relativize)
                    .map(DocumentationConsistencyTest::unixPath)
                    .forEach(files::add);
        }
        return files;
    }

    private static Set<String> targetFiles(String section) {
        Set<String> targets = new LinkedHashSet<>();
        for (List<String> row : tableRows(section)) {
            if (row.isEmpty()) {
                continue;
            }
            Matcher code = INLINE_CODE.matcher(row.get(0));
            if (code.find()) {
                targets.add(code.group(1));
            }
        }
        return targets;
    }

    private static Map<String, Integer> testRows(String matrix) {
        Map<String, Integer> tests = new LinkedHashMap<>();
        for (List<String> row : tableRows(section(
                matrix, "### 2.7 Tests", "### 2.9 Operations files edited outside `backend/`"))) {
            if (row.size() != 4) {
                continue;
            }
            Matcher code = INLINE_CODE.matcher(row.get(0));
            if (!code.find() || !row.get(2).matches("\\d+")) {
                continue;
            }
            String path = code.group(1);
            assertThat(tests.put(path, Integer.parseInt(row.get(2)))).as(path).isNull();
        }
        return tests;
    }

    private static Map<String, List<String>> coverageRows(String matrix) {
        Map<String, List<String>> coverage = new LinkedHashMap<>();
        for (List<String> row : tableRows(section(matrix, "## 4. Coverage summary",
                "Marker count check:"))) {
            if (row.size() != 5 || "Coverage set".equals(row.get(0))) {
                continue;
            }
            coverage.put(row.get(0), List.copyOf(row.subList(1, 5)));
        }
        return coverage;
    }

    private static List<List<String>> tableRows(String section) {
        List<List<String>> rows = new ArrayList<>();
        for (String line : section.lines().toList()) {
            if (!line.startsWith("|") || line.matches("^\\|[-:| ]+\\|$")) {
                continue;
            }
            String[] cells = line.split("\\|", -1);
            if (cells.length < 3) {
                continue;
            }
            List<String> row = Arrays.stream(cells, 1, cells.length - 1)
                    .map(String::strip)
                    .toList();
            rows.add(row);
        }
        return rows;
    }

    private static String section(String document, String startHeading, String endHeading) {
        int start = document.indexOf(startHeading);
        int end = document.indexOf(endHeading, start + startHeading.length());
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException(
                    "Cannot locate section from '" + startHeading + "' to '" + endHeading + "'.");
        }
        return document.substring(start, end);
    }

    private static String inlineCode(String cell) {
        Matcher matcher = INLINE_CODE.matcher(cell);
        if (!matcher.find()) {
            throw new IllegalStateException("Expected an inline-code path in: " + cell);
        }
        return matcher.group(1);
    }

    private static String withoutBackendPrefix(String path) {
        return path.startsWith("backend/") ? path.substring("backend/".length()) : path;
    }

    private static Set<String> javaSources(Path root) throws IOException {
        Set<String> sources = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(root::relativize)
                    .map(DocumentationConsistencyTest::unixPath)
                    .sorted()
                    .forEach(sources::add);
        }
        return sources;
    }

    private static int countTestCases(Class<?> type)
            throws ReflectiveOperationException {
        int count = 0;
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPrivate(method.getModifiers())) {
                continue;
            }
            if (method.isAnnotationPresent(Test.class)) {
                count++;
            }
            if (method.isAnnotationPresent(ParameterizedTest.class)) {
                count += countParameterizedInvocations(type, method);
            }
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            count += countTestCases(nested);
        }
        return count;
    }

    private static int countParameterizedInvocations(Class<?> owner, Method testMethod)
            throws ReflectiveOperationException {
        int count = 0;
        ValueSource values = testMethod.getAnnotation(ValueSource.class);
        if (values != null) {
            count += values.shorts().length + values.bytes().length + values.ints().length
                    + values.longs().length + values.floats().length + values.doubles().length
                    + values.chars().length + values.booleans().length + values.strings().length
                    + values.classes().length;
        }

        for (CsvSource csv : testMethod.getAnnotationsByType(CsvSource.class)) {
            count += csv.value().length;
            if (!csv.textBlock().isBlank()) {
                count += (int) csv.textBlock().lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .count();
            }
        }

        if (testMethod.isAnnotationPresent(NullSource.class)) {
            count++;
        }
        if (testMethod.isAnnotationPresent(EmptySource.class)) {
            count++;
        }
        if (testMethod.isAnnotationPresent(NullAndEmptySource.class)) {
            count += 2;
        }

        MethodSource methods = testMethod.getAnnotation(MethodSource.class);
        if (methods != null) {
            String[] providers = methods.value().length == 0
                    ? new String[] {testMethod.getName()}
                    : methods.value();
            for (String provider : providers) {
                count += countProviderElements(owner, provider);
            }
        }

        if (count == 0) {
            throw new IllegalStateException("No supported argument source found for "
                    + owner.getName() + '#' + testMethod.getName());
        }
        return count;
    }

    private static int countProviderElements(Class<?> owner, String specification)
            throws ReflectiveOperationException {
        Class<?> providerType = owner;
        String providerName = specification;
        int separator = specification.indexOf('#');
        if (separator >= 0) {
            providerType = Class.forName(specification.substring(0, separator));
            providerName = specification.substring(separator + 1);
        }

        final String requiredName = providerName;
        Method provider = Arrays.stream(providerType.getDeclaredMethods())
                .filter(method -> requiredName.equals(method.getName()))
                .filter(method -> method.getParameterCount() == 0)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No zero-argument provider '" + specification + "' for " + owner.getName()));
        if (!Modifier.isStatic(provider.getModifiers())) {
            throw new IllegalStateException(
                    "Documentation case counting requires a static provider: " + specification);
        }
        provider.setAccessible(true);
        try {
            return countElements(provider.invoke(null));
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    private static int countElements(Object elements) {
        if (elements == null) {
            throw new IllegalStateException("A method source returned null.");
        }
        if (elements instanceof BaseStream<?, ?> stream) {
            try (stream) {
                return countIterator(stream.iterator());
            }
        }
        if (elements instanceof Iterable<?> iterable) {
            return countIterator(iterable.iterator());
        }
        if (elements instanceof Iterator<?> iterator) {
            return countIterator(iterator);
        }
        if (elements.getClass().isArray()) {
            return Array.getLength(elements);
        }
        throw new IllegalStateException(
                "Unsupported method-source return type: " + elements.getClass().getName());
    }

    private static int countIterator(Iterator<?> iterator) {
        int count = 0;
        while (iterator.hasNext()) {
            iterator.next();
            count++;
        }
        return count;
    }

    private static Path locateBackendRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (isBackendRoot(current)) {
                return current;
            }
            Path nested = current.resolve("backend");
            if (isBackendRoot(nested)) {
                return nested;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate backend/pom.xml and explainability artifacts.");
    }

    private static boolean isBackendRoot(Path candidate) {
        return Files.isRegularFile(candidate.resolve("pom.xml"))
                && Files.isRegularFile(candidate.resolve("docs/DECISION_LOG.md"))
                && Files.isRegularFile(candidate.resolve("docs/TRACEABILITY_MATRIX.md"));
    }

    private static String unixPath(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private record DecisionRow(
            int identifier, String decision, String alternatives, String rationale, String risks) {
    }
}