# Traceability Matrix

This matrix maps the retired Python/Flask backend onto the delivered Java/Spring Boot backend in
both directions. Section 1 reads source → target: every construct that existed in
`backend/app/**` and `backend/tests/**` is listed with the Java construct that carries it forward.
Section 2 reads target → source: every delivered file under `backend/` is listed with the source
construct it derives from, or is marked as net-new with the decision that authorises it.

**Relationship to the decision log.** This file records *what maps to what*. `docs/DECISION_LOG.md`
records *why*, and it is the only place reasoning lives. Where a row names a `DL-` identifier, that
identifier has a complete row in the log — `DL-001` … `DL-305`, one unbroken sequence of 305 rows — and no
reasoning is duplicated here. This file states mappings and measured facts only; a contract contrast is
stated as two claims and never as a preference between two options (DL-058).

**Delivery state.** This matrix describes the tree as delivered. Every count below was obtained by
enumerating the working tree, and every source line number was read back from the retired files at
commit `80f1d53d^`, the last commit at which they existed. The delivered tree holds **58** classes under
`backend/src/main/java` and **19** under `backend/src/test/java` — **77** Java classes — together with
`backend/pom.xml`, `backend/.gitignore`, `backend/.dockerignore`,
`backend/src/main/resources/application.yml`,
`backend/src/main/resources/META-INF/spring.factories`,
`backend/src/test/resources/application-test.yml` and the
two files in `backend/docs`, for **85** delivered artifacts. Section 2 carries one row for each of them,
and §4 restates the counts as an auditable table. The per-class case counts live in §2.7 and nowhere
else, so each is stated once and measured once.

Every module holds exactly the classes the frozen inventory names: `config` 7, `security` 3, `api` 6,
`dto` 16, `entity` 4, `repository` 4, `service` 7, `service/mapper` 3, `task` 3, `exception` 3, `util` 1
and the root package 1, summing to 58. No module holds a class the inventory does not name.

**Net-new targets.** Twenty-two delivered targets carry the marker *No source construct — net-new*.
Fifteen of them are the set AAP §0.7.3 enumerates:
`api/AuthController`, `dto/LoginRequest` and `dto/TokenResponse` (DL-019); `service/ResponseService`
(DL-076), `service/SettingsService` (DL-043) and `service/AnalyticsService` (DL-041, DL-042);
`service/mapper/TweetMapper`, `service/mapper/ResponseMapper` and `service/mapper/SettingMapper`
(DL-295); `config/DatabaseUrlTranslator` (DL-027); `util/DelimitedStringListConverter` (DL-024);
`backend/pom.xml` (DL-002/DL-003/DL-004); `backend/src/test/resources/application-test.yml` (DL-009);
and the two files in `backend/docs` (DL-058). Where the retired tree contains a call site, an import or a
configuration value that fixes such a target's signature, the row names that site in an
**Occasioned by** clause. The clause records provenance and the marker still reads
*No source construct — net-new* (DL-296). Two further targets carry the same marker and no occasioning
site: `backend/.gitignore` and `backend/.dockerignore` (DL-055). The remaining five are the net-new test
classes §1.10 enumerates: `api/AuthControllerTest`, `service/ResponseServiceTest`, `service/SettingsServiceTest`,
`service/AnalyticsServiceTest` and `config/DatabaseUrlTranslatorTest`. Fifteen plus two plus five is
twenty-two.

Every status value a row carries is one of the three above, and no row names a file that does not exist
on disk. Section 3 records that the pending-target list is empty.

**Status values.**

| Value | Meaning |
|-------|---------|
| `Delivered` | The named target exists on disk and carries the source construct. |
| `Retired` | The source construct is not carried forward; the row names the decision that records the disposition. |
| `Retained by decision` | The defect lies wholly in `frontend/**`, which this migration does not edit; the row names the entry that authorises leaving it. |

---

## 1. Source → target

### 1.1 Retired Python files

All twenty files under `backend/app/**` and `backend/tests/**` are deleted (DL-001, DL-060). The line
count of each is the count at `80f1d53d^`.

| # | Retired file | Lines | Java target |
|---|--------------|-------|-------------|
| 1 | `app/main.py` | 52 | `ScannerApplication`, `config/CorsConfig`, `security/SecurityConfig`, `api/GlobalExceptionHandler`, `config/AsyncSchedulingConfig` |
| 2 | `app/core/config.py` | 17 | `config/ScannerProperties` and `src/main/resources/application.yml` |
| 3 | `app/core/security.py` | 17 | `security/JwtService` and the `BCryptPasswordEncoder` bean of `security/SecurityConfig` |
| 4 | `app/db/database.py` | 12 | `config/DataSourceConfig` and the four repository interfaces |
| 5 | `app/db/models.py` | 43 | `entity/Tweet`, `entity/Response`, `entity/AiTool`, `entity/Setting`, `util/DelimitedStringListConverter` |
| 6 | `app/schema/tweet.py` | 13 | `dto/TweetDto` |
| 7 | `app/schema/response.py` | 8 | `dto/ResponseDto` |
| 8 | `app/api/tweets.py` | 54 | `api/TweetController`, `dto/PaginatedTweetsDto`, `dto/PaginationDto`, `dto/AnalysisResultDto` |
| 9 | `app/api/responses.py` | 64 | `api/ResponseController`, `dto/PaginatedResponsesDto`, `dto/CreateResponseRequest`, `dto/UpdateResponseRequest` |
| 10 | `app/api/settings.py` | 23 | `api/SettingController`, `dto/SettingDto`, `dto/UpdateSettingRequest` |
| 11 | `app/api/analytics.py` | 24 | `api/AnalyticsController`, `dto/TrendsDto`, `dto/SummaryDto` |
| 12 | `app/services/twitter_service.py` | 49 | `service/TwitterService` and `task/TweetStreamClient` |
| 13 | `app/services/sentiment_analysis.py` | 34 | `service/SentimentAnalysisService` |
| 14 | `app/services/notion_service.py` | 52 | `service/NotionService` and `config/RestClientConfig` |
| 15 | `app/services/llm_service.py` | 36 | `service/LlmService` |
| 16 | `app/tasks/tweet_monitoring.py` | 54 | `task/TweetStreamClient` and `task/TweetStreamListener` |
| 17 | `app/tasks/response_generation.py` | 49 | `task/ResponseGenerationScheduler` |
| 18 | `tests/test_api.py` | 58 | the six `@WebMvcTest` classes and `ScannerApplicationTests` |
| 19 | `tests/test_services.py` | 67 | the seven service test classes |
| 20 | `tests/test_tasks.py` | 58 | `task/ResponseGenerationSchedulerTest` and `task/TweetStreamListenerTest` |

### 1.2 HTTP routes

Eleven routes are carried forward with the same method, the same literal path, the same path-variable
name, the same query-parameter names and defaults and the same status codes. Routes are served
unprefixed: no `/api`, no `/v1` (DL-217). One route is net-new and marked as such.

| # | Source route | Source location | Delivered route | Controller method |
|---|--------------|-----------------|-----------------|-------------------|
| 1 | `GET /tweets` — `page` default 1, `per_page` default 10 | `api/tweets.py:L9-21` | `GET /tweets` | `api/TweetController.listTweets` |
| 2 | `GET /tweets/<tweet_id>` — 404 `{"error":"Tweet not found"}` | `api/tweets.py:L23-32` | `GET /tweets/{tweetId}` | `api/TweetController.getTweet` |
| 3 | `POST /tweets/<tweet_id>/analyze` | `api/tweets.py:L36-55` | `POST /tweets/{tweetId}/analyze` | `api/TweetController.analyzeTweet` |
| 4 | `GET /responses` | `api/responses.py:L8-20` | `GET /responses` | `api/ResponseController.listResponses` |
| 5 | `GET /responses/<response_id>` — 404 `{"error":"Response not found"}` | `api/responses.py:L22-31` | `GET /responses/{responseId}` | `api/ResponseController.getResponse` |
| 6 | `POST /responses` — 400, 201, 500 | `api/responses.py:L33-49` | `POST /responses` | `api/ResponseController.createResponse` |
| 7 | `PUT /responses/<response_id>` — 400, 200, 404 | `api/responses.py:L51-65` | `PUT /responses/{responseId}` | `api/ResponseController.updateResponse` |
| 8 | `GET /settings` | `api/settings.py:L7-11` | `GET /settings` | `api/SettingController.listSettings` |
| 9 | `PUT /settings/<key>` — 400, 404, 200 | `api/settings.py:L13-24` | `PUT /settings/{key}` | `api/SettingController.updateSetting` |
| 10 | `GET /analytics/trends` | `api/analytics.py:L7-15` | `GET /analytics/trends` | `api/AnalyticsController.getTrends` |
| 11 | `GET /analytics/summary` | `api/analytics.py:L17-25` | `GET /analytics/summary` | `api/AnalyticsController.getSummary` |
| 12 | *No source construct — net-new* — DL-019 | no auth route is registered at `main.py:L26-29` | `POST /auth/token` | `api/AuthController.issueToken` |

Twelve routes are mapped in total. `POST /auth/token` is the only route that does not require an
authenticated principal (DL-019, DL-021).

### 1.3 Tables, columns and the association

Four tables carry the four source table names. Twenty columns carry the twenty source column names and
types. One association is declared. No `NOT NULL`, no `UNIQUE` and no length bound is added (TR-3).

| Source table | Source location | Delivered entity | `@Table(name=…)` |
|--------------|-----------------|------------------|------------------|
| `tweets` | `db/models.py:L8-18` | `entity/Tweet` | `tweets` |
| `responses` | `db/models.py:L21-28` | `entity/Response` | `responses` |
| `ai_tools` | `db/models.py:L33-37` | `entity/AiTool` | `ai_tools` |
| `settings` | `db/models.py:L40-44` | `entity/Setting` | `settings` |

| # | Table | Source column and type | Source line | Delivered field | Java type | Column mapping |
|---|-------|------------------------|-------------|-----------------|-----------|----------------|
| 1 | `tweets` | `id` `Integer` primary key | `L10` | `id` | `Integer` | `@Id @GeneratedValue` |
| 2 | `tweets` | `content` `String` | `L11` | `content` | `String` | `@JdbcTypeCode(LONGVARCHAR)` |
| 3 | `tweets` | `like_count` `Integer` | `L12` | `likeCount` | `Integer` | plain |
| 4 | `tweets` | `created_at` `DateTime` | `L13` | `createdAt` | `LocalDateTime` | plain |
| 5 | `tweets` | `doubt_rating` `Float` | `L14` | `doubtRating` | `Double` | plain |
| 6 | `tweets` | `media` `String` | `L15` | `media` | `List<String>` | `@Convert` + `@JdbcTypeCode(LONGVARCHAR)` |
| 7 | `tweets` | `quoted_tweet_id` `String` | `L16` | `quotedTweetId` | `String` | `@JdbcTypeCode(LONGVARCHAR)` |
| 8 | `tweets` | `user_id` `String` | `L17` | `userId` | `String` | `@JdbcTypeCode(LONGVARCHAR)` |
| 9 | `tweets` | `ai_tools_mentioned` `String` | `L18` | `aiToolsMentioned` | `List<String>` | `@Convert` + `@JdbcTypeCode(LONGVARCHAR)` |
| 10 | `responses` | `id` `Integer` primary key | `L23` | `id` | `Integer` | `@Id @GeneratedValue` |
| 11 | `responses` | `content` `String` | `L24` | `content` | `String` | `@JdbcTypeCode(LONGVARCHAR)` |
| 12 | `responses` | `generated_at` `DateTime` | `L25` | `generatedAt` | `LocalDateTime` | plain |
| 13 | `responses` | `is_approved` `Boolean` | `L26` | `isApproved` | `Boolean` | plain |
| 14 | `responses` | `tweet_id` `Integer` `ForeignKey('tweets.id')` | `L28` | `tweet` | `Tweet` | `@ManyToOne @JoinColumn(name="tweet_id")` |
| 15 | `ai_tools` | `id` `Integer` primary key | `L35` | `id` | `Integer` | `@Id @GeneratedValue` |
| 16 | `ai_tools` | `name` `String` | `L36` | `name` | `String` | `@JdbcTypeCode(LONGVARCHAR)` |
| 17 | `ai_tools` | `description` `String` | `L37` | `description` | `String` | `@JdbcTypeCode(LONGVARCHAR)` |
| 18 | `settings` | `key` `String` primary key | `L42` | `key` | `String` | `@Id`, `@Column(name="\"key\"")`, plain type |
| 19 | `settings` | `value` `String` | `L43` | `value` | `String` | `@Column(name="\"value\"")` + `@JdbcTypeCode(LONGVARCHAR)` |
| 20 | `settings` | `description` `String` | `L44` | `description` | `String` | `@JdbcTypeCode(LONGVARCHAR)` |

Ten of the twenty columns carry `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`: rows 2, 6, 7, 8, 9, 11, 16, 17, 19
and 20. `settings.key` carries the plain `String` mapping, and it is the one character column that a
primary key indexes (DL-068, DL-069). No field in any entity declares `columnDefinition`.

