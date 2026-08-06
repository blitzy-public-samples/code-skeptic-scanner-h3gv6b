package com.codeskeptic.scanner;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

// Net-new (no Python counterpart) — DL-004, DL-005, DL-053, DL-056, DL-057, DL-106, DL-107,
// DL-215, DL-218, DL-243 — see docs/DECISION_LOG.md
/**
 * Verifies the repository's backend build, container, CI, CD and deployment-script contracts.
 *
 * <p>Each workflow assertion covers one of the line-level edits this migration is authorised to make
 * and the pre-refactor content each edit replaced — see docs/DECISION_LOG.md DL-057 and DL-215.
 */
@DisplayName("Backend operations contracts")
class OperationsContractTest {

    private static final Path REPOSITORY_ROOT = repositoryRoot();

    private static final Path DOCKERFILE =
            REPOSITORY_ROOT.resolve("infrastructure/docker/Dockerfile.backend");

    private static final Path CI_WORKFLOW =
            REPOSITORY_ROOT.resolve(".github/workflows/ci.yml");

    private static final Path CD_WORKFLOW =
            REPOSITORY_ROOT.resolve(".github/workflows/cd.yml");

    private static final Path DEPLOY_SCRIPT = REPOSITORY_ROOT.resolve("scripts/deploy.sh");

    private static final Set<String> DOCKER_INSTRUCTIONS = Set.of(
            "FROM", "ARG", "COPY", "RUN", "WORKDIR", "USER", "EXPOSE", "HEALTHCHECK", "CMD");

    @Test
    @DisplayName("locates the repository root from the Maven or repository working directory")
    void locatesTheRepositoryRootFromTheMavenOrRepositoryWorkingDirectory() {
        assertThat(REPOSITORY_ROOT.resolve(".github")).isDirectory();
        assertThat(REPOSITORY_ROOT.resolve("backend/pom.xml")).isRegularFile();
        assertThat(REPOSITORY_ROOT.resolve("infrastructure/docker/Dockerfile.backend"))
                .isRegularFile();
    }

    @Test
    @DisplayName("uses only recognized complete Dockerfile instructions")
    void usesOnlyRecognizedCompleteDockerfileInstructions() throws IOException {
        List<String> instructions = dockerInstructions(read(DOCKERFILE));

        assertThat(instructions).isNotEmpty();
        assertThat(instructions).allSatisfy(instruction -> {
            String keyword = instruction.substring(0, instruction.indexOf(' '));
            assertThat(keyword).isIn(DOCKER_INSTRUCTIONS);
            assertThat(instruction).doesNotEndWith("\\");
        });
    }

    @Test
    @DisplayName("builds the backend with Maven 3.9.16 on Java 21")
    void buildsTheBackendWithMavenOnJava21() throws IOException {
        String dockerfile = read(DOCKERFILE);

        assertThat(dockerfile)
                .contains("FROM maven:3.9.16-eclipse-temurin-21@sha256:"
                        + "c07f7ccfb8ca6c9fa29ee523f00afa7d2ca6132c92f8652c4aebb5ee3491f502"
                        + " AS build")
                .contains("COPY pom.xml .")
                .contains("RUN mvn -B dependency:go-offline")
                .contains("COPY src ./src")
                .contains("RUN mvn -B clean package -DskipTests");
    }

    @Test
    @DisplayName("runs the packaged jar as the unprivileged Java 21 runtime user on port 5000")
    void runsThePackagedJarAsTheUnprivilegedJavaRuntimeUserOnPort5000() throws IOException {
        String dockerfile = read(DOCKERFILE);

        assertThat(dockerfile)
                .contains("FROM eclipse-temurin:21.0.11_10-jre@sha256:"
                        + "8cef5fc7bebe421363ab543a2f4db5caf7d119d8db67d56b0f56c485d2de4d55")
                .contains("COPY --from=build --chown=0:10001 /app/target/*.jar app.jar")
                .contains("USER 10001:10001")
                .contains("EXPOSE 5000")
                .contains("HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3")
                .contains("\"http://127.0.0.1:${PORT:-5000}/tweets\"")
                .contains("CMD [\"sh\", \"-c\", "
                        + "\"exec java -XX:MaxRAMPercentage=75.0 -jar app.jar\"]");
    }

