package com.codeskeptic.scanner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.catalina.util.ServerInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

// Net-new (no source construct: the retired tree carried no dependency manifest at all) — DL-169,
// DL-170, DL-240, DL-241 — see docs/DECISION_LOG.md
/**
 * Holds {@code backend/pom.xml} to the dependency contract the module declares.
 *
 * <p>Three properties of the build are asserted here.
 *
 * <ul>
 *   <li>The two version properties the file overrides are declared, and each names a version at or
 *       above the patch release that carries the published security fix for that coordinate:
 *       {@code tomcat.version} at or above {@value #TOMCAT_FLOOR} and {@code postgresql.version} at
 *       or above {@value #POSTGRESQL_FLOOR}.</li>
 *   <li>The artifacts actually on the classpath report those same versions, read from the shipped
 *       jars at runtime rather than from the file: {@link ServerInfo#getServerNumber()} for the
 *       embedded container and {@code org.postgresql.util.DriverInfo.DRIVER_VERSION}, read
 *       reflectively so the compiler cannot fold the constant into this class.</li>
 *   <li>Every version the file states is a concrete version. No {@code LATEST}, no {@code RELEASE}
 *       and no unresolved placeholder appears, and exactly the five coordinates the module pins
 *       carry a {@code <version>} element of their own.</li>
 * </ul>
 *
 * <p>The file is parsed with the JDK document builder, configured to resolve no external entity and
 * to disallow a document type declaration.
 *
 * <p>This class starts no Spring context, opens no socket and reads no environment variable.
 */
@DisplayName("Build dependency contract")
class BuildDependencyContractTest {

    /** Lowest embedded-container version this module ships. */
    private static final String TOMCAT_FLOOR = "10.1.57";

    /** Lowest PostgreSQL JDBC driver version this module ships. */
    private static final String POSTGRESQL_FLOOR = "42.7.12";

    /** Lowest accepted Netty version; the BOM manages 4.1.135.Final — DL-100. */
    private static final String NETTY_FLOOR = "4.1.136.Final";

    /** Lowest accepted Jackson BOM version; the BOM manages 2.21.4 — DL-101. */
    private static final String JACKSON_FLOOR = "2.21.5";

    /** Version text that names no single release. */
    private static final Pattern CONCRETE_VERSION =
            Pattern.compile("^[0-9]+(\\.[0-9]+)*([.-][A-Za-z0-9]+)*$");

    /** Coordinates that carry a {@code <version>} element of their own. */
    private static final Map<String, String> EXPLICIT_PINS = Map.of(
            "io.jsonwebtoken:jjwt-api", "0.13.0",
            "io.jsonwebtoken:jjwt-impl", "0.13.0",
            "io.jsonwebtoken:jjwt-jackson", "0.13.0",
            "com.google.cloud:google-cloud-language", "2.96.0",
            "com.openai:openai-java", "4.49.0",
            "com.mysql:mysql-connector-j", "26.7.0");

    /** The parsed project descriptor, read once. */
    private static final Element PROJECT = readProjectDescriptor();

    @Nested
    @DisplayName("declared version overrides")
    class DeclaredOverrides {

        @ParameterizedTest(name = "[{index}] {0} is declared at or above {1}")
        @CsvSource({
            "tomcat.version," + TOMCAT_FLOOR,
            "postgresql.version," + POSTGRESQL_FLOOR,
            "netty.version," + NETTY_FLOOR,
            "jackson-bom.version," + JACKSON_FLOOR
        })
        @DisplayName("overrides the managed version of a coordinate carrying a published fix")
        void overridesTheManagedVersionOfACoordinateCarryingAPublishedFix(String property,
                String floor) {

            String declared = propertyValue(property);

            assertThat(declared).as(property).isNotNull();
            assertThat(CONCRETE_VERSION.matcher(declared).matches()).as(property).isTrue();
            assertThat(compare(declared, floor)).as("%s %s >= %s", property, declared, floor)
                    .isNotNegative();
        }