| Source association | Source location | Delivered mapping |
|--------------------|-----------------|-------------------|
| `relationship("Response", order_by=Response.id, back_populates="tweet")` | `db/models.py:L30` | `entity/Tweet.responses` — `@OneToMany(mappedBy="tweet")` with `@OrderBy("id ASC")`, and the inverse `entity/Response.tweet` — `@ManyToOne @JoinColumn(name="tweet_id")` |

One association is declared, and it is the only one. `tweets.ai_tools_mentioned` remains a character
column and is not a foreign key to `ai_tools` and not a join table (DL-024).

### 1.4 Business rules

Both rules are transcribed. The arithmetic is unchanged.

| Rule | Source expression | Source line | Delivered expression | Delivered location |
|------|-------------------|-------------|----------------------|--------------------|
| Doubt rating | `doubt_rating = (1 - sentiment_score) * 5` then `max(0, min(10, doubt_rating))` | `services/sentiment_analysis.py:L29,L32` | `(1 - sentimentScore) * 5` then `Math.max(0.0d, Math.min(10.0d, doubtRating))` | `service/SentimentAnalysisService.calculateDoubtRating(double)` |
| Popularity gate | `popularity_threshold = self.settings.TWEET_POPULARITY_THRESHOLD` then `if tweet.likes >= popularity_threshold` | `services/twitter_service.py:L43,L46`; default 100 at `core/config.py:L10` | `likeCount >= popularityThreshold`, inclusive, default 100 | `service/TwitterService.meetsPopularityThreshold(Integer, int)` |

A `NaN` sentiment score yields 10.0, which is the value `max(0, min(10, nan))` yields in Python
(DL-036). An absent like count yields `false`. Both boundaries are asserted: the doubt rating at
scores −1, 1, 0, −0.5, 0.5 and the out-of-range −2 and 2; the gate at 99, 100 and 101.
### 1.5 Configuration keys

The retired `Settings` class declared seven keys. Eight further keys were read by code and declared
nowhere, so every code path touching one of the eight raised `AttributeError`. All fifteen are declared
in `application.yml` and bound through `config/ScannerProperties` (defect D3, goal G6).

| # | Source key | Declared at | Read at | Delivered key |
|---|-----------|-------------|---------|---------------|
| 1 | `TWITTER_API_KEY` | `core/config.py:L5` | `services/twitter_service.py:L12` | `scanner.twitter.api-key` |
| 2 | `TWITTER_API_SECRET` | `core/config.py:L6` | — | `scanner.twitter.api-secret` |
| 3 | `NOTION_API_KEY` | `core/config.py:L7` | `services/notion_service.py:L8` | `scanner.notion.api-key` |
| 4 | `OPENAI_API_KEY` | `core/config.py:L8` | `services/llm_service.py:L9`, as the lower-case `openai_api_key` | `scanner.openai.api-key` |
| 5 | `DATABASE_URL` | `core/config.py:L9` | `db/database.py:L7` | `scanner.database-url` |
| 6 | `TWEET_POPULARITY_THRESHOLD` — default 100 | `core/config.py:L10` | `services/twitter_service.py:L43` | `scanner.popularity-threshold` — default 100 |
| 7 | `RESPONSE_GENERATION_DELAY` — default 60 | `core/config.py:L11` | `tasks/response_generation.py:L50`, as `response_generation_interval` | `scanner.response-generation-delay-seconds` — default 60 |
| 8 | `SECRET_KEY` | *declared nowhere* | `core/security.py:L11` | `scanner.jwt.secret` |
| 9 | `ALGORITHM` | *declared nowhere* | `core/security.py:L11` | `scanner.jwt.algorithm` |
| 10 | `NOTION_DATABASE_ID` | *declared nowhere* | `services/notion_service.py:L24,L35` | `scanner.notion.database-id` |
| 11 | `TWITTER_API_SECRET_KEY` | *declared nowhere* | `services/twitter_service.py:L12` | `scanner.twitter.api-secret-key` |
| 12 | `TWITTER_ACCESS_TOKEN` | *declared nowhere* | `services/twitter_service.py:L13`, `tasks/tweet_monitoring.py:L48` | `scanner.twitter.access-token` |
| 13 | `TWITTER_ACCESS_TOKEN_SECRET` | *declared nowhere* | `services/twitter_service.py:L13`, `tasks/tweet_monitoring.py:L49` | `scanner.twitter.access-token-secret` |
| 14 | `TWITTER_CONSUMER_KEY` | *declared nowhere* | `tasks/tweet_monitoring.py:L46` | `scanner.twitter.consumer-key` |
| 15 | `TWITTER_CONSUMER_SECRET` | *declared nowhere* | `tasks/tweet_monitoring.py:L47` | `scanner.twitter.consumer-secret` |

Two of the fifteen were read under a name the class did not declare: `OPENAI_API_KEY` at
`services/llm_service.py:L9` reads the lower-case attribute `openai_api_key`, and
`RESPONSE_GENERATION_DELAY` at `tasks/response_generation.py:L50` reads
`settings.response_generation_interval`. Both are defects, recorded as D5 and D2 in §1.8.

Three of the fifteen name a credential the canonical pair already carries. `TWITTER_API_SECRET_KEY`,
`TWITTER_CONSUMER_KEY` and `TWITTER_CONSUMER_SECRET` are declared as aliases whose nested placeholder
defaults point at the canonical pair, so each resolves with no duplicate secret supplied (DL-031).

#### 1.5.1 Complete target configuration inventory

`application.yml` declares **49** keys. **39** sit under the `scanner.` prefix and bind to
`config/ScannerProperties`, which the annotation processor renders as 9 groups and 39 properties in
`target/classes/META-INF/spring-configuration-metadata.json`. The remaining **10** are framework keys.
Relaxed binding accepts an environment override for every key that names one; a key with no environment
placeholder is changed through a profile document or a command-line property.

| # | Key | Origin | Environment override | Default | Decision |
|---|-----|--------|----------------------|---------|----------|
| 1 | `server.port` | `main.py:L53` | `PORT` | `5000` | DL-029 |
| 2 | `server.forward-headers-strategy` | *Net-new* | `FORWARD_HEADERS_STRATEGY` | `framework` | DL-293 |
| 3 | `server.max-http-request-header-size` | *Net-new* | `MAX_HTTP_REQUEST_HEADER_SIZE` | `8KB` | DL-238 |
| 4 | `server.shutdown` | *Net-new* | `SERVER_SHUTDOWN` | `graceful` | DL-294 |
| 5 | `logging.level.com.codeskeptic.scanner` | *Net-new* | `SCANNER_LOG_LEVEL` | `INFO` | DL-052, DL-206 |
| 6 | `spring.main.web-application-type` | *Net-new* | — | `servlet` | DL-030 |
| 7 | `spring.lifecycle.timeout-per-shutdown-phase` | *Net-new* | `SHUTDOWN_GRACE_PERIOD` | `30s` | DL-294 |
| 8 | `spring.jpa.hibernate.ddl-auto` | *Net-new* | — | `update` | DL-026 |
| 9 | `spring.jpa.open-in-view` | *Net-new* | — | `false` | DL-026 |
| 10 | `spring.jpa.properties.hibernate.auto_quote_keyword` | `db/models.py:L42-43` | — | `true` | DL-061 |
| 11 | `scanner.database-url` | `core/config.py:L9`, `db/database.py:L7` | `DATABASE_URL` | *none — must be supplied* | DL-027 |
| 12 | `scanner.popularity-threshold` | `core/config.py:L10`, `services/twitter_service.py:L43,L46` | `TWEET_POPULARITY_THRESHOLD` | `100` | — |
| 13 | `scanner.response-generation-delay-seconds` | `core/config.py:L11`, `tasks/response_generation.py:L50` | `RESPONSE_GENERATION_DELAY` | `60` | — |
| 14 | `scanner.twitter.api-key` | `core/config.py:L5-6` | `TWITTER_API_KEY` | *empty* | — |
| 15 | `scanner.twitter.api-secret` | `core/config.py:L5-6` | `TWITTER_API_SECRET` | *empty* | — |
| 16 | `scanner.twitter.api-secret-key` | `services/twitter_service.py:L12` | `TWITTER_API_SECRET_KEY` | `${TWITTER_API_SECRET:}` | DL-031 |
| 17 | `scanner.twitter.consumer-key` | `tasks/tweet_monitoring.py:L46-47` | `TWITTER_CONSUMER_KEY` | `${TWITTER_API_KEY:}` | DL-031 |
| 18 | `scanner.twitter.consumer-secret` | `tasks/tweet_monitoring.py:L46-47` | `TWITTER_CONSUMER_SECRET` | `${TWITTER_API_SECRET:}` | DL-031 |
| 19 | `scanner.twitter.access-token` | `services/twitter_service.py:L13`, `tasks/tweet_monitoring.py:L48-49` | `TWITTER_ACCESS_TOKEN` | *empty* | DL-046 |
| 20 | `scanner.twitter.access-token-secret` | `services/twitter_service.py:L13`, `tasks/tweet_monitoring.py:L48-49` | `TWITTER_ACCESS_TOKEN_SECRET` | *empty* | DL-046 |
| 21 | `scanner.twitter.request-timeout-seconds` | *Net-new* | `TWITTER_REQUEST_TIMEOUT_SECONDS` | `10` | DL-230 |
| 22 | `scanner.notion.api-key` | `core/config.py:L7`, `services/notion_service.py:L8` | `NOTION_API_KEY` | *empty* | — |
| 23 | `scanner.notion.database-id` | `services/notion_service.py:L24,L35` | `NOTION_DATABASE_ID` | *empty* | — |
| 24 | `scanner.notion.api-version` | *Net-new* | `NOTION_API_VERSION` | `2022-06-28` | DL-151 |
| 25 | `scanner.notion.connect-timeout-seconds` | *Net-new* | `NOTION_CONNECT_TIMEOUT_SECONDS` | `5` | DL-150 |
| 26 | `scanner.notion.read-timeout-seconds` | *Net-new* | `NOTION_READ_TIMEOUT_SECONDS` | `10` | DL-150 |
| 27 | `scanner.notion.mirror-max-retries` | *Net-new* | `NOTION_MIRROR_MAX_RETRIES` | `2` | DL-253 |
| 28 | `scanner.notion.mirror-retry-backoff-millis` | *Net-new* | `NOTION_MIRROR_RETRY_BACKOFF_MILLIS` | `500` | DL-253 |
| 29 | `scanner.openai.api-key` | `core/config.py:L8`, `services/llm_service.py:L9` | `OPENAI_API_KEY` | *empty* | — |
| 30 | `scanner.openai.model` | *Net-new* | — | `gpt-5.6-terra` | DL-033 |
| 31 | `scanner.openai.max-completion-tokens` | `services/llm_service.py:L22` | `OPENAI_MAX_COMPLETION_TOKENS` | `150` | DL-034, DL-202 |
| 32 | `scanner.openai.temperature` | `services/llm_service.py:L25` | `OPENAI_TEMPERATURE` | `0.7` | DL-145, DL-200 |
| 33 | `scanner.openai.n` | `services/llm_service.py:L23` | `OPENAI_N` | `1` | — |
| 34 | `scanner.openai.reasoning-effort` | *Net-new* | `OPENAI_REASONING_EFFORT` | `none` | DL-145 |
| 35 | `scanner.openai.request-timeout-seconds` | *Net-new* | `OPENAI_REQUEST_TIMEOUT_SECONDS` | `30` | DL-146 |
| 36 | `scanner.openai.max-retries` | *Net-new* | `OPENAI_MAX_RETRIES` | `2` | DL-146, DL-201 |
| 37 | `scanner.jwt.secret` | `core/security.py:L11` | `SECRET_KEY` | *none — must be supplied* | DL-016, DL-184, DL-185, DL-186 |
| 38 | `scanner.jwt.algorithm` | `core/security.py:L11` | `ALGORITHM` | `HS256` | DL-015, DL-108, DL-184 |
| 39 | `scanner.jwt.expiration-minutes` | `core/security.py:L9-10` | — | `60` | DL-017, DL-142 |
| 40 | `scanner.auth.username` | *Net-new* | `AUTH_USERNAME` | `admin` | DL-020 |
| 41 | `scanner.auth.password-hash` | *Net-new* | `AUTH_PASSWORD_HASH` | *none — must be supplied* | DL-020 |
| 42 | `scanner.analytics.trend-window-days` | *Net-new* | — | `30` | DL-042 |
| 43 | `scanner.ingestion.stream-base-keywords` | *Net-new* | — | *four terms* | DL-044 |
| 44 | `scanner.ingestion.max-stream-rules` | *Net-new* | `TWITTER_MAX_STREAM_RULES` | `25` | DL-254 |
| 45 | `scanner.ingestion.stream-idle-timeout-seconds` | *Net-new* | `TWITTER_STREAM_IDLE_TIMEOUT_SECONDS` | `60` | DL-256 |
| 46 | `scanner.background.enabled` | *Net-new* | `SCANNER_BACKGROUND_ENABLED` | `true` | DL-250 |
| 47 | `scanner.background.stream-enabled` | *Net-new* | `TWITTER_STREAM_ENABLED` | `true` | DL-250 |
| 48 | `scanner.background.response-generation-enabled` | *Net-new* | `RESPONSE_GENERATION_ENABLED` | `true` | DL-250 |
| 49 | `scanner.background.max-candidates-per-pass` | *Net-new* | `RESPONSE_GENERATION_MAX_CANDIDATES_PER_PASS` | `200` | DL-282 |

