# Traceability Matrix

This matrix maps the retired Python/Flask backend onto the delivered Java/Spring Boot backend in
both directions. Section 1 reads source → target: every construct that existed in
`backend/app/**` and `backend/tests/**` is listed with the Java construct that carries it forward.
Section 2 reads target → source: every delivered file under `backend/` is listed with the source
construct it derives from, or is marked as net-new with the decision that authorises it.

**Relationship to the decision log.** This file records *what maps to what*. `docs/DECISION_LOG.md`
records *why*, and it is the only place reasoning lives. Where a row names a `DL-` identifier, that
identifier has a complete row in the log; no reasoning is duplicated here.

**Delivery state.** This matrix describes the tree as delivered, not as planned. Six main classes and
ten test classes named by the Agent Action Plan are outside this checkpoint's processing boundary and
are not present on disk. Every source construct they would carry is still listed below, with its
target named and its status recorded as `PLANNED`, so a reader can tell a mapping that exists from a
mapping that is scheduled. Section 3 collects those pending targets in one place. No row in this file
names a file that does not exist without saying so.

**Status values.**

| Value | Meaning |
|-------|---------|
| `Delivered` | The named target exists on disk and carries the source construct. |
| `Partly delivered` | Some named targets exist and at least one does not; the row says which. |
| `PLANNED` | The named target does not exist at this checkpoint. The source construct is unimplemented in Java so far. |
| `Retired` | The source construct is deliberately not carried forward; the row says where the decision is recorded. |

---

## 1. Source → target

### 1.1 Retired Python files

All twenty files under `backend/app/**` and `backend/tests/**` were deleted in commit `80f1d53d`
(DL-060). Each has a row.

| # | Source file | Java target(s) | Status |
|---|-------------|----------------|--------|
| 1 | `backend/app/main.py` | `ScannerApplication` (composition root, replacing the `create_app()` factory and the duplicate module-level `Flask` object at `:L13`), `config/CorsConfig` (`CORS(app)` at `:L20`), `security/SecurityConfig` (`JWTManager(app)` at `:L22`), `api/GlobalExceptionHandler` (the 404 and 500 handlers at `:L31-37`), `config/AsyncSchedulingConfig` (`@EnableScheduling`, replacing `initialize_background_tasks()` at `:L41-48`) | Delivered |
| 2 | `backend/app/core/config.py` | `config/ScannerProperties` plus `src/main/resources/application.yml`. The per-call `get_settings()` factory at `:L17-18` becomes one injected singleton (DL-031); the `.env` convention at `:L13-15` becomes environment-variable binding | Delivered |
| 3 | `backend/app/core/security.py` | `security/JwtService` (`create_access_token` at `:L6-12` → jjwt HS256, DL-014/DL-017/DL-018) and the `BCryptPasswordEncoder` bean in `security/SecurityConfig` (the passlib context at `:L14-18`, DL-020). The unused `decode` import at `:L1` is retired | Delivered |
| 4 | `backend/app/db/database.py` | `config/DataSourceConfig` (replacing the per-call `create_engine`/`sessionmaker` at `:L5-13`) plus `repository/TweetRepository`, `repository/ResponseRepository`, `repository/AiToolRepository`, `repository/SettingRepository` | Delivered |
| 5 | `backend/app/db/models.py` | `entity/Tweet`, `entity/Response`, `entity/AiTool`, `entity/Setting`, and `util/DelimitedStringListConverter` for the two delimited columns (DL-024) | Delivered |
| 6 | `backend/app/schema/tweet.py` | `dto/TweetDto` — the nine components at `:L5-14`, snake_case member names, string identifier (DL-022/DL-023) | Delivered |
| 7 | `backend/app/schema/response.py` | `dto/ResponseDto` — the five components at `:L4-9`, with the non-null `id` invariant of DL-167 | Delivered |
| 8 | `backend/app/api/tweets.py` | `api/TweetController` (three routes) — **PLANNED**. Delivered already: `dto/PaginatedTweetsDto` and `dto/PaginationDto` (the envelope at `:L18-21`, DL-038), `dto/AnalysisResultDto` (`:L52-55`), `service/TwitterService.getPaginatedTweets`/`getTweet`/`updateTweetAnalysis` (the three methods the handlers call at `:L16,L27,L50`), `service/mapper/TweetMapper` (the `to_dict()` at `:L19,L30` that the source never defined) | Partly delivered |
| 9 | `backend/app/api/responses.py` | `api/ResponseController` (four routes) — **PLANNED**. Delivered already: `dto/PaginatedResponsesDto` (`:L17-20`), `dto/CreateResponseRequest` (`:L38-41`), `dto/UpdateResponseRequest` (`:L54`), `service/ResponseService` (all four methods the handlers call), `service/mapper/ResponseMapper` (the `to_dict()` at `:L18,L29,L47,L63`), `exception/ResponseGenerationException` (the 500 literal at `:L49`) | Partly delivered |
| 10 | `backend/app/api/settings.py` | `api/SettingController.getSettings` and `.updateSetting`, `dto/SettingDto` (DL-039), `dto/UpdateSettingRequest`, `service/SettingsService`, `service/mapper/SettingMapper`. The statically-invoked `SettingsService.get_all_settings()` at `:L10` becomes an instance call on an injected bean (DL-043) | Delivered |
| 11 | `backend/app/api/analytics.py` | `api/AnalyticsController` (two routes) — **PLANNED**. Delivered already: `service/AnalyticsService.getTrends`/`getSummary` (both zero-argument, matching `:L14,L24`), `dto/TrendsDto` (`:L13-15`, DL-042), `dto/SummaryDto` (`:L23-25`, DL-041) | Partly delivered |
| 12 | `backend/app/services/twitter_service.py` | `service/TwitterService` — the four methods the class lacked, plus `meetsPopularityThreshold` correcting the `tweet.likes` field error at `:L48`. The `pass`-stub `stream_tweets` at `:L16-23` maps to `task/TweetStreamClient` — **PLANNED** | Partly delivered |
| 13 | `backend/app/services/sentiment_analysis.py` | `service/SentimentAnalysisService.analyzeSentiment(String)` (`:L14-24`, DL-036) and `.calculateDoubtRating(double)` (`:L26-35`, DL-062) | Delivered |
| 14 | `backend/app/services/notion_service.py` | `service/NotionService.storeTweet` (`:L14-26`), `.getTweets` (`:L32-38`) and `.updateTweetResponse` (the method `response_generation.py:L30` called but the class did not have), plus `config/RestClientConfig` replacing `Client(auth=…)` at `:L8` (DL-013) | Delivered |
| 15 | `backend/app/services/llm_service.py` | `service/LlmService.generateResponse` — Chat Completions replacing `Completion.create(engine="text-davinci-002", …)` at `:L19-26` (DL-032/DL-033), the three call literals at `:L22-25` preserved as configuration defaults, the prompt template at `:L16` preserved verbatim (DL-035) | Delivered |
| 16 | `backend/app/tasks/tweet_monitoring.py` | `task/TweetStreamClient` and `task/TweetStreamListener` — **PLANNED** (DL-044/DL-045/DL-046). Delivered already: `config/WebClientConfig`, the `WebClient` bean replacing the tweepy `Stream` construction at `:L45-51` | Partly delivered |
| 17 | `backend/app/tasks/response_generation.py` | `task/ResponseGenerationScheduler` — **PLANNED** (DL-047). Delivered already: `config/AsyncSchedulingConfig` carrying `@EnableScheduling` and the task scheduler that replaces the broker-less Celery application at `:L8` | Partly delivered |
| 18 | `backend/tests/test_api.py` | `api/GlobalExceptionHandlerTest` (delivered, covering the two error envelopes at `main.py:L31-37`). `api/TweetControllerTest`, `api/ResponseControllerTest`, `api/SettingControllerTest`, `api/AnalyticsControllerTest`, `api/AuthControllerTest` and `ScannerApplicationTests` — **PLANNED**. The `fastapi.testclient` import at `:L2` is retired outright | Partly delivered |
| 19 | `backend/tests/test_services.py` | `service/SentimentAnalysisServiceTest`, `service/NotionServiceTest`, `service/LlmServiceTest`, `service/SettingsServiceTest` and `service/SettingsServiceSeedingIntegrationTest` (delivered). `service/TwitterServiceTest`, `service/ResponseServiceTest`, `service/AnalyticsServiceTest` — **PLANNED**. The wrong-package-root imports at `:L3-6` and the two `pass` stubs at `:L12-22` are retired | Partly delivered |
| 20 | `backend/tests/test_tasks.py` | `task/ResponseGenerationSchedulerTest` and `task/TweetStreamListenerTest` — **PLANNED**. The import at `:L3` names neither a module nor symbols that exist, so nothing carries forward from it | PLANNED |