    @Test
    @DisplayName("contains no retired Python backend build instruction")
    void containsNoRetiredPythonBackendBuildInstruction() throws IOException {
        assertThat(read(DOCKERFILE).toLowerCase())
                .doesNotContain("python:", "pip install", "requirements.txt", "python app.py");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {".github/workflows/ci.yml", ".github/workflows/cd.yml"})
    @DisplayName("parses each backend workflow as YAML")
    void parsesEachBackendWorkflowAsYaml(String relativePath) throws IOException {
        Object document = yaml().load(read(REPOSITORY_ROOT.resolve(relativePath)));

        assertThat(document).isInstanceOf(Map.class);
        Map<?, ?> mapping = (Map<?, ?>) document;
        assertThat(mapping.containsKey("name")).isTrue();
        assertThat(mapping.containsKey("jobs")).isTrue();
    }

    @Test
    @DisplayName("runs the backend CI job on Temurin 21 with Maven verification")
    void runsTheBackendCiJobOnTemurin21WithMavenVerification() throws IOException {
        String workflow = read(CI_WORKFLOW);

        assertThat(workflow)
                .contains("uses: actions/setup-java@v4")
                .contains("distribution: 'temurin'")
                .contains("java-version: '21'")
                .contains("cache: maven");

        String backendStep = section(workflow, "- name: Build and test backend",
                "- name: Run frontend tests");
        assertThat(backendStep)
                .contains("working-directory: ./backend")
                .contains("run: mvn -B clean verify")
                .doesNotContain("python", "pip", "flake8", "mypy", "pytest");
    }

    @Test
    @DisplayName("carries no retired Python backend step and leaves every frontend step standing")
    void carriesNoRetiredPythonBackendStepAndLeavesEveryFrontendStepStanding() throws IOException {
        String workflow = read(CI_WORKFLOW);

        assertThat(workflow)
                .doesNotContain("actions/setup-python")
                .doesNotContain("pip install -r backend/requirements.txt")
                .doesNotContain("flake8 backend")
                .doesNotContain("mypy backend")
                .doesNotContain("pytest backend/tests")
                .doesNotContain("python -m build");

        assertThat(workflow)
                .contains("jobs:\n  build-and-test:")
                .contains("uses: actions/setup-node@v2")
                .contains("node-version: '14'")
                .contains("run: npm ci")
                .contains("npm run lint")
                .contains("npm run type-check")
                .contains("run: npm test")
                .contains("run: npm run build")
                .contains("- name: Deploy to staging");
    }

    @Test
    @DisplayName("builds the backend image from the declared Dockerfile in the backend context")
    void buildsTheBackendImageFromTheDeclaredDockerfileInTheBackendContext() throws IOException {
        String workflow = read(CD_WORKFLOW);

        assertThat(workflow)
                .contains("docker build -t $BACKEND_IMAGE "
                        + "-f infrastructure/docker/Dockerfile.backend ./backend")
                .contains("docker push $BACKEND_IMAGE");
    }