Rows 1 to 10 are the framework keys. Rows 11 to 49 are the `scanner.` group, and row 11 is the only key
in the file declared with no default at all besides `scanner.jwt.secret` at row 37 and
`scanner.auth.password-hash` at row 41: those three must be supplied and startup fails without them
(DL-016, DL-020, DL-027).

Stream activation is configuration — rows 46 and 47 — and so is the response-generation switch at row
48, the rule cap at row 44, the idle bound at row 45 and the per-pass candidate ceiling at row 49.
Reconnection pacing is not configuration: the exponential backoff is code (DL-045); the per-record
dispatch scheduler is code (DL-220); the one-mebibyte record bound is a code constant (DL-222); the
`ai_tools` page size and scan bound are code constants (DL-291); and the only bound the X transport takes
from configuration is `scanner.twitter.request-timeout-seconds` at row 21, which the filtered-stream
subscription does not carry (DL-230).

#### 1.5.2 Seeded `settings` rows and their consumers

`service/SettingsService.seedDefaultSettings` seeds exactly three rows, insert-if-absent on
`ApplicationReadyEvent` (DL-040, DL-159), and `PUT /settings/{key}` edits them. Two of the three have a
runtime consumer, and both consumers apply the same precedence: the row overrides the configured
property, and an absent, `null` or unusable stored value falls back to that property with one `WARN`
naming the key and never the stored value.

| Row `key` | Seeded from | Runtime consumer | Accepted stored value | Fallback | Decision |
|-----------|-------------|------------------|-----------------------|----------|----------|
| `tweet_popularity_threshold` | `scanner.popularity-threshold` | `service/TwitterService.popularityThresholdInForce()`, called once per stream cycle by `task/TweetStreamClient` and passed to `meetsPopularityThreshold(Integer, int)` for every record of that cycle; the single-argument `meetsPopularityThreshold(Integer)` resolves the row itself for a caller outside a cycle | any `int` once trimmed, including a value at or below zero | `scanner.popularity-threshold` when the row is absent, holds `null` or does not parse | DL-040, DL-255 |
| `response_generation_delay` | `scanner.response-generation-delay-seconds` | *No runtime consumer.* The row is seeded, served by `GET /settings` and editable through `PUT /settings/{key}`; no delivered code path reads it | any value the route accepts | not applicable — the row is not read | DL-047, DL-227 |
| `stream_keywords` | `scanner.ingestion.stream-base-keywords`, comma-joined; seeded as the empty string | `task/TweetStreamClient.readOverrideTerms()`, read by `composeRuleTerms()` before every connection | a comma-separated list holding at least one term that survives the literal-term allowlist of DL-257; the surviving terms replace the whole rule set, and `boundedToRuleCap` then caps the set at `scanner.ingestion.max-stream-rules` with the excess dropped under one `WARN` naming counts only | `scanner.ingestion.stream-base-keywords` union every usable `ai_tools.name` when the row is absent, holds `null`, or holds no term that survives the allowlist | DL-044, DL-254, DL-257, DL-291 |

The pass is paced by `@Scheduled(fixedDelayString = "${scanner.response-generation-delay-seconds}",
timeUnit = TimeUnit.SECONDS)` on `task/ResponseGenerationScheduler.generatePendingResponses()`. The
framework resolves that interval **once, at registration**, so an edit to the
`response_generation_delay` row changes nothing until the process restarts, and an edit to the property
changes nothing in a running process either. DL-227 and DL-228 record the per-pass resolution an earlier
revision carried; neither mechanism is delivered.

`stream_keywords` is seeded as the empty string, so the seeded row supplies no term of its own and the
composed set is the configured base terms union every usable `ai_tools.name` until an operator writes a
value (DL-044).

### 1.6 External integrations

Four external systems, one adapter bean each. Vendor types do not cross an adapter boundary. The
relational database stays the system of record and Notion stays a secondary mirror (goal G4).

| System | Source client | Source location | Delivered adapter | Delivered mechanism |
|--------|---------------|-----------------|-------------------|---------------------|
| X (Twitter) | `tweepy` `API`, `OAuthHandler`, `Stream(...).filter(track=[...])` against the retired v1.1 `statuses/filter` | `services/twitter_service.py:L1,L12-23`; `tasks/tweet_monitoring.py:L1,L45-55` | `task/TweetStreamClient`, with `service/TwitterService` holding the relational operations | `WebClient` over the X API v2 filtered stream at `GET /2/tweets/search/stream`, rules reconciled through `POST /2/tweets/search/stream/rules`, an app-only bearer token exchanged at `POST oauth2/token`, and reconnection honouring `429` with `x-rate-limit-reset` (DL-012, DL-045, DL-046) |
| Google Cloud Natural Language | `LanguageServiceClient()` under Application Default Credentials | `services/sentiment_analysis.py:L1,L8` | `service/SentimentAnalysisService` | `com.google.cloud:google-cloud-language` 2.96.0; the client is acquired on first use and released on shutdown, and Application Default Credentials are retained (DL-288) |
| OpenAI | `from openai import Completion` against `text-davinci-002` on the removed Completions API | `services/llm_service.py:L1,L19-26` | `service/LlmService` | `com.openai:openai-java` 4.49.0 over Chat Completions, the model identifier in configuration, `max_completion_tokens` carrying the source value 150 (DL-032, DL-033, DL-034) |
| Notion | `notion_client.Client(auth=…)`, `pages.create`, `databases.query` | `services/notion_service.py:L1,L8,L23-26,L32-38` | `service/NotionService`, over the `RestClient` bean of `config/RestClientConfig` | `org.springframework.web.client.RestClient`, which ships with `spring-boot-starter-web`; the adapter issues a POST query, a POST create and a PATCH update and carries text as one item per 2,000 characters (DL-013, DL-292) |

No delivered code path publishes to X. `task/TweetStreamClient` issues a token exchange, a rules read, a
rules mutation and a stream subscription, and it issues no post (IR7, DL-046).

### 1.7 Retired PyPI packages

The retired tree carried no dependency manifest of any kind, so the set below was reconstructed from
import statements and every package in it was unpinned (defect A4). Fifteen packages are retired; the
delivered `backend/pom.xml` declares 16 dependencies under one parent, for 17 coordinates, of which 5
carry an explicit version.

| # | Retired package | Import evidence | Java replacement | Status |
|---|-----------------|-----------------|------------------|--------|
| 1 | Flask | `main.py:L1`; `api/*.py:L1` | `spring-boot-starter-web` | Retired |
| 2 | Flask-Cors | `main.py:L2` | `CorsConfigurationSource`, which `spring-boot-starter-web` supplies | Retired |
| 3 | Flask-JWT-Extended | `main.py:L3`; `api/*.py:L2` | `spring-boot-starter-security` filter chain | Retired |
| 4 | PyJWT | `core/security.py:L1` | `io.jsonwebtoken` jjwt 0.13.0 — `jjwt-api`, `jjwt-impl`, `jjwt-jackson` | Retired |
| 5 | passlib | `core/security.py:L3` | `BCryptPasswordEncoder`, which `spring-boot-starter-security` supplies | Retired |
| 6 | SQLAlchemy | `db/database.py:L1-2`; `db/models.py:L1-3` | `spring-boot-starter-data-jpa`, Hibernate 6.6.53.Final | Retired |
| 7 | pydantic | `core/config.py:L2`; `schema/*.py:L1` | `@ConfigurationProperties`, Java records, `spring-boot-starter-validation` | Retired |
| 8 | celery | `tasks/response_generation.py:L1` | `@EnableScheduling` and `@Scheduled`; no broker and no queue | Retired |
| 9 | tweepy | `services/twitter_service.py:L1`; `tasks/tweet_monitoring.py:L1` | `WebClient` from `spring-boot-starter-webflux` against X API v2 (DL-012) | Retired |
| 10 | google-cloud-language | `services/sentiment_analysis.py:L1` | `com.google.cloud:google-cloud-language` 2.96.0 | Retired |
| 11 | notion-client | `services/notion_service.py:L1` | `RestClient` from `spring-boot-starter-web` (DL-013) | Retired |
| 12 | openai | `services/llm_service.py:L1` | `com.openai:openai-java` 4.49.0 | Retired |
| 13 | pytest | `tests/test_api.py:L1`; `tests/test_tasks.py:L1` | JUnit Jupiter 5.12.2, from `spring-boot-starter-test` | Retired |
| 14 | unittest and unittest.mock | `tests/test_services.py:L1-2`; `tests/test_tasks.py:L2` | JUnit Jupiter and Mockito 5.17.0, from `spring-boot-starter-test` | Retired |
| 15 | fastapi | `tests/test_api.py:L2` — a `TestClient` import made against a Flask application | `MockMvc`; the dependency has no successor coordinate | Retired |

Five coordinates carry an explicit version: `jjwt-api`, `jjwt-impl` and `jjwt-jackson` at 0.13.0,
`google-cloud-language` at 2.96.0 and `openai-java` at 4.49.0. The other twelve are managed by
`spring-boot-starter-parent` 3.5.16. `postgresql`, `mysql-connector-j` and `h2` are declared at
`runtime` scope; `spring-boot-starter-test` and `spring-security-test` at `test` scope;
`spring-boot-configuration-processor` as `optional`. `<properties>` declares one property,
`<java.version>21</java.version>`, and `<build>` declares one plugin,
`spring-boot-maven-plugin` (DL-003, DL-004, DL-009).
### 1.8 Defect closure

The six named defects D1–D6, followed by every additional defect surfaced while reading the retired
tree, numbered A1–A32. A closed defect is one whose Java target exists. Every row below is either
closed by a delivered construct — status `Delivered` — or held as it is, status
`Retained by decision`, meaning the defect lies wholly in `frontend/**`, which this migration must not
edit, and the entry named in the row authorises leaving it. That status is not the word
`Retired`, which §1.7 uses for a dependency this migration removed: A15–A32 are retained, not removed.
Rows A15–A32 carry the twenty-two confirmed client-seam items `DL-059` inventories — A15 carries the
first five and each later row carries one — and where closure rests on a construct outside this module,
the row names it.