### 1.2 HTTP routes

Eleven routes are preserved unprefixed with identical methods, paths, path-variable names,
query-parameter names, defaults and status codes (AAP G1). A twelfth route is net-new (DL-019). Path
variables are declared `String` on every route so a non-numeric segment yields 404 rather than 400
(DL-048).

| # | Source route | Source handler | Java target | Service method | Status |
|---|--------------|----------------|-------------|----------------|--------|
| 1 | `GET /tweets` (`page` default 1, `per_page` default 10) | `tweets.py:L9-21 get_tweets` | `api/TweetController.getTweets` | `service/TwitterService.getPaginatedTweets(int, int)` | PLANNED (controller); service Delivered |
| 2 | `GET /tweets/<tweet_id>` — 200, or 404 `{"error": "Tweet not found"}` | `tweets.py:L23-32 get_tweet` | `api/TweetController.getTweet` | `service/TwitterService.getTweet(String)` | PLANNED (controller); service Delivered |
| 3 | `POST /tweets/<tweet_id>/analyze` — 200 `{"tweet_id", "analysis_result"}` | `tweets.py:L36-55 analyze_tweet` | `api/TweetController.analyzeTweet` | `service/SentimentAnalysisService.analyzeSentiment(String)` then `service/TwitterService.updateTweetAnalysis(String, double)` (DL-037) | PLANNED (controller); services Delivered |
| 4 | `GET /responses` (`page` default 1, `per_page` default 10) | `responses.py:L8-20 get_responses` | `api/ResponseController.getResponses` | `service/ResponseService.getPaginatedResponses(int, int)` | PLANNED (controller); service Delivered |
| 5 | `GET /responses/<response_id>` — 200, or 404 `{"error": "Response not found"}` | `responses.py:L22-31 get_response` | `api/ResponseController.getResponse` | `service/ResponseService.getResponseById(String)` | PLANNED (controller); service Delivered |
| 6 | `POST /responses` — 400 `{"error": "Tweet ID is required"}`, 201, 500 `{"error": "Failed to generate response"}` | `responses.py:L33-49 generate_response` | `api/ResponseController.generateResponse` | `service/ResponseService.generateResponse(String)` (DL-076/DL-168) | PLANNED (controller); service Delivered |
| 7 | `PUT /responses/<response_id>` — 400 `{"error": "Update data is required"}`, 200, 404 `{"error": "Response not found or update failed"}` | `responses.py:L51-65 update_response` | `api/ResponseController.updateResponse` | `service/ResponseService.updateResponse(String, UpdateResponseRequest)` | PLANNED (controller); service Delivered |
| 8 | `GET /settings` — 200, array of `{key, value, description}` | `settings.py:L7-11 get_settings` | `api/SettingController.getSettings` (`@GetMapping("/settings")`) | `service/SettingsService.getAllSettings()` (DL-039) | Delivered |
| 9 | `PUT /settings/<key>` — 400 `{"error": "No value provided"}`, 404 `{"error": "Setting not found"}`, 200 | `settings.py:L13-24 update_setting` | `api/SettingController.updateSetting` (`@PutMapping("/settings/{key}")`) | `service/SettingsService.updateSetting(String, String)` | Delivered |
| 10 | `GET /analytics/trends` — 200 | `analytics.py:L7-15 get_trends` | `api/AnalyticsController.getTrends` | `service/AnalyticsService.getTrends()` (DL-042) | PLANNED (controller); service Delivered |
| 11 | `GET /analytics/summary` — 200 | `analytics.py:L17-25 get_summary` | `api/AnalyticsController.getSummary` | `service/AnalyticsService.getSummary()` (DL-041) | PLANNED (controller); service Delivered |
| 12 | *No source construct — net-new*: `POST /auth/token`, the only unauthenticated route | — (no `/login`, `/token` or `/auth` blueprint is registered at `main.py:L26-29`, and `create_access_token` has no call site) | `api/AuthController.issueToken` (`@PostMapping("/auth/token")`) | `security/JwtService.createAccessToken` (DL-019/DL-078/DL-079) | Delivered |

### 1.3 Tables, columns and the association

Four tables, twenty columns, one association — no table, column, index or constraint is added
(AAP G2). `ai_tools_mentioned` stays a plain character column and is never a foreign key.

| # | Source table | Source column and type | Java field | Java mapping | Status |
|---|--------------|------------------------|------------|--------------|--------|
| 1 | `tweets` | `id Integer primary_key` (`models.py:L10`) | `entity/Tweet.id` `Long` | `@Id @GeneratedValue(IDENTITY) @Column(name = "id")` (DL-049) | Delivered |
| 2 | `tweets` | `content String` (`:L11`) | `entity/Tweet.content` `String` | `@Column(name = "content", length = Integer.MAX_VALUE)` (DL-068) | Delivered |
| 3 | `tweets` | `like_count Integer` (`:L12`) | `entity/Tweet.likeCount` `Integer` | `@Column(name = "like_count")` | Delivered |
| 4 | `tweets` | `created_at DateTime` (`:L13`) | `entity/Tweet.createdAt` `LocalDateTime` | `@Column(name = "created_at")` | Delivered |
| 5 | `tweets` | `doubt_rating Float` (`:L14`) | `entity/Tweet.doubtRating` `Double` | `@Column(name = "doubt_rating")` | Delivered |
| 6 | `tweets` | `media String` (`:L15`) | `entity/Tweet.media` `List<String>` | `@Convert(DelimitedStringListConverter) @Column(name = "media", length = Integer.MAX_VALUE)` (DL-024/DL-068) | Delivered |
| 7 | `tweets` | `quoted_tweet_id String` (`:L16`) | `entity/Tweet.quotedTweetId` `String` | `@Column(name = "quoted_tweet_id", length = Integer.MAX_VALUE)` | Delivered |
| 8 | `tweets` | `user_id String` (`:L17`) | `entity/Tweet.userId` `String` | `@Column(name = "user_id", length = Integer.MAX_VALUE)` | Delivered |
| 9 | `tweets` | `ai_tools_mentioned String` (`:L18`) | `entity/Tweet.aiToolsMentioned` `List<String>` | `@Convert(DelimitedStringListConverter) @Column(name = "ai_tools_mentioned", length = Integer.MAX_VALUE)` — no foreign key to `ai_tools` | Delivered |
| 10 | `responses` | `id Integer primary_key` (`:L23`) | `entity/Response.id` `Long` | `@Id @GeneratedValue(IDENTITY) @Column(name = "id")` | Delivered |
| 11 | `responses` | `content String` (`:L24`) | `entity/Response.content` `String` | `@Column(name = "content", length = Integer.MAX_VALUE)` (DL-068) | Delivered |
| 12 | `responses` | `generated_at DateTime` (`:L25`) | `entity/Response.generatedAt` `LocalDateTime` | `@Column(name = "generated_at")` (DL-077) | Delivered |
| 13 | `responses` | `is_approved Boolean` (`:L26`) | `entity/Response.isApproved` `Boolean` | `@Column(name = "is_approved")` — a flag a human reads, never a trigger | Delivered |
| 14 | `responses` | `tweet_id Integer ForeignKey('tweets.id')` (`:L27`) | `entity/Response.tweet` `Tweet` | `@ManyToOne @JoinColumn(name = "tweet_id")` — the column and the `:L28` relationship are mapped by this one association | Delivered |
| 15 | `ai_tools` | `id Integer primary_key` (`:L35`) | `entity/AiTool.id` `Integer` | `@Id @GeneratedValue(IDENTITY) @Column(name = "id")` (DL-070) | Delivered |
| 16 | `ai_tools` | `name String` (`:L36`) | `entity/AiTool.name` `String` | `@Column(name = "name", length = Integer.MAX_VALUE)` (DL-068) | Delivered |
| 17 | `ai_tools` | `description String` (`:L37`) | `entity/AiTool.description` `String` | `@Column(name = "description", length = Integer.MAX_VALUE)` (DL-068) | Delivered |
| 18 | `settings` | `key String primary_key` (`:L42`) | `entity/Setting.key` `String` | `@Id @Column(name = "\"key\"", length = 255)` — quoted reserved word (DL-061), bounded by the documented exception (DL-069) | Delivered |
| 19 | `settings` | `value String` (`:L43`) | `entity/Setting.value` `String` | `@Column(name = "\"value\"", length = Integer.MAX_VALUE)` (DL-061/DL-068) | Delivered |
| 20 | `settings` | `description String` (`:L44`) | `entity/Setting.description` `String` | `@Column(name = "description", length = Integer.MAX_VALUE)` (DL-068) | Delivered |

