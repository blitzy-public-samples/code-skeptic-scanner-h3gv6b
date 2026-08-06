package com.codeskeptic.scanner.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.codeskeptic.scanner.config.ScannerProperties;
import com.codeskeptic.scanner.entity.Setting;
import com.codeskeptic.scanner.repository.SettingRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests for {@link BackgroundOwnership}, the time-limited lease that admits one process at a time
 * to the two background paths — DL-281.
 *
 * <p>Every collaborator is a double and the clock is driven by hand, so the claim, the renewal, the
 * takeover of a lapsed lease and the release are all exercised without waiting and without a database.
 * The transaction template is the real component over a mocked manager, so the callback runs exactly
 * as it does in production.
 */
// Net-new (no Python counterpart) — DL-281 — see docs/DECISION_LOG.md
@ExtendWith(MockitoExtension.class)
@DisplayName("BackgroundOwnership")
class BackgroundOwnershipTest {

    /** Instant every case starts at. */
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    /** Lease term every case is configured with, in seconds. */
    private static final long TTL_SECONDS = 120L;

    /** Renewal interval every case is configured with, in seconds. */
    private static final long RENEW_SECONDS = 30L;

    /** Key of the lease row. */
    private static final String KEY = BackgroundOwnership.OWNER_SETTING_KEY;

    @Mock
    private SettingRepository settingRepository;

    @Mock
    private TweetStreamClient streamClient;

    /** Clock the unit under test reads. */
    private MutableClock clock;

    /** Unit under test. */
    private BackgroundOwnership ownership;

    /** Records what the unit writes. */
    private ListAppender<ILoggingEvent> appender;