| # | Defect | Source evidence | Java resolution | Status |
|---|--------|-----------------|-----------------|--------|
| D1 | Ingestion never persists anything: the listener builds a `Tweet` and abandons it, and the keyword set is empty so the stream could not start regardless | `tasks/tweet_monitoring.py:L29` — the deferred-work comment reading "Add database session and commit tweet", standing where the commit should have been — and `:L53-55` (`keywords = []` then `stream.filter(track=keywords)`) | `task/TweetStreamListener` validating the required stream fields and persisting valid rows through `repository/TweetRepository.save`, and `task/TweetStreamClient` composing a non-empty rule set from configured base terms union `ai_tools.name`, overridable by the `stream_keywords` row (DL-044/DL-080) | Delivered |
| D2 | The response scheduler raises `NameError` on its own final line: `time.sleep(...)` without importing `time`, and it reads `settings.response_generation_interval` where the declared property is `RESPONSE_GENERATION_DELAY` | `tasks/response_generation.py:L50`; `core/config.py:L11` | `task/ResponseGenerationScheduler.generatePendingResponses()` carrying `@Scheduled(fixedDelayString = "${scanner.response-generation-delay-seconds}", timeUnit = TimeUnit.SECONDS)`, so the interval runs from the completion of one pass to the start of the next — the work-then-sleep order of `:L41-50`. `config/AsyncSchedulingConfig` carries `@EnableScheduling` and publishes the `taskScheduler` the pass runs on (DL-047, DL-227, DL-251) | Delivered |
| D3 | Eight configuration keys are read by code but declared nowhere, so any code path touching them raises `AttributeError` | `core/config.py:L5-11` declares seven; `core/security.py:L11` (`SECRET_KEY` and `ALGORITHM` on one line), `services/notion_service.py:L24,L35`, `services/twitter_service.py:L12,L13`, `tasks/tweet_monitoring.py:L46-49` read eight more | All fifteen declared in `application.yml` and bound through `config/ScannerProperties`; the three Twitter aliases resolve through nested defaults (DL-031). Full inventory in §1.5 | Delivered |
| D4 | Three service classes are imported by controllers and do not exist anywhere in the repository | `api/responses.py:L3` (`ResponseService`), `api/settings.py:L3` (`SettingsService`), `api/analytics.py:L3` (`AnalyticsService`) | `service/ResponseService`, `service/SettingsService`, `service/AnalyticsService` — every method signature dictated by the call site that already existed (DL-039 … DL-043, DL-073, DL-075, DL-076, DL-200, DL-201, DL-202) | Delivered |
| D5 | The language-model call targets `text-davinci-002` through the removed Completions API, and assigns `Completion.api_key` from a lower-case attribute the settings class does not declare | `services/llm_service.py:L9,L19-26` | `service/LlmService` over Chat Completions with the model identifier in configuration (DL-032/DL-033), `max_completion_tokens` replacing `max_tokens` with a model-usable configurable default (DL-034/DL-202), the key read from `scanner.openai.api-key`, and generated text returned as `String` (DL-081) | Delivered |
| D6 | No token-issuance path exists: `create_access_token` has zero call sites, no auth route is registered, and every route's guard is a no-op | `core/security.py:L6-12`; `main.py:L26-29` | `api/AuthController.issueToken` on `POST /auth/token` with `security/JwtService`, `dto/LoginRequest` and `dto/TokenResponse`, over a configuration-backed principal (DL-019/DL-020) | Delivered |
| A1 | Every authorization guard is a no-op: `@jwt_required` is applied bare without parentheses, which in `flask-jwt-extended` 4.x registers the decorator factory and not the guard | `api/tweets.py:L10`; `api/responses.py:L9,L23,L34,L52`; `api/settings.py:L8,L14`; `api/analytics.py:L8,L18` — all eleven routes | `security/SecurityConfig` requiring an authenticated principal on every mapped endpoint except `POST /auth/token`, enforced by `security/JwtAuthenticationFilter`. Java enforces where Python did not, which is the behaviour change DL-021 records | Delivered |
| A2 | Background work is started synchronously and never returns: `initialize_background_tasks()` claims to run the stream "in a separate thread" but the tweepy call blocks, and the scheduler it calls next is a `while True` loop | `main.py:L41-48`; `tasks/response_generation.py:L41` | `ScannerApplication` carries `@SpringBootApplication`, `@ConfigurationPropertiesScan` and the `utcClock()` bean (DL-278); scheduling is enabled declaratively and the stream is lifecycle-managed. The two lifecycle components are `config/AsyncSchedulingConfig`, which carries `@EnableScheduling` and publishes the `taskScheduler` the `@Scheduled` pass of `task/ResponseGenerationScheduler` runs on (DL-047, DL-251), and `task/TweetStreamClient`, a `SmartLifecycle` bean the context starts after refresh and stops on shutdown (DL-045, DL-220, DL-290) | Delivered |
| A3 | There is no `__init__.py` anywhere, so `backend/app/**` is not an importable Python package tree at all, compounding the wrong-package-root test imports | absence throughout `backend/app/**`; `tests/test_services.py:L3-6` | Maven standard directory layout with a declared package per directory. No equivalent construct is required, and the wrong-root imports have no counterpart to carry forward | Delivered |
| A4 | No dependency manifest exists, yet three files install from `requirements.txt` | absence of `backend/requirements.txt`; `infrastructure/docker/Dockerfile.backend:L8-11`, `.github/workflows/ci.yml:L26-29`, `scripts/setup_environment.sh:L12` | `backend/pom.xml`. The Dockerfile and the CI job are rewritten; `scripts/setup_environment.sh` remains unchanged (DL-054) | Delivered |
| A5 | The CD job cannot build any image: `docker build … ./backend` finds no Dockerfile in that context, and never could | `.github/workflows/cd.yml:L36` | `-f infrastructure/docker/Dockerfile.backend` added, context unchanged (DL-056) | Delivered |
| A6 | Two dead imports: `from os import getenv`, never used, and `from jwt import encode, decode` where `decode` is never used | `core/config.py:L1`; `core/security.py:L1` | Neither is carried forward. `config/ScannerProperties` binds through the framework, and `security/JwtService` declares only what it calls | Delivered |
| A7 | `to_dict()` is called on entities four times and is never defined on any model | `api/tweets.py:L19,L30`; `api/responses.py:L18,L29,L47,L63`; `db/models.py` defines no such method | `service/mapper/TweetMapper`, `service/mapper/ResponseMapper` and `service/mapper/SettingMapper` (DL-295) | Delivered |
| A8 | The candidate-tweet query uses a `.query` attribute declarative models do not have and names a relationship that does not exist — `response`, where the declared attribute is `responses` | `tasks/response_generation.py:L43`; `db/models.py:L30` | `repository/TweetRepository.findUnansweredBatchAfter(Integer afterId, Pageable)` — the JPQL `t.responses is empty` predicate the derived name expressed, read as bounded keyset batches ordered by `id ASC` and drained batch by batch within one scheduled pass (DL-248) | Delivered |
| A9 | `Tweet.get(tweet_id)` and `response.save()` are called on declarative models, which expose neither | `tasks/response_generation.py:L16,L25-26` | `repository/TweetRepository.findById` for the generation subject, `TweetRepository.findByIdForUpdate` for the short storage transaction, and `repository/ResponseRepository.save`, all called from `service/ResponseService` (DL-086/DL-195) | Delivered |
| A10 | The analyze path is broken on both sides in complementary ways: the controller calls `sentiment_analysis.analyze(tweet.content)`, which does not exist, while the real `analyze_sentiment(tweet)` reads `tweet.text`, a field the schema does not have | `api/tweets.py:L46`; `services/sentiment_analysis.py:L14`; `schema/tweet.py:L5-14` | `service/SentimentAnalysisService.analyzeSentiment(String text)` returning a `double`, invoked with the post's content (DL-036/DL-037) | Delivered |
| A11 | `SettingsService.get_all_settings()` and `.update_setting(...)` are invoked statically on a class that does not exist | `api/settings.py:L10,L20` | Instance methods on the injected `service/SettingsService` bean (DL-043) | Delivered |
| A12 | `notion_service.update_tweet_response(...)` is called but the class has no such method, and the property mapping builds Notion fields from tweet attributes that mostly do not exist | `tasks/response_generation.py:L30`; `services/notion_service.py:L14-20` | `service/NotionService.updateTweetResponse(String, String)` and a property mapping rebuilt against the components `dto/TweetDto` actually declares | Delivered |
| A13 | Nothing ever creates the schema: there is no `Base.metadata.create_all()`, no migrations directory and no CLI, so the application cannot serve a request against a fresh database | `db/database.py:L1-13`; absence of any migration path | `spring.jpa.hibernate.ddl-auto: update` (DL-026), with `create-drop` against H2 under the test profile | Delivered |
| A14 | A new engine and session factory are created on every call, with no pooling, no closing and no transaction management | `db/database.py:L5-13` | One pooled `DataSource` from `config/DataSourceConfig`, HikariCP, Spring Data repositories and `@Transactional` boundaries at the service methods | Delivered |
| A15 | The client sends camelCase member names and an `/api` prefix that the backend never served, names the page-size parameter `perPage` where the backend names it `per_page`, imports an `authService` module that does not exist, and calls a `generate-response` endpoint that does not exist | `frontend/src/schema/*.ts`, `frontend/src/services/api.ts:L25,L43`, `frontend/src/utils/api.ts:L2` — the `authService` import is on line 2, while `:L13-16` is where the resolved token is written into the `Authorization: Bearer` header | Not reconciled; the backend retains snake_case, unprefixed routes and `per_page` (DL-022/DL-038/DL-059/DL-217). This row carries the first five of the twenty-two confirmed items; rows A16–A32 carry the remaining seventeen, one each, and `DL-059` inventories all twenty-two in one place | Retained by decision |
| A16 | The transport helper and its callers disagree on their own signature: `fetchWithAuth(url, method, data?)` is declared with a required `Method`, while one caller omits it and two pass an Axios-style configuration object in its place, so no call site binds under strict TypeScript | `frontend/src/utils/api.ts:L8,L19-23`; `frontend/src/services/api.ts:L26,L34-36,L44-46` | Not reconciled — no backend change can repair a disagreement internal to the client, and every file involved is under `frontend/**` (DL-059) | Retained by decision |
| A17 | A successful body is unwrapped twice: the helper returns `response.data` and every caller reads `.data` again, while no backend body carries a top-level `data` member, so each of the three operations evaluates to `undefined` | `frontend/src/utils/api.ts:L25-26`; `frontend/src/services/api.ts:L27,L37,L47` | Not reconciled — the backend body is the source-faithful envelope of `api/tweets.py:L18-21` and `api/responses.py:L17-20` (DL-038); a `data` wrapper adds a member the retired tree never emitted (DL-059) | Retained by decision |
| A18 | Two imported packages are declared nowhere and installed nowhere — `axios` and `zod` — and the `app/utils/api` specifier resolves under no configured alias | `frontend/package.json:L6-28`; `frontend/src/services/api.ts:L1-2`; `frontend/src/schema/{tweet,response,setting,aiTool}.ts:L1` | Not reconciled — the manifest and the import specifiers are both `frontend/**` files (DL-059) | Retained by decision |
| A19 | The analyze response contract is unrelated on the two sides: the client types `{sentiment: string, keywords: string[]}` while `POST /tweets/{tweetId}/analyze` answers `{tweet_id, analysis_result}` | `frontend/src/services/api.ts:L11-16,L32-37` versus `api/tweets.py:L52-55` | Not reconciled — the backend shape is the source-faithful one of `api/tweets.py:L52-55` (DL-037); `dto/AnalysisResultDto` is unchanged and the client type is the scaffold leftover (DL-059) | Retained by decision |
| A20 | The client reads the wrong error member: `error.response?.data?.message`, while every backend error body carries the single key `error`, so each approved literal is discarded for a generic fallback | `frontend/src/utils/api.ts:L30-31` versus `app/main.py:L31-37` | Not reconciled — a `message` alias adds a key the source never emitted (DL-210/DL-212); the client must read `error` (DL-059) | Retained by decision |
| A21 | The client's schemas reject bodies the backend considers valid: `z.date()` rejects the ISO-8601 string Jackson writes, `.optional()` admits `undefined` but not the JSON `null` written for `quoted_tweet_id`, and `description` and the three AI-tool members are typed required where the DTOs permit `null` | `frontend/src/schema/tweet.ts:L7,L10`; `frontend/src/schema/response.ts:L6`; `frontend/src/schema/setting.ts:L6`; `frontend/src/schema/aiTool.ts:L4-6` | Not reconciled. The timestamp form is the ISO-8601 text Jackson writes for `LocalDateTime`, whose `generated_at` value and precision are fixed by DL-232; the `quoted_tweet_id` nullability is the sole `Optional[str]` of `schema/tweet.py:L12` (DL-080); `dto/SettingDto` and `dto/AiToolDto` derive from `db/models.py:L40-44` and `:L33-37`, which declare no required-ness, and no `NOT NULL` may be added to justify inventing one (DL-059) | Retained by decision |
| A22 | No backend origin is configured anywhere on the client — no `baseURL`, no `proxy`, no API-origin setting — while the deployment puts the backend on Cloud Run and the frontend behind a storage bucket and CDN, so a bearer token reaches the frontend origin once a token provider exists | `frontend/src/utils/api.ts:L12-23`; `frontend/package.json:L1-54`; `.github/workflows/cd.yml:L41-61` | Not reconciled — latent while `authService` does not exist and nothing calls `POST /auth/token` (DL-019); resolving it is a deployment or `frontend/**` change, both outside this module (DL-059) | Retained by decision |
| A23 | Two Notion operations are called against routes the backend does not declare: `POST /api/notion/sync` and `GET /api/notion/data` | `frontend/src/services/notionService.ts:L16,L37` versus the route table of §1.2 | Not reconciled — the delivered route table is the eleven ported routes plus `POST /auth/token` and declares neither path; the Notion adapter is driven by this service's own background paths and exposes no HTTP surface (DL-059) | Retained by decision |
| A24 | The same Notion module cannot resolve or type itself: it imports `fetchWithAuth` from a module that exports only `getTweets`, `analyzeTweet` and `generateResponse`, references two types declared nowhere, and calls `response.json()` on a value the Axios helper has already decoded | `frontend/src/services/notionService.ts:L1,L10,L34,L40,L49` versus `frontend/src/services/api.ts` | Not reconciled — every file involved is internal to the client, and no backend change reaches it (DL-059) | Retained by decision |
| A25 | Five client operations its own screens import are declared nowhere in the client's API module — `getTweet`, `getResponse`, `getSettings`, `updateSetting` and `getAnalytics` | `frontend/src/pages/TweetView.tsx:L4,L21`; `frontend/src/pages/ResponseView.tsx:L4,L15`; `frontend/src/components/Settings.tsx:L2,L18,L27`; `frontend/src/components/Analytics.tsx:L3,L20` versus `frontend/src/services/api.ts` | Not reconciled — the routes two of them address are delivered and mapped in §1.2, `GET /tweets/{tweetId}` and `GET /responses/{responseId}`, so the gap is in the client's export list and not in the route surface (DL-059) | Retained by decision |
| A26 | The settings screen reads the reply as a key-to-value map and sends a bare value, while the route answers an array of objects and requires a one-member body | `frontend/src/components/Settings.tsx:L9,L18-19,L27,L40` versus `api/settings.py:L7-11,L13-24` | Not reconciled — `GET /settings` answers a JSON array of `{key, value, description}` (DL-039) and `PUT /settings/{key}` reads `{value}` (DL-050); a map shape drops `description` (DL-059) | Retained by decision |
| A27 | The analytics screen makes one call against two routes and renders six members no response carries — `labels`, `values`, `totalUsers`, `activeUsers`, `totalRevenue` and `averageOrderValue` | `frontend/src/components/Analytics.tsx:L20,L36,L40,L65-68`; `frontend/package.json:L2` | Not reconciled — `GET /analytics/summary` answers the seven members of DL-041 and `GET /analytics/trends` the day-bucketed series of DL-042, both zero-argument; the last four members are leftovers from the `financial-dashboard` scaffold the manifest is still named after (DL-059) | Retained by decision |
| A28 | The list operations are typed and consumed as bare arrays while the routes answer a paged envelope | `frontend/src/services/api.ts:L24`; `frontend/src/store/tweetSlice.ts:L24`; `frontend/src/services/twitterService.ts:L26-27` versus `api/tweets.py:L18-21` | Not reconciled — the `{tweets, pagination}` and `{responses, pagination}` envelopes are the source-faithful shape and their keys are fixed by DL-038 (DL-059) | Retained by decision |
| A29 | Three call sites pass argument shapes the operations do not declare: the paged read is handed to `useQuery` and so receives its context object, is called elsewhere with a single options object, and the analyze call is passed a whole tweet where an identifier is declared | `frontend/src/components/Dashboard.tsx:L8`; `frontend/src/store/tweetSlice.ts:L24`; `frontend/src/components/TweetAnalysis.tsx:L15` | Not reconciled — the two query parameters and their defaults of 1 and 10 are frozen by endpoint parity (DL-217) and the analyze route takes the identifier in its path (DL-037); the argument shapes are client-side (DL-059) | Retained by decision |
| A30 | A consumer filters on `tweet.popularity`, a member neither side declares | `frontend/src/services/twitterService.ts:L27` versus `dto/TweetDto` and `frontend/src/schema/tweet.ts` | Not reconciled — the delivered member is `like_count`, whose sanctioned client spelling is `likeCount` (DL-022); no column is added (DL-059) | Retained by decision |
| A31 | The client's TypeScript base configuration is missing and three further imports resolve to nothing: `tsconfig.base.json`, `@reduxjs/toolkit`, `dayjs`, `./TweetList` and `./PerformanceMetrics` — and `extends` is stated as a one-element array where a string is required | `frontend/tsconfig.json:L25-27`; `frontend/src/store/tweetSlice.ts:L1`; `frontend/src/utils/formatters.ts:L1`; `frontend/src/components/Dashboard.tsx:L4-5`; `frontend/package.json:L6-28` | Not reconciled — the absent file, the two undeclared packages and the two absent components are all `frontend/**`, and a read-only `tsc --noEmit` over the delivered client reports 67 errors of which these are part (DL-059) | Retained by decision |
| A32 | A component is imported as a named export while it is exported as a default only, and the router is used through an API the declared version removed — `Switch` and `Route component={…}` | `frontend/src/pages/ResponseView.tsx:L3` versus `frontend/src/components/ResponseManagement.tsx:L64`; `frontend/src/app.tsx:L2,L18-24` versus `frontend/package.json:L12` | Not reconciled — both are internal to the client and neither has a backend counterpart to change (DL-059) | Retained by decision |
### 1.9 Scaffolding markers