| Source association | Java target | Status |
|--------------------|-------------|--------|
| `Tweet.responses = relationship("Response", order_by=Response.id, back_populates="tweet")` (`models.py:L30`) and its inverse `Response.tweet` (`:L28`) — the only association in the schema | `entity/Tweet.responses` as `@OneToMany(mappedBy = "tweet") @OrderBy("id ASC")`, with `entity/Response.tweet` as `@ManyToOne @JoinColumn(name = "tweet_id")`. Table names, column name and ordering are asserted against live JDBC metadata by `repository/JpaMappingIntegrationTest` | Delivered |

### 1.4 Business rules

Both rules are transcribed, not adjusted (AAP G3).

| # | Source rule | Source location | Java target | Status |
|---|-------------|-----------------|-------------|--------|
| 1 | Doubt rating: `(1 - sentiment_score) * 5`, then clamped to `[0, 10]` | `sentiment_analysis.py:L29,L32` | `service/SentimentAnalysisService.calculateDoubtRating(double)` — `Math.max(0.0d, Math.min(10.0d, (1 - s) * 5))`, preceded by the explicit `Double.isNaN` branch returning `10.0` (DL-062). Vectors at −1 → 10.0, 0 → 5.0, 1 → 0.0, −0.5 → 7.5, 0.5 → 2.5, and out-of-range −2 → 10.0, 2 → 0.0, plus NaN and both infinities, are asserted by `service/SentimentAnalysisServiceTest` | Delivered |
| 2 | Popularity gate: `like_count >= TWEET_POPULARITY_THRESHOLD`, default 100 | `twitter_service.py:L48` (reading the wrong field name `tweet.likes`) and `core/config.py:L10` | `service/TwitterService.meetsPopularityThreshold(Integer)` reading `scanner.popularity-threshold`, with the field-name error corrected to `likeCount`. The 99/100/101 boundary is asserted by `service/TwitterServiceTest` — **PLANNED** | Delivered (rule); PLANNED (its dedicated test) |

### 1.5 Configuration keys

Fifteen keys: the seven `Settings` declared at `core/config.py:L5-11`, and the eight that code paths
read without any declaration existing — the drift set closed by AAP G6. Every one is declared in
`src/main/resources/application.yml` and bound through `config/ScannerProperties`.

| # | Environment key | Declared in source? | Source reference | `application.yml` property | Default | Status |
|---|-----------------|---------------------|------------------|----------------------------|---------|--------|
| 1 | `TWITTER_API_KEY` | Yes | `core/config.py:L5` | `scanner.twitter.api-key` | empty | Delivered |
| 2 | `TWITTER_API_SECRET` | Yes | `core/config.py:L6` | `scanner.twitter.api-secret` | empty | Delivered |
| 3 | `NOTION_API_KEY` | Yes | `core/config.py:L7` | `scanner.notion.api-key` | empty | Delivered |
| 4 | `OPENAI_API_KEY` | Yes | `core/config.py:L8` | `scanner.openai.api-key` | empty | Delivered |
| 5 | `DATABASE_URL` | Yes | `core/config.py:L9` | `scanner.database-url` — deliberately not `spring.datasource.url`, because a SQLAlchemy URL is not a JDBC URL (DL-027) | none; required | Delivered |
| 6 | `TWEET_POPULARITY_THRESHOLD` | Yes | `core/config.py:L10` | `scanner.popularity-threshold` | `100` | Delivered |
| 7 | `RESPONSE_GENERATION_DELAY` | Yes | `core/config.py:L11` | `scanner.response-generation-delay-seconds` | `60` | Delivered |
| 8 | `SECRET_KEY` | **No — drift** | read by `core/security.py:L11` | `scanner.jwt.secret` | none; fail fast (DL-016) | Delivered |
| 9 | `ALGORITHM` | **No — drift** | read by `core/security.py:L11` | `scanner.jwt.algorithm` | `HS256` (DL-015) | Delivered |
| 10 | `NOTION_DATABASE_ID` | **No — drift** | read by `services/notion_service.py:L25` | `scanner.notion.database-id` | empty | Delivered |
| 11 | `TWITTER_API_SECRET_KEY` | **No — drift** | read by `services/twitter_service.py:L12` | `scanner.twitter.api-secret-key`, nested-default alias onto `TWITTER_API_SECRET` (DL-031) | falls through to `TWITTER_API_SECRET` | Delivered |
| 12 | `TWITTER_CONSUMER_KEY` | **No — drift** | read by `tasks/tweet_monitoring.py:L46` | `scanner.twitter.consumer-key`, alias onto `TWITTER_API_KEY` (DL-031) | falls through to `TWITTER_API_KEY` | Delivered |
| 13 | `TWITTER_CONSUMER_SECRET` | **No — drift** | read by `tasks/tweet_monitoring.py:L47` | `scanner.twitter.consumer-secret`, alias onto `TWITTER_API_SECRET` (DL-031) | falls through to `TWITTER_API_SECRET` | Delivered |
| 14 | `TWITTER_ACCESS_TOKEN` | **No — drift** | read by `tasks/tweet_monitoring.py:L48` | `scanner.twitter.access-token` | empty | Delivered |
| 15 | `TWITTER_ACCESS_TOKEN_SECRET` | **No — drift** | read by `tasks/tweet_monitoring.py:L49` | `scanner.twitter.access-token-secret` | empty | Delivered |

Three further properties have no environment key in the source because the behaviour they configure
did not exist there, and each is authorised by a decision rather than by a source line:
`scanner.auth.username` / `scanner.auth.password-hash` (DL-020), `scanner.analytics.trend-window-days`
(DL-042), and `scanner.ingestion.stream-base-keywords` (DL-044). `server.port` is pinned to
`${PORT:5000}` (DL-029) and `spring.main.web-application-type` to `servlet` (DL-030). No property key
exists for Google Cloud Natural Language, because `LanguageServiceClient()` uses Application Default
Credentials exactly as `services/sentiment_analysis.py:L8` did.

### 1.6 External integrations

Four external systems, one adapter bean each; no SDK type crosses an adapter boundary (AAP G4).

| # | External system | Source client | Source location | Java adapter | Java client choice | Status |
|---|-----------------|---------------|-----------------|--------------|--------------------|--------|
| 1 | Google Cloud Natural Language | `LanguageServiceClient` (Application Default Credentials) | `services/sentiment_analysis.py:L1,L8` | `service/SentimentAnalysisService` | `com.google.cloud:google-cloud-language` 2.96.0 (DL-010) | Delivered |
| 2 | OpenAI | `from openai import Completion`, the removed 0.x API | `services/llm_service.py:L1,L19-26` | `service/LlmService` | `com.openai:openai-java` 4.49.0 over Chat Completions (DL-011/DL-032) | Delivered |
| 3 | Notion | `notion_client.Client` | `services/notion_service.py:L1,L8` | `service/NotionService` with `config/RestClientConfig` | `org.springframework.web.client.RestClient`, no SDK (DL-013) | Delivered |
| 4 | X (Twitter) | tweepy `API`/`OAuthHandler` and `Stream(...).filter(track=[...])` against the retired v1.1 `statuses/filter` | `services/twitter_service.py:L1`, `tasks/tweet_monitoring.py:L1,L55` | `task/TweetStreamClient` — **PLANNED**; `config/WebClientConfig` delivered | `WebClient` against the X API v2 filtered stream, app-only bearer token (DL-012/DL-045/DL-046) | Partly delivered |

### 1.7 Retired PyPI packages

The Python backend had no dependency manifest of any kind, so this set was reconstructed from import
statements. Fifteen packages retire; seventeen Maven coordinates replace them, five explicitly pinned
and twelve BOM-managed.