        @Test
        @DisplayName("inherits the agreed Spring Boot parent and the Java release")
        void inheritsTheAgreedSpringBootParentAndTheJavaRelease() {
            Element parent = childElement(PROJECT, "parent");

            assertThat(parent).isNotNull();
            assertThat(text(childElement(parent, "groupId"))).isEqualTo("org.springframework.boot");
            assertThat(text(childElement(parent, "artifactId")))
                    .isEqualTo("spring-boot-starter-parent");
            assertThat(text(childElement(parent, "version"))).isEqualTo("3.5.16");
            assertThat(propertyValue("java.version")).isEqualTo("21");
        }

        @ParameterizedTest(name = "[{index}] {0} is not overridden")
        @ValueSource(strings = {"mysql.version", "spring-framework.version", "hibernate.version",
            "h2.version"})
        @DisplayName("leaves every other managed version to the dependency BOM")
        void leavesEveryOtherManagedVersionToTheDependencyBom(String property) {
            assertThat(propertyValue(property)).as(property).isNull();
        }
    }

    @Nested
    @DisplayName("resolved artifacts")
    class ResolvedArtifacts {

        @Test
        @DisplayName("ships an embedded container at or above the fixed patch release")
        void shipsAnEmbeddedContainerAtOrAboveTheFixedPatchRelease() {
            String resolved = ServerInfo.getServerNumber();

            assertThat(resolved).isNotBlank();
            assertThat(compare(resolved, TOMCAT_FLOOR))
                    .as("tomcat-embed-core %s >= %s", resolved, TOMCAT_FLOOR)
                    .isNotNegative();
            assertThat(compare(resolved, propertyValue("tomcat.version")))
                    .as("the resolved container matches the declared override")
                    .isZero();
        }

        @Test
        @DisplayName("ships a PostgreSQL driver at or above the fixed patch release")
        void shipsAPostgresqlDriverAtOrAboveTheFixedPatchRelease() throws Exception {
            String resolved = (String) Class.forName("org.postgresql.util.DriverInfo")
                    .getField("DRIVER_VERSION")
                    .get(null);

            assertThat(resolved).isNotBlank();
            assertThat(compare(resolved, POSTGRESQL_FLOOR))
                    .as("postgresql %s >= %s", resolved, POSTGRESQL_FLOOR)
                    .isNotNegative();
            assertThat(compare(resolved, propertyValue("postgresql.version")))
                    .as("the resolved driver matches the declared override")
                    .isZero();
        }