The retired Python tree carried exactly twenty-three scaffolding comments of two kinds, and each has a
row below. The inventory was read back from commit `80f1d53d^` by scanning the files.

* **Assistance banner** — the all-capitals request-for-review banner comment. Twenty occurrences:
  fifteen under `backend/app/**` and five under `backend/tests/**`.
* **Deferred-work tag** — the all-capitals four-letter deferred-work tag. Three occurrences, all in
  `backend/app/tasks/tweet_monitoring.py`, identified below by the text that followed the tag.

This file names the two kinds descriptively and never reproduces either literal token. A scan of
`backend/` for the banner, for the deferred-work tag, or for the five-letter defect tag that
conventionally accompanies it matches **0** lines — `backend/src/**`, `backend/pom.xml`,
`backend/docs/DECISION_LOG.md` and this file included. The delivered
`infrastructure/docker/Dockerfile.backend` likewise carries no banner — the block that occupied
`L22-28` of the retired file is removed.

Markers outside the ported surface remain in place; they are outside this migration's scope. **Nine** are
tracked outside `frontend/**`, at the line numbers the delivered tree carries:
`.github/workflows/ci.yml:L54` and `.github/workflows/cd.yml:L65`, both inside deploy placeholders this
migration does not edit; `infrastructure/terraform/main.tf:L109`,
`infrastructure/terraform/outputs.tf:L66`, `infrastructure/terraform/variables.tf:L78`,
`infrastructure/docker/Dockerfile.frontend:L25`, and `scripts/setup_environment.sh:L27`, `:L32` and
`:L52`, all untouched (DL-054). `frontend/**` carries a further **16** occurrences across **11** tracked
files (DL-059).

| # | Kind | Source location | Resolution | Status |
|---|------|-----------------|------------|--------|
| 1 | Assistance banner | `api/analytics.py:L10` | `service/AnalyticsService.getTrends()` implemented; metric set decided in DL-042 | Delivered |
| 2 | Assistance banner | `api/analytics.py:L20` | `service/AnalyticsService.getSummary()` implemented; metric set decided in DL-041 | Delivered |
| 3 | Assistance banner | `api/responses.py:L36` | `service/ResponseService.generateResponse(String)` implemented, with the two-outcome contract of DL-076 | Delivered |
| 4 | Assistance banner | `api/tweets.py:L34` | `service/TwitterService.updateTweetAnalysis(String, double)` implemented; the analyze contract is decided in DL-037 | Delivered |
| 5 | Assistance banner | `main.py:L41` | Replaced by declarative scheduling: `config/AsyncSchedulingConfig` carries `@EnableScheduling` and the blocking composition-root call is gone (A2) | Delivered |
| 6 | Assistance banner | `services/llm_service.py:L11` | Client construction deferred to first use in `service/LlmService.openAiClient()`; no static SDK state is written | Delivered |
| 7 | Assistance banner | `services/llm_service.py:L34` | `service/LlmService.generateResponse(TweetDto)` returns the generated text as a `String` (DL-081), where the source returned a partial dictionary, and `service/ResponseService` builds the wire record from the stored row | Delivered |
| 8 | Assistance banner | `services/notion_service.py:L10` | `config/RestClientConfig` supplies a configured `RestClient`; `scanner.notion.database-id` is a declared property | Delivered |
| 9 | Assistance banner | `services/notion_service.py:L30` | `service/NotionService.getTweets(int, String)` implemented, including default page size and cursor omission (DL-191) | Delivered |
| 10 | Assistance banner | `services/sentiment_analysis.py:L10` | Client acquisition and release implemented in `service/SentimentAnalysisService`, with Application Default Credentials retained (DL-288) | Delivered |
| 11 | Assistance banner | `services/twitter_service.py:L16` | The `pass`-stub `stream_tweets` becomes `task/TweetStreamClient` (DL-045) | Delivered |
| 12 | Assistance banner | `tasks/response_generation.py:L12` | `task/ResponseGenerationScheduler` (DL-047) | Delivered |
| 13 | Assistance banner | `tasks/response_generation.py:L36` | `task/ResponseGenerationScheduler` (DL-047) | Delivered |
| 14 | Assistance banner | `tasks/tweet_monitoring.py:L13` | `task/TweetStreamListener` | Delivered |
| 15 | Deferred-work tag — "Add database session and commit tweet" | `tasks/tweet_monitoring.py:L29` | `task/TweetStreamListener` persisting through `repository/TweetRepository.save` (D1) | Delivered |
| 16 | Deferred-work tag — "Implement response generation logic" | `tasks/tweet_monitoring.py:L32` | `task/TweetStreamListener` calling `service/ResponseService.generateResponseIfAbsentFor(Tweet)`, which is delivered with the parent-row lock guard of DL-195 | Delivered |
| 17 | Assistance banner | `tasks/tweet_monitoring.py:L36` | `task/TweetStreamClient` (DL-045, DL-046) | Delivered |
| 18 | Deferred-work tag — "Define keywords for streaming" | `tasks/tweet_monitoring.py:L53` | Decided in DL-044: configured base terms union every usable `ai_tools.name`, overridable by the `stream_keywords` row. All three parts are delivered — `scanner.ingestion.stream-base-keywords` holds the four base terms, `service/SettingsService` seeds the row, and `task/TweetStreamClient.composeRuleTerms()` composes the set and `boundedToRuleCap` caps it at `scanner.ingestion.max-stream-rules` (DL-254, DL-257, DL-291). The composed set is non-empty by construction | Delivered |
| 19 | Assistance banner | `tests/test_api.py:L53` | The date-range probe it flagged is not honoured; the trend window is a configured property (DL-042) | Delivered |
| 20 | Assistance banner | `tests/test_services.py:L13` | Replaced by real assertions in `service/TwitterServiceTest` | Delivered |
| 21 | Assistance banner | `tests/test_services.py:L19` | Replaced by real assertions in `service/TwitterServiceTest` | Delivered |
| 22 | Assistance banner | `tests/test_services.py:L50` | Replaced by `service/LlmServiceTest`, which asserts against the delivered Chat Completions call; the source's `generate_text` does not exist | Delivered |
| 23 | Assistance banner | `tests/test_tasks.py:L53` | Replaced by `task/ResponseGenerationSchedulerTest` and `task/TweetStreamListenerTest`, both delivered. The rate-limiting test the marker asks for at `:L55` is not written (DL-283), and no test asserts the `429` and `x-rate-limit-reset` handling of `task/TweetStreamClient`; that handling is held by review (DL-214) | Delivered |

### 1.10 Python test files

Three files, 183 lines, none of which can import. The delivered suite is **19** JUnit classes carrying
**1681** cases, all passing. The class-by-class case counts are maintained in §2.7 and nowhere else.

| Source test file | Source defect | JUnit replacements | Status |
|------------------|---------------|--------------------|--------|
| `backend/tests/test_api.py` | Imports `fastapi.testclient.TestClient` at `:L2` and points it at a Flask application; `test_get_settings` at `:L37-39` requests `/settings/` with a trailing slash and expects a key-to-value map; `test_update_settings` at `:L41-44` `PUT`s to `/settings/`, which is not a registered route; `:L50-51` names `total_tweets` and `total_responses`, which is the one piece of usable evidence in the file | `api/TweetControllerTest`, `api/ResponseControllerTest`, `api/SettingControllerTest`, `api/AnalyticsControllerTest`, `api/GlobalExceptionHandlerTest` (both error envelopes and every translated exception type, DL-181) and `ScannerApplicationTests` (context-load smoke). The two settings expectations are discounted (DL-039) and the summary key names are honoured (DL-041) | Delivered |
| `backend/tests/test_services.py` | Wrong package root at `:L3-6` (`from services.…`); two `pass` stubs at `:L12-22`; tests for `create_page`, `update_page` and `generate_text` at `:L28-38,L44-48`, none of which exist; asserts sentiment analysis returns `'positive'`/`'negative'`/`'neutral'` at `:L58-65` where the implementation returns a float | `service/SentimentAnalysisServiceTest`, `service/TwitterServiceTest`, `service/NotionServiceTest` and `service/LlmServiceTest` carry the four source origins; `service/ResponseServiceTest`, `service/SettingsServiceTest` and `service/AnalyticsServiceTest` cover the three services the source imported and never contained. The two `pass` stubs at `:L12-22` are replaced by the popularity-gate matrix of `service/TwitterServiceTest` | Delivered |
| `backend/tests/test_tasks.py` | `from backend.tasks import monitor_tweets, generate_response` at `:L3` — neither the module path nor either symbol exists | `task/ResponseGenerationSchedulerTest` and `task/TweetStreamListenerTest` | Delivered |

**Five** of the nineteen have no source construct of any kind: `api/AuthControllerTest` (DL-019),
`service/ResponseServiceTest` (DL-076, DL-211), `service/SettingsServiceTest` (DL-043),
`service/AnalyticsServiceTest` (DL-041, DL-042) and `config/DatabaseUrlTranslatorTest` (DL-027, DL-064,
DL-071, DL-072, DL-187). **Fourteen** carry a source construct, and §2.7 names it for each: three cover a
production construct whose origin is not a retired test file — `security/JwtServiceTest` covers
`core/security.py:L6-12`, `repository/JpaMappingIntegrationTest` covers `db/models.py`, and
`api/GlobalExceptionHandlerTest` covers `main.py:L31-37` — and the remaining eleven each descend from one
of the three retired test files.

No test class exists for `task/TweetStreamClient` or for `config/AsyncSchedulingConfig`: the frozen test
inventory names neither, and the stream client's behaviour is asserted from `ScannerApplicationTests`
where it is reachable and held by review where it is not (DL-214, DL-216).
### 1.11 Source expectations carried forward, and the two discounted

The retired test suite is the only place the source states an expectation about a response body it never
produced. Three such expectations exist. One is honoured and two are discounted; each row names the
entry that owns the choice, and none of them is argued here.