| # | Retired package | Evidence of use | Java replacement | Status |
|---|-----------------|-----------------|------------------|--------|
| 1 | Flask | `main.py:L1`, `api/*.py:L1` | `spring-boot-starter-web` (DL-013) | Retired |
| 2 | Flask-Cors | `main.py:L2` | `CorsConfigurationSource` in `config/CorsConfig`, shipped with `starter-web` (DL-051) | Retired |
| 3 | Flask-JWT-Extended | `main.py:L3`, `api/*.py:L2` | `spring-boot-starter-security` filter chain (DL-014/DL-021) | Retired |
| 4 | PyJWT | `core/security.py:L1` | `io.jsonwebtoken` jjwt 0.13.0, three modules (DL-014) | Retired |
| 5 | passlib | `core/security.py:L3` | `BCryptPasswordEncoder`, shipped with `starter-security` (DL-020) | Retired |
| 6 | SQLAlchemy | `db/database.py:L1-2`, `db/models.py:L1-3` | `spring-boot-starter-data-jpa` with Hibernate 6.6.53.Final | Retired |
| 7 | pydantic | `core/config.py:L2`, `schema/*.py:L1` | `@ConfigurationProperties`, Java records, `spring-boot-starter-validation` (DL-050) | Retired |
| 8 | celery | `tasks/response_generation.py:L1` | `@EnableScheduling` / `@Scheduled` — no broker (DL-047) | Retired |
| 9 | tweepy | `services/twitter_service.py:L1`, `tasks/tweet_monitoring.py:L1` | `WebClient` from `spring-boot-starter-webflux` (DL-012) | Retired |
| 10 | google-cloud-language | `services/sentiment_analysis.py:L1` | `com.google.cloud:google-cloud-language` 2.96.0 (DL-010) | Retired |
| 11 | notion-client | `services/notion_service.py:L1` | `RestClient` from `spring-boot-starter-web` (DL-013) | Retired |
| 12 | openai | `services/llm_service.py:L1` | `com.openai:openai-java` 4.49.0 (DL-011) | Retired |
| 13 | pytest | `tests/test_api.py:L1`, `tests/test_tasks.py:L1` | JUnit Jupiter 5.12.2 from `spring-boot-starter-test` | Retired |
| 14 | unittest / unittest.mock | `tests/test_services.py:L1-2`, `tests/test_tasks.py:L2` | JUnit Jupiter plus Mockito 5.17.0 | Retired |
| 15 | fastapi | `tests/test_api.py:L2` — present only because of a mistaken `TestClient` import against a Flask app | None. `MockMvc` replaces it and the dependency disappears entirely | Retired |

Three Maven coordinates carry an explicit version above the value `spring-boot-dependencies:3.5.16`
manages, and each is a decision in its own right rather than an inherited value:
`org.postgresql:postgresql` 42.7.13 (DL-169), `com.mysql:mysql-connector-j` 26.7.0 (DL-170) and
`tomcat.version` 10.1.57 (DL-171). Both JDBC drivers ship at `runtime` scope in one artifact
(DL-028).


### 1.8 Defect closure

The six defects named by AAP G7, followed by every additional defect the analysis surfaced. A closed
defect is one whose Java target exists; where the target is `PLANNED` the defect is decided but not
yet closed in code, and the row says so.

| # | Defect | Source evidence | Java resolution | Status |
|---|--------|-----------------|-----------------|--------|
| D1 | Ingestion never persists anything: the listener builds a `Tweet` and abandons it, and the keyword set is empty so the stream could not start regardless | `tasks/tweet_monitoring.py:L29` (`# TODO: Add database session and commit tweet`), `:L53-55` (`keywords = []` then `stream.filter(track=keywords)`) | `task/TweetStreamListener` persisting through `repository/TweetRepository.save` inside a transaction, and `task/TweetStreamClient` composing a non-empty rule set from configured base terms union `ai_tools.name`, overridable by the `stream_keywords` row (DL-044) — **PLANNED** | PLANNED |
| D2 | The response scheduler raises `NameError` on its own final line: `time.sleep(...)` without importing `time`, and it reads `settings.response_generation_interval` where the declared property is `RESPONSE_GENERATION_DELAY` | `tasks/response_generation.py:L50`; `core/config.py:L11` | `task/ResponseGenerationScheduler` with `@Scheduled(fixedDelayString = "${scanner.response-generation-delay-seconds}", timeUnit = SECONDS)` (DL-047) — **PLANNED**. `config/AsyncSchedulingConfig` and the `scanner.response-generation-delay-seconds` property are delivered | PLANNED (the scheduler); Delivered (its configuration) |
| D3 | Eight configuration keys are read by code but declared nowhere, so any code path touching them raises `AttributeError` | `core/config.py:L5-11` declares seven; `core/security.py:L11`, `services/notion_service.py:L25`, `services/twitter_service.py:L12`, `tasks/tweet_monitoring.py:L46-49` read eight more | All fifteen declared in `application.yml` and bound through `config/ScannerProperties`; the three Twitter aliases resolve through nested defaults (DL-031). Full inventory in §1.5 | Delivered |
| D4 | Three service classes are imported by controllers and do not exist anywhere in the repository | `api/responses.py:L3` (`ResponseService`), `api/settings.py:L3` (`SettingsService`), `api/analytics.py:L3` (`AnalyticsService`) | `service/ResponseService`, `service/SettingsService`, `service/AnalyticsService` — every method signature dictated by the call site that already existed (DL-039 … DL-043, DL-073, DL-075, DL-076, DL-077) | Delivered |
| D5 | The language-model call targets `text-davinci-002` through the removed Completions API, and assigns `Completion.api_key` from a lower-case attribute the settings class does not declare | `services/llm_service.py:L9,L19-26` | `service/LlmService` over Chat Completions with the model identifier in configuration (DL-032/DL-033), `max_completion_tokens` replacing `max_tokens` (DL-034), and the key read from `scanner.openai.api-key` | Delivered |
| D6 | No token-issuance path exists: `create_access_token` has zero call sites, no auth route is registered, and every route's guard is a no-op | `core/security.py:L6-12`; `main.py:L26-29` | `api/AuthController.issueToken` on `POST /auth/token` with `security/JwtService`, `dto/LoginRequest` and `dto/TokenResponse`, over a configuration-backed principal (DL-019/DL-020) | Delivered |
| A1 | Every authorization guard is a no-op: `@jwt_required` is applied bare without parentheses, which in `flask-jwt-extended` 4.x registers the decorator factory rather than the guard | `api/tweets.py:L10`; `api/responses.py:L9,L23,L34,L52`; `api/settings.py:L8,L14`; `api/analytics.py:L8,L18` — all eleven routes | `security/SecurityConfig` requiring an authenticated principal on every mapped endpoint except `POST /auth/token`, enforced by `security/JwtAuthenticationFilter`. Java enforces where Python did not, which is an intentional behaviour change (DL-021) | Delivered |
| A2 | Background work is started synchronously and never returns: `initialize_background_tasks()` claims to run the stream "in a separate thread" but the tweepy call blocks, and the scheduler it calls next is a `while True` loop | `main.py:L41-48`; `tasks/response_generation.py:L41` | `ScannerApplication` carries `@SpringBootApplication` and `@ConfigurationPropertiesScan` only; scheduling is enabled declaratively by `config/AsyncSchedulingConfig`, and the stream is lifecycle-managed rather than started from the composition root. The two lifecycle components are **PLANNED** | Partly delivered |
| A3 | There is no `__init__.py` anywhere, so `backend/app/**` is not an importable Python package tree at all, compounding the wrong-package-root test imports | absence throughout `backend/app/**`; `tests/test_services.py:L3-6` | Maven standard directory layout with a declared package per directory. No equivalent construct is required, and the wrong-root imports have no counterpart to carry forward | Delivered |
| A4 | No dependency manifest exists, yet three files install from `requirements.txt` | absence of `backend/requirements.txt`; `infrastructure/docker/Dockerfile.backend:L8-11`, `.github/workflows/ci.yml:L26-29`, `scripts/setup_environment.sh:L12` | `backend/pom.xml`. The Dockerfile and the CI job are rewritten; `scripts/setup_environment.sh` is deliberately left untouched (DL-054) | Delivered |
| A5 | The CD job cannot build any image: `docker build … ./backend` finds no Dockerfile in that context, and never could | `.github/workflows/cd.yml:L36` | `-f infrastructure/docker/Dockerfile.backend` added, context unchanged (DL-056) | Delivered |
| A6 | Two dead imports: `from os import getenv`, never used, and `from jwt import encode, decode` where `decode` is never used | `core/config.py:L1`; `core/security.py:L1` | Neither is carried forward. `config/ScannerProperties` binds through the framework, and `security/JwtService` declares only what it calls | Delivered |
| A7 | `to_dict()` is called on entities four times and is never defined on any model | `api/tweets.py:L19,L30`; `api/responses.py:L18,L29,L47,L63`; `db/models.py` defines no such method | `service/mapper/TweetMapper`, `service/mapper/ResponseMapper` and `service/mapper/SettingMapper` | Delivered |
| A8 | The candidate-tweet query uses a `.query` attribute declarative models do not have and names a relationship that does not exist — `response` rather than `responses` | `tasks/response_generation.py:L43`; `db/models.py:L30` | `repository/TweetRepository.findByResponsesIsEmpty()` | Delivered |
| A9 | `Tweet.get(tweet_id)` and `response.save()` are called on declarative models, which expose neither | `tasks/response_generation.py:L16,L25-26` | `repository/TweetRepository.findById` and `repository/ResponseRepository.save`, called from `service/ResponseService`; the scheduler that also needs them is **PLANNED** | Partly delivered |
| A10 | The analyze path is broken on both sides in complementary ways: the controller calls `sentiment_analysis.analyze(tweet.content)`, which does not exist, while the real `analyze_sentiment(tweet)` reads `tweet.text`, a field the schema does not have | `api/tweets.py:L46`; `services/sentiment_analysis.py:L14`; `schema/tweet.py:L5-14` | `service/SentimentAnalysisService.analyzeSentiment(String text)` returning a `double`, invoked with the post's content (DL-036/DL-037) | Delivered |
| A11 | `SettingsService.get_all_settings()` and `.update_setting(...)` are invoked statically on a class that does not exist | `api/settings.py:L10,L20` | Instance methods on the injected `service/SettingsService` bean (DL-043) | Delivered |
| A12 | `notion_service.update_tweet_response(...)` is called but the class has no such method, and the property mapping builds Notion fields from tweet attributes that mostly do not exist | `tasks/response_generation.py:L30`; `services/notion_service.py:L14-20` | `service/NotionService.updateTweetResponse(String, String)` and a property mapping rebuilt against the components `dto/TweetDto` actually declares | Delivered |
| A13 | Nothing ever creates the schema: there is no `Base.metadata.create_all()`, no migrations directory and no CLI, so the application cannot serve a request against a fresh database | `db/database.py:L1-13`; absence of any migration path | `spring.jpa.hibernate.ddl-auto: update` (DL-026), with `create-drop` against H2 under the test profile | Delivered |
| A14 | A new engine and session factory are created on every call, with no pooling, no closing and no transaction management | `db/database.py:L5-13` | One pooled `DataSource` from `config/DataSourceConfig`, HikariCP, Spring Data repositories and `@Transactional` boundaries at the service methods | Delivered |
| A15 | The client sends camelCase member names and an `/api` prefix that the backend never served, imports an `authService` module that does not exist, and calls a `generate-response` endpoint that does not exist | `frontend/src/schema/*.ts`, `frontend/src/services/api.ts:L25,L43`, `frontend/src/utils/api.ts:L13-16` | Deliberately not reconciled on either side; the backend keeps snake_case and unprefixed routes (DL-022/DL-059) | Retired |