        @Test
        @DisplayName("keeps both JDBC drivers loadable from the test classpath")
        void keepsBothJdbcDriversLoadableFromTheTestClasspath() {
            assertThatCode(() -> Class.forName("org.postgresql.Driver"))
                    .doesNotThrowAnyException();
            assertThatCode(() -> Class.forName("com.mysql.cj.jdbc.Driver"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("version determinism")
    class VersionDeterminism {

        @Test
        @DisplayName("states a concrete version for every coordinate that carries one")
        void statesAConcreteVersionForEveryCoordinateThatCarriesOne() {
            declaredDependencies().forEach((coordinate, version) -> {
                if (version == null) {
                    return;
                }
                assertThat(version).as(coordinate)
                        .isNotBlank()
                        .doesNotContain("${")
                        .isNotEqualToIgnoringCase("LATEST")
                        .isNotEqualToIgnoringCase("RELEASE");
                assertThat(CONCRETE_VERSION.matcher(version).matches()).as(coordinate).isTrue();
            });
        }

        @Test
        @DisplayName("pins exactly the coordinates the module owns")
        void pinsExactlyTheCoordinatesTheModuleOwns() {
            Map<String, String> pinned = new LinkedHashMap<>();
            declaredDependencies().forEach((coordinate, version) -> {
                if (version != null) {
                    pinned.put(coordinate, version);
                }
            });

            assertThat(pinned).containsExactlyInAnyOrderEntriesOf(EXPLICIT_PINS);
        }

        @Test
        @DisplayName("declares no dependency twice")
        void declaresNoDependencyTwice() {
            List<String> coordinates = new ArrayList<>();
            for (Element dependency : dependencyElements()) {
                coordinates.add(text(childElement(dependency, "groupId")) + ':'
                        + text(childElement(dependency, "artifactId")));
            }

            assertThat(coordinates).doesNotHaveDuplicates();
        }
    }

    /**
     * Reads every declared dependency as a coordinate mapped to its stated version.
     *
     * @return one entry per {@code <dependency>}, whose value is {@code null} when the element
     *     states no version; never {@code null}
     */
    private static Map<String, String> declaredDependencies() {
        Map<String, String> declared = new LinkedHashMap<>();
        for (Element dependency : dependencyElements()) {
            String coordinate = text(childElement(dependency, "groupId")) + ':'
                    + text(childElement(dependency, "artifactId"));
            declared.put(coordinate, text(childElement(dependency, "version")));
        }
        return declared;
    }

    /**
     * Reads the {@code <dependency>} elements of the project's own {@code <dependencies>} block.
     *
     * @return the elements in declaration order; never {@code null}
     */
    private static List<Element> dependencyElements() {
        Element dependencies = childElement(PROJECT, "dependencies");
        assertThat(dependencies).isNotNull();

        List<Element> elements = new ArrayList<>();
        NodeList children = dependencies.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.ELEMENT_NODE && "dependency".equals(child.getNodeName())) {
                elements.add((Element) child);
            }
        }
        return elements;
    }

    /**
     * Reads one entry of the project's {@code <properties>} block.
     *
     * @param name the property name
     * @return the stated value, or {@code null} when the property is not declared
     */
    private static String propertyValue(String name) {
        Element properties = childElement(PROJECT, "properties");
        assertThat(properties).isNotNull();
        return text(childElement(properties, name));
    }

    /**
     * Reads the first direct child element of {@code parent} carrying {@code name}.
     *
     * @param parent the element to search
     * @param name   the child element name
     * @return the child, or {@code null} when {@code parent} has no such direct child
     */
    private static Element childElement(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        return null;
    }

    /**
     * Reads the stripped text of an element.
     *
     * @param element the element, possibly {@code null}
     * @return the stripped text, or {@code null} when {@code element} is {@code null}
     */
    private static String text(Element element) {
        return (element == null) ? null : element.getTextContent().strip();
    }

    /**
     * Compares two dotted version strings component by component.
     *
     * <p>A missing trailing component is read as zero, so {@code 10.1.57} and {@code 10.1.57.0}
     * compare equal. A component that is not a number compares as zero, which keeps a qualifier such
     * as {@code Final} from ordering ahead of a numbered release.
     *
     * @param left  the version on the left of the comparison
     * @param right the version on the right of the comparison
     * @return a negative number when {@code left} is lower, zero when the two are equal, and a
     *     positive number when {@code left} is higher
     */
    private static int compare(String left, String right) {
        String[] leftParts = left.split("[.\\-]");
        String[] rightParts = right.split("[.\\-]");
        int width = Math.max(leftParts.length, rightParts.length);

        for (int index = 0; index < width; index++) {
            int leftValue = numberAt(leftParts, index);
            int rightValue = numberAt(rightParts, index);
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    /**
     * Reads one version component as a number.
     *
     * @param parts the split version
     * @param index the component to read
     * @return the component as a number, or zero when it is absent or is not a number
     */
    private static int numberAt(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /**
     * Parses {@code backend/pom.xml} into its document element.
     *
     * @return the {@code <project>} element; never {@code null}
     */
    private static Element readProjectDescriptor() {
        Path descriptor = Path.of("pom.xml");
        if (!Files.isRegularFile(descriptor)) {
            descriptor = Path.of("backend", "pom.xml");
        }
        assertThat(Files.isRegularFile(descriptor)).as("%s is readable", descriptor).isTrue();

        try (InputStream source = Files.newInputStream(descriptor)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(source).getDocumentElement();
        } catch (Exception unreadable) {
            throw new IllegalStateException("pom.xml could not be parsed", unreadable);
        }
    }
}