| # | Source expectation | Source location | Delivered treatment | Decision |
|---|--------------------|-----------------|---------------------|----------|
| 1 | `GET /settings` answers a key-to-value map containing an `auto_response` key, requested as `/settings/` with a trailing slash | `tests/test_api.py:L36-39` | **Discounted.** `GET /settings` answers a JSON array of `{key, value, description}`, the only shape carrying all three columns `db/models.py:L40-44` declares, and the registered path is `/settings` without a trailing slash (`api/settings.py:L7`). No `auto_response` row is seeded. The sibling `PUT` to `/settings/` at `:L41-44` addresses no registered route either, the registered path being `/settings/<key>` at `:L13` | DL-039 |
| 2 | The analytics endpoints accept a date range, probed as `?start_date=2023-01-01&end_date=2023-12-31` | `tests/test_api.py:L55-59`, written under the assistance banner at `:L53` that flags the whole case as needing adjustment | **Discounted.** `api/AnalyticsController` introduces no query parameter, both service methods being zero-argument at `api/analytics.py:L14,L24`; the observation window is the configured `scanner.analytics.trend-window-days` | DL-042 |
| 3 | The analytics summary body carries `total_tweets` and `total_responses` | `tests/test_api.py:L50`, `:L51` | **Honoured.** Both member names appear verbatim in `dto/SummaryDto`, and `api/AnalyticsControllerTest` asserts them. These two names are the one piece of usable body evidence anywhere in the retired suite | DL-041 |

---

## 2. Target → source

Every file delivered under `backend/`, plus the four operations files edited outside it. A target with
no source construct carries the marker *No source construct — net-new* and the decision-log identifier
that authorises it, so an absent source is explicit in the mapping. Where the retired tree holds a call
site, an import or a configuration value that fixes such a target's signature, that site is named in an
**Occasioned by** clause on the same row (DL-296).

### 2.1 Build, configuration and documentation

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `backend/pom.xml` | *No source construct — net-new* — DL-002, DL-003, DL-004 | No Python manifest ever existed; the absence is defect A4. `spring-boot-starter-parent` 3.5.16 with `<java.version>21</java.version>` as the single property. 16 declared dependencies under one parent, for 17 coordinates; 5 carry an explicit version and 12 are managed by the parent BOM. `postgresql`, `mysql-connector-j` and `h2` are all `runtime` scope (DL-009). One plugin is declared, `spring-boot-maven-plugin`; every plugin version is inherited |
| `backend/.gitignore` | *No source construct — net-new* — DL-055 | Exactly one line, `target/`, and no comment |
| `backend/.dockerignore` | *No source construct — net-new* — DL-055 | Exactly one line, `target/`, and no comment |
| `backend/src/main/resources/application.yml` | `core/config.py` — all seven declared keys — plus the eight keys read without declaration, plus the `.env` convention at `:L13-15` | Full key inventory in §1.5.1: 49 keys, 39 under `scanner.` and 10 framework keys. `server.port` DL-029, forwarded headers DL-293, header-size declaration DL-238, bounded graceful shutdown DL-294, web type DL-030, `ddl-auto` DL-026, reserved-word quoting DL-061, the Twitter aliases DL-031, the `scanner.jwt` accepted-value and key-length comments DL-184. It carries no `scanner.datasource.pool` block and no `spring.datasource` key; the pool takes the framework defaults (DL-027, DL-270) |
| `backend/src/test/resources/application-test.yml` | *No source construct — net-new* — DL-009, DL-016, DL-026, DL-027, DL-061 | No Python test configuration existed. H2 in memory with `create-drop`, reserved-word quoting, and a fixed signing key and bcrypt hash so `mvn clean verify` needs no manual step. `scanner.twitter.consumer-key` and `consumer-secret` are blank, so the X stream declines to start under test (DL-046). `response-generation-delay-seconds` is 86400, so no scheduled pass runs during a test |
| `backend/src/main/resources/META-INF/spring.factories` | *No source construct — net-new* — DL-305 | One `org.springframework.boot.diagnostics.FailureAnalyzer` entry naming `config/DataSourceConfig$DatabaseStartupFailureAnalyzer`. This is the resource location `SpringFactoriesLoader` reads for analyzers; an analyzer runs before an application context exists, so it cannot be a bean and `AutoConfiguration.imports` does not carry it |
| `backend/docs/DECISION_LOG.md` | *No source construct — net-new* — required by Rule 1; DL-058 | 305 entries, `DL-001` … `DL-305`, with no gap and no repeat, each carrying decision, alternatives, rationale and risks. Every identifier cited anywhere in this repository resolves to one of them |
| `backend/docs/TRACEABILITY_MATRIX.md` | *No source construct — net-new* — required by Rule 1; DL-058, DL-296 | This file. It carries mappings and measured facts only, and `DECISION_LOG.md` holds the rationale for every choice it names |

### 2.2 Application core, configuration and security

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `ScannerApplication.java` | `main.py:L13-39` — the `create_app()` factory and the duplicate module-level `Flask` object | `@SpringBootApplication` and `@ConfigurationPropertiesScan`, plus the one `utcClock()` bean returning `Clock.systemUTC()` (DL-278). One application context replaces two application objects (A2, DL-209) |
| `config/ScannerProperties.java` | `core/config.py:L4-15` — the single `Settings` class | One root record with nine nested groups — twitter, notion, openai, jwt, auth, analytics, ingestion, background — rendered by the annotation processor as 9 groups and 39 properties. Credential components are never rendered (DL-063); `twitter.request-timeout-seconds` is that group's one non-credential component (DL-230); the `background` group is DL-250 and DL-282 |
| `config/DataSourceConfig.java` | `db/database.py:L5-13` — the per-call `create_engine` and `sessionmaker` | One pooled `DataSource` built from the translated URL (A14). Pool geometry and timing are the framework defaults; no `scanner.datasource.pool` key and no `spring.datasource` key is read (DL-027, DL-270). For a `jdbc:mysql:` URL it reads one connection's `DatabaseMetaData` product name and version before the persistence layer starts and refuses a MariaDB server with a message naming `DATABASE_URL`, closing the pool first; a connection that cannot be opened is reported and the caller proceeds (DL-304). The nested `DatabaseStartupFailureAnalyzer` replaces the framework's report for a failure of the JDBC and dialect chain and is registered through `META-INF/spring.factories` rather than as a bean (DL-305) |
| `config/DatabaseUrlTranslator.java` | *No source construct — net-new* — DL-027. **Occasioned by** `core/config.py:L9` — the opaque `DATABASE_URL`, which the source read and never interpreted | SQLAlchemy-style URL to JDBC URL plus separated credentials (DL-027, DL-064, DL-072). Five accepted schemes across three vendors — `postgresql`, `postgres`, `mysql`, `mariadb`, `h2` — one per runtime-scope driver (DL-071, DL-187), any `+driver` suffix stripped, any letter case. A value already beginning `jdbc:` passes through unchanged with null credentials (DL-064). An unrecognised scheme and an empty value each fail fast naming the supported set. `spring.jpa.database-platform` is not set, so Hibernate detects the dialect from connection metadata |
| `config/CorsConfig.java` | `main.py:L20` — `CORS(app)` with no arguments | Permissive `CorsConfigurationSource`, unchanged (DL-051) |
| `config/WebClientConfig.java` | `tasks/tweet_monitoring.py:L45-51` — the tweepy `Stream` construction | `WebClient` bean for the X API v2 base URL (DL-012) |
| `config/RestClientConfig.java` | `services/notion_service.py:L8` — `Client(auth=…)` | `RestClient` bean carrying the Notion base URL, the bearer token and the `Notion-Version` header, over a retained `HttpClient` bean this class publishes with the connect bound and releases through a bounded `@PreDestroy` (DL-013, DL-150, DL-151, DL-221, DL-264). The API version is read from configuration with a compiled fallback (DL-193), and every configured header value passes one strip-and-reject gate before it is installed (DL-289) |
| `config/AsyncSchedulingConfig.java` | `main.py:L41-48` and `tasks/response_generation.py:L8` — the blocking initialiser and the broker-less Celery application | `@Configuration` and `@EnableScheduling`, plus the single `taskScheduler` bean carrying a pool size, a thread-name prefix, wait-for-tasks-on-shutdown and a termination await (DL-251). It registers no task and declares no `Trigger`: the pass is paced by the `@Scheduled` annotation on `task/ResponseGenerationScheduler` (DL-047, DL-227, DL-228) |
| `security/SecurityConfig.java` | `main.py:L22` (`JWTManager(app)`), `core/security.py:L14-18` (the passlib context) and the eleven bare `@jwt_required` sites | One `SecurityFilterChain`, a `BCryptPasswordEncoder` bean and a configuration-backed `InMemoryUserDetailsManager` holding exactly one principal and no authority (DL-020, DL-021). No table backs the credential store, so the schema stays the four tables of `db/models.py`. `POST /auth/token` is `permitAll` and carries a 4096-byte request-body bound (DL-118); the chain declares `Strict-Transport-Security` inline at the framework's own values (DL-277) and takes the framework default for every other response header |
| `security/JwtService.java` | `core/security.py:L6-12` — `create_access_token` | jjwt HS256, `exp = now + TTL`, `sub`-only claims (DL-014 … DL-018). The configured secret is read as raw UTF-8 text and its bytes are the key material (DL-186); construction refuses an unset, blank or unresolved-placeholder secret and material below 32 bytes (DL-185) |
| `security/JwtAuthenticationFilter.java` | The eleven `@jwt_required` decorator sites — a guard that enforced nothing | A `OncePerRequestFilter` that validates the bearer token (A1, DL-021). It writes one sentence on acceptance and one when a presented token names a principal the credential store does not hold, and nothing when a token fails to verify, which `security/JwtService` records itself; no record carries a request method, path, token or principal (DL-111) |

### 2.3 API and DTOs

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `api/TweetController.java` | `api/tweets.py:L9-55` — all three routes | Paths, methods, the `page` and `per_page` names with their 1 and 10 defaults, and the status codes preserved. `@PathVariable String` on both path routes, so a non-numeric identifier answers 404 and not 400 (DL-048). Services are injected final fields, replacing the per-request `TwitterService()` of `:L14,L26,L39` (TR-14) |
| `api/ResponseController.java` | `api/responses.py:L8-65` — all four routes | Paths, methods and every status code and literal preserved: 400 `Tweet ID is required`, 201, 500 `Failed to generate response`, 400 `Update data is required`, 404 `Response not found or update failed` and 404 `Response not found`. `POST /responses` reports two outcomes and never 404 (DL-076) |
| `api/SettingController.java` | `api/settings.py:L7-24` — both routes | Paths, methods, status codes and all four wire literals preserved. `GET /settings` answers a JSON array of `{key, value, description}` (DL-039); `PUT /settings/{key}` reads a one-member body and answers 400 `No value provided` and 404 `Setting not found` (DL-050) |
| `api/AnalyticsController.java` | `api/analytics.py:L7-25` — both routes | `GET /analytics/trends` and `GET /analytics/summary`, both zero-argument, so no query parameter is introduced: the observation window stays configuration (DL-042) |
| `api/AuthController.java` | *No source construct — net-new* — DL-019 | `POST /auth/token`, the only unauthenticated route. 401 handling is DL-078 and the `sub` claim is DL-079. A verification already in progress waits at most `VERIFICATION_WAIT_MILLIS` of 250 ms; the wait does not lengthen with attempt count and is not a limiter (DL-196) |
| `api/GlobalExceptionHandler.java` | `main.py:L31-37` — `@app.errorhandler(404)` and `(500)` | `@RestControllerAdvice` reproducing `{"error": "Not found"}` and `{"error": "Internal server error"}` byte for byte (TR-7, DL-181). It translates the application exceptions and nothing the framework already answers with its own body; the framework's own 405, 415, 406 and 413 answers are left to it, which is the parity position: Werkzeug answered those with its own page, and only 404 and 500 reached a Flask handler (DL-092). Six `@ExceptionHandler` methods serialise through `dto/ErrorResponse` (DL-210). The class also declares one `@Bean` of type `ErrorAttributes`, backed by a private nested `DefaultErrorAttributes` subclass, so the servlet `ERROR` dispatch a `sendError` produces renders the same single key — *no source construct — net-new* for that bean, **occasioned by** the two `@app.errorhandler` bodies it keeps identical across both dispatch types (DL-183) |
| `dto/TweetDto.java` | `schema/tweet.py:L5-14` | Nine components, `snake_case` on the wire via `@JsonProperty`; `id` serialises as `String` (DL-023); `media` and `ai_tools_mentioned` serialise as JSON arrays (DL-024); `quoted_tweet_id` is the one `Optional[str]` of `:L12` (DL-080) |
| `dto/ResponseDto.java` | `schema/response.py:L4-9` | Five components; `id` and `tweet_id` serialise as `String` (DL-023); `generated_at` value and precision fixed by DL-232 |
| `dto/SettingDto.java` | `db/models.py:L40-44` | `{key, value, description}`, the only shape carrying all three `settings` columns (DL-039) |
| `dto/AiToolDto.java` | `db/models.py:L33-37` | `{id, name, description}`; `id` serialises as `String` (DL-023) |
| `dto/PaginatedTweetsDto.java` | `api/tweets.py:L18-21` | The `{"tweets": [...], "pagination": {...}}` envelope (DL-038) |
| `dto/PaginatedResponsesDto.java` | `api/responses.py:L17-20` | The `{"responses": [...], "pagination": {...}}` envelope (DL-038) |
| `dto/PaginationDto.java` | `api/tweets.py:L20` — the envelope member whose producing method never existed | Keys `page`, `per_page`, `total`, `total_pages`, derived from Spring Data's `Page` as `getNumber() + 1`, `getSize()`, `getTotalElements()` and `getTotalPages()`. `page` is 1-based on the wire and 0-based in Spring Data (DL-038) |
| `dto/AnalysisResultDto.java` | `api/tweets.py:L52-55` | `{"tweet_id": <the path string as received>, "analysis_result": <the sentiment score>}` (DL-037) |
| `dto/CreateResponseRequest.java` | `api/responses.py:L38` and the 400 branch at `:L40-41` | One component, `tweet_id`, carrying `@NotNull` — one of exactly two `@NotNull` in the tree (IR9, DL-050) |
| `dto/UpdateResponseRequest.java` | `api/responses.py:L54` — the free-form `request.json` | Partial update of `content` and `is_approved` only. An explicit JSON `null` on either key binds as a write of `null` (DL-244); a repeated member binds as the free-form handler did, with no parser feature enabled (DL-188); a value of the wrong JSON type is coerced where the framework coerces it and is not rejected by this record (DL-231) |
| `dto/UpdateSettingRequest.java` | `api/settings.py:L16` and the 400 branch at `:L17-18` | One component, `value`, carrying `@NotNull` — the second of the two (IR9, DL-050) |
| `dto/TrendsDto.java` | `api/analytics.py:L13-15` | `{"trends": [ {date, tweet_count, average_doubt_rating, total_likes}, … ]}`, bucketed by day (DL-042) |
| `dto/SummaryDto.java` | `api/analytics.py:L23-25` | Seven members; `total_tweets` and `total_responses` are evidenced verbatim at `tests/test_api.py:L50-51` (DL-041). A null aggregate is reported as null (DL-075) |
| `dto/LoginRequest.java` | *No source construct — net-new* — DL-019 | `{username, password}` |
| `dto/TokenResponse.java` | *No source construct — net-new* — DL-019 | `{access_token, token_type, expires_in}` |
| `dto/ErrorResponse.java` | `main.py:L31-37` — the two error bodies | One component, `error`, matching every error body the source emitted. No `message` alias is added (DL-210, DL-212) |

