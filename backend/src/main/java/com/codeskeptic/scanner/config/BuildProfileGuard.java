package com.codeskeptic.scanner.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

// Net-new (no Python counterpart; the retired tree declared no profiles at all) — DL-244 — see
// docs/DECISION_LOG.md
/**
 * Refuses to start the application when the build-scoped {@code test} profile is active outside the
 * build.
 *
 * <p>{@code src/test/resources/application-test.yml} is build-scoped: it pins an in-memory H2
 * database with {@code create-drop}, supplies empty X credentials so no request reaches the provider,
 * and carries a signing key and a bcrypt hash that are held in version control — see
 * docs/DECISION_LOG.md DL-205. A deployed revision started with {@code SPRING_PROFILES_ACTIVE=test}
 * would therefore mint tokens under a published key and serve against a throwaway schema that is
 * dropped at shutdown.
 *
 * <p>The signal this guard reads is the presence of {@value #TEST_FRAMEWORK_CLASS} on the bean class
 * loader. A Spring Boot executable jar carries no test-scoped dependency, so that class is absent
 * from a packaged artifact and present in every Surefire-launched context; {@code mvn clean verify} is
 * therefore unaffected — see docs/DECISION_LOG.md DL-244.
 *
 * <p>The check is a {@link BeanFactoryPostProcessor}, so it runs before the container instantiates any
 * singleton. A deployment that activates the profile therefore fails on the profile itself rather than
 * on whichever bean happens to be built first, which is what makes the failure message actionable. A
 * bean factory post-processor is instantiated before constructor autowiring is available, so the two
 * values this class reads arrive through {@link EnvironmentAware} and {@link BeanClassLoaderAware}
 * rather than through a constructor — DL-244.
 *
 * <p>Every other profile, and the default profile, are left untouched: this class matches the profile
 * name exactly, reads and alters no bean definition, and publishes no bean.
 */
@Component
public class BuildProfileGuard
        implements BeanFactoryPostProcessor, EnvironmentAware, BeanClassLoaderAware {

    // Logging baseline — DL-052 — see docs/DECISION_LOG.md
    private static final Logger log = LoggerFactory.getLogger(BuildProfileGuard.class);

    /** Profile {@code src/test/resources/application-test.yml} binds to — DL-205. */
    private static final String BUILD_PROFILE = "test";

    /** Property that activates a profile, named by the failure this guard raises. */
    private static final String PROFILES_PROPERTY = "spring.profiles.active";

    /**
     * Test-framework class present in a Surefire-launched context and absent from a packaged
     * artifact, because a Spring Boot executable jar carries no test-scoped dependency — DL-244.
     */
    private static final String TEST_FRAMEWORK_CLASS = "org.junit.jupiter.api.Test";

    /**
     * Supplies the active profiles. The container sets it before
     * {@link #postProcessBeanFactory(ConfigurableListableBeanFactory)} runs; while it is
     * {@code null} the guard has nothing to inspect and passes.
     */
    private Environment environment;

    /**
     * Loader the presence of {@value #TEST_FRAMEWORK_CLASS} is tested against. The container supplies
     * it before {@link #postProcessBeanFactory(ConfigurableListableBeanFactory)} runs; this class's
     * own loader applies until then.
     */
    private ClassLoader beanClassLoader = BuildProfileGuard.class.getClassLoader();

    /**
     * Accepts the environment whose active profiles this guard reads.
     *
     * @param environment the context's environment
     */
    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /**
     * Accepts the loader the container resolves bean classes with.
     *
     * @param classLoader the bean class loader; a {@code null} value leaves this class's own loader in
     *     place
     */
    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        if (classLoader != null) {
            this.beanClassLoader = classLoader;
        }
    }

    /**
     * Fails context refresh when the build-scoped profile is active without the build's own
     * classpath, before any singleton is instantiated.
     *
     * <p>Nothing is done when {@value #BUILD_PROFILE} is not among the active profiles. When it is,
     * the guard records the fact and returns if {@value #TEST_FRAMEWORK_CLASS} is present, and
     * otherwise raises an {@link IllegalStateException} naming both the profile and
     * {@value #PROFILES_PROPERTY}.
     *
     * @param beanFactory the factory being post-processed; no bean definition is read or altered
     * @throws IllegalStateException when the {@value #BUILD_PROFILE} profile is active in a context
     *     that carries no test framework
     */
    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (!buildProfileActive()) {
            return;
        }

        if (ClassUtils.isPresent(TEST_FRAMEWORK_CLASS, beanClassLoader)) {
            log.info("The '{}' profile is active in a build context", BUILD_PROFILE);
            return;
        }

        throw new IllegalStateException("The '" + BUILD_PROFILE + "' profile is build-scoped and "
                + "must not be activated in a deployed revision: it pins an in-memory database and "
                + "carries a signing key held in version control. Remove '" + BUILD_PROFILE
                + "' from " + PROFILES_PROPERTY + ".");
    }

    /**
     * Reports whether the build-scoped profile is among the active profiles.
     *
     * @return {@code true} when {@value #BUILD_PROFILE} is active
     */
    private boolean buildProfileActive() {
        if (environment == null) {
            return false;
        }
        for (String active : environment.getActiveProfiles()) {
            if (BUILD_PROFILE.equals(active)) {
                return true;
            }
        }
        return false;
    }
}