### 1.9 Scaffolding markers

Twenty `HUMAN ASSISTANCE NEEDED` markers and three `TODO` markers existed in the Python tree; the
list below was read back from commit `80f1d53d^` rather than transcribed. The Java tree under
`backend/src/**`, together with `backend/pom.xml`, contains none of either, and the
`HUMAN ASSISTANCE NEEDED` block at `infrastructure/docker/Dockerfile.backend:L22-28` is removed. The
marker names appear in this file and nowhere else under `backend/docs/**`, and only inside the
inventory cells below, where naming the retired marker is the row's whole purpose — a grep for either
name under `backend/` therefore returns these rows and nothing that marks unfinished work.

| # | Marker | Source location | Resolution | Status |
|---|--------|-----------------|------------|--------|
| 1 | HUMAN ASSISTANCE NEEDED | `api/analytics.py:L10` | `service/AnalyticsService.getTrends()` implemented; metric set decided in DL-042 | Delivered |
| 2 | HUMAN ASSISTANCE NEEDED | `api/analytics.py:L20` | `service/AnalyticsService.getSummary()` implemented; metric set decided in DL-041 | Delivered |
| 3 | HUMAN ASSISTANCE NEEDED | `api/responses.py:L36` | `service/ResponseService.generateResponse(String)` implemented, with the two-outcome contract of DL-076 | Delivered |
| 4 | HUMAN ASSISTANCE NEEDED | `api/tweets.py:L34` | `service/TwitterService.updateTweetAnalysis(String, double)` implemented; the analyze contract is decided in DL-037 | Delivered |
| 5 | HUMAN ASSISTANCE NEEDED | `main.py:L41` | Replaced by declarative scheduling in `config/AsyncSchedulingConfig`; the blocking composition-root call is gone (A2) | Delivered |
| 6 | HUMAN ASSISTANCE NEEDED | `services/llm_service.py:L11` | Client construction deferred to first use in `service/LlmService.openAiClient()`; no static SDK state is written | Delivered |
| 7 | HUMAN ASSISTANCE NEEDED | `services/llm_service.py:L34` | `service/LlmService.generateResponse` returns a complete `GeneratedResponse` (DL-167) instead of the source's partial dictionary | Delivered |
| 8 | HUMAN ASSISTANCE NEEDED | `services/notion_service.py:L10` | `config/RestClientConfig` supplies a configured `RestClient`; `scanner.notion.database-id` is a declared property | Delivered |
| 9 | HUMAN ASSISTANCE NEEDED | `services/notion_service.py:L30` | `service/NotionService.getTweets(int, String)` implemented, including default page size and cursor omission | Delivered |
| 10 | HUMAN ASSISTANCE NEEDED | `services/sentiment_analysis.py:L10` | Client acquisition and release implemented in `service/SentimentAnalysisService`, with Application Default Credentials retained | Delivered |
| 11 | HUMAN ASSISTANCE NEEDED | `services/twitter_service.py:L16` | The `pass`-stub `stream_tweets` becomes `task/TweetStreamClient` — **PLANNED** (DL-045) | PLANNED |
| 12 | HUMAN ASSISTANCE NEEDED | `tasks/response_generation.py:L12` | `task/ResponseGenerationScheduler` — **PLANNED** (DL-047) | PLANNED |
| 13 | HUMAN ASSISTANCE NEEDED | `tasks/response_generation.py:L36` | `task/ResponseGenerationScheduler` — **PLANNED** (DL-047) | PLANNED |
| 14 | HUMAN ASSISTANCE NEEDED | `tasks/tweet_monitoring.py:L13` | `task/TweetStreamListener` — **PLANNED** | PLANNED |
| 15 | TODO: Add database session and commit tweet | `tasks/tweet_monitoring.py:L29` | `task/TweetStreamListener` persisting through `repository/TweetRepository.save` — **PLANNED** (D1) | PLANNED |
| 16 | TODO: Implement response generation logic | `tasks/tweet_monitoring.py:L32` | `task/TweetStreamListener` calling `service/ResponseService.generateResponse`, which is delivered — **PLANNED** | PLANNED |
| 17 | HUMAN ASSISTANCE NEEDED | `tasks/tweet_monitoring.py:L36` | `task/TweetStreamClient` — **PLANNED** (DL-045/DL-046) | PLANNED |
| 18 | TODO: Define keywords for streaming | `tasks/tweet_monitoring.py:L53` | Decided in DL-044: configured base terms union every `ai_tools.name`, overridable by the `stream_keywords` setting row. `scanner.ingestion.stream-base-keywords` and the seeded row are delivered; the composing client is **PLANNED** | Partly delivered |
| 19 | HUMAN ASSISTANCE NEEDED | `tests/test_api.py:L53` | The date-range probe it flagged is deliberately not honoured; the trend window is a configured property instead (DL-042) | Delivered |
| 20 | HUMAN ASSISTANCE NEEDED | `tests/test_services.py:L13` | Replaced by real assertions in `service/TwitterServiceTest` — **PLANNED** | PLANNED |
| 21 | HUMAN ASSISTANCE NEEDED | `tests/test_services.py:L19` | Replaced by real assertions in `service/TwitterServiceTest` — **PLANNED** | PLANNED |
| 22 | HUMAN ASSISTANCE NEEDED | `tests/test_services.py:L50` | Replaced by `service/LlmServiceTest`, which asserts against the delivered Chat Completions call rather than the absent `generate_text` | Delivered |
| 23 | HUMAN ASSISTANCE NEEDED | `tests/test_tasks.py:L53` | Replaced by `task/ResponseGenerationSchedulerTest` and `task/TweetStreamListenerTest` — **PLANNED** | PLANNED |