### 2.4 Entities, repositories, mappers and utilities

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `entity/Tweet.java` | `db/models.py:L8-18` plus the relationship attached at `:L30` | `@Table(name = "tweets")`, nine columns by their source names and types. Five carry `@JdbcTypeCode(SqlTypes.LONGVARCHAR)` and none declares `columnDefinition` (DL-068). `@OneToMany(mappedBy = "tweet")` with `@OrderBy("id ASC")`, lazy on both sides with no cascade (DL-162). `ai_tools_mentioned` stays a character column and is not a foreign key (DL-024, DL-070) |
| `entity/Response.java` | `db/models.py:L21-28` | `@Table(name = "responses")`, five columns by their source names and types; `content` carries `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`; `@ManyToOne(fetch = LAZY) @JoinColumn(name = "tweet_id")` for the inverse side (DL-162) |
| `entity/AiTool.java` | `db/models.py:L33-37` | `@Table(name = "ai_tools")`, three columns; `name` and `description` carry `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`; no association (DL-070) |
| `entity/Setting.java` | `db/models.py:L40-44` | `@Table(name = "settings")`, `key` as `@Id`. Both reserved-word columns are quoted, `@Column(name = "\"key\"")` and `@Column(name = "\"value\"")` (DL-061). `value` and `description` carry `@JdbcTypeCode(SqlTypes.LONGVARCHAR)`; `key` carries the plain mapping, so the primary key is generated at a bounded capacity every vendor can index (DL-069) |
| `repository/TweetRepository.java` | `db/database.py:L10-13` — the per-call session | `JpaRepository<Tweet, Integer>` (DL-138) declaring seven members: the keyset batch `findUnansweredBatchAfter(Integer, Pageable)` expressing `t.responses is empty` (A8, DL-248), `findByIdForUpdate`, the `AnalysisSubject` projection read, the `updateDoubtRating` write, the `TweetAggregate` summary read, the `DailyTrend` window read and `findChunk(Pageable)`. A page of tweets at or below the window bound is read through the inherited `findAll(Pageable)`; a larger page is written from the lazily read view whose windows `findChunk` supplies (DL-249, DL-297) |
| `repository/ResponseRepository.java` | `db/database.py:L10-13` — the per-call session | `JpaRepository<Response, Integer>` declaring the `ResponseRow` projection page read with an explicit count query (DL-245, DL-249), the `ApprovalCounts` summary read, `existsByTweetId`, `findByIdForUpdate` with its lock-wait hints, and `findRowChunk(Pageable)`, the window read a page above the bound is written from, which carries no count query (DL-297) |
| `repository/AiToolRepository.java` | `db/database.py:L10-13` — the per-call session | `JpaRepository<AiTool, Integer>` plus `findNames(Pageable)`, which projects the `name` column alone. The caller pages it and filters each page as it merges, so the rule cap bounds the terms collected and not the rows read (DL-291) |
| `repository/SettingRepository.java` | `db/database.py:L10-13` | `JpaRepository<Setting, String>` — the key is the primary key |
| `service/mapper/TweetMapper.java` | *No source construct — net-new* — DL-295. **Occasioned by** the `tweet.to_dict()` calls at `api/tweets.py:L19,L30`, a method `db/models.py` never declared | Entity to `TweetDto`, carrying the identifier-to-string and delimited-column-to-array conversions (A7, DL-023, DL-024) and preserving null on every nullable component (DL-133) |
| `service/mapper/ResponseMapper.java` | *No source construct — net-new* — DL-295. **Occasioned by** the `response.to_dict()` calls at `api/responses.py:L18,L29,L47,L63` | Entity to `ResponseDto` and `ResponseRow` projection to `ResponseDto` in one class (DL-245) |
| `service/mapper/SettingMapper.java` | *No source construct — net-new* — DL-295. **Occasioned by** the serialisation performed inline at `api/settings.py:L11,L24` and never factored out | Entity to `SettingDto` (DL-039) |
| `util/DelimitedStringListConverter.java` | *No source construct — net-new* — DL-024. **Occasioned by** `db/models.py:L15,L18` read against `schema/tweet.py:L11,L14` — single `String` columns the schema exposed as `List[str]` with no conversion anywhere | Comma-delimited `AttributeConverter`, applied to `tweets.media` and `tweets.ai_tools_mentioned`. The column definition is unchanged |

### 2.5 Exceptions

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `exception/NotFoundException.java` | The 404 branches of `api/tweets.py:L32`, `api/responses.py:L31,L65` and `api/settings.py:L22` | Carries the message literal, so `api/GlobalExceptionHandler` reproduces the source body verbatim |
| `exception/BadRequestException.java` | The 400 branches of `api/responses.py:L41,L57` and `api/settings.py:L18` | Carries `Tweet ID is required`, `Update data is required` and `No value provided` |
| `exception/ResponseGenerationException.java` | `api/responses.py:L49` | Maps to the 500 body `{"error": "Failed to generate response"}`; the adapter codes it converts are DL-083 and DL-178 |
### 2.6 Services

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `service/TwitterService.java` | `services/twitter_service.py` | Implements the four methods the controllers called and the class lacked: `getPaginatedTweets(int, int)` (`api/tweets.py:L16`), `getTweet(String)` (`:L27`), `updateTweetAnalysis(String, double)` (`:L50`) and `meetsPopularityThreshold` (`tasks/tweet_monitoring.py`). The gate is declared in two overloads so a caller handling many records resolves the threshold once per cycle (DL-040, DL-255). A page of at most `PAGE_FETCH_CHUNK_ROWS` = 500 rows is read by one statement and a larger page is returned as a lazily read view of consecutive windows, so the rows resident stay bounded however large `per_page` is and no wire member changes (DL-297). The source's `tweet.likes` at `:L46` becomes `likeCount`; the popularity comparison stays inclusive with a default of 100 |
| `service/SentimentAnalysisService.java` | `services/sentiment_analysis.py` | `analyzeSentiment(String text)` returns the document sentiment score as a `double`, reconciling the controller's call to a method that did not exist with the real method's read of a field the schema did not have (A10, DL-036). `calculateDoubtRating(double)` transcribes `(1 - score) * 5` and the `[0, 10]` clamp with no arithmetic change (DL-036). The client is acquired on first use and released on shutdown, rejecting new work before it drains (DL-268, DL-288). The call is bounded on a 5 s per-attempt deadline, a 10 s deadline across the call, two attempts and `UNAVAILABLE` alone as retryable (DL-298) |
| `service/NotionService.java` | `services/notion_service.py` | `storeTweet(TweetDto)` (`:L14-26`), `getTweets(int, String)` (`:L32-38`) and `updateTweetResponse(String, String)`, which `tasks/response_generation.py:L30` called and the class did not declare (A12). Text is carried as one item per 2,000 characters and read back by concatenating every item (DL-090, DL-292). Only a retryable answer is retried — a 429, a 5xx status and a transport failure (DL-253). The property mapping is rebuilt against the components `dto/TweetDto` declares. A page identifier that reaches a request path must be a single path segment and is rejected otherwise (DL-303) |
| `service/LlmService.java` | `services/llm_service.py` | Chat Completions replaces `Completion.create(engine="text-davinci-002", …)` at `:L19-26` (D5, DL-032, DL-033). `max_tokens` 150 becomes `max_completion_tokens` 150, and `temperature` 0.7 and `n` 1 are carried unchanged (DL-034, DL-200, DL-202). The lower-case `openai_api_key` mismatch at `:L9` is resolved to `scanner.openai.api-key`; the prompt template at `:L16` is preserved and its `Context:` slot is populated from `ai_tools_mentioned` and `doubt_rating` (DL-035); the method returns the generated text as a `String` (DL-081). Every `U+0000` code point is removed from a reply at this boundary before the blank test, and only the count removed is recorded (DL-299) |
| `service/ResponseService.java` | *No source construct — net-new* — DL-076. **Occasioned by** the `ResponseService` import at `api/responses.py:L3` and the four call sites at `:L15,L26,L44,L60`, which fix every signature | `getPaginatedResponses(int, int)`, `getResponseById(String)`, `generateResponse(String)`, `updateResponse(String, UpdateResponseRequest)`, plus the two background entry points `generateResponseIfAbsent(String)` and `generateResponseIfAbsentFor(Tweet)`, declared as two distinctly named methods and not two overloads (DL-226). The page read carries the same lazily read window view above 500 rows (DL-297). A stored row is written with `is_approved` false and `generated_at` now, under a parent-row lock (DL-086, DL-195). No path publishes to X (IR7) |
| `service/SettingsService.java` | *No source construct — net-new* — DL-043. **Occasioned by** the `SettingsService` import at `api/settings.py:L3` and the two static invocations at `:L10,L20` | `getAllSettings()` and `updateSetting(String, String)` as instance methods on an injected bean (A11, DL-043), plus `seedDefaultSettings()`, which seeds exactly three rows insert-if-absent on `ApplicationReadyEvent` and never overwrites a key already present (DL-040, DL-159). Degenerate input is reported and does not escape as a framework exception (DL-073) |
| `service/AnalyticsService.java` | *No source construct — net-new* — DL-041, DL-042. **Occasioned by** the `AnalyticsService` import at `api/analytics.py:L3` and the two zero-argument call sites at `:L14,L24` | `getSummary()` returning the seven members of DL-041 and `getTrends()` returning the day-bucketed series of DL-042, both computed as JPA aggregates over the four existing tables. No column, index, materialised view or cache is added. The observation window is `scanner.analytics.trend-window-days`, read in UTC from the injected `Clock` (DL-278) |