    @Test
    @DisplayName("changes the CD workflow at its backend build line and nowhere else")
    void changesTheCdWorkflowAtItsBackendBuildLineAndNowhereElse() throws IOException {
        String workflow = read(CD_WORKFLOW);

        assertThat(workflow)
                .contains("BACKEND_IMAGE: gcr.io/${{ secrets.GCP_PROJECT_ID }}/code-skeptic-backend")
                .contains("uses: google-github-actions/setup-gcloud@v0.2.1")
                .contains("run: gcloud auth configure-docker")
                .contains("gcloud run deploy code-skeptic-backend")
                .contains("--image $BACKEND_IMAGE")
                .contains("gsutil -m rsync -r frontend/build gs://$FRONTEND_BUCKET")
                .contains("gcloud compute backend-buckets create code-skeptic-frontend")
                .contains("uses: 8398a7/action-slack@v3");

        assertThat(workflow)
                .doesNotContain("artifacts repositories")
                .doesNotContain("gcloud secrets")
                .doesNotContain("--min-instances")
                .doesNotContain("--max-instances")
                .doesNotContain("--no-cpu-throttling")
                .doesNotContain("--port ");
    }

    @Test
    @DisplayName("declares no repository-hygiene file outside the frozen operations inventory")
    void declaresNoRepositoryHygieneFileOutsideTheFrozenOperationsInventory() {
        assertThat(REPOSITORY_ROOT.resolve(".github/dependabot.yml")).doesNotExist();
        assertThat(REPOSITORY_ROOT.resolve(".yamllint.yml")).doesNotExist();
        assertThat(REPOSITORY_ROOT.resolve(".github/workflows/ci.yml")).isRegularFile();
        assertThat(REPOSITORY_ROOT.resolve(".github/workflows/cd.yml")).isRegularFile();
    }

    @Test
    @DisplayName("passes bash syntax validation")
    void passesBashSyntaxValidation() throws Exception {
        Process process = new ProcessBuilder("bash", "-n", DEPLOY_SCRIPT.toString())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(finished).isTrue();
        assertThat(process.exitValue()).isZero();
        assertThat(output).isEmpty();
    }

    @Test
    @DisplayName("packages the backend with Maven in the deployment script")
    void packagesTheBackendWithMavenInTheDeploymentScript() throws IOException {
        String script = read(DEPLOY_SCRIPT);
        String backendBlock = section(script, "# Package backend application",
                "# Deploy backend to Google Cloud Run");

        assertThat(backendBlock)
                .contains("cd backend")
                .contains("mvn clean package")
                .contains("gcloud builds submit --tag gcr.io/code-skeptic-scanner/backend")
                .doesNotContain("npm run build", "python", "pip");
    }

    /**
     * Reads a UTF-8 repository file.
     *
     * @param path file path
     * @return file contents
     * @throws IOException when the file cannot be read
     */
    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * Returns the text between two exact markers.
     *
     * @param text full document
     * @param start start marker
     * @param end end marker
     * @return selected text including the start marker and excluding the end marker
     */
    private static String section(String text, String start, String end) {
        int from = text.indexOf(start);
        int to = text.indexOf(end, from + start.length());
        if (from < 0 || to < 0) {
            throw new IllegalStateException("Document section markers are absent: "
                    + start + " / " + end);
        }
        return text.substring(from, to);
    }

    /**
     * Parses logical Dockerfile instructions, joining line continuations.
     *
     * @param dockerfile Dockerfile text
     * @return logical instructions without comments or blank lines
     */
    private static List<String> dockerInstructions(String dockerfile) {
        List<String> instructions = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String rawLine : dockerfile.lines().toList()) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            boolean continued = line.endsWith("\\");
            String segment = continued ? line.substring(0, line.length() - 1).stripTrailing() : line;
            if (!current.isEmpty()) {
                current.append(' ');
            }
            current.append(segment);
            if (!continued) {
                instructions.add(current.toString());
                current.setLength(0);
            }
        }
        assertThat(current).as("unterminated Dockerfile continuation").isEmpty();
        return instructions;
    }

    /**
     * Creates a safe YAML reader for trusted workflow files.
     *
     * @return YAML parser
     */
    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(1_000_000);
        return new Yaml(new SafeConstructor(options));
    }

    /**
     * Finds the checkout containing both {@code .github} and {@code backend}.
     *
     * @return repository root
     */
    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".github"))
                    && Files.isRegularFile(current.resolve("backend/pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("No repository root exists above user.dir.");
    }
}