### 1.10 Python test files

Three files, 183 lines, none of which can import. The Agent Action Plan names nineteen JUnit classes
as their replacement; nine are delivered.

| Source test file | Source defect | JUnit replacements | Status |
|------------------|---------------|--------------------|--------|
| `backend/tests/test_api.py` | Imports `fastapi.testclient.TestClient` at `:L2` and points it at a Flask application; `test_get_settings` at `:L37-39` requests `/settings/` with a trailing slash and expects a key-to-value map; `test_update_settings` at `:L41-44` `PUT`s to `/settings/`, which is not a registered route; `:L50-51` names `total_tweets` and `total_responses`, which is the one piece of usable evidence in the file | Delivered: `api/GlobalExceptionHandlerTest` (30 cases over both error envelopes). PLANNED: `api/TweetControllerTest`, `api/ResponseControllerTest`, `api/SettingControllerTest`, `api/AnalyticsControllerTest`, `api/AuthControllerTest`, `ScannerApplicationTests`. The two settings expectations are deliberately discounted (DL-039) and the summary key names are honoured (DL-041) | Partly delivered |
| `backend/tests/test_services.py` | Wrong package root at `:L3-6` (`from services.…`); two `pass` stubs at `:L12-22`; tests for `create_page`, `update_page` and `generate_text` at `:L28-38,L44-48`, none of which exist; asserts sentiment analysis returns `'positive'`/`'negative'`/`'neutral'` at `:L58-65` where the implementation returns a float | Delivered: `service/SentimentAnalysisServiceTest` (52 cases), `service/NotionServiceTest` (85), `service/LlmServiceTest` (70), `service/SettingsServiceTest` (63), `service/SettingsServiceSeedingIntegrationTest` (9). PLANNED: `service/TwitterServiceTest`, `service/ResponseServiceTest`, `service/AnalyticsServiceTest` | Partly delivered |
| `backend/tests/test_tasks.py` | `from backend.tasks import monitor_tweets, generate_response` at `:L3` — neither the module path nor either symbol exists | PLANNED: `task/ResponseGenerationSchedulerTest`, `task/TweetStreamListenerTest` | PLANNED |

Four delivered test classes descend from none of the three retired test files, because the Python suite
tested none of what they cover. Two of the four have no source construct of any kind and are net-new:
`config/DatabaseUrlTranslatorTest` (108 cases, DL-027/DL-064/DL-071/DL-072) and
`service/SettingsServiceSeedingIntegrationTest` (9 cases, DL-040). The other two test production
constructs that do have a source origin, recorded in §2.7 rather than here:
`security/JwtServiceTest` (57 cases) covers `core/security.py:L6-12`, and
`repository/JpaMappingIntegrationTest` (30 cases) covers `db/models.py`.


---

## 2. Target → source

Every file delivered under `backend/`, plus the four operations files edited outside it. A target with
no source construct is marked *net-new* and carries the decision-log identifier that authorises it, so
an absent source reads as a documented state rather than a gap.

Net-new comes in two shapes and both are marked. *No source construct — net-new* means nothing in the
Python tree corresponds to the target at all. *Net-new class, derived from …* means the Python tree
called for the construct but never contained it — an import of a class that does not exist, a method
invoked on a class that does not declare it, or a configuration value read with no code to interpret
it. The second shape names what called for it, because that call site is what fixed the target's
signature; it is still net-new code, not a port.

### 2.1 Build, configuration and documentation

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `backend/pom.xml` | *No source construct — net-new* — DL-002/DL-003/DL-004 | No Python manifest ever existed; the absence is defect A4. Version overrides are DL-169, DL-170 and DL-171 |
| `backend/.gitignore` | *No source construct — net-new* — DL-055 | `target/` only |
| `backend/.dockerignore` | *No source construct — net-new* — DL-055 | `target/` only |
| `backend/src/main/resources/application.yml` | `backend/app/core/config.py` (all seven declared keys) plus the eight keys read without declaration, plus the `.env` convention at `:L13-15` | Full key inventory in §1.5. `server.port` DL-029, web type DL-030, `ddl-auto` DL-026, reserved-word quoting DL-061 |
| `backend/src/test/resources/application-test.yml` | *No source construct — net-new* — DL-009/DL-016/DL-026/DL-061 | No Python test configuration existed. H2 with `create-drop`, reserved-word quoting, and a test JWT secret so `mvn clean verify` needs no manual step |
| `backend/docs/DECISION_LOG.md` | *No source construct — net-new* — required by Rule 1 | Eighty-four entries, `DL-001` … `DL-171` |
| `backend/docs/TRACEABILITY_MATRIX.md` | *No source construct — net-new* — required by Rule 1 | This file |

### 2.2 Application core, configuration and security

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `ScannerApplication.java` | `main.py:L13-39` — the `create_app()` factory and the duplicate module-level `Flask` object | `@SpringBootApplication` plus `@ConfigurationPropertiesScan`; one application context replaces two application objects |
| `config/ScannerProperties.java` | `core/config.py:L4-15` — the single `Settings` class | Nested twitter/notion/openai/jwt/auth/analytics/ingestion groups; credential components are never rendered |
| `config/DataSourceConfig.java` | `db/database.py:L5-13` — the per-call `create_engine` and `sessionmaker` | One pooled `DataSource` built from the translated URL (A14) |
| `config/DatabaseUrlTranslator.java` | *Net-new class, derived from* `core/config.py:L9` — the opaque `DATABASE_URL`, which the source read but never interpreted | SQLAlchemy-style URL to JDBC URL plus separated credentials (DL-027/DL-064/DL-071/DL-072); the dialect is never hardcoded |
| `config/CorsConfig.java` | `main.py:L20` — `CORS(app)` with no arguments | Permissive `CorsConfigurationSource`, deliberately unchanged (DL-051) |
| `config/WebClientConfig.java` | `tasks/tweet_monitoring.py:L45-51` — the tweepy `Stream` construction | `WebClient` bean for the X API v2 base URL (DL-012) |
| `config/RestClientConfig.java` | `services/notion_service.py:L8` — `Client(auth=…)` | `RestClient` bean carrying the Notion base URL, version header and bearer token (DL-013) |
| `config/AsyncSchedulingConfig.java` | `main.py:L41-48` and `tasks/response_generation.py:L8` — the blocking initialiser and the broker-less Celery application | `@EnableScheduling` plus a task scheduler; no broker and no queue (DL-047) |
| `security/SecurityConfig.java` | `main.py:L22` (`JWTManager(app)`), `core/security.py:L14-18` (the passlib context) and the eleven bare `@jwt_required` sites | One `SecurityFilterChain`, a `BCryptPasswordEncoder` bean and a configuration-backed `InMemoryUserDetailsManager` (DL-020/DL-021) |
| `security/JwtService.java` | `core/security.py:L6-12` — `create_access_token` | jjwt HS256, `exp = now + TTL`, `sub`-only claims (DL-014 … DL-018) |
| `security/JwtAuthenticationFilter.java` | The `@jwt_required` decorator sites — a guard that enforced nothing | A `OncePerRequestFilter` that actually validates the bearer token (A1, DL-021) |