### 2.6a Background tasks

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `task/TweetStreamClient.java` | `tasks/tweet_monitoring.py:L36-55` and the `pass`-stub `stream_tweets` at `services/twitter_service.py:L16-23` | A `SmartLifecycle` bean the context starts after refresh and stops on shutdown (DL-045, DL-220). `WebClient` over the X API v2 filtered stream replaces the retired v1.1 `statuses/filter` the tweepy call targeted; the app-only bearer token is exchanged at runtime from the consumer pair, so no new mandatory secret is introduced (DL-046). `composeRuleTerms()` composes the rule set and `boundedToRuleCap` caps it, so the empty `track` list of `:L53-55` becomes a set non-empty by construction (DL-044, DL-254, DL-257). Reconnection uses exponential backoff and honours `429` with `x-rate-limit-reset` (DL-045, DL-280). Each subscribed cycle carries a generation number, and a cycle's terminal callback writes shared state only while its own generation is current (DL-290). New intake is refused once a stop is requested, before a record is parsed or counted (DL-259). Record dispatch is bounded in flight at a concurrency of 4 over a prefetch of 1 rather than serialised (DL-258) |
| `task/TweetStreamListener.java` | `tasks/tweet_monitoring.py:L8-34` | Replaces a class that never subclassed a tweepy listener, so `on_status` was never invoked (`:L8-11`). It validates every member a stored column declares required before a row is written (DL-223) and persists through `repository/TweetRepository.save`, closing the deferred-work tag at `:L29` (D1). It calls `service/ResponseService.generateResponseIfAbsentFor(Tweet)`, closing the tag at `:L32`. The mirror write reports at two levels and its failure is not propagated (DL-194, DL-224). A refusal the repository raises is contained: one `WARN` names the refusal type and the number of content characters, the record is skipped and the records behind it continue (DL-302). The entity field names used in construction at `:L22-28` are corrected |
| `task/ResponseGenerationScheduler.java` | `tasks/response_generation.py:L35-50` | `@Scheduled(fixedDelayString = "${scanner.response-generation-delay-seconds}", timeUnit = TimeUnit.SECONDS)` replaces `while True` plus `time.sleep`, closing both defects on the final line — the missing `time` import and the wrong property name (D2, DL-047). The interval is a fixed **delay**, measured from the completion of one pass to the start of the next, which is the work-then-sleep order of `:L41-50` (IR10). `Tweet.query.filter` at `:L43` becomes `repository/TweetRepository.findUnansweredBatchAfter` (A8, DL-248); `Tweet.get` at `:L16` and `response.save()` at `:L25-26` become repository calls (A9); Celery's `.delay()` at `:L47` becomes a direct in-process service call. A pass attempts at most `scanner.background.max-candidates-per-pass` candidates (DL-282) and runs only in a process whose `scanner.background` switches both hold (DL-250). A candidate that fails on three consecutive passes is passed over on later passes, with at most 1,000 identifiers held in memory and nothing recorded in any column (DL-300), and a pass in flight abandons its remainder on `ContextClosedEvent` (DL-301) |

### 2.7 Tests

Nineteen classes carrying **1681** cases, all passing. The counts below were read from
`target/surefire-reports`. This is the only place the per-class counts are stated.

| Target file | Source construct | Cases | Notes |
|-------------|------------------|-------|-------|
| `ScannerApplicationTests.java` | `tests/test_api.py` — the import the file could not perform | 47 | Context-load smoke plus the composition assertions: one `DataSource` built from the translated URL, no `spring.datasource` key read, the bounded graceful shutdown of DL-294, and the reachable stream-client behaviours of DL-259, DL-290 and DL-291 |
| `api/TweetControllerTest.java` | `tests/test_api.py` | 58 | All three routes, the 1 and 10 defaults, the 404 literal, and the non-numeric path identifier answering 404 (DL-048). A page whose rows hold empty nullable columns is answered with JSON null (DL-080), and a page above the window bound is written from the lazily read view (DL-297) |
| `api/ResponseControllerTest.java` | `tests/test_api.py` | 117 | All four routes and every status code and literal, including the two-outcome contract of DL-076. A row holding an empty `is_approved` or `tweet_id` is answered with JSON null and stays updatable (DL-080), and content far longer than a width bound is forwarded whole (DL-068) |
| `api/SettingControllerTest.java` | `tests/test_api.py:L36-44` | 59 | The array shape of DL-039, both 400 and 404 literals, and the discounted trailing-slash expectation |
| `api/AnalyticsControllerTest.java` | `tests/test_api.py:L47-59` | 14 | Both zero-argument routes; asserts `total_tweets` and `total_responses` by name (DL-041) |
| `api/AuthControllerTest.java` | *No source construct — net-new* — DL-019 | 110 | `POST /auth/token` happy path, the empty 401 of DL-078, the `sub` claim of DL-079 and the 4096-byte body bound of DL-118 |
| `api/GlobalExceptionHandlerTest.java` | `main.py:L31-37` | 120 | Both error envelopes byte for byte, every translated exception type, and the framework answers this advice leaves alone (DL-092, DL-181) |
| `service/SentimentAnalysisServiceTest.java` | `tests/test_services.py:L58-65` | 64 | Doubt-rating boundaries at scores −1, 1, 0, −0.5 and 0.5, the out-of-range clamp at −2 and 2, the `NaN` parity value of 10.0 (DL-036) and the four bounds of the call settings (DL-298) |
| `service/TwitterServiceTest.java` | `tests/test_services.py:L12-22` | 139 | Replaces the two `pass` stubs. Popularity gate at 99, 100 and 101 against the default of 100, the settings-row precedence of DL-255, and every window of a page read as a lazily read view bounded at 500 rows (DL-297) |
| `service/NotionServiceTest.java` | `tests/test_services.py:L28-38` | 138 | The delivered POST query, POST create and PATCH update; the 2,000-character item split and its concatenating inverse at eight lengths from 1 to 12,345 (DL-292); the retry classification of DL-253; and the single-path-segment page identifier of DL-303 |
| `service/LlmServiceTest.java` | `tests/test_services.py:L44-48` | 187 | Asserts against the delivered Chat Completions call; the source's `generate_text` does not exist. Includes the `U+0000` removal of DL-299 |
| `service/ResponseServiceTest.java` | *No source construct — net-new* — DL-076, DL-211 | 142 | The four route-facing operations, the two background entry points, the parent-row lock guard of DL-195, and every window of a page read as a lazily read view bounded at 500 rows (DL-297) |
| `service/SettingsServiceTest.java` | *No source construct — net-new* — DL-043 | 66 | The two route-facing operations, the three-row seed of DL-040, the insert-only path of DL-159 and the requested key order of DL-039 |
| `service/AnalyticsServiceTest.java` | *No source construct — net-new* — DL-041, DL-042 | 42 | The seven summary members, the day-bucketed series, and the null aggregate of DL-075 |
| `task/ResponseGenerationSchedulerTest.java` | `tests/test_tasks.py` | 42 | The `@Scheduled` fixed-delay declaration, the per-pass candidate ceiling of DL-282, the ownership gate of DL-250, the per-candidate set-aside of DL-300 and the abandonment of a pass in flight on context close (DL-301) |
| `task/TweetStreamListenerTest.java` | `tests/test_tasks.py` | 47 | Required-member validation (DL-223), the persist path closing D1, the two-level mirror reporting of DL-224, and the contained persistence refusal of DL-302 |
| `repository/JpaMappingIntegrationTest.java` | `db/models.py` | 51 | `@DataJpaTest`: the four table names, all twenty column names, the association ordering, the ten wide character columns and the bounded primary key, and the vendor-independent invariants that no generated character type is a large-object type and every generated primary-key column is indexable (DL-068, DL-069, DL-166) |
| `security/JwtServiceTest.java` | `core/security.py:L6-12` | 101 | Mint-and-parse round trip, the expiry offset, the raw-UTF-8 secret contract of DL-186 and the fail-fast floor of DL-185 |
| `config/DatabaseUrlTranslatorTest.java` | *No source construct — net-new* — DL-027, DL-064, DL-071, DL-072, DL-187, DL-304 | 137 | All five accepted schemes, the `+driver` suffix strip, the `jdbc:` pass-through, credential extraction, the fail-fast on an unrecognised scheme and on an empty value, a registered driver for every translated URL, and the server-product refusal of DL-304 |

### 2.8 Documented constructs not introduced

| Documented construct | Where documented | Delivered treatment |
|----------------------|------------------|---------------------|
| A `User` entity with `id`, `handle`, `followerCount`, `lastTweetDate` | `documentation/Technical Specifications.md`, §DATABASE DESIGN | Not introduced. That shape describes a Twitter account and not an application principal, and the credential store is configuration-backed with no table (DL-020). The schema stays the four tables of §1.3 |
| An `/api` route prefix and a `perPage` query parameter | `documentation/Technical Specifications.md:L386-387`; `frontend/src/services/api.ts:L25` | Not introduced. Routes stay unprefixed and the parameter stays `per_page` (DL-217, A15) |
| camelCase wire member names | `frontend/src/schema/*.ts` | Not introduced. The wire stays `snake_case` (DL-022, A15) |
| `POST /api/tweets/{tweetId}/generate-response` | `frontend/src/services/api.ts:L43` | Not introduced. `POST /responses` carries response generation (A15) |
| `POST /api/notion/sync` and `GET /api/notion/data` | `frontend/src/services/notionService.ts:L16,L37` | Not introduced. The Notion adapter exposes no HTTP surface (A23) |
| A message broker or queue | `tasks/response_generation.py:L8` — a Celery application with no broker | Not introduced. `@Scheduled` runs the pass in process (DL-047) |
| A migration tool | absence throughout the retired tree | Not introduced. `ddl-auto: update` bootstraps the schema (A13, DL-026) |

### 2.9 Operations files edited outside `backend/`

| Target file | Lines changed | Change |
|-------------|---------------|--------|
| `infrastructure/docker/Dockerfile.backend` | `L2`, `L8-11`, `L14`, `L20`, `L22-28` | Multi-stage build on `maven:3.9-eclipse-temurin-21` with an `eclipse-temurin:21-jre` runtime. The `requirements.txt` copy and `pip install` are removed (A4); `COPY ./backend .` becomes a `pom.xml` copy, a `dependency:go-offline` step and a `src` copy; `CMD ["python","app.py"]` becomes `java -jar` on the Boot jar. `EXPOSE 5000` is unchanged (IR2). The assistance banner at `L22-28` is removed. The file is 31 lines, the last two recording that it declares no `ARG`, `ENV`, `USER`, `ENTRYPOINT` or `HEALTHCHECK` (DL-056, DL-307) |
| `.github/workflows/ci.yml` | `L16-19`, `L26-29`, `L35-39`, `L47-50`, `L56-59` | `actions/setup-java@v4` with Temurin 21 and Maven caching replaces `actions/setup-python@v2` with 3.9; the `pip install` step is removed; `flake8` and `mypy` are removed, superseded by Maven compilation (DL-057); `mvn -B clean verify` with `working-directory: ./backend` replaces `pytest backend/tests` and `python -m build`. Every frontend step is byte-identical to the retired file |
| `.github/workflows/cd.yml` | `L36` only | `-f infrastructure/docker/Dockerfile.backend` added so a Dockerfile is found; the `./backend` context is unchanged (A5, DL-056). One line differs from the retired file |
| `scripts/deploy.sh` | `L18` only | `npm run build` inside `cd backend` becomes the Maven package command (DL-053). One line differs from the retired file |

`infrastructure/terraform/**` is unchanged: the Cloud Run container spec declares no container port and
the module's only port declaration is a permissive firewall rule, so preserving port 5000 triggers no
edit. `scripts/setup_environment.sh` is unchanged, and its reference to `requirements.txt` at `:L12`
remains (DL-054). `infrastructure/docker/Dockerfile.frontend` and every file under `frontend/**` are
unchanged.

---

## 3. Targets not delivered at this checkpoint

**None.** Every target named in §1 and §2 exists on disk, and the pending-target list is empty. Every
status value a row carries is one of the three the legend defines.

## 4. Coverage summary

Each count below is the measured value for the delivered tree.

| Coverage set | Required | Delivered | Where |
|--------------|----------|-----------|-------|
| Retired Python files | 20 | 20 rows, all `Retired` | §1.1 |
| HTTP routes | 11 ported | 11 ported plus 1 net-new = 12 mapped | §1.2 |
| Tables | 4 | 4 | §1.3 |
| Columns | 20 | 20 | §1.3 |
| Associations | 1 | 1 | §1.3 |
| Business rules | 2 | 2, transcribed | §1.4 |
| Source configuration keys | 15 — 7 declared, 8 read without declaration | 15 declared and bound | §1.5 |
| Delivered configuration keys | — | 49: 39 under `scanner.`, 10 framework | §1.5.1 |
| Seeded `settings` rows | 3 | 3 | §1.5.2 |
| External integrations | 4 | 4 adapters | §1.6 |
| Retired PyPI packages | 15 | 15 rows | §1.7 |
| Named defects | D1–D6 | 6 rows, all `Delivered` | §1.8 |
| Additional defects found while reading the retired tree | — | A1–A32: A1–A14 `Delivered`, A15–A32 `Retained by decision` | §1.8 |
| Scaffolding markers | 23 — 20 banners, 3 deferred-work tags | 23 rows; 0 of either token remain under `backend/` | §1.9 |
| Python test files | 3 | 3 rows, mapped onto 19 JUnit classes | §1.10 |
| Source expectations | 3 | 1 honoured, 2 discounted | §1.11 |
| Delivered main classes | 58 | 58 rows | §2.2–§2.6a |
| Delivered test classes | 19 | 19 rows, 1681 cases | §2.7 |
| Delivered non-Java artifacts | 8 | 8 rows | §2.1 |
| Delivered artifacts, total | 85 | 85 rows | §2.1–§2.7 |
| Operations files edited | 4 | 4 rows | §2.9 |
| Targets marked *No source construct — net-new* | the 15 AAP §0.7.3 enumerates | 22 rows carry the marker — the 15 enumerated, the 2 ignore files and the 5 net-new test classes — each with an authorising identifier | §2.1–§2.7 |
| Targets not delivered | 0 | 0 | §3 |