    /** Logger the appender is attached to. */
    private Logger ownershipLogger;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(START);
        ownership = ownershipWith(ScannerProperties.Background.of(true, true, true));
        appender = new ListAppender<>();
        appender.start();
        ownershipLogger = (Logger) LoggerFactory.getLogger(BackgroundOwnership.class);
        ownershipLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        ownershipLogger.detachAppender(appender);
        appender.stop();
    }

    @Nested
    @DisplayName("claiming the lease")
    class Claiming {

        @Test
        @DisplayName("creates the lease row when the table holds none")
        void createsTheLeaseRowWhenTheTableHoldsNone() {
            when(settingRepository.findById(KEY)).thenReturn(Optional.empty());

            ownership.start();

            ArgumentCaptor<Setting> written = ArgumentCaptor.captor();
            verify(settingRepository).saveAndFlush(written.capture());
            assertThat(written.getValue().getKey()).isEqualTo(KEY);
            assertThat(written.getValue().getValue()).contains(ownership.instanceId());
            assertThat(written.getValue().getDescription()).isNotBlank();
            assertThat(ownership.isOwner()).isTrue();
        }

        @Test
        @DisplayName("takes over a lapsed lease through a compare-and-set on the value it read")
        void takesOverALapsedLeaseThroughACompareAndSetOnTheValueItRead() {
            String lapsed = "another-instance@" + (START.toEpochMilli() - 1L);
            when(settingRepository.findById(KEY)).thenReturn(Optional.of(row(lapsed)));
            when(settingRepository.replaceValueIfUnchanged(eq(KEY), eq(lapsed), anyString()))
                    .thenReturn(1);

            ownership.start();

            ArgumentCaptor<String> replacement = ArgumentCaptor.captor();
            verify(settingRepository)
                    .replaceValueIfUnchanged(eq(KEY), eq(lapsed), replacement.capture());
            assertThat(replacement.getValue()).contains(ownership.instanceId());
            assertThat(ownership.isOwner()).isTrue();
        }

        @Test
        @DisplayName("leaves a live lease held by another process untouched")
        void leavesALiveLeaseHeldByAnotherProcessUntouched() {
            String live = "another-instance@" + (START.toEpochMilli() + 60_000L);
            when(settingRepository.findById(KEY)).thenReturn(Optional.of(row(live)));

            ownership.start();

            verify(settingRepository, never()).replaceValueIfUnchanged(anyString(), anyString(),
                    anyString());
            verify(settingRepository, never()).saveAndFlush(any(Setting.class));
            assertThat(ownership.isOwner()).isFalse();
        }

        @Test
        @DisplayName("renews a live lease it already holds")
        void renewsALiveLeaseItAlreadyHolds() {
            when(settingRepository.findById(KEY)).thenReturn(Optional.empty());
            ownership.start();
            String held = ownership.instanceId() + "@" + (START.toEpochMilli() + TTL_SECONDS * 1000L);
            when(settingRepository.findById(KEY)).thenReturn(Optional.of(row(held)));
            when(settingRepository.replaceValueIfUnchanged(eq(KEY), eq(held), anyString()))
                    .thenReturn(1);

            clock.advance(Duration.ofSeconds(RENEW_SECONDS));

            assertThat(ownership.renewOwnership()).isTrue();
            verify(settingRepository).replaceValueIfUnchanged(eq(KEY), eq(held), anyString());
        }

        @Test
        @DisplayName("uses the null-valued statement when the row holds no value")
        void usesTheNullValuedStatementWhenTheRowHoldsNoValue() {
            when(settingRepository.findById(KEY)).thenReturn(Optional.of(row(null)));
            when(settingRepository.replaceAbsentValue(eq(KEY), anyString())).thenReturn(1);

            ownership.start();

            verify(settingRepository).replaceAbsentValue(eq(KEY), anyString());
            assertThat(ownership.isOwner()).isTrue();
        }

        @Test
        @DisplayName("holds no lease when the compare-and-set matches no row")
        void holdsNoLeaseWhenTheCompareAndSetMatchesNoRow() {
            String lapsed = "another-instance@" + (START.toEpochMilli() - 1L);
            when(settingRepository.findById(KEY)).thenReturn(Optional.of(row(lapsed)));
            when(settingRepository.replaceValueIfUnchanged(eq(KEY), eq(lapsed), anyString()))
                    .thenReturn(0);

            ownership.start();

            assertThat(ownership.isOwner()).isFalse();
        }

        @Test
        @DisplayName("holds no lease when another process created the row first")
        void holdsNoLeaseWhenAnotherProcessCreatedTheRowFirst() {
            when(settingRepository.findById(KEY)).thenReturn(Optional.empty());
            when(settingRepository.saveAndFlush(any(Setting.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate key"));

            assertThatCode(() -> ownership.start()).doesNotThrowAnyException();

            assertThat(ownership.isOwner()).isFalse();
        }

        @Test
        @DisplayName("fails closed when the store cannot be reached")
        void failsClosedWhenTheStoreCannotBeReached() {
            when(settingRepository.findById(KEY))
                    .thenThrow(new QueryTimeoutException("the store is unreachable"));

            assertThatCode(() -> ownership.start()).doesNotThrowAnyException();

            assertThat(ownership.isOwner()).isFalse();
            assertThat(rendered(Level.WARN))
                    .anySatisfy(message -> assertThat(message)
                            .contains("could not be claimed")
                            .contains("QueryTimeoutException"));
        }

        @ParameterizedTest(name = "stored value [{0}] is vacant")
        @ValueSource(strings = {"no-separator", "instance@", "instance@not-a-number",
            "instance@ ", "@0", "released@0"})
        @DisplayName("treats a value carrying no readable lapse instant as vacant")
        void treatsAValueCarryingNoReadableLapseInstantAsVacant(String stored) {
            when(settingRepository.findById(KEY)).thenReturn(Optional.of(row(stored)));
            when(settingRepository.replaceValueIfUnchanged(eq(KEY), eq(stored), anyString()))
                    .thenReturn(1);

            ownership.start();

            assertThat(ownership.isOwner()).isTrue();
        }

        @Test
        @DisplayName("claims once for repeated start calls")
        void claimsOnceForRepeatedStartCalls() {
            when(settingRepository.findById(KEY)).thenReturn(Optional.empty());

            ownership.start();
            ownership.start();

            verify(settingRepository).saveAndFlush(any(Setting.class));
        }
    }

    @Nested
    @DisplayName("holding and losing the lease")
    class HoldingAndLosing {

        @Test
        @DisplayName("reports no ownership once its own term has lapsed")
        void reportsNoOwnershipOnceItsOwnTermHasLapsed() {
            when(settingRepository.findById(KEY)).thenReturn(Optional.empty());
            ownership.start();
            assertThat(ownership.isOwner()).isTrue();

            clock.advance(Duration.ofSeconds(TTL_SECONDS));

            assertThat(ownership.isOwner()).isFalse();
        }

        @Test
        @DisplayName("reports no ownership before the first claim")
        void reportsNoOwnershipBeforeTheFirstClaim() {
            assertThat(ownership.isOwner()).isFalse();
            verifyNoInteractions(settingRepository);
        }

        @Test
        @DisplayName("issues no statement to answer whether it holds the lease")
        void issuesNoStatementToAnswerWhetherItHoldsTheLease() {
            when(settingRepository.findById(KEY)).thenReturn(Optional.empty());
            ownership.start();

            assertThat(ownership.isOwner()).isTrue();
            assertThat(ownership.isOwner()).isTrue();

            verify(settingRepository).findById(KEY);
        }

        @Test
        @DisplayName("renews nothing before it has started")
        void renewsNothingBeforeItHasStarted() {
            assertThat(ownership.renewOwnership()).isFalse();
            verifyNoInteractions(settingRepository, streamClient);
        }

        @Test
        @DisplayName("records the loss once and stops a running stream")
        void recordsTheLossOnceAndStopsARunningStream() {
            when(settingRepository.findById(KEY)).thenReturn(Optional.empty());
            ownership.start();
            String taken = "another-instance@" + (START.toEpochMilli() + 600_000L);
            when(settingRepository.findById(KEY)).thenReturn(Optional.of(row(taken)));
            when(streamClient.isRunning()).thenReturn(true);

            assertThat(ownership.renewOwnership()).isFalse();
            assertThat(ownership.renewOwnership()).isFalse();

            verify(streamClient, times(1)).stop();
            assertThat(rendered(Level.WARN))
                    .filteredOn(message -> message.contains("lost by instance"))
                    .hasSize(1);
        }

        @Test
        @DisplayName("stops no stream that is not running")
        void stopsNoStreamThatIsNotRunning() {
            when(settingRepository.findById(KEY)).thenReturn(Optional.empty());
            ownership.start();
            String taken = "another-instance@" + (START.toEpochMilli() + 600_000L);
            when(settingRepository.findById(KEY)).thenReturn(Optional.of(row(taken)));
            when(streamClient.isRunning()).thenReturn(false);

            ownership.renewOwnership();

            verify(streamClient, never()).stop();
        }

        @Test
        @DisplayName("starts the stream when a renewal takes over a lapsed lease")
        void startsTheStreamWhenARenewalTakesOverALapsedLease() {
            String live = "another-instance@" + (START.toEpochMilli() + 60_000L);
            when(settingRepository.findById(KEY)).thenReturn(Optional.of(row(live)));
            ownership.start();
            assertThat(ownership.isOwner()).isFalse();

            clock.advance(Duration.ofSeconds(120L));
            when(settingRepository.replaceValueIfUnchanged(eq(KEY), eq(live), anyString()))
                    .thenReturn(1);
            when(streamClient.isRunning()).thenReturn(false);

            assertThat(ownership.renewOwnership()).isTrue();

            verify(streamClient).start();
            assertThat(rendered(Level.INFO))
                    .anySatisfy(message -> assertThat(message).contains("claimed by instance"));
        }

        @Test
        @DisplayName("starts no stream in a process whose stream switch is off")
        void startsNoStreamInAProcessWhoseStreamSwitchIsOff() {
            ownership = ownershipWith(ScannerProperties.Background.of(true, false, true));
            when(settingRepository.findById(KEY)).thenReturn(Optional.empty());

            ownership.start();
            ownership.renewOwnership();

            verify(streamClient, never()).start();
        }

        @Test
        @DisplayName("starts no stream that is already running")
        void startsNoStreamThatIsAlreadyRunning() {
            when(settingRepository.findById(KEY)).thenReturn(Optional.empty());
            ownership.start();
            when(streamClient.isRunning()).thenReturn(true);
            String held = ownership.instanceId() + "@" + (START.toEpochMilli() + 120_000L);
            when(settingRepository.findById(KEY)).thenReturn(Optional.of(row(held)));
            when(settingRepository.replaceValueIfUnchanged(eq(KEY), eq(held), anyString()))
                    .thenReturn(1);

            ownership.renewOwnership();

            verify(streamClient, never()).start();
        }
    }

    @Nested
    @DisplayName("releasing the lease")
    class Releasing {

        @Test
        @DisplayName("releases through a compare-and-set on the value it wrote")
        void releasesThroughACompareAndSetOnTheValueItWrote() {
            when(settingRepository.findById(KEY)).thenReturn(Optional.empty());
            ownership.start();
            when(settingRepository.replaceValueIfUnchanged(eq(KEY), anyString(), anyString()))
                    .thenReturn(1);

            ownership.stop();

            ArgumentCaptor<String> expected = ArgumentCaptor.captor();
            ArgumentCaptor<String> replacement = ArgumentCaptor.captor();
            verify(settingRepository)
                    .replaceValueIfUnchanged(eq(KEY), expected.capture(), replacement.capture());
            assertThat(expected.getValue()).contains(ownership.instanceId());
            assertThat(replacement.getValue()).doesNotContain(ownership.instanceId());
            assertThat(ownership.isOwner()).isFalse();
            assertThat(ownership.isRunning()).isFalse();
        }

        @Test
        @DisplayName("writes nothing when it held no lease")
        void writesNothingWhenItHeldNoLease() {
            String live = "another-instance@" + (START.toEpochMilli() + 60_000L);
            when(settingRepository.findById(KEY)).thenReturn(Optional.of(row(live)));
            ownership.start();

            ownership.stop();

            verify(settingRepository, never()).replaceValueIfUnchanged(anyString(), anyString(),
                    anyString());
        }

        @Test
        @DisplayName("raises nothing when the release cannot be written")
        void raisesNothingWhenTheReleaseCannotBeWritten() {
            when(settingRepository.findById(KEY)).thenReturn(Optional.empty());
            ownership.start();
            when(settingRepository.replaceValueIfUnchanged(eq(KEY), anyString(), anyString()))
                    .thenThrow(new QueryTimeoutException("the store is unreachable"));

            assertThatCode(() -> ownership.stop()).doesNotThrowAnyException();

            assertThat(rendered(Level.WARN))
                    .anySatisfy(message -> assertThat(message).contains("could not be released"));
        }

        @Test
        @DisplayName("releases once for repeated stop calls")
        void releasesOnceForRepeatedStopCalls() {
            when(settingRepository.findById(KEY)).thenReturn(Optional.empty());
            ownership.start();
            when(settingRepository.replaceValueIfUnchanged(eq(KEY), anyString(), anyString()))
                    .thenReturn(1);

            ownership.stop();
            ownership.stop();

            verify(settingRepository).replaceValueIfUnchanged(eq(KEY), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("the declared shape")
    class DeclaredShape {

        @ParameterizedTest(name = "enabled={0}, stream={1}, generation={2} starts automatically: {3}")
        @CsvSource({"true,true,true,true", "true,true,false,true", "true,false,true,true",
            "true,false,false,false", "false,true,true,false", "false,false,false,false"})
        @DisplayName("starts automatically only where a background path is enabled")
        void startsAutomaticallyOnlyWhereABackgroundPathIsEnabled(boolean enabled,
                boolean streamEnabled,
                boolean generationEnabled,
                boolean expected) {
            BackgroundOwnership configured = ownershipWith(
                    ScannerProperties.Background.of(enabled, streamEnabled, generationEnabled));

            assertThat(configured.isAutoStartup()).isEqualTo(expected);
        }

        @Test
        @DisplayName("starts automatically when the group is unbound")
        void startsAutomaticallyWhenTheGroupIsUnbound() {
            assertThat(ownershipWith(null).isAutoStartup()).isTrue();
        }

        @Test
        @DisplayName("stops after the stream client, which the default phase orders last")
        void stopsAfterTheStreamClientWhichTheDefaultPhaseOrdersLast() {
            assertThat(SmartLifecycle.class).isAssignableFrom(BackgroundOwnership.class);
            assertThat(ownership.getPhase()).isLessThan(SmartLifecycle.DEFAULT_PHASE);
        }

        @Test
        @DisplayName("mints one identity per process")
        void mintsOneIdentityPerProcess() {
            assertThat(ownership.instanceId())
                    .isNotBlank()
                    .isEqualTo(ownership.instanceId())
                    .isNotEqualTo(ownershipWith(ScannerProperties.Background.of(true, true, true))
                            .instanceId());
        }

        @Test
        @DisplayName("names its identity in no record, only a correlation token")
        void namesItsIdentityInNoRecordOnlyACorrelationToken() {
            when(settingRepository.findById(KEY)).thenReturn(Optional.empty());

            ownership.start();

            assertThat(appender.list)
                    .isNotEmpty()
                    .allSatisfy(record -> assertThat(record.getFormattedMessage())
                            .doesNotContain(ownership.instanceId())
                            .contains("hmac256:"));
        }

        @Test
        @DisplayName("rejects an absent collaborator, naming the one that is absent")
        void rejectsAnAbsentCollaboratorNamingTheOneThatIsAbsent() {
            TransactionTemplate template = inlineTransactions();
            ScannerProperties properties = properties(
                    ScannerProperties.Background.of(true, true, true));

            assertThatNullPointerException()
                    .isThrownBy(() -> new BackgroundOwnership(null, properties, clock, template,
                            provider()))
                    .withMessageContaining("settingRepository");
            assertThatNullPointerException()
                    .isThrownBy(() -> new BackgroundOwnership(settingRepository, null, clock,
                            template, provider()))
                    .withMessageContaining("properties");
            assertThatNullPointerException()
                    .isThrownBy(() -> new BackgroundOwnership(settingRepository, properties, null,
                            template, provider()))
                    .withMessageContaining("clock");
            assertThatNullPointerException()
                    .isThrownBy(() -> new BackgroundOwnership(settingRepository, properties, clock,
                            null, provider()))
                    .withMessageContaining("transactionTemplate");
            assertThatNullPointerException()
                    .isThrownBy(() -> new BackgroundOwnership(settingRepository, properties, clock,
                            template, null))
                    .withMessageContaining("streamClient");
        }

        @Test
        @DisplayName("reaches no HTTP client and no publish path")
        void reachesNoHttpClientAndNoPublishPath() {
            assertThat(BackgroundOwnership.class.getDeclaredFields())
                    .extracting(field -> field.getType().getSimpleName())
                    .doesNotContain("WebClient", "RestClient", "TwitterService");
        }
    }

    /**
     * Builds the unit under test over the supplied background group.
     *
     * @param background the {@code scanner.background} group to bind, or {@code null} for none
     * @return the unit under test
     */
    private BackgroundOwnership ownershipWith(ScannerProperties.Background background) {
        return new BackgroundOwnership(settingRepository, properties(background), clock,
                inlineTransactions(), provider());
    }

    /**
     * Builds a transaction template that runs its callback inline.
     *
     * @return the template handed to the unit under test
     */
    private static TransactionTemplate inlineTransactions() {
        return new TransactionTemplate(new PlatformTransactionManager() {

            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
                // The callback needs no commit to run inline.
            }

            @Override
            public void rollback(TransactionStatus status) {
                // The callback needs no rollback to run inline.
            }
        });
    }

    /**
     * Builds a bound configuration carrying the supplied background group.
     *
     * @param background the group to bind, or {@code null} for none
     * @return the bound configuration
     */
    private static ScannerProperties properties(ScannerProperties.Background background) {
        return new ScannerProperties(null, 100, 60L, null, null, null, null, null, null, null,
                background);
    }

    /**
     * Wraps the stream-client double in a provider that resolves it on demand.
     *
     * @return the provider handed to the unit under test
     */
    private ObjectProvider<TweetStreamClient> provider() {
        return new ObjectProvider<>() {

            @Override
            public TweetStreamClient getObject() {
                return streamClient;
            }

            @Override
            public TweetStreamClient getObject(Object... args) {
                return streamClient;
            }

            @Override
            public TweetStreamClient getIfAvailable() {
                return streamClient;
            }

            @Override
            public TweetStreamClient getIfUnique() {
                return streamClient;
            }
        };
    }

    /**
     * Builds a lease row carrying one value.
     *
     * @param value the stored value, which may be {@code null}
     * @return the row
     */
    private static Setting row(String value) {
        Setting setting = new Setting();
        setting.setKey(KEY);
        setting.setValue(value);
        setting.setDescription("lease");
        return setting;
    }

    /**
     * Renders every record written at one level.
     *
     * @param level the level to select
     * @return the formatted messages, in the order they were written
     */
    private List<String> rendered(Level level) {
        return appender.list.stream()
                .filter(record -> record.getLevel() == level)
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.toList());
    }

    /** Clock a case advances by hand. */
    private static final class MutableClock extends Clock {

        /** Instant this clock reports. */
        private Instant now;

        /**
         * Starts the clock at one instant.
         *
         * @param start the instant to report until the clock is advanced
         */
        private MutableClock(Instant start) {
            this.now = start;
        }

        /**
         * Moves the reported instant forward.
         *
         * @param amount the span to move forward by
         */
        void advance(Duration amount) {
            this.now = this.now.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