### 2.3 API and DTOs

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `api/SettingController.java` | `api/settings.py:L7-24` — both routes | Paths, methods, status codes and all four wire literals preserved |
| `api/AuthController.java` | *No source construct — net-new* — DL-019 | `POST /auth/token`, the only unauthenticated route; 401 handling is DL-078, the `sub` claim is DL-079 |
| `api/GlobalExceptionHandler.java` | `main.py:L31-37` — `@app.errorhandler(404)` and `(500)` | `{"error": "Not found"}` and `{"error": "Internal server error"}` reproduced exactly; per-route literals routed through the three exception types (DL-065/DL-066) |
| `dto/TweetDto.java` | `schema/tweet.py:L5-14` | Nine components, snake_case names, string identifier, arrays for the two delimited columns (DL-022/DL-023/DL-024) |
| `dto/ResponseDto.java` | `schema/response.py:L4-9` | Five components; `id` and `tweet_id` serialise as strings; non-null `id` invariant (DL-167) |
| `dto/SettingDto.java` | `db/models.py:L40-44` | `{key, value, description}` — the shape that carries all three columns (DL-039) |
| `dto/AiToolDto.java` | `db/models.py:L33-37` | `{id, name, description}` |
| `dto/PaginatedTweetsDto.java` | `api/tweets.py:L18-21` | The `{"tweets": [...], "pagination": {...}}` envelope |
| `dto/PaginatedResponsesDto.java` | `api/responses.py:L17-20` | The `{"responses": [...], "pagination": {...}}` envelope |
| `dto/PaginationDto.java` | `api/tweets.py:L20` — the envelope key, whose inner keys the source never defined | `page`, `per_page`, `total`, `total_pages`, with the 1-based-to-0-based conversion (DL-038) |
| `dto/AnalysisResultDto.java` | `api/tweets.py:L52-55` | `{"tweet_id", "analysis_result"}`; the result is the sentiment score (DL-037) |
| `dto/CreateResponseRequest.java` | `api/responses.py:L38-41` | `{tweet_id}` with `@NotNull` — one of the only two Bean Validation constraints in the tree (DL-050) |
| `dto/UpdateResponseRequest.java` | `api/responses.py:L54` — the free-form `request.json` | Partial update of `content` and/or `is_approved` only |
| `dto/UpdateSettingRequest.java` | `api/settings.py:L16-18` | `{value}` with `@NotNull` — the second of the two constraints (DL-050) |
| `dto/TrendsDto.java` | `api/analytics.py:L13-15` | Day-bucketed series over a configured window (DL-042) |
| `dto/SummaryDto.java` | `api/analytics.py:L23-25`, with two key names evidenced at `tests/test_api.py:L50-51` | Seven aggregate metrics (DL-041/DL-075) |
| `dto/LoginRequest.java` | *No source construct — net-new* — DL-019 | `{username, password}`; both components render as redacted (DL-067) |
| `dto/TokenResponse.java` | *No source construct — net-new* — DL-019 | `{access_token, token_type, expires_in}` |
| `dto/ErrorResponse.java` | `main.py:L31-37` | Single `{error}` component matching every error body in the source |

### 2.4 Entities, repositories, mappers and utilities

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `entity/Tweet.java` | `db/models.py:L8-18` plus the relationship attached at `:L30` | `@Table(name = "tweets")`, nine columns, `@OneToMany` with `@OrderBy("id ASC")` |
| `entity/Response.java` | `db/models.py:L21-28` | `@Table(name = "responses")`, five columns, `@ManyToOne @JoinColumn(name = "tweet_id")` (DL-025) |
| `entity/AiTool.java` | `db/models.py:L32-37` | `@Table(name = "ai_tools")`, three columns, no association (DL-070) |
| `entity/Setting.java` | `db/models.py:L39-44` | `@Table(name = "settings")`, `key` as `@Id`; both reserved-word columns quoted (DL-061/DL-069) |
| `repository/TweetRepository.java` | `db/database.py:L10-13`, plus the broken candidate query at `tasks/response_generation.py:L43` | `JpaRepository<Tweet, Long>`, `findByResponsesIsEmpty()` and the analytics aggregates (A8, DL-075) |
| `repository/ResponseRepository.java` | `db/database.py:L10-13` | `JpaRepository<Response, Long>` with `countByIsApprovedTrue()` |
| `repository/AiToolRepository.java` | `db/database.py:L10-13` | `JpaRepository<AiTool, Integer>`; supplies tool names to the keyword set (DL-044) |
| `repository/SettingRepository.java` | `db/database.py:L10-13` | `JpaRepository<Setting, String>` — the key is the primary key |
| `service/mapper/TweetMapper.java` | *Net-new class, derived from* the `to_dict()` called at `api/tweets.py:L19,L30` and never defined | Identifier-to-string and delimited-column-to-array conversion (A7, DL-023/DL-024) |
| `service/mapper/ResponseMapper.java` | *Net-new class, derived from* the `to_dict()` called at `api/responses.py:L18,L29,L47,L63` and never defined | Same, for `responses` (A7) |
| `service/mapper/SettingMapper.java` | *Net-new class, derived from* the serialisation `api/settings.py:L11,L24` performed inline and never factored out | Entity to `SettingDto` (DL-039) |
| `util/DelimitedStringListConverter.java` | *Net-new class, derived from* `db/models.py:L15,L18` against `schema/tweet.py:L11,L14` — single `String` columns the schema exposed as `List[str]` with no conversion anywhere | Comma-delimited, blank-safe, no schema change (DL-024) |

### 2.5 Exceptions

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `exception/NotFoundException.java` | The 404 branches across `api/*.py` — `'Tweet not found'`, `'Response not found'`, `'Response not found or update failed'`, `'Setting not found'` | Private constructor with a static factory per literal, so the wire message set is closed (DL-065/DL-066) |
| `exception/BadRequestException.java` | The 400 branches across `api/*.py` — `'Tweet ID is required'`, `'Update data is required'`, `'No value provided'` | Same closed-set treatment (DL-065/DL-066) |
| `exception/ResponseGenerationException.java` | `api/responses.py:L49` — the 500 body `{"error": "Failed to generate response"}` | One literal, one outcome (DL-065/DL-076/DL-168) |

### 2.6 Services

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `service/TwitterService.java` | `services/twitter_service.py`, plus the four methods the controllers called that the class did not declare | Pageable listing, single fetch, analysis update and the popularity gate; the `tweet.likes` field error at `:L48` corrected |
| `service/SentimentAnalysisService.java` | `services/sentiment_analysis.py:L14-35` | `analyzeSentiment(String)` and the transcribed doubt-rating formula with an explicit NaN branch (DL-036/DL-037/DL-062) |
| `service/NotionService.java` | `services/notion_service.py:L14-38`, plus the absent `update_tweet_response` | Mirroring only; the relational database stays the system of record (A12, DL-013) |
| `service/LlmService.java` | `services/llm_service.py:L16-32` | Chat Completions, the three call literals preserved, `GeneratedResponse` as the internal result (DL-032 … DL-035, DL-167, DL-168) |
| `service/ResponseService.java` | *Net-new class, derived from* the four call sites at `api/responses.py:L15,L26,L44,L60`; the class was imported at `:L3` and never existed (D4) | Signatures dictated by the call sites; never publishes to X (DL-076/DL-077/DL-168) |
| `service/SettingsService.java` | *Net-new class, derived from* the two call sites at `api/settings.py:L10,L20`; the class was imported at `:L3` and never existed (D4) | Instance methods on an injected bean, plus idempotent seeding (DL-039/DL-040/DL-043/DL-073) |
| `service/AnalyticsService.java` | *Net-new class, derived from* the two zero-argument call sites at `api/analytics.py:L14,L24`; the class was imported at `:L3` and never existed (D4) | Aggregates over the four existing tables only; no new column, index or cache (DL-041/DL-042/DL-075) |

### 2.7 Tests

