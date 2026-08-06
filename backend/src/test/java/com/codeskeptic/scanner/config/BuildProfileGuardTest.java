package com.codeskeptic.scanner.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.mock.env.MockEnvironment;

// Net-new (the retired backend/ tree declared no profiles) — DL-279 — see docs/DECISION_LOG.md
/**
 * Exercises {@link BuildProfileGuard}, the startup check that keeps the build-scoped {@code test}
 * profile out of a deployed revision.
 *
 * <p>Assertions cover the three states the guard distinguishes: the build profile is not active, the
 * build profile is active with the test framework present, and the build profile is active with the
 * test framework absent. The last is driven by handing the guard a bean class loader that reports the
 * framework class as absent, which is the classpath of a packaged Boot jar; nothing is packaged.
 *
 * <p>The guard is also asserted to read its two values through the container callbacks and to declare
 * a no-argument constructor — DL-279.
 *
 * <p>No Spring context is started, no network call is made and no database is reached.
 */
@DisplayName("BuildProfileGuard")
class BuildProfileGuardTest {

    /** Profile the guard reads. */
    private static final String BUILD_PROFILE = "test";

    /** Class whose absence the guard reads as a deployed revision. */
    private static final String TEST_FRAMEWORK_CLASS = "org.junit.jupiter.api.Test";

    @Test
    @DisplayName("runs its check before any singleton is built and takes its two values from the "
            + "container")
    void runsItsCheckBeforeAnySingletonIsBuilt() {
        assertThat(BeanFactoryPostProcessor.class).isAssignableFrom(BuildProfileGuard.class);
        assertThat(EnvironmentAware.class).isAssignableFrom(BuildProfileGuard.class);
        assertThat(BeanClassLoaderAware.class).isAssignableFrom(BuildProfileGuard.class);
    }

    @Test
    @DisplayName("declares a no-argument constructor, which a bean factory post-processor requires")
    void declaresANoArgumentConstructor() {
        assertThatCode(BuildProfileGuard::new).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("starts when the build profile is active and the test framework is present")
    void startsWhenTheBuildProfileIsActiveAndTheTestFrameworkIsPresent() {
        BuildProfileGuard guard = guardFor(BUILD_PROFILE);

        assertThatCode(() -> check(guard)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "the {0} profile alone starts the application")
    @ValueSource(strings = {"prod", "staging", "local", "testing", "integration-test"})
    @DisplayName("starts for any profile whose name is not exactly the build profile")
    void startsForAnyProfileThatIsNotTheBuildProfile(String profile) {
        BuildProfileGuard guard = guardFor(profile);
        guard.setBeanClassLoader(hidingClassLoader());

        assertThatCode(() -> check(guard)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("starts when no profile is active at all")
    void startsWhenNoProfileIsActiveAtAll() {
        BuildProfileGuard guard = guardFor();
        guard.setBeanClassLoader(hidingClassLoader());

        assertThatCode(() -> check(guard)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("starts when the container supplied no environment")
    void startsWhenTheContainerSuppliedNoEnvironment() {
        BuildProfileGuard guard = new BuildProfileGuard();
        guard.setBeanClassLoader(hidingClassLoader());

        assertThatCode(() -> check(guard)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("starts when the build profile is active beside another profile in a build context")
    void startsWhenTheBuildProfileIsActiveBesideAnotherProfile() {
        BuildProfileGuard guard = guardFor("local", BUILD_PROFILE);

        assertThatCode(() -> check(guard)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses to start when the build profile is active and the test framework is absent")
    void refusesToStartWhenTheTestFrameworkIsAbsent() {
        BuildProfileGuard guard = guardFor(BUILD_PROFILE);
        guard.setBeanClassLoader(hidingClassLoader());

        assertThatIllegalStateException()
                .isThrownBy(() -> check(guard))
                .withMessageContaining("'" + BUILD_PROFILE + "' profile is build-scoped")
                .withMessageContaining("spring.profiles.active");
    }

    @Test
    @DisplayName("refuses to start when the build profile is active beside another one without the "
            + "test framework")
    void refusesToStartWhenTheBuildProfileIsActiveBesideAnotherWithoutTheTestFramework() {
        BuildProfileGuard guard = guardFor("prod", BUILD_PROFILE);
        guard.setBeanClassLoader(hidingClassLoader());

        assertThatIllegalStateException().isThrownBy(() -> check(guard));
    }

    @Test
    @DisplayName("keeps its own loader when the container supplies none")
    void keepsItsOwnLoaderWhenTheContainerSuppliesNone() {
        BuildProfileGuard guard = guardFor(BUILD_PROFILE);
        guard.setBeanClassLoader(null);

        assertThatCode(() -> check(guard)).doesNotThrowAnyException();
    }

    /**
     * Runs the guard's check against a bare bean factory, which the guard never reads.
     *
     * @param guard the guard to check
     */
    private static void check(BuildProfileGuard guard) {
        guard.postProcessBeanFactory(new DefaultListableBeanFactory());
    }

    /**
     * Builds a guard whose environment reports the supplied profiles as active.
     *
     * @param profiles the active profiles
     * @return the guard, holding the default bean class loader
     */
    private static BuildProfileGuard guardFor(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        BuildProfileGuard guard = new BuildProfileGuard();
        guard.setEnvironment(environment);
        return guard;
    }

    /**
     * Builds a loader that reports {@value #TEST_FRAMEWORK_CLASS} as absent and delegates every other
     * request, reproducing the classpath of an artifact carrying no test-scoped dependency.
     *
     * @return the loader
     */
    private static ClassLoader hidingClassLoader() {
        return new ClassLoader(BuildProfileGuardTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {

                if (TEST_FRAMEWORK_CLASS.equals(name)) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
    }
}