| Target file | Source construct | Cases | Notes |
|-------------|------------------|-------|-------|
| `api/GlobalExceptionHandlerTest.java` | `main.py:L31-37` and `tests/test_api.py` | 30 | Both error envelopes byte-for-byte, plus every per-route literal |
| `config/DatabaseUrlTranslatorTest.java` | *No source construct — net-new* — DL-027/DL-064/DL-071/DL-072 | 108 | 49 methods: translation, credential extraction and rejection, six look-alike properties, ports, schemes, redaction |
| `repository/JpaMappingIntegrationTest.java` | `db/models.py` | 30 | `@DataJpaTest` over table names, column names, physical JDBC metadata, unbounded round trips and association ordering (DL-061/DL-068/DL-069) |
| `security/JwtServiceTest.java` | `core/security.py:L6-12` | 57 | Mint/parse round trip, expiry offset, algorithm matrix and HMAC key-length boundary (DL-014 … DL-018) |
| `service/LlmServiceTest.java` | `tests/test_services.py:L44-48` | 70 | The delivered Chat Completions call, the generation-failure matrix and lazy-client behaviour (DL-167/DL-168) |
| `service/NotionServiceTest.java` | `tests/test_services.py:L28-38` | 85 | All three operations plus transport failures, unconfigured database id, null bodies and cursor handling |
| `service/SentimentAnalysisServiceTest.java` | `tests/test_services.py:L58-65` | 52 | Finite and clamping vectors, NaN and both infinities, null input, propagated client failure and client lifecycle |
| `service/SettingsServiceTest.java` | `tests/test_api.py:L36-44` and `tests/test_services.py` | 63 | Both operations through the real `SettingMapper`, plus the seeding-normalisation matrix |
| `service/SettingsServiceSeedingIntegrationTest.java` | *No source construct — net-new* — DL-040 | 9 | A real Boot context over H2 publishing a real `ApplicationReadyEvent`; idempotence and no-overwrite |

### 2.8 Operations files edited outside `backend/`

Line ranges name the *original* lines that changed, so each row reads as a source-to-target edit.

| Target file | Original lines changed | Source construct | Notes |
|-------------|------------------------|------------------|-------|
| `infrastructure/docker/Dockerfile.backend` | `L2`, `L8-11`, `L14`, `L20`, `L22-28` | The Python image, the `pip install` from a manifest that never existed, `CMD ["python","app.py"]` against a file that never existed, and the `HUMAN ASSISTANCE NEEDED` block | Multi-stage `maven:3.9-eclipse-temurin-21` build plus an `eclipse-temurin:21-jre` runtime running the Boot jar. `EXPOSE 5000` unchanged (DL-005/DL-029) |
| `.github/workflows/ci.yml` | `L16-19`, `L26-29`, `L35-39`, `L47-50`, `L56-59` | `setup-python` 3.9, `pip install -r backend/requirements.txt`, `flake8`, `mypy`, `pytest`, `python -m build` | JDK 21 with Maven caching plus `mvn -B clean verify`. Frontend steps untouched, including the absent lint scripts (DL-057/DL-059/DL-074) |
| `.github/workflows/cd.yml` | `L36` only | `docker build -t $BACKEND_IMAGE ./backend`, which found no Dockerfile | `-f infrastructure/docker/Dockerfile.backend` added, context unchanged (A5, DL-056) |
| `scripts/deploy.sh` | `L18` only | `npm run build` executed inside `cd backend` | `mvn clean package` (DL-004/DL-053) |

`infrastructure/terraform/**`, `scripts/setup_environment.sh` (DL-054), `infrastructure/docker/Dockerfile.frontend`, `frontend/**` (DL-059), `documentation/**` and `README.md` are unchanged.


---

## 3. Targets not delivered at this checkpoint

Collected here so that every `PLANNED` status above resolves to one place. Each is named by the Agent
Action Plan, each carries source constructs listed in section 1, and none exists on disk. This section
is the inverse of section 2: it lists targets that section 2 cannot yet contain.

| # | Planned target | Source construct it will carry | Rows above that depend on it |
|---|----------------|--------------------------------|------------------------------|
| 1 | `api/TweetController.java` | `api/tweets.py:L9-55` — three routes | §1.1 #8, §1.2 #1–3 |
| 2 | `api/ResponseController.java` | `api/responses.py:L8-65` — four routes | §1.1 #9, §1.2 #4–7 |
| 3 | `api/AnalyticsController.java` | `api/analytics.py:L7-25` — two routes | §1.1 #11, §1.2 #10–11 |
| 4 | `task/TweetStreamClient.java` | `tasks/tweet_monitoring.py:L36-55` and `services/twitter_service.py:L16-23` | §1.1 #12/#16, §1.6 #4, §1.8 D1, §1.9 #11/#17/#18 |
| 5 | `task/TweetStreamListener.java` | `tasks/tweet_monitoring.py:L8-34` | §1.1 #16, §1.8 D1/A2, §1.9 #14/#15/#16 |
| 6 | `task/ResponseGenerationScheduler.java` | `tasks/response_generation.py:L35-50` | §1.1 #17, §1.8 D2/A2/A9, §1.9 #12/#13 |
| 7 | `api/TweetControllerTest.java` | `tests/test_api.py` | §1.1 #18, §1.10 |
| 8 | `api/ResponseControllerTest.java` | `tests/test_api.py` | §1.1 #18, §1.10 |
| 9 | `api/SettingControllerTest.java` | `tests/test_api.py:L36-44` | §1.1 #18, §1.10 |
| 10 | `api/AnalyticsControllerTest.java` | `tests/test_api.py:L47-59` | §1.1 #18, §1.10 |
| 11 | `api/AuthControllerTest.java` | *net-new* — DL-019 | §1.1 #18, §1.10 |
| 12 | `ScannerApplicationTests.java` | `tests/test_api.py` — context-load smoke | §1.1 #18, §1.10 |
| 13 | `service/TwitterServiceTest.java` | `tests/test_services.py:L12-22` | §1.4 #2, §1.9 #20/#21, §1.10 |
| 14 | `service/ResponseServiceTest.java` | *net-new* | §1.1 #19, §1.10 |
| 15 | `service/AnalyticsServiceTest.java` | *net-new* | §1.1 #19, §1.10 |
| 16 | `task/ResponseGenerationSchedulerTest.java` | `tests/test_tasks.py` | §1.1 #20, §1.9 #23, §1.10 |
| 17 | `task/TweetStreamListenerTest.java` | `tests/test_tasks.py` | §1.1 #20, §1.9 #23, §1.10 |

Two consequences follow and are recorded so they are not mistaken for defects in what is delivered.
The three undelivered controllers mean the eight routes they carry return 404 at runtime even though
every service method behind them is delivered and tested; `GET /settings`, `PUT /settings/{key}` and
`POST /auth/token` are served end to end. The three undelivered task classes mean no ingestion runs
and no scheduled generation fires, so the `tweets` table is populated only through the routes.

---

## 4. Coverage summary

| Coverage set | Required | Covered by a row | Delivered | Planned |
|--------------|----------|------------------|-----------|---------|
| Retired Python files (§1.1) | 20 | 20 | 11 fully, 8 partly | 1 |
| HTTP routes (§1.2) | 11 preserved + 1 net-new | 12 | 3 end to end | 9 controllers; every service behind them delivered |
| Tables (§1.3) | 4 | 4 | 4 | 0 |
| Columns (§1.3) | 20 | 20 | 20 | 0 |
| Associations (§1.3) | 1 | 1 | 1 | 0 |
| Business rules (§1.4) | 2 | 2 | 2 | 0 |
| Configuration keys (§1.5) | 15 | 15 | 15 | 0 |
| External integrations (§1.6) | 4 | 4 | 3 fully, 1 partly | 0 |
| Retired PyPI packages (§1.7) | 15 | 15 | 15 | 0 |
| Named defects D1–D6 (§1.8) | 6 | 6 | 4 | 2 |
| Additional defects A1–A15 (§1.8) | 15 | 15 | 12 fully, 2 partly, 1 retired by decision | 0 |
| Scaffolding markers (§1.9) | 23 | 23 | 12 fully, 1 partly | 10 |
| Python test files (§1.10) | 3 | 3 | 2 partly | 1 |
| Delivered main Java classes (§2.2–§2.6) | 52 | 52 | 52 | — |
| Delivered test Java classes (§2.7) | 9 | 9 | 9 | — |
| Delivered resources and build files (§2.1) | 7 | 7 | 7 | — |
| Operations files edited (§2.8) | 4 | 4 | 4 | — |
| Planned targets (§3) | 17 | 17 | 0 | 17 |

Every construct in every required coverage set has a row. Sixty-eight files under `backend/` and four
operations files outside it are mapped back to a source construct or marked net-new with the decision
that authorises them. Seventeen planned targets are named rather than omitted, so the difference
between "mapped" and "delivered" is visible in every row rather than inferred.

Marker count check: `backend/src/**` and `backend/pom.xml` contain zero `HUMAN ASSISTANCE NEEDED` and
zero `TODO` markers, against twenty and three respectively in the retired Python tree (§1.9). The only
occurrences anywhere under `backend/` are the inventory cells of §1.9, which name the retired markers
in order to account for them.
