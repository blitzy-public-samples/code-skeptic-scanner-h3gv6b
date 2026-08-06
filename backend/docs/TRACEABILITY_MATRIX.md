# Traceability Matrix

This matrix maps the retired Python/Flask backend onto the delivered Java/Spring Boot backend in
both directions. Section 1 reads source → target: every construct that existed in
`backend/app/**` and `backend/tests/**` is listed with the Java construct that carries it forward.
Section 2 reads target → source: every delivered file under `backend/` is listed with the source
construct it derives from, or is marked as net-new with the decision that authorises it.

**Relationship to the decision log.** This file records *what maps to what*. `docs/DECISION_LOG.md`
records *why*, and it is the only place reasoning lives. Where a row names a `DL-` identifier, that
identifier has a complete row in the log — `DL-001` … `DL-289`, one unbroken sequence (289 rows) — and no
reasoning is duplicated here.

**Delivery state.** This matrix describes the tree as delivered. Every count below was obtained by
enumerating the working tree, not carried over from any planning document, and every source line number
was read back from the retired files at commit `80f1d53d^`. The delivered tree holds **sixty-eight**
files under `backend/src/main/java` and **forty-six** under `backend/src/test/java` — one hundred and
fourteen Java classes — together with `backend/pom.xml`, `backend/.gitignore`, `backend/.dockerignore`,
`backend/src/main/resources/application.yml`, `backend/src/test/resources/application-test.yml` and the
two files in `backend/docs`, for **one hundred and twenty-one** delivered artifacts. Section 2 carries one
row for each of them, and §4 restates the counts as an auditable table. The per-class case counts live in
§2.7 and nowhere else, so each is stated once and measured once.

Eleven main classes have no counterpart of any kind in the retired tree and are authorised
individually: `api/AuthController` (DL-019), `dto/LoginRequest` and `dto/TokenResponse` (DL-019),
`config/DataSourcePoolProperties` (DL-270/DL-271), `config/ClockConfig` (DL-278),
`config/BuildProfileGuard` (DL-279), `config/RequestMediaTypeConfig` (DL-236),
`config/ContainerErrorResponseConfig` (DL-237/DL-238), `task/BackgroundOwnership` (DL-281),
`util/ConfiguredValues` (DL-287) and `util/LogSafe` (DL-119). Each of the eleven carries a
*No source construct — net-new* row in §2, and §2 carries no twelfth. Four further main classes are net-new — `config/DatabaseUrlTranslator`,
`util/DelimitedStringListConverter`, `util/QueryParameters` and `util/StreamRuleTerms` — and each names
in §2.2 and §2.4 the source construct it derives from. One further
class, `api/ErrorDispatchController`, was delivered by an earlier revision and has since been deleted
together with its test, and a nested `ErrorEnvelopeController` inside `api/GlobalExceptionHandler` was a
second, later form of the same construct and is likewise gone: DL-183 serves the servlet `ERROR` envelope
from an `ErrorAttributes` bean inside that handler while Spring Boot's own `BasicErrorController` stays
mapped, so `api/` holds exactly six classes, no type in the tree implements `ErrorController`, no nested
type of the handler is request-mapped, and that same handler is the one declaration from which the
container-level error valve reads its status and literal (DL-237). The
twenty-seven test classes beyond the frozen test inventory of nineteen are admitted by DL-096 and
enumerated by DL-216, so what is present is forty-six files under `backend/src/test/java`, which is
the plan's nineteen plus the twenty-seven that inventory could not name. Twenty-four of the forty-six
delivered test classes carry a row naming a source construct, and the remaining twenty-two have no source
construct of any kind.
No row in this file carries the `PLANNED` status, and no row names a file that does not exist on disk.
Section 3 records that the pending-target list is empty.

**Status values.**

| Value | Meaning |
|-------|---------|
| `Delivered` | The named target exists on disk and carries the source construct. |
| `Partly delivered` | Some named targets exist and at least one does not; the row says which. |
| `PLANNED` | The named target does not exist at this checkpoint. **No row carries this status any longer** — every named target is delivered; the value is retained so the legend still explains the vocabulary earlier revisions of this file used. |
| `Retired` | The source construct is not carried forward; the row names the decision that records the disposition. |

---

## 1. Source → target

### 1.1 Retired Python files

All twenty files under `backend/app/**` and `backend/tests/**` were deleted in commit `80f1d53d`
(DL-060). Each has a row. The `Status` column reports whether the file's *constructs* are carried
forward, which is what the legend above defines; the two expectations written into
`backend/tests/test_api.py` that are not honoured are dispositioned in §1.10 and §1.11 and not
here; no row of this table reads *Partly delivered*.

| # | Source file | Java target(s) | Status |
|---|-------------|----------------|--------|
| 1 | `backend/app/main.py` | `ScannerApplication` (composition root, replacing the `create_app()` factory and the duplicate module-level `Flask` object at `:L13`), `config/CorsConfig` (`CORS(app)` at `:L20`), `security/SecurityConfig` (`JWTManager(app)` at `:L22`), `api/GlobalExceptionHandler` (the 404 and 500 handlers at `:L31-37`), `api/GlobalExceptionHandler`'s `ErrorAttributes` bean (the same two envelopes on the servlet `ERROR` dispatch, which no `@RestControllerAdvice` can reach — DL-183), `config/AsyncSchedulingConfig` (`@EnableScheduling`, replacing `initialize_background_tasks()` at `:L41-48`) | Delivered |
| 2 | `backend/app/core/config.py` | `config/ScannerProperties` plus `src/main/resources/application.yml`. The per-call `get_settings()` factory at `:L17-18` becomes one injected singleton (DL-031); the `.env` convention at `:L13-15` becomes environment-variable binding | Delivered |
| 3 | `backend/app/core/security.py` | `security/JwtService` (`create_access_token` at `:L6-12` → jjwt HS256, DL-014/DL-017/DL-018) and the `BCryptPasswordEncoder` bean in `security/SecurityConfig` (the passlib context at `:L14-18`, DL-020). The unused `decode` import at `:L1` is retired | Delivered |
| 4 | `backend/app/db/database.py` | `config/DataSourceConfig` (replacing the per-call `create_engine`/`sessionmaker` at `:L5-13`) plus `repository/TweetRepository`, `repository/ResponseRepository`, `repository/AiToolRepository`, `repository/SettingRepository` | Delivered |
| 5 | `backend/app/db/models.py` | `entity/Tweet`, `entity/Response`, `entity/AiTool`, `entity/Setting`, and `util/DelimitedStringListConverter` for the two delimited columns (DL-024) | Delivered |
| 6 | `backend/app/schema/tweet.py` | `dto/TweetDto` — the nine components at `:L5-14`, snake_case member names, string identifier (DL-022/DL-023), and the required-versus-optional split the schema declares: the canonical constructor rejects a null value for the six required scalars and leaves `quoted_tweet_id`, the sole `Optional[str]` at `:L12`, nullable (DL-080) | Delivered |
| 7 | `backend/app/schema/response.py` | `dto/ResponseDto` — the five components at `:L4-9`, all five of which `:L5-9` declares required and the canonical constructor rejects a null value for, naming the wire key; `service/mapper/ResponseMapper` carries a null column through to that constructor and does not test it, and the requirement is declared in one place only (DL-080/DL-133) | Delivered |
| 8 | `backend/app/api/tweets.py` | `api/TweetController` (three routes), with `dto/PaginatedTweetsDto` and `dto/PaginationDto` (the envelope at `:L18-21`, DL-038), `dto/AnalysisResultDto` (`:L52-55`), `service/TwitterService.getPaginatedTweets`/`getTweet`/`updateTweetAnalysis` (the three methods the handlers call at `:L16,L27,L50`), `service/mapper/TweetMapper` (the `to_dict()` at `:L19,L30` that the source never defined) | Delivered |
| 9 | `backend/app/api/responses.py` | `api/ResponseController` (four routes), with `dto/PaginatedResponsesDto` (`:L17-20`), `dto/CreateResponseRequest` (`:L38-41`), `dto/UpdateResponseRequest` (`:L54`), `service/ResponseService` (all four methods the handlers call), `service/mapper/ResponseMapper` (the `to_dict()` at `:L18,L29,L47,L63`), `exception/ResponseGenerationException` (the 500 literal at `:L49`) | Delivered |
| 10 | `backend/app/api/settings.py` | `api/SettingController.getSettings` and `.updateSetting`, `dto/SettingDto` (DL-039), `dto/UpdateSettingRequest`, `service/SettingsService`, `service/mapper/SettingMapper`. The statically-invoked `SettingsService.get_all_settings()` at `:L10` becomes an instance call on an injected bean (DL-043) | Delivered |
| 11 | `backend/app/api/analytics.py` | `api/AnalyticsController` (two routes), with `service/AnalyticsService.getTrends`/`getSummary` (both zero-argument, matching `:L14,L24`), `dto/TrendsDto` (`:L13-15`, DL-042), `dto/SummaryDto` (`:L23-25`, DL-041) | Delivered |
| 12 | `backend/app/services/twitter_service.py` | `service/TwitterService` — the four methods the class lacked, plus `meetsPopularityThreshold` correcting the `tweet.likes` field error at `:L46`. The `pass`-stub `stream_tweets` at `:L16-23` maps to `task/TweetStreamClient` | Delivered |
| 13 | `backend/app/services/sentiment_analysis.py` | `service/SentimentAnalysisService.analyzeSentiment(String)` (`:L14-24`, DL-036) and `.calculateDoubtRating(double)` (`:L26-35`, DL-062) | Delivered |
| 14 | `backend/app/services/notion_service.py` | `service/NotionService.storeTweet` (`:L14-26`), `.getTweets` (`:L32-38`) and `.updateTweetResponse` (the method `response_generation.py:L30` called but the class did not have), plus `config/RestClientConfig` replacing `Client(auth=…)` at `:L8` (DL-013). The reconstruction at `:L44-50`, which indexed `[0]` directly and read properties fed from fields the source model never declared, becomes a guarded read that skips and counts a page it cannot turn into a complete wire record (DL-088/DL-090/DL-219) | Delivered |
| 15 | `backend/app/services/llm_service.py` | `service/LlmService.generateResponse` — Chat Completions replacing `Completion.create(engine="text-davinci-002", …)` at `:L19-26` (DL-032/DL-033); the prompt at `:L16` preserved (DL-035); generated text returned as a `String` for `ResponseService` to persist (DL-081); source tuning literals retained as provenance while current defaults/omissions follow DL-034, DL-145, DL-200 and DL-202 | Delivered |
| 16 | `backend/app/tasks/tweet_monitoring.py` | `task/TweetStreamClient` and `task/TweetStreamListener` (DL-044/DL-045/DL-046), with `config/WebClientConfig` replacing the tweepy `Stream` construction at `:L45-51`; the listener validates every wire-required payload value before persistence and skips malformed records (DL-080) | Delivered |
| 17 | `backend/app/tasks/response_generation.py` | `task/ResponseGenerationScheduler` (DL-047), with `config/AsyncSchedulingConfig` carrying `@EnableScheduling` and the task scheduler that replaces the broker-less Celery application at `:L8` | Delivered |
| 18 | `backend/tests/test_api.py` | `api/GlobalExceptionHandlerTest` (delivered, covering the two error envelopes at `main.py:L31-37`), `api/SettingControllerTest` and `api/AuthControllerTest` (delivered). `api/TweetControllerTest`, `api/ResponseControllerTest`, `api/AnalyticsControllerTest` and `ScannerApplicationTests`, all delivered. The `fastapi.testclient` import at `:L2` is retired outright. Two of the file's expectations are not honoured — the key-to-value settings map with `auto_response` (DL-039) and the analytics date-range probe (DL-042) — which §1.10 and §1.11 record: those are source *expectations*, not named targets, so this row reads *Delivered* under the legend above and the two discounted expectations are dispositioned there | Delivered |
| 19 | `backend/tests/test_services.py` | `service/SentimentAnalysisServiceTest`, `service/NotionServiceTest`, `service/LlmServiceTest`, `service/SettingsServiceTest`, `service/SettingsServiceSeedingIntegrationTest`, `service/TwitterServiceTest`, `service/ResponseServiceTest` and `service/AnalyticsServiceTest` — all delivered. The wrong-package-root imports at `:L3-6` and the two `pass` stubs at `:L12-22` are retired | Delivered |
| 20 | `backend/tests/test_tasks.py` | `task/ResponseGenerationSchedulerTest` (22 cases) and `task/TweetStreamListenerTest` (45), both delivered, together with the two classes covering `task/TweetStreamClient` (DL-214). The import at `:L3` names neither a module nor symbols that exist, so nothing carries forward from it; the rate-limiting wish at `:L55` is not honoured (DL-283) | Delivered |

### 1.2 HTTP routes

Eleven routes are preserved unprefixed — no `/api`, no `/v1` — with identical methods, paths,
path-variable names, query-parameter names, defaults and status codes. A twelfth route is net-new
(DL-019). Path variables are declared `String` on every route; a non-numeric segment yields 404
(DL-048). The `page` default is `1` (`api/tweets.py:L12`, `api/responses.py:L11`) and the `per_page`
default is `10` (`api/tweets.py:L13`, `api/responses.py:L12`).

| # | Source route | Source handler | Java target | Service method | Status |
|---|--------------|----------------|-------------|----------------|--------|
| 1 | `GET /tweets` (`page` default 1, `per_page` default 10) | `tweets.py:L9-21 get_tweets` | `api/TweetController.getTweets` | `service/TwitterService.getPaginatedTweets(int, int)` | delivered (controller); service Delivered |
| 2 | `GET /tweets/<tweet_id>` — 200, or 404 `{"error": "Tweet not found"}` | `tweets.py:L23-32 get_tweet` | `api/TweetController.getTweet` | `service/TwitterService.getTweet(String)` | delivered (controller); service Delivered |
| 3 | `POST /tweets/<tweet_id>/analyze` — 200 `{"tweet_id", "analysis_result"}` | `tweets.py:L36-55 analyze_tweet` | `api/TweetController.analyzeTweet` | `service/SentimentAnalysisService.analyzeSentiment(String)` then `service/TwitterService.updateTweetAnalysis(String, double)` (DL-037) | delivered (controller); services Delivered |
| 4 | `GET /responses` (`page` default 1, `per_page` default 10) | `responses.py:L8-20 get_responses` | `api/ResponseController.getResponses` | `service/ResponseService.getPaginatedResponses(int, int)` | delivered (controller); service Delivered |
| 5 | `GET /responses/<response_id>` — 200, or 404 `{"error": "Response not found"}` | `responses.py:L22-31 get_response` | `api/ResponseController.getResponse` | `service/ResponseService.getResponseById(String)` | delivered (controller); service Delivered |
| 6 | `POST /responses` — 400 `{"error": "Tweet ID is required"}`, 201, 500 `{"error": "Failed to generate response"}` | `responses.py:L33-49 generate_response` | `api/ResponseController.generateResponse` | `service/ResponseService.generateResponse(String)` (DL-076/DL-178) | delivered (controller); service Delivered |
| 7 | `PUT /responses/<response_id>` — 400 `{"error": "Update data is required"}`, 200, 404 `{"error": "Response not found or update failed"}` | `responses.py:L51-65 update_response` | `api/ResponseController.updateResponse` | `service/ResponseService.updateResponse(String, UpdateResponseRequest)` | delivered (controller); service Delivered |
| 8 | `GET /settings` — 200, array of `{key, value, description}` | `settings.py:L7-11 get_settings` | `api/SettingController.getSettings` (`@GetMapping("/settings")`) | `service/SettingsService.getAllSettings()` (DL-039) | Delivered |
| 9 | `PUT /settings/<key>` — 400 `{"error": "No value provided"}`, 404 `{"error": "Setting not found"}`, 200 | `settings.py:L13-24 update_setting` | `api/SettingController.updateSetting` (`@PutMapping("/settings/{key}")`) | `service/SettingsService.updateSetting(String, String)` | Delivered |
| 10 | `GET /analytics/trends` — 200 | `analytics.py:L7-15 get_trends` | `api/AnalyticsController.getTrends` | `service/AnalyticsService.getTrends()` (DL-042) | Delivered |
| 11 | `GET /analytics/summary` — 200 | `analytics.py:L17-25 get_summary` | `api/AnalyticsController.getSummary` | `service/AnalyticsService.getSummary()` (DL-041) | Delivered |
| 12 | *No source construct — net-new*: `POST /auth/token`, the only unauthenticated route | — (no `/login`, `/token` or `/auth` blueprint is registered at `main.py:L26-29`, and `create_access_token` has no call site) | `api/AuthController.issueToken` (`@PostMapping("/auth/token")`) | `security/JwtService.generateToken` (DL-019/DL-078/DL-079) | Delivered |

### 1.3 Tables, columns and the association

Four tables, twenty columns, one association. No table, column or index is added, and no
`NOT NULL`, `UNIQUE` or length bound is introduced: every character column states the
capacity-free `varchar` its source declaration renders (DL-068). `ai_tools_mentioned` stays a plain
character column and is never a foreign key or a join table to `ai_tools`.

| # | Source table | Source column and type | Java field | Java mapping | Status |
|---|--------------|------------------------|------------|--------------|--------|
| 1 | `tweets` | `id Integer primary_key` (`models.py:L10`) | `entity/Tweet.id` `Integer` | `@Id @GeneratedValue(IDENTITY) @Column(name = "id")` (DL-049/DL-138) | Delivered |
| 2 | `tweets` | `content String` (`:L11`) | `entity/Tweet.content` `String` | `@Column(name = "content", columnDefinition = "varchar")` (DL-068) | Delivered |
| 3 | `tweets` | `like_count Integer` (`:L12`) | `entity/Tweet.likeCount` `Integer` | `@Column(name = "like_count")` | Delivered |
| 4 | `tweets` | `created_at DateTime` (`:L13`) | `entity/Tweet.createdAt` `LocalDateTime` | `@Column(name = "created_at")` | Delivered |
| 5 | `tweets` | `doubt_rating Float` (`:L14`) | `entity/Tweet.doubtRating` `Double` | `@Column(name = "doubt_rating")` | Delivered |
| 6 | `tweets` | `media String` (`:L15`) | `entity/Tweet.media` `List<String>` | `@Convert(DelimitedStringListConverter) @Column(name = "media", columnDefinition = "varchar")` (DL-024/DL-068) | Delivered |
| 7 | `tweets` | `quoted_tweet_id String` (`:L16`) | `entity/Tweet.quotedTweetId` `String` | `@Column(name = "quoted_tweet_id", columnDefinition = "varchar")` (DL-068) | Delivered |
| 8 | `tweets` | `user_id String` (`:L17`) | `entity/Tweet.userId` `String` | `@Column(name = "user_id", columnDefinition = "varchar")` (DL-068) | Delivered |
| 9 | `tweets` | `ai_tools_mentioned String` (`:L18`) | `entity/Tweet.aiToolsMentioned` `List<String>` | `@Convert(DelimitedStringListConverter) @Column(name = "ai_tools_mentioned", columnDefinition = "varchar")` (DL-024/DL-068) — no foreign key to `ai_tools` | Delivered |
| 10 | `responses` | `id Integer primary_key` (`:L23`) | `entity/Response.id` `Integer` | `@Id @GeneratedValue(IDENTITY) @Column(name = "id")` (DL-025/DL-138) | Delivered |
| 11 | `responses` | `content String` (`:L24`) | `entity/Response.content` `String` | `@Column(name = "content", columnDefinition = "varchar")` — no declared length (DL-068) | Delivered |
| 12 | `responses` | `generated_at DateTime` (`:L25`) | `entity/Response.generatedAt` `LocalDateTime` | `@Column(name = "generated_at")`; the value is minted where the row is built (DL-232) | Delivered |
| 13 | `responses` | `is_approved Boolean` (`:L26`) | `entity/Response.isApproved` `Boolean` | `@Column(name = "is_approved")` — a flag a human reads, never a trigger | Delivered |
| 14 | `responses` | `tweet_id Integer ForeignKey('tweets.id')` (`:L27`) | `entity/Response.tweet` `Tweet` | `@ManyToOne(fetch = LAZY) @JoinColumn(name = "tweet_id")` (DL-162), no cascade and no `orphanRemoval` — the column and the `:L28` relationship are mapped by this one association | Delivered |
| 15 | `ai_tools` | `id Integer primary_key` (`:L35`) | `entity/AiTool.id` `Integer` | `@Id @GeneratedValue(IDENTITY) @Column(name = "id")` (DL-070) | Delivered |
| 16 | `ai_tools` | `name String` (`:L36`) | `entity/AiTool.name` `String` | `@Column(name = "name", columnDefinition = "varchar")` (DL-068) | Delivered |
| 17 | `ai_tools` | `description String` (`:L37`) | `entity/AiTool.description` `String` | `@Column(name = "description", columnDefinition = "varchar")` (DL-068) | Delivered |
| 18 | `settings` | `key String primary_key` (`:L42`) | `entity/Setting.key` `String` | `@Id @Column(name = "\"key\"", columnDefinition = "varchar")` — quoted reserved word (DL-061), no declared length, generated as a capacity-free `varchar` on H2 and PostgreSQL (DL-069) | Delivered |
| 19 | `settings` | `value String` (`:L43`) | `entity/Setting.value` `String` | `@Column(name = "\"value\"", columnDefinition = "varchar")` (DL-061/DL-068) | Delivered |
| 20 | `settings` | `description String` (`:L44`) | `entity/Setting.description` `String` | `@Column(name = "description", columnDefinition = "varchar")` (DL-068) | Delivered |

| # | Source association construct | Source location | Java target | Status |
|---|------------------------------|-----------------|-------------|--------|
| 1 | The association itself — `Tweet.responses = relationship("Response", …, back_populates="tweet")`, attached **after** the class body and not declared inside it, with its inverse `tweet = relationship("Tweet", back_populates="responses")`. The only association in the schema | owning side `models.py:L30`; inverse `models.py:L28`; foreign-key column `tweet_id` at `models.py:L27` | `entity/Tweet.responses` as `@OneToMany(mappedBy = "tweet")`, with `entity/Response.tweet` as `@ManyToOne @JoinColumn(name = "tweet_id")` (DL-025/DL-162). Table names and the join-column name are asserted against live JDBC metadata by `repository/JpaMappingIntegrationTest` | Delivered |
| 2 | The association **ordering** — the `order_by=Response.id` argument, which fixes the collection's iteration order by the child primary key | `models.py:L30` (the `order_by=Response.id` argument of the same call) | `@OrderBy("id ASC")` on `entity/Tweet.responses`. `repository/JpaMappingIntegrationTest` asserts the exact ordering of a loaded collection, so the guarantee is executable | Delivered |

### 1.4 Business rules

Both rules are transcribed, not adjusted.

| # | Source rule | Source location | Java target | Status |
|---|-------------|-----------------|-------------|--------|
| 1 | Doubt rating: `(1 - sentiment_score) * 5`, then clamped to `[0, 10]` | `sentiment_analysis.py:L29,L32` | `service/SentimentAnalysisService.calculateDoubtRating(double)` — `Math.max(0.0d, Math.min(10.0d, (1 - s) * 5))`, preceded by the explicit `Double.isNaN` branch returning `10.0` (DL-062). Vectors at −1 → 10.0, 0 → 5.0, 1 → 0.0, −0.5 → 7.5, 0.5 → 2.5, and out-of-range −2 → 10.0, 2 → 0.0, plus NaN and both infinities, are asserted by `service/SentimentAnalysisServiceTest` | Delivered |
| 2 | Popularity gate: `like_count >= TWEET_POPULARITY_THRESHOLD`, default 100 | `twitter_service.py:L46` — `if tweet.likes >= popularity_threshold:`, inside `check_popularity_threshold` declared at `:L42` with the threshold read at `:L43`, reading the field name `tweet.likes` that `schema/tweet.py:L5-14` does not declare — and `core/config.py:L10` | `service/TwitterService.meetsPopularityThreshold(Integer)` reading `scanner.popularity-threshold`, with the field-name error corrected to `likeCount`. The 99/100/101 boundary is asserted by `service/TwitterServiceTest` | Delivered (rule); delivered (its dedicated test) |

### 1.5 Configuration keys

Fifteen keys: the seven `Settings` fields declared at `core/config.py:L5-11`, and the eight that code
paths read without any declaration existing — the drift set, every member of which raised
`AttributeError` on first access. Every one of the fifteen is declared in
`src/main/resources/application.yml` and bound through `config/ScannerProperties`.

| # | Environment key | Declared in source? | Source reference | `application.yml` property | Default | Status |
|---|-----------------|---------------------|------------------|----------------------------|---------|--------|
| 1 | `TWITTER_API_KEY` | Yes | `core/config.py:L5` | `scanner.twitter.api-key` | empty | Delivered |
| 2 | `TWITTER_API_SECRET` | Yes | `core/config.py:L6` | `scanner.twitter.api-secret` | empty | Delivered |
| 3 | `NOTION_API_KEY` | Yes | `core/config.py:L7` | `scanner.notion.api-key` | empty | Delivered |
| 4 | `OPENAI_API_KEY` | Yes | `core/config.py:L8` | `scanner.openai.api-key` | empty | Delivered |
| 5 | `DATABASE_URL` | Yes | `core/config.py:L9` | `scanner.database-url`, translated to JDBC by `config/DatabaseUrlTranslator`; `spring.datasource.url` is unset (DL-027) | none; required | Delivered |
| 6 | `TWEET_POPULARITY_THRESHOLD` | Yes | `core/config.py:L10` | `scanner.popularity-threshold` | `100` | Delivered |
| 7 | `RESPONSE_GENERATION_DELAY` | Yes | `core/config.py:L11` | `scanner.response-generation-delay-seconds` | `60` | Delivered |
| 8 | `SECRET_KEY` | **No — drift** | read by `core/security.py:L11` | `scanner.jwt.secret` | none; fail fast for unset, blank and unresolved-placeholder alike (DL-016, DL-185) | Delivered |
| 9 | `ALGORITHM` | **No — drift** | read by `core/security.py:L11` | `scanner.jwt.algorithm` | `HS256`, the only accepted value; the in-file comment states so (DL-015, DL-184) | Delivered |
| 10 | `NOTION_DATABASE_ID` | **No — drift** | read by `services/notion_service.py:L24` (`parent={"database_id": …}`) and `:L35` (`database_id=…`) | `scanner.notion.database-id` | empty | Delivered |
| 11 | `TWITTER_API_SECRET_KEY` | **No — drift** | read by `services/twitter_service.py:L12` | `scanner.twitter.api-secret-key`, nested-default alias onto `TWITTER_API_SECRET` (DL-031) | falls through to `TWITTER_API_SECRET` | Delivered |
| 12 | `TWITTER_CONSUMER_KEY` | **No — drift** | read by `tasks/tweet_monitoring.py:L46` | `scanner.twitter.consumer-key`, alias onto `TWITTER_API_KEY` (DL-031) | falls through to `TWITTER_API_KEY` | Delivered |
| 13 | `TWITTER_CONSUMER_SECRET` | **No — drift** | read by `tasks/tweet_monitoring.py:L47` | `scanner.twitter.consumer-secret`, alias onto `TWITTER_API_SECRET` (DL-031) | falls through to `TWITTER_API_SECRET` | Delivered |
| 14 | `TWITTER_ACCESS_TOKEN` | **No — drift** | read by `services/twitter_service.py:L13` and `tasks/tweet_monitoring.py:L48` | `scanner.twitter.access-token` | empty | Delivered |
| 15 | `TWITTER_ACCESS_TOKEN_SECRET` | **No — drift** | read by `services/twitter_service.py:L13` and `tasks/tweet_monitoring.py:L49` | `scanner.twitter.access-token-secret` | empty | Delivered |

Further properties have no environment key in the source: `scanner.auth.username` /
`scanner.auth.password-hash` (DL-020), `scanner.analytics.trend-window-days` (DL-042),
`scanner.ingestion.stream-base-keywords` (DL-044), `scanner.twitter.request-timeout-seconds` — the bound on
the X token exchange and stream-rules calls, overridable as `TWITTER_REQUEST_TIMEOUT_SECONDS` (DL-230) —
and the stream activation, timeout, backoff and bounded-dispatch settings (DL-198). Twenty-two further
properties were added while the review findings were resolved, each environment-overridable and each
range-checked at binding: the three `scanner.background.*` ownership flags, bound from
`SCANNER_BACKGROUND_ENABLED`, `TWITTER_STREAM_ENABLED` and `RESPONSE_GENERATION_ENABLED` (DL-250); the ownership lease term and renewal interval
(DL-281) and the per-pass candidate ceiling (DL-282);
`scanner.ingestion.max-stream-rules` (DL-254) and `scanner.ingestion.stream-idle-timeout-seconds`
(DL-256); `scanner.notion.mirror-max-retries` and `scanner.notion.mirror-retry-backoff-millis`
(DL-253); the eight `scanner.datasource.pool.*` keys that are the pool's whole configurable surface
(DL-270, DL-271); and `server.shutdown: graceful` with
`spring.lifecycle.timeout-per-shutdown-phase: 30s` (DL-271). `server.port` is `${PORT:5000}` (DL-029) and
`spring.main.web-application-type` is `servlet` (DL-030). Google Cloud Natural Language uses
Application Default Credentials as `services/sentiment_analysis.py:L8` did.

#### 1.5.1 Complete target configuration inventory

`src/main/resources/application.yml` declares **fifty-one** environment placeholders and **nine** further
settings that carry a literal value with no placeholder. The table is grouped by property prefix rather
than by position in the file. The list is exhaustive and is what the file contains; a key absent from
this table is absent from the service.

| # | Environment placeholder | Property | Default | In the source set? | Decision |
|---|-------------------------|----------|---------|--------------------|----------|
| 1 | `PORT` | `server.port` | `5000` | No | DL-029 |
| 2 | `FORWARD_HEADERS_STRATEGY` | `server.forward-headers-strategy` | `framework` | No | DL-277 |
| 3 | `MAX_HTTP_REQUEST_HEADER_SIZE` | `server.max-http-request-header-size` | `8KB` | No | DL-238 |
| 4 | `SCANNER_LOG_LEVEL` | `logging.level.com.codeskeptic.scanner` | `INFO` | No | DL-052/DL-206 |
| 5 | `DATABASE_URL` | `scanner.database-url` | none; required | Yes (1 of 15) | DL-027 |
| 6 | `TWEET_POPULARITY_THRESHOLD` | `scanner.popularity-threshold` | `100` | Yes | DL-039/DL-040 |
| 7 | `RESPONSE_GENERATION_DELAY` | `scanner.response-generation-delay-seconds` | `60` | Yes | DL-047/DL-227 |
| 8 | `TWITTER_API_KEY` | `scanner.twitter.api-key` | empty | Yes | DL-031 |
| 9 | `TWITTER_API_SECRET` | `scanner.twitter.api-secret` | empty | Yes | DL-031 |
| 10 | `TWITTER_API_SECRET_KEY` | `scanner.twitter.api-secret-key` | falls through to `TWITTER_API_SECRET` | Yes (drift) | DL-031 |
| 11 | `TWITTER_CONSUMER_KEY` | `scanner.twitter.consumer-key` | falls through to `TWITTER_API_KEY` | Yes (drift) | DL-031 |
| 12 | `TWITTER_CONSUMER_SECRET` | `scanner.twitter.consumer-secret` | falls through to `TWITTER_API_SECRET` | Yes (drift) | DL-031 |
| 13 | `TWITTER_ACCESS_TOKEN` | `scanner.twitter.access-token` | empty | Yes (drift) | DL-046 |
| 14 | `TWITTER_ACCESS_TOKEN_SECRET` | `scanner.twitter.access-token-secret` | empty | Yes (drift) | DL-046 |
| 15 | `TWITTER_REQUEST_TIMEOUT_SECONDS` | `scanner.twitter.request-timeout-seconds` | `10` | No | DL-230 |
| 16 | `NOTION_API_KEY` | `scanner.notion.api-key` | empty | Yes | DL-013 |
| 17 | `NOTION_DATABASE_ID` | `scanner.notion.database-id` | empty | Yes (drift) | DL-013 |
| 18 | `NOTION_API_VERSION` | `scanner.notion.api-version` | `2022-06-28` | No | DL-151/DL-193 |
| 19 | `NOTION_CONNECT_TIMEOUT_SECONDS` | `scanner.notion.connect-timeout-seconds` | `5` | No | DL-128/DL-150 |
| 20 | `NOTION_READ_TIMEOUT_SECONDS` | `scanner.notion.read-timeout-seconds` | `10` | No | DL-128/DL-150 |
| 21 | `OPENAI_API_KEY` | `scanner.openai.api-key` | empty | Yes | DL-011 |
| 22 | `OPENAI_MAX_COMPLETION_TOKENS` | `scanner.openai.max-completion-tokens` | `150` | No | DL-034/DL-202 |
| 23 | `OPENAI_TEMPERATURE` | `scanner.openai.temperature` | `0.7` | No | DL-200 |
| 24 | `OPENAI_N` | `scanner.openai.n` | `1` | No | DL-201 |
| 25 | `OPENAI_REASONING_EFFORT` | `scanner.openai.reasoning-effort` | `none` | No | DL-145 |
| 26 | `OPENAI_REQUEST_TIMEOUT_SECONDS` | `scanner.openai.request-timeout-seconds` | `30` | No | DL-146 |
| 27 | `OPENAI_MAX_RETRIES` | `scanner.openai.max-retries` | `2` | No | DL-146 |
| 28 | `SECRET_KEY` | `scanner.jwt.secret` | none; required | Yes (drift) | DL-016/DL-185/DL-186 |
| 29 | `ALGORITHM` | `scanner.jwt.algorithm` | `HS256` | Yes (drift) | DL-015/DL-108/DL-186 |
| 30 | `AUTH_USERNAME` | `scanner.auth.username` | `admin` | No | DL-020 |
| 31 | `AUTH_PASSWORD_HASH` | `scanner.auth.password-hash` | none; required | No | DL-020/DL-116/DL-189 |
| 32 | `SERVER_SHUTDOWN` | `server.shutdown` | `graceful` | No | DL-271 |
| 33 | `SHUTDOWN_GRACE_PERIOD` | `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | No | DL-271 |
| 34 | `DB_POOL_MAXIMUM_SIZE` | `scanner.datasource.pool.maximum-size` | `10` | No | DL-270 |
| 35 | `DB_POOL_MINIMUM_IDLE` | `scanner.datasource.pool.minimum-idle` | `2` | No | DL-270 |
| 36 | `DB_POOL_CONNECTION_TIMEOUT_MILLIS` | `scanner.datasource.pool.connection-timeout-millis` | `30000` | No | DL-270 |
| 37 | `DB_POOL_VALIDATION_TIMEOUT_MILLIS` | `scanner.datasource.pool.validation-timeout-millis` | `5000` | No | DL-270 |
| 38 | `DB_POOL_IDLE_TIMEOUT_MILLIS` | `scanner.datasource.pool.idle-timeout-millis` | `600000` | No | DL-270 |
| 39 | `DB_POOL_MAX_LIFETIME_MILLIS` | `scanner.datasource.pool.max-lifetime-millis` | `1800000` | No | DL-270 |
| 40 | `DB_POOL_LEAK_DETECTION_THRESHOLD_MILLIS` | `scanner.datasource.pool.leak-detection-threshold-millis` | `0` (off) | No | DL-270 |
| 41 | `DB_POOL_NAME` | `scanner.datasource.pool.name` | `code-skeptic-scanner-pool` | No | DL-270 |
| 42 | `NOTION_MIRROR_MAX_RETRIES` | `scanner.notion.mirror-max-retries` | `2` | No | DL-253 |
| 43 | `NOTION_MIRROR_RETRY_BACKOFF_MILLIS` | `scanner.notion.mirror-retry-backoff-millis` | `500` | No | DL-253 |
| 44 | `TWITTER_MAX_STREAM_RULES` | `scanner.ingestion.max-stream-rules` | `25` | No | DL-254 |
| 45 | `TWITTER_STREAM_IDLE_TIMEOUT_SECONDS` | `scanner.ingestion.stream-idle-timeout-seconds` | `60` | No | DL-256 |
| 46 | `SCANNER_BACKGROUND_ENABLED` | `scanner.background.enabled` | `true` | No | DL-250 |
| 47 | `TWITTER_STREAM_ENABLED` | `scanner.background.stream-enabled` | `true` | No | DL-250 |
| 48 | `RESPONSE_GENERATION_ENABLED` | `scanner.background.response-generation-enabled` | `true` | No | DL-250 |
| 49 | `BACKGROUND_LEASE_TTL_SECONDS` | `scanner.background.lease-ttl-seconds` | `120` | No | DL-281 |
| 50 | `BACKGROUND_LEASE_RENEW_SECONDS` | `scanner.background.lease-renew-seconds` | `30` | No | DL-281 |
| 51 | `RESPONSE_GENERATION_MAX_CANDIDATES_PER_PASS` | `scanner.background.max-candidates-per-pass` | `200` | No | DL-282 |

Nine settings carry a literal value and no placeholder: `spring.main.web-application-type: servlet`
(DL-030), `spring.jackson.parser.strict-duplicate-detection: true` (DL-188),
`spring.jpa.hibernate.ddl-auto: update` (DL-026), `spring.jpa.open-in-view: false` (DL-026),
`spring.jpa.properties.hibernate.auto_quote_keyword: true` (DL-061), `scanner.openai.model:
gpt-5.6-terra` (DL-033), `scanner.jwt.expiration-minutes: 60` (DL-017/DL-142),
`scanner.analytics.trend-window-days: 30` (DL-042) and the four-term list
`scanner.ingestion.stream-base-keywords` (DL-044). Relaxed binding still accepts an environment
override for each — `SCANNER_OPENAI_MODEL`, `SCANNER_ANALYTICS_TREND_WINDOW_DAYS` and so on — so the
absence of a placeholder fixes the default, not the ability to change it.

Stream activation is configuration — rows 46 to 48 — and so are the rule cap at row 44, the
idle bound at row 45, the ownership lease pacing at rows 49 and 50 and the work bounds at rows
51 to 55. Reconnection pacing is not: the exponential backoff is code, not configuration
(DL-045); the per-record dispatch scheduler is code (DL-220); the one-mebibyte record bound is a code
constant (DL-222); and the only bound the X transport takes from configuration is
`scanner.twitter.request-timeout-seconds` at row 15, which the filtered-stream subscription does not
carry (DL-230).

#### 1.5.2 Seeded `settings` rows and their consumers

`service/SettingsService` seeds three rows idempotently on `ApplicationReadyEvent` (DL-040, DL-159) and
`PUT /settings/{key}` edits them. Two of the three are read at runtime, and both readers apply the same
precedence: the row overrides the configured property, and an absent, `null` or unusable stored value
falls back to that property with one `WARN` naming the key and never the stored value. The third,
`response_generation_delay`, is seeded and reported but paces nothing — the row below says so.

| Row `key` | Seeded from | Runtime consumer | Accepted stored value | Fallback | Decision |
|-----------|-------------|------------------|-----------------------|----------|----------|
| `tweet_popularity_threshold` | `scanner.popularity-threshold` | `service/TwitterService.popularityThresholdInForce()`, called once per stream cycle by `task/TweetStreamClient` and passed to `meetsPopularityThreshold(Integer, int)` for every record of that cycle; the single-argument `meetsPopularityThreshold(Integer)` resolves the row itself for callers outside a cycle | any `int` once trimmed, including a value at or below zero | `scanner.popularity-threshold` when the row is absent, holds `null` or does not parse | DL-040, DL-255 |
| `response_generation_delay` | `scanner.response-generation-delay-seconds` | `config/AsyncSchedulingConfig.resolveDelay()`, called by the registered `Trigger` each time the next pass is computed — that is, after every completed pass; the first pass is not paced by it and runs at startup | a positive `long` once trimmed | `scanner.response-generation-delay-seconds` when the row is absent, holds `null`, does not parse, or is not positive; a resolved value is then clamped into `[1s, 365d]` and a failed read leaves the configured value in force | DL-197, DL-251 |
| `stream_keywords` | `scanner.ingestion.stream-base-keywords`, comma-joined | `task/TweetStreamClient.composeRuleSet()`, read before every connection | a comma-separated list holding at least one term that survives the literal-term allowlist of DL-257; the surviving terms replace the whole rule set, and the set is then capped at `scanner.ingestion.max-stream-rules` with the excess dropped under one `WARN` naming counts only | `scanner.ingestion.stream-base-keywords` union every `ai_tools.name` when the row is absent, holds `null`, or holds no term that survives the allowlist | DL-044, DL-254, DL-257 |

`response_generation_delay` is read by the registered `Trigger` each time the next pass is computed,
so a row value takes effect from the pass after the one already scheduled — DL-228. The pass carries no
`@Scheduled` annotation: `config/AsyncSchedulingConfig` registers it through
`ScheduledTaskRegistrar.addTriggerTask` with a completion-based `Trigger`, whose first firing is the
startup instant and whose later firings are the previous completion plus the resolved delay — DL-251.
A row value the accepted bound refuses leaves `scanner.response-generation-delay-seconds` in force.

### 1.6 External integrations

Four external systems, one adapter bean each; no SDK type crosses an adapter boundary. The relational database stays the system of record and Notion stays a secondary mirror.

| # | External system | Source client | Source location | Java adapter | Java client choice | Status |
|---|-----------------|---------------|-----------------|--------------|--------------------|--------|
| 1 | Google Cloud Natural Language | `LanguageServiceClient` (Application Default Credentials) | `services/sentiment_analysis.py:L1,L8` | `service/SentimentAnalysisService` | `com.google.cloud:google-cloud-language` 2.96.0 (DL-010) | Delivered |
| 2 | OpenAI | `from openai import Completion`, the removed 0.x API | `services/llm_service.py:L1,L19-26` | `service/LlmService` | `com.openai:openai-java` 4.49.0 over Chat Completions (DL-011/DL-032) | Delivered |
| 3 | Notion | `notion_client.Client` | `services/notion_service.py:L1,L8` | `service/NotionService` with `config/RestClientConfig` | `org.springframework.web.client.RestClient`, no SDK (DL-013) | Delivered |
| 4 | X (Twitter) | tweepy `API`/`OAuthHandler` and `Stream(...).filter(track=[...])` against the retired v1.1 `statuses/filter` | `services/twitter_service.py:L1`, `tasks/tweet_monitoring.py:L1,L55` | `task/TweetStreamClient` over the `WebClient` bean of `config/WebClientConfig` | `WebClient` against the X API v2 filtered stream, app-only bearer token (DL-012/DL-045/DL-046) | Delivered |

### 1.7 Retired PyPI packages

The Python backend had no dependency manifest of any kind, so this set was reconstructed from import
statements. Fifteen packages retire; seventeen Maven coordinates replace them, five explicitly pinned
and twelve BOM-managed.

| # | Retired package | Evidence of use | Java replacement | Status |
|---|-----------------|-----------------|------------------|--------|
| 1 | Flask | `main.py:L1`, `api/*.py:L1` | `spring-boot-starter-web`, declared in `backend/pom.xml` | Retired |
| 2 | Flask-Cors | `main.py:L2` | `CorsConfigurationSource` in `config/CorsConfig`, shipped with `starter-web` (DL-051) | Retired |
| 3 | Flask-JWT-Extended | `main.py:L3`, `api/*.py:L2` | `spring-boot-starter-security` filter chain (DL-014/DL-021) | Retired |
| 4 | PyJWT | `core/security.py:L1` | `io.jsonwebtoken` jjwt 0.13.0, three modules (DL-014) | Retired |
| 5 | passlib | `core/security.py:L3` | `BCryptPasswordEncoder`, shipped with `starter-security` (DL-020) | Retired |
| 6 | SQLAlchemy | `db/database.py:L1-2`, `db/models.py:L1-3` | `spring-boot-starter-data-jpa` with Hibernate 6.6.53.Final | Retired |
| 7 | pydantic | `core/config.py:L2`, `schema/*.py:L1` | `@ConfigurationProperties`, Java records, `spring-boot-starter-validation` (DL-050) | Retired |
| 8 | celery | `tasks/response_generation.py:L1` | `@EnableScheduling` plus one completion-based `Trigger` task registered by `config/AsyncSchedulingConfig` — no `@Scheduled` annotation, no broker and no queue (DL-047, DL-251) | Retired |
| 9 | tweepy | `services/twitter_service.py:L1`, `tasks/tweet_monitoring.py:L1` | `WebClient` from `spring-boot-starter-webflux` (DL-012) | Retired |
| 10 | google-cloud-language | `services/sentiment_analysis.py:L1` | `com.google.cloud:google-cloud-language` 2.96.0 (DL-010) | Retired |
| 11 | notion-client | `services/notion_service.py:L1` | `RestClient` from `spring-boot-starter-web` (DL-013) | Retired |
| 12 | openai | `services/llm_service.py:L1` | `com.openai:openai-java` 4.49.0 (DL-011) | Retired |
| 13 | pytest | `tests/test_api.py:L1`, `tests/test_tasks.py:L1` | JUnit Jupiter 5.12.2 from `spring-boot-starter-test` | Retired |
| 14 | unittest / unittest.mock | `tests/test_services.py:L1-2`, `tests/test_tasks.py:L2` | JUnit Jupiter plus Mockito 5.17.0 | Retired |
| 15 | fastapi | `tests/test_api.py:L2` — incompatible `TestClient` import against a Flask app | None. `MockMvc` replaces it and the dependency disappears entirely | Retired |

`backend/pom.xml` declares four dependency version properties — `tomcat.version` 10.1.57,
`netty.version` 4.1.136.Final, `jackson-bom.version` 2.21.5 and `postgresql.version` 42.7.13 — each
raising a coordinate `spring-boot-dependencies:3.5.16` manages, and one version on a coordinate itself,
`com.mysql:mysql-connector-j` 26.7.0; that BOM publishes no property for it. Every other
coordinate takes the version the BOM resolves. DL-170 owns the boundary between the two and
`BuildDependencyContractTest` asserts both halves. Both JDBC drivers ship at `runtime` scope in one
artifact (DL-028); `com.h2database:h2` 2.3.232 is `test` scope, is absent from the executable jar and has
no translator scheme (DL-071, DL-242).


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
| D2 | The response scheduler raises `NameError` on its own final line: `time.sleep(...)` without importing `time`, and it reads `settings.response_generation_interval` where the declared property is `RESPONSE_GENERATION_DELAY` | `tasks/response_generation.py:L50`; `core/config.py:L11` | `task/ResponseGenerationScheduler.generatePendingResponses()`, registered by `config/AsyncSchedulingConfig` through `ScheduledTaskRegistrar.addTriggerTask` with a completion-based `Trigger` that reads `scanner.response-generation-delay-seconds` — or the `response_generation_delay` row that overrides it — on every computation (DL-047, DL-251). The method carries no `@Scheduled` annotation | Delivered |
| D3 | Eight configuration keys are read by code but declared nowhere, so any code path touching them raises `AttributeError` | `core/config.py:L5-11` declares seven; `core/security.py:L11` (`SECRET_KEY` and `ALGORITHM` on one line), `services/notion_service.py:L24,L35`, `services/twitter_service.py:L12,L13`, `tasks/tweet_monitoring.py:L46-49` read eight more | All fifteen declared in `application.yml` and bound through `config/ScannerProperties`; the three Twitter aliases resolve through nested defaults (DL-031). Full inventory in §1.5 | Delivered |
| D4 | Three service classes are imported by controllers and do not exist anywhere in the repository | `api/responses.py:L3` (`ResponseService`), `api/settings.py:L3` (`SettingsService`), `api/analytics.py:L3` (`AnalyticsService`) | `service/ResponseService`, `service/SettingsService`, `service/AnalyticsService` — every method signature dictated by the call site that already existed (DL-039 … DL-043, DL-073, DL-075, DL-076, DL-200, DL-201, DL-202) | Delivered |
| D5 | The language-model call targets `text-davinci-002` through the removed Completions API, and assigns `Completion.api_key` from a lower-case attribute the settings class does not declare | `services/llm_service.py:L9,L19-26` | `service/LlmService` over Chat Completions with the model identifier in configuration (DL-032/DL-033), `max_completion_tokens` replacing `max_tokens` with a model-usable configurable default (DL-034/DL-202), the key read from `scanner.openai.api-key`, and generated text returned as `String` (DL-081) | Delivered |
| D6 | No token-issuance path exists: `create_access_token` has zero call sites, no auth route is registered, and every route's guard is a no-op | `core/security.py:L6-12`; `main.py:L26-29` | `api/AuthController.issueToken` on `POST /auth/token` with `security/JwtService`, `dto/LoginRequest` and `dto/TokenResponse`, over a configuration-backed principal (DL-019/DL-020) | Delivered |
| A1 | Every authorization guard is a no-op: `@jwt_required` is applied bare without parentheses, which in `flask-jwt-extended` 4.x registers the decorator factory and not the guard | `api/tweets.py:L10`; `api/responses.py:L9,L23,L34,L52`; `api/settings.py:L8,L14`; `api/analytics.py:L8,L18` — all eleven routes | `security/SecurityConfig` requiring an authenticated principal on every mapped endpoint except `POST /auth/token`, enforced by `security/JwtAuthenticationFilter`. Java enforces where Python did not, which is the behaviour change DL-021 records | Delivered |
| A2 | Background work is started synchronously and never returns: `initialize_background_tasks()` claims to run the stream "in a separate thread" but the tweepy call blocks, and the scheduler it calls next is a `while True` loop | `main.py:L41-48`; `tasks/response_generation.py:L41` | `ScannerApplication` carries `@SpringBootApplication` and `@ConfigurationPropertiesScan` only; scheduling is enabled declaratively by `config/AsyncSchedulingConfig`, and the stream is lifecycle-managed, not started from the composition root. The two lifecycle components are `config/AsyncSchedulingConfig`, which carries `@EnableScheduling` and publishes the `taskScheduler` the trigger-registered sweep of `task/ResponseGenerationScheduler` runs on (DL-047, DL-251), and `task/TweetStreamClient`, a `SmartLifecycle` bean the context starts after refresh and stops on shutdown (DL-045/DL-220) | Delivered |
| A3 | There is no `__init__.py` anywhere, so `backend/app/**` is not an importable Python package tree at all, compounding the wrong-package-root test imports | absence throughout `backend/app/**`; `tests/test_services.py:L3-6` | Maven standard directory layout with a declared package per directory. No equivalent construct is required, and the wrong-root imports have no counterpart to carry forward | Delivered |
| A4 | No dependency manifest exists, yet three files install from `requirements.txt` | absence of `backend/requirements.txt`; `infrastructure/docker/Dockerfile.backend:L8-11`, `.github/workflows/ci.yml:L26-29`, `scripts/setup_environment.sh:L12` | `backend/pom.xml`. The Dockerfile and the CI job are rewritten; `scripts/setup_environment.sh` remains unchanged (DL-054) | Delivered |
| A5 | The CD job cannot build any image: `docker build … ./backend` finds no Dockerfile in that context, and never could | `.github/workflows/cd.yml:L36` | `-f infrastructure/docker/Dockerfile.backend` added, context unchanged (DL-056) | Delivered |
| A6 | Two dead imports: `from os import getenv`, never used, and `from jwt import encode, decode` where `decode` is never used | `core/config.py:L1`; `core/security.py:L1` | Neither is carried forward. `config/ScannerProperties` binds through the framework, and `security/JwtService` declares only what it calls | Delivered |
| A7 | `to_dict()` is called on entities four times and is never defined on any model | `api/tweets.py:L19,L30`; `api/responses.py:L18,L29,L47,L63`; `db/models.py` defines no such method | `service/mapper/TweetMapper`, `service/mapper/ResponseMapper` and `service/mapper/SettingMapper` | Delivered |
| A8 | The candidate-tweet query uses a `.query` attribute declarative models do not have and names a relationship that does not exist — `response`, where the declared attribute is `responses` | `tasks/response_generation.py:L43`; `db/models.py:L30` | `repository/TweetRepository.findUnansweredBatchAfter(Integer afterId, Pageable)` — the JPQL `t.responses is empty` predicate the derived name expressed, read as bounded keyset batches ordered by `id ASC` and drained batch by batch within one scheduled pass (DL-248, which replaced the unbounded backlog read an earlier revision declared) | Delivered |
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
row below. The inventory was read back from commit `80f1d53d^` by scanning the files, not transcribed
from memory.

* **Assistance banner** — the all-capitals request-for-review banner comment. Twenty occurrences:
  fifteen under `backend/app/**` and five under `backend/tests/**`.
* **Deferred-work tag** — the all-capitals four-letter deferred-work tag. Three occurrences, all in
  `backend/app/tasks/tweet_monitoring.py`, identified below by the text that followed the tag.

This file names the two kinds descriptively and never reproduces either literal token, so a scan of
`backend/**` for either token returns nothing at all: `backend/src/**`, `backend/pom.xml`,
`backend/docs/DECISION_LOG.md` and this file are all free of both. The delivered
`infrastructure/docker/Dockerfile.backend` likewise carries no banner — the block that occupied
`L22-28` of the retired file is removed.

Markers outside the ported surface remain in place; they are outside this migration's scope.
Nine are enumerable outside `frontend/**`: `.github/workflows/ci.yml` and `.github/workflows/cd.yml`
each retain the banner they carried at `L65` before this migration edited them, now standing at `L64`
and `L98` respectively; `infrastructure/terraform/main.tf:L109`,
`infrastructure/terraform/outputs.tf:L66`, `infrastructure/terraform/variables.tf:L78`,
`infrastructure/docker/Dockerfile.frontend:L25` and `scripts/setup_environment.sh:L27`, `:L32` and
`:L52` are untouched. `frontend/**` carries a further sixteen across eleven files (DL-059).

| # | Kind | Source location | Resolution | Status |
|---|------|-----------------|------------|--------|
| 1 | Assistance banner | `api/analytics.py:L10` | `service/AnalyticsService.getTrends()` implemented; metric set decided in DL-042 | Delivered |
| 2 | Assistance banner | `api/analytics.py:L20` | `service/AnalyticsService.getSummary()` implemented; metric set decided in DL-041 | Delivered |
| 3 | Assistance banner | `api/responses.py:L36` | `service/ResponseService.generateResponse(String)` implemented, with the two-outcome contract of DL-076 | Delivered |
| 4 | Assistance banner | `api/tweets.py:L34` | `service/TwitterService.updateTweetAnalysis(String, double)` implemented; the analyze contract is decided in DL-037 | Delivered |
| 5 | Assistance banner | `main.py:L41` | Replaced by declarative scheduling in `config/AsyncSchedulingConfig`; the blocking composition-root call is gone (A2) | Delivered |
| 6 | Assistance banner | `services/llm_service.py:L11` | Client construction deferred to first use in `service/LlmService.openAiClient()`; no static SDK state is written | Delivered |
| 7 | Assistance banner | `services/llm_service.py:L34` | `service/LlmService.generateResponse` returns the generated text as a `String` (DL-081), where the source returned a partial dictionary, and `service/ResponseService` builds the wire record from the stored row | Delivered |
| 8 | Assistance banner | `services/notion_service.py:L10` | `config/RestClientConfig` supplies a configured `RestClient`; `scanner.notion.database-id` is a declared property | Delivered |
| 9 | Assistance banner | `services/notion_service.py:L30` | `service/NotionService.getTweets(int, String)` implemented, including default page size and cursor omission | Delivered |
| 10 | Assistance banner | `services/sentiment_analysis.py:L10` | Client acquisition and release implemented in `service/SentimentAnalysisService`, with Application Default Credentials retained | Delivered |
| 11 | Assistance banner | `services/twitter_service.py:L16` | The `pass`-stub `stream_tweets` becomes `task/TweetStreamClient` (DL-045) | Delivered |
| 12 | Assistance banner | `tasks/response_generation.py:L12` | `task/ResponseGenerationScheduler` (DL-047) | Delivered |
| 13 | Assistance banner | `tasks/response_generation.py:L36` | `task/ResponseGenerationScheduler` (DL-047) | Delivered |
| 14 | Assistance banner | `tasks/tweet_monitoring.py:L13` | `task/TweetStreamListener` | Delivered |
| 15 | Deferred-work tag — "Add database session and commit tweet" | `tasks/tweet_monitoring.py:L29` | `task/TweetStreamListener` persisting through `repository/TweetRepository.save` (D1) | Delivered |
| 16 | Deferred-work tag — "Implement response generation logic" | `tasks/tweet_monitoring.py:L32` | `task/TweetStreamListener` calling the background-only `service/ResponseService.generateResponseIfAbsent`, which is delivered with the canonical claim and parent-row lock guard (DL-195) | Delivered |
| 17 | Assistance banner | `tasks/tweet_monitoring.py:L36` | `task/TweetStreamClient` (DL-045/DL-046) | Delivered |
| 18 | Deferred-work tag — "Define keywords for streaming" | `tasks/tweet_monitoring.py:L53` | Decided in DL-044: configured base terms union every `ai_tools.name`, overridable by the `stream_keywords` setting row. `scanner.ingestion.stream-base-keywords` and the seeded row are delivered; the composing client is | Delivered |
| 19 | Assistance banner | `tests/test_api.py:L53` | The date-range probe it flagged is not honoured; the trend window is a configured property (DL-042) | Delivered |
| 20 | Assistance banner | `tests/test_services.py:L13` | Replaced by real assertions in `service/TwitterServiceTest` | Delivered |
| 21 | Assistance banner | `tests/test_services.py:L19` | Replaced by real assertions in `service/TwitterServiceTest` | Delivered |
| 22 | Assistance banner | `tests/test_services.py:L50` | Replaced by `service/LlmServiceTest`, which asserts against the delivered Chat Completions call; the source's `generate_text` does not exist | Delivered |
| 23 | Assistance banner | `tests/test_tasks.py:L53` | Replaced by `task/ResponseGenerationSchedulerTest` and `task/TweetStreamListenerTest`, both delivered. The rate-limiting test the marker asks for at `:L55` is not written (DL-283); the `429` and `x-rate-limit-reset` handling is asserted by `task/TweetStreamClientTest` (DL-214) | Delivered |

### 1.10 Python test files

Three files, 183 lines, none of which can import. Their delivered JUnit replacements are listed below;
the complete current class-by-class case counts are maintained in §2.7.

| Source test file | Source defect | JUnit replacements | Status |
|------------------|---------------|--------------------|--------|
| `backend/tests/test_api.py` | Imports `fastapi.testclient.TestClient` at `:L2` and points it at a Flask application; `test_get_settings` at `:L37-39` requests `/settings/` with a trailing slash and expects a key-to-value map; `test_update_settings` at `:L41-44` `PUT`s to `/settings/`, which is not a registered route; `:L50-51` names `total_tweets` and `total_responses`, which is the one piece of usable evidence in the file | Delivered: `api/GlobalExceptionHandlerTest` (both error envelopes, every translated exception type and the `ErrorAttributes` bean of DL-183, DL-181), `api/SettingControllerTest`, `api/AuthControllerTest`, `api/TweetControllerTest`, `api/ResponseControllerTest`, `api/AnalyticsControllerTest` and `ScannerApplicationTests` — DL-216; §2.7 carries each class's measured case count. `api/ErrorDispatchControllerTest` was a further class here until DL-183 withdrew the controller it covered; the container's own `ERROR` dispatch is now asserted end to end by `config/HttpChainIntegrationTest` against a running connector. The two settings expectations are discounted (DL-039) and the summary key names are honoured (DL-041) | Partly delivered |
| `backend/tests/test_services.py` | Wrong package root at `:L3-6` (`from services.…`); two `pass` stubs at `:L12-22`; tests for `create_page`, `update_page` and `generate_text` at `:L28-38,L44-48`, none of which exist; asserts sentiment analysis returns `'positive'`/`'negative'`/`'neutral'` at `:L58-65` where the implementation returns a float | Delivered: `service/SentimentAnalysisServiceTest`, `service/NotionServiceTest`, `service/LlmServiceTest`, `service/SettingsServiceTest`, `service/SettingsServiceSeedingIntegrationTest`, `service/TwitterServiceTest`, `service/ResponseServiceTest` (including the canonical claim and multi-instance storage guard of DL-195) and `service/AnalyticsServiceTest`. The two `pass` stubs at `:L12-22` are replaced by the popularity-gate matrix of `service/TwitterServiceTest`; exact counts are in §2.7 | Delivered |
| `backend/tests/test_tasks.py` | `from backend.tasks import monitor_tweets, generate_response` at `:L3` — neither the module path nor either symbol exists | Delivered: `task/ResponseGenerationSchedulerTest` and `task/TweetStreamListenerTest`, whose measured case counts are in §2.7 | Delivered |

Thirty-three of the forty-six delivered test classes descend from none of the three retired test files:
the Python suite tested none of what they cover. Twenty-two of the forty-six have no source construct of any
kind and are net-new — `api/AuthControllerTest` and `api/AuthControllerAdviceTest` (DL-019, also named
against `test_api.py` above, since they test a route that file could not know about),
`config/DatabaseUrlTranslatorTest` (DL-027/DL-064/DL-071/DL-072/DL-187), `config/DataSourceConfigTest`
(DL-027/DL-270/DL-271), `config/RequestMediaTypeConfigTest` (DL-235/DL-236),
`config/ContainerErrorResponseConfigTest` (DL-237/DL-238), `config/BuildProfileGuardTest` (DL-279),
`config/HttpChainIntegrationTest` (DL-051/DL-183/DL-237/DL-238/DL-240), `security/CachedBodyRequestTest` (DL-118),
`security/RequestBodyLimitIntegrationTest` (DL-118), `service/AnalyticsServiceTest`
(DL-041/DL-042/DL-247), `service/ResponseServiceTest` (DL-096/DL-211), `service/SettingsServiceSeedingIntegrationTest`
(DL-040), `task/BackgroundOwnershipTest` (DL-281/DL-284), `util/ConfiguredValuesTest` (DL-287),
`util/LogSafeTest` (DL-208),
`util/StreamRuleTermsTest` (DL-257), `BuildDependencyContractTest` (DL-169/DL-170/DL-240/DL-241), `DecisionLogCitationTest` (DL-216),
`DecisionPointerIntegrityTest` (DL-058/DL-099/DL-216), `DocumentationConsistencyTest` (DL-216) and
`TraceabilityCoverageTest` (DL-260). Eleven more cover a production construct that does have a source
origin and are recorded in §2.7 and not here: `security/JwtServiceTest` covers
`core/security.py:L6-12`, `security/JwtAuthenticationFilterTest` the bare `@jwt_required` sites,
`repository/JpaMappingIntegrationTest` `db/models.py` and the real persistence coordination of response
storage, `config/AsyncSchedulingConfigTest` the `while True` loop at
`tasks/response_generation.py:L41-50`, `config/RestClientConfigTest` `Client(auth=…)` at
`services/notion_service.py:L8`, `config/WebClientConfigTest` the tweepy `Stream` construction at
`tasks/tweet_monitoring.py:L45-51`, `config/MainProfileConfigurationContractTest` the `Settings` class at
`core/config.py:L4-15`, `task/TweetStreamClientTest` and `task/TweetStreamClientLifecycleTest`
`tasks/tweet_monitoring.py:L36-55` with the `pass`-stub `stream_tweets` at
`services/twitter_service.py:L16-23`, `util/QueryParametersTest` `api/tweets.py:L12-13` and
`api/responses.py:L11-12`, and `OperationsContractTest` the four operations files this migration edits.
The remaining thirteen classes each name a retired test file in §2.7.

`backend/tests/test_tasks.py` is replaced by `task/ResponseGenerationSchedulerTest`,
`task/TweetStreamListenerTest` and the two `task/TweetStreamClient` test classes listed in §2.7.

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
no source construct is marked *net-new* and carries its governing decision-log identifier, so an absent
source is explicit in the mapping.

Net-new comes in two shapes and both are marked. *No source construct — net-new* means nothing in the
Python tree corresponds to the target at all. *Net-new class, derived from …* means the Python tree
called for the construct but never contained it — an import of a class that does not exist, a method
invoked on a class that does not declare it, or a configuration value read with no code to interpret
it. The second shape identifies the call site that fixes the target signature; it is still net-new
code, not a port.

### 2.1 Build, configuration and documentation

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `backend/pom.xml` | *No source construct — net-new* — DL-002/DL-003/DL-004 | No Python manifest ever existed; the absence is defect A4. `spring-boot-starter-web` replaces Flask and Flask-Cors (DL-206). Version overrides are the four properties DL-100, DL-101, DL-169 and DL-240 declare, plus the coordinate-level raise of DL-241, under the umbrella of DL-170; Connector/J is pinned to 26.7.0 above the managed 9.7.0 (DL-241, which superseded the unpinned reading of DL-170); H2 carries `test` scope and not `runtime`, so it is absent from the repackaged jar (DL-242). The `<proc>full</proc>` compiler configuration is DL-174 |
| `backend/.gitignore` | *No source construct — net-new* — DL-055 | `target/` only |
| `backend/.dockerignore` | *No source construct — net-new* — DL-055 | `target/` only |
| `backend/src/main/resources/application.yml` | `backend/app/core/config.py` (all seven declared keys) plus the eight keys read without declaration, plus the `.env` convention at `:L13-15` | Full key inventory in §1.5. `server.port` DL-029, web type DL-030, `ddl-auto` DL-026, reserved-word quoting DL-061, the `scanner.jwt` accepted-value and key-length comments DL-184. It additionally carries the background-ownership flags (DL-250), the two ingestion bounds (DL-254, DL-256), the two Notion mirror-retry keys (DL-253), the eight `scanner.datasource.pool.*` keys that are the pool's whole configurable surface with each accepted range stated inline (DL-270, DL-271), and `server.shutdown: graceful` with `spring.lifecycle.timeout-per-shutdown-phase: 30s` (DL-271) |
| `backend/src/test/resources/application-test.yml` | *No source construct — net-new* — DL-009/DL-016/DL-026/DL-027/DL-061 | No Python test configuration existed. H2 with `create-drop`, reserved-word quoting, and a test JWT secret so `mvn clean verify` needs no manual step. It also declares every `scanner.datasource.pool.*` key explicitly with a four-connection ceiling, so a test context holds few connections (DL-271), and declares all three background-ownership flags enabled, so the composition assertions exercise the same ownership decision a single-process deployment takes while the X stream still declines to start on blank consumer credentials (DL-250, DL-046) |
| `backend/docs/DECISION_LOG.md` | *No source construct — net-new* — required by Rule 1 | Two hundred and eighty-nine entries, `DL-001` … `DL-289`, with no gap and no repeat, each carrying decision, alternatives, rationale and risks. Every identifier cited anywhere in this repository resolves to one of them |
| `backend/docs/TRACEABILITY_MATRIX.md` | *No source construct — net-new* — required by Rule 1 | This file |

### 2.2 Application core, configuration and security

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `ScannerApplication.java` | `main.py:L13-39` — the `create_app()` factory and the duplicate module-level `Flask` object | `@SpringBootApplication` plus `@ConfigurationPropertiesScan` and nothing else; one application context replaces two application objects (A2, DL-209) |
| `config/ScannerProperties.java` | `core/config.py:L4-15` — the single `Settings` class | Nested twitter/notion/openai/jwt/auth/analytics/ingestion groups; credential components are never rendered; `twitter.request-timeout-seconds` is the group's one non-credential component (DL-230) |
| `config/DataSourceConfig.java` | `db/database.py:L5-13` — the per-call `create_engine` and `sessionmaker` | One pooled `DataSource` built from the translated URL (A14). Pool geometry and timing come from the allowlisted `scanner.datasource.pool.*` group of `config/DataSourcePoolProperties`; the whole `spring.datasource.*` surface is unread, a connection-identity key among sixteen refuses to start, and any other key under that prefix is reported at `WARN` and ignored (DL-270, DL-271) |
| `config/DataSourcePoolProperties.java` | *No source construct — net-new* — DL-270, DL-271. `db/database.py:L5-8` built a fresh unpooled engine per call and configured no pool at all | The complete pool surface, bound from `scanner.datasource.pool.*`: eight components carrying geometry and timing only, so no JDBC URL, credential, driver or data-source class name, catalog, schema, initialisation statement or driver-property map is expressible. Every bound is range-checked as it binds against HikariCP's own floors and is refused, not substituted; `minimum-idle` defaults to 2 where HikariCP's own default holds `maximum-size` idle, and `maximum-size` carries a ceiling of 100 |
| `config/DatabaseUrlTranslator.java` | *Net-new class, derived from* `core/config.py:L9` — the opaque `DATABASE_URL`, which the source read but never interpreted | SQLAlchemy-style URL to JDBC URL plus separated credentials (DL-027/DL-064/DL-072); the scheme set is `postgresql`, `postgres`, `mysql` and `mariadb`, one per runtime-scope driver, and a `jdbc:` value passes through unchanged (DL-071); the dialect is never hardcoded |
| `config/CorsConfig.java` | `main.py:L20` — `CORS(app)` with no arguments | Permissive `CorsConfigurationSource`, unchanged (DL-051) |
| `config/ClockConfig.java` | *No source construct — net-new* — DL-278. `api/analytics.py:L13-14` read no clock, and the `AnalyticsService` it imported did not exist | One `Clock` bean, `Clock.systemUTC()`, so every observation-window bound `service/AnalyticsService` derives is read in UTC from an injected time source and not from a static call |
| `config/BuildProfileGuard.java` | *No source construct — net-new* — DL-279. The retired tree declared no profiles at all | Startup check that refuses to start when the build-scoped `test` profile is active outside the build, so the H2 URL, the fixed signing key and the bcrypt hash of `application-test.yml` cannot reach a deployed revision |
| `config/RequestMediaTypeConfig.java` | *No source construct — net-new* — DL-236. The behaviour it protects is the 415 the framework already answers for an absent `Content-Type`, which `api/settings.py` and the other three route modules never reached, Flask having not read the header | Withholds a `Content-Type` naming no concrete media type from request processing, ahead of the security chain, so `DefaultCorsProcessor` and the message converters no longer raise `IllegalArgumentException`; publishes the single declaration of that predicate, which `api/GlobalExceptionHandler` reads as well (DL-235) |
| `config/ContainerErrorResponseConfig.java` | *No source construct — net-new* — DL-237/DL-238. The behaviour it reproduces is `main.py:L31-37`: the retired tree's WSGI server had no equivalent of a connector-level rejection | Replaces the container's HTML `ErrorReportValve` with one writing the single-key envelope as `application/json`, taking its status and literal from the single declaration `api/GlobalExceptionHandler` publishes and applying the shared header policy of DL-277 and the permissive CORS parity of DL-051 |
| `config/WebClientConfig.java` | `tasks/tweet_monitoring.py:L45-51` — the tweepy `Stream` construction | `WebClient` bean for the X API v2 base URL (DL-012) |
| `config/RestClientConfig.java` | `services/notion_service.py:L8` — `Client(auth=…)` | `RestClient` bean carrying the Notion base URL, the bearer token and the `Notion-Version` header value, over a retained `HttpClient` bean published by this class with the connect bound and released by a bounded `@PreDestroy` — `shutdown`, a ten-second `awaitTermination`, then `shutdownNow` — with `destroyMethod` cleared and the unbounded inferred `close()` unused (DL-013, DL-150, DL-151, DL-221, DL-264). Every configured header value passes one strip-and-reject gate before it is installed (DL-289). The read bound is applied per request by the `JdkClientHttpRequestFactory` built over that client |
| `config/AsyncSchedulingConfig.java` | `main.py:L41-48` and `tasks/response_generation.py:L8` — the blocking initialiser and the broker-less Celery application | `@EnableScheduling` plus the single `taskScheduler` bean, which carries a pool size, a thread-name prefix, wait-for-tasks-on-shutdown and a thirty-second termination await (DL-251); the sole reader of the `response_generation_delay` row on the scheduling path, once per pass (DL-227), with the next instant computed at the completion of the previous pass (DL-228). The first pass runs immediately, the resolved delay is bounded, a failing trigger read leaves the configured value in force and cannot leave the task unscheduled (DL-251), and the task is registered only in a process whose `scanner.background` switches both hold (DL-250) |
| `security/SecurityConfig.java` | `main.py:L22` (`JWTManager(app)`), `core/security.py:L14-18` (the passlib context) and the eleven bare `@jwt_required` sites | One `SecurityFilterChain`, a `BCryptPasswordEncoder` bean and a configuration-backed `InMemoryUserDetailsManager` (DL-020/DL-021) |
| `security/JwtService.java` | `core/security.py:L6-12` — `create_access_token` | jjwt HS256, `exp = now + TTL`, `sub`-only claims (DL-014 … DL-018); construction refuses an unset, blank or unresolved-placeholder secret (DL-185) |
| `security/JwtAuthenticationFilter.java` | The `@jwt_required` decorator sites — a guard that enforced nothing | A `OncePerRequestFilter` that actually validates the bearer token (A1, DL-021); it writes two fixed sentences — one on acceptance and one when a presented token names a principal the credential store does not hold — and nothing at all when a token fails to verify, which `security/JwtService` records itself; no record carries a request method, path, token or principal (DL-111) |

### 2.3 API and DTOs

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `api/TweetController.java` | `api/tweets.py:L9-55` — all three routes | Paths, methods, the `page`/`per_page` names and their 1 and 10 defaults, and the status codes preserved; `@PathVariable String` on both path routes so a non-numeric segment answers 404 (DL-048); collaborators injected once, where `api/tweets.py:L14,L26,L39` constructed one per request |
| `api/ResponseController.java` | `api/responses.py:L8-65` — all four routes | Paths, methods and every status code and literal preserved: 400 `Tweet ID is required`, 201, 500 `Failed to generate response`, 400 `Update data is required`, 404 `Response not found or update failed` (DL-076). The route path never consults the background existence guard (DL-195) |
| `api/AnalyticsController.java` | `api/analytics.py:L7-25` — both routes | `GET /analytics/trends` and `GET /analytics/summary`, both zero-argument, so no query parameter is introduced: the observation window stays configuration-borne (DL-042, DL-247) and the day bucketing stays in the repository (DL-213). The summary is three statements — one projection per table read (DL-180, DL-247) |
| `api/SettingController.java` | `api/settings.py:L7-24` — both routes | Paths, methods, status codes and all four wire literals preserved |
| `api/AuthController.java` | *No source construct — net-new* — DL-019 | `POST /auth/token`, the only unauthenticated route; 401 handling is DL-078, the `sub` claim is DL-079 |
| `api/GlobalExceptionHandler.java` | `main.py:L31-37` — `@app.errorhandler(404)` and `(500)` | `{"error": "Not found"}` and `{"error": "Internal server error"}` reproduced exactly; per-route literals routed through the three exception types (DL-065/DL-066). Also declares the `ErrorAttributes` bean that renders the same envelope on the servlet `ERROR` dispatch, which `sendError` puts beyond the reach of any `@RestControllerAdvice`, and publishes the same status-and-literal selection to `config/ContainerErrorResponseConfig`. Spring Boot's `BasicErrorController` remains the mapped handler: this class declares no request mapping, implements no `ErrorController` and holds one nested type, the attribute source. Both withdrawn forms of that controller — the top-level `api/ErrorDispatchController` and the nested `ErrorEnvelopeController` — are gone, which keeps `api/` at the six classes the frozen inventory names (DL-183) |
| `dto/TweetDto.java` | `schema/tweet.py:L5-14` | Nine components, snake_case names, string identifier, arrays for the two delimited columns (DL-022/DL-023/DL-024). The canonical constructor rejects a null `id`, `content`, `like_count`, `created_at`, `doubt_rating` and `user_id` — the eight required fields of `:L6-14` less the two normalised lists — and leaves `quoted_tweet_id`, the sole `Optional[str]`, nullable (DL-080) |
| `dto/ResponseDto.java` | `schema/response.py:L4-9` | Five components; `id` and `tweet_id` serialise as strings; the canonical constructor rejects a null value for all five, which is what `:L5-9` declares required, raising a `NullPointerException` naming the wire key. This constructor is the only guard: `service/mapper/ResponseMapper` passes a null column value through to it (DL-080/DL-133), and `service/ResponseService.updateResponse` tests the row before saving, and a write that empties a required member is answered with the route's own 404 literal and not with a failed conversion (DL-244) |
| `dto/SettingDto.java` | `db/models.py:L40-44` | `{key, value, description}` — the shape that carries all three columns (DL-039) |
| `dto/AiToolDto.java` | `db/models.py:L33-37` | `{id, name, description}` |
| `dto/PaginatedTweetsDto.java` | `api/tweets.py:L18-21` | The `{"tweets": [...], "pagination": {...}}` envelope |
| `dto/PaginatedResponsesDto.java` | `api/responses.py:L17-20` | The `{"responses": [...], "pagination": {...}}` envelope |
| `dto/PaginationDto.java` | `api/tweets.py:L20` — the envelope key, whose inner keys the source never defined | `page`, `per_page`, `total`, `total_pages`, with the 1-based-to-0-based conversion (DL-038) |
| `dto/AnalysisResultDto.java` | `api/tweets.py:L52-55` | `{"tweet_id", "analysis_result"}`; the result is the sentiment score (DL-037) |
| `dto/CreateResponseRequest.java` | `api/responses.py:L38-41` | `{tweet_id}` bound as a raw `JsonNode` with `@NotNull` — one of the only two Bean Validation constraints in the tree (DL-050) — and `usableTweetId()` applying the truth test of the `if not tweet_id` guard to the carried value (DL-286) |
| `dto/UpdateResponseRequest.java` | `api/responses.py:L54` — the free-form `request.json` | Partial update of `content` and/or `is_approved` only, held as raw `JsonNode` components so a key's presence is independent of its value (DL-082). The compact canonical constructor accepts `content` only as a JSON string or an explicit JSON `null` and `is_approved` only as a JSON boolean or an explicit JSON `null`, and refuses the whole body for any other carried type: the `IllegalArgumentException` reaches the wire as 400 `{"error": "Bad request"}` through the request-body converter, naming no member, and the service is never entered (DL-231, DL-092). An explicit `null` is a write of `null` and is answered with the route's 404 literal once it empties a member `dto/ResponseDto` declares required, with the transaction rolled back (DL-244, DL-080). A body carrying neither key reaches 400 `Update data is required` before any row is read (DL-082). No Bean Validation constraint is declared on this record (DL-050) |
| `dto/UpdateSettingRequest.java` | `api/settings.py:L16-18` | `{value}` with `@NotNull` — the second of the two constraints (DL-050) |
| `dto/TrendsDto.java` | `api/analytics.py:L13-15` | Day-bucketed series over a configured window (DL-042), bucketed in JPQL (DL-213) |
| `dto/SummaryDto.java` | `api/analytics.py:L23-25`, with two key names evidenced at `tests/test_api.py:L50-51` | Seven aggregate metrics read under one snapshot (DL-041/DL-075/DL-180) |
| `dto/LoginRequest.java` | *No source construct — net-new* — DL-019 | `{username, password}`; both components render as redacted (DL-067) |
| `dto/TokenResponse.java` | *No source construct — net-new* — DL-019 | `{access_token, token_type, expires_in}` |
| `dto/ErrorResponse.java` | `main.py:L31-37` | Single `{error}` component matching all ten error bodies in the source; no second key is ever added (DL-210) |

### 2.4 Entities, repositories, mappers and utilities

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `entity/Tweet.java` | `db/models.py:L8-18` plus the relationship attached at `:L30` | `@Table(name = "tweets")`, nine columns with the five character columns stating the capacity-free `varchar` (DL-068), `@OneToMany` with `@OrderBy("id ASC")` and the lazy default (DL-162); `Integer` identifier (DL-138); identifier-based `equals`/`hashCode` (DL-023) |
| `entity/Response.java` | `db/models.py:L21-28` — the `Response` model | `@Table(name = "responses")`, five columns by their source names and types, `content` stating the capacity-free `varchar` and no declared length (DL-068), and the owning `@ManyToOne @JoinColumn(name = "tweet_id")` of the single association (DL-162). `generated_at` is minted once, truncated to microseconds, inside the inserting transaction (DL-232). A page of responses is read as the closed `ResponseRow` projection carrying the response columns plus `tweet.id`, so no `tweets` row is loaded (DL-245) |
| `entity/AiTool.java` | `db/models.py:L32-37` | `@Table(name = "ai_tools")`, three columns with `name` and `description` stating the capacity-free `varchar` (DL-068), no association (DL-070); identifier-based `equals`/`hashCode` (DL-023) |
| `entity/Setting.java` | `db/models.py:L39-44` | `@Table(name = "settings")`, `key` as `@Id`; both reserved-word columns quoted (DL-061) and all three stating the capacity-free `varchar`, the primary key included (DL-068/DL-069); identifier-based `equals`/`hashCode` (DL-023) |
| `repository/TweetRepository.java` | `db/database.py:L10-13` — the per-call session | `JpaRepository<Tweet, Integer>` with seven members: the pageable page read ordered by `id ASC` (DL-249), the keyset batch `findUnansweredBatchAfter` replacing `Tweet.query.filter(Tweet.response == None)` (DL-248), `findByIdForUpdate` carrying the bounded pessimistic write lock (DL-246), the `AnalysisSubject` projection and the single-statement `updateDoubtRating` of the analyze route (DL-263), the `[now-window, now]` day-bucketed trends query (DL-213, DL-247) and the one-statement summary aggregate (DL-180, DL-247) |
| `repository/ResponseRepository.java` | `db/database.py:L10-13` — the per-call session | `JpaRepository<Response, Integer>` with the `ResponseRow` projection page read plus its explicit count query (DL-245, DL-249), `existsByTweetId` for the cross-replica claim check read before the provider call and again inside the storing transaction (DL-195, DL-226, DL-252), and the approval counts projection (DL-180, DL-247) |
| `repository/AiToolRepository.java` | `db/database.py:L10-13` — the per-call session | `JpaRepository<AiTool, Integer>` plus `findNames(Pageable)`, which projects the `name` column alone and is bounded by the caller, so composing the stream rule set loads no `ai_tools` entity (DL-044, DL-254) |
| `repository/SettingRepository.java` | `db/database.py:L10-13` | `JpaRepository<Setting, String>` — the key is the primary key |
| `service/mapper/TweetMapper.java` | *Net-new class, derived from* the `to_dict()` called at `api/tweets.py:L19,L30` and never defined | Identifier-to-string and delimited-column-to-array conversion (A7, DL-023/DL-024); an explicit hand-written mapper bean that carries every `null` column value through (DL-133), the identifier included (DL-080) |
| `service/mapper/ResponseMapper.java` | The `response.to_dict()` calls at `api/responses.py:L18,L29,L47,L63` — a method the model never declared | Entity-to-`ResponseDto` and projection-to-`ResponseDto` mapping in one class: the identifier and the parent identifier are rendered as strings (DL-023), and every column value — a `null` included — is carried through unchanged, with an absent `tweet` association yielding a `null` `tweet_id` and no dereference. This class asserts no column present and rejects nothing; a row missing a required member fails in the `ResponseDto` constructor, which is the single declaration site of that requirement (DL-080, DL-133). The projection overload reads the parent identifier from the `ResponseRow` projection, so no `tweets` row is loaded to render a page (DL-245) |
| `service/mapper/SettingMapper.java` | *Net-new class, derived from* the serialisation `api/settings.py:L11,L24` performed inline and never factored out | Entity to `SettingDto` (DL-039), through the same explicit mapper layer as the other two (DL-133) |
| `util/DelimitedStringListConverter.java` | *Net-new class, derived from* `db/models.py:L15,L18` against `schema/tweet.py:L11,L14` — single `String` columns the schema exposed as `List[str]` with no conversion anywhere | Comma-delimited, blank-safe, no schema change; the one authorized codec, used by the JPA attribute converter alone — `service/NotionService` references it nowhere and neither delimited column is mirrored (DL-024/DL-164/DL-088) |
| `util/QueryParameters.java` | *Net-new class, derived from* `request.args.get('page', 1, type=int)` at `api/tweets.py:L12-13` and `api/responses.py:L11-12` | Reproduces Werkzeug's conversion-with-fallback: an absent, blank or non-numeric value yields the declared default and not an error status — DL-217, and the queryable-offset bound is DL-225 |
| `util/ConfiguredValues.java` | *No source construct — net-new* — DL-287 | The single definition of the unresolved-placeholder shape, replacing three copies in `security/SecurityConfig`, `security/JwtService` and `config/DatabaseUrlTranslator`; trims before matching so all three call sites are symmetric |
| `util/StreamRuleTerms.java` | *Net-new class, derived from* `tasks/tweet_monitoring.py:L53-55` — the deferred-work tag reading "Define keywords for streaming" and the empty `track` list | The one X filtered-stream rule grammar both callers hold to: one term becomes one rule, a term holding whitespace is rendered quoted and every other term unchanged, a term is admitted only within the 128-character bound and the letter-digit-space-hyphen-underscore-period-apostrophe allowlist, and the delimited `stream_keywords` value is split once here at a bound of 512 segments (DL-257, DL-254) |
| `util/LogSafe.java` | *No source construct — net-new* — DL-119. The retired tree carried no logging framework at all: `backend/app/**` holds no `logging`, `logger` or `getLogger` reference | Renders an identifier as `hmac256:` plus the first eight bytes of an HMAC-SHA-256 keyed with a per-process secret (DL-119), bounds a caller-supplied value against log injection (DL-149), and renders a failure as a type chain and an origin frame (DL-197), so the logging baseline (DL-052) emits fixed metadata without a vendor message, a stack or a stored value |

### 2.5 Exceptions

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `exception/NotFoundException.java` | The 404 branches across `api/*.py` — `'Tweet not found'`, `'Response not found'`, `'Response not found or update failed'`, `'Setting not found'` | Private constructor with a static factory per literal, so the wire message set is closed (DL-065/DL-066) |
| `exception/BadRequestException.java` | The 400 branches across `api/*.py` — `'Tweet ID is required'`, `'Update data is required'`, `'No value provided'` | Same closed-set treatment (DL-065/DL-066/DL-212) |
| `exception/ResponseGenerationException.java` | `api/responses.py:L49` — the 500 body `{"error": "Failed to generate response"}` | One literal, one outcome (DL-065/DL-076/DL-178/DL-212) |

### 2.6 Services and background tasks

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `service/TwitterService.java` | `services/twitter_service.py` — the whole module, including the four methods the controllers call and the class lacked | Six public operations: the pageable listing whose wire `page`/`per_page` contract is unchanged while rows are fetched, mapped and serialised in bounded chunks (DL-249); the single fetch; `updateTweetAnalysis`; `analyzeTweet`, which reads the row once as a projection, scores it with no transaction open and writes the derived rating in one statement (DL-263); the popularity gate in two overloads so a caller may resolve the threshold once per cycle (DL-040, DL-255); and `popularityThresholdInForce`, whose unparseable-row warning is suppressed by key (DL-255) |
| `service/SentimentAnalysisService.java` | `services/sentiment_analysis.py:L12-35` — `analyze_sentiment` and `calculate_doubt_rating` | `analyzeSentiment(String)` returning the bare document score, reconciling the two complementary broken sides of the source call (DL-036, DL-037), with a non-finite score rejected at the provider boundary (DL-233); `calculateDoubtRating(double)` transcribing `clamp((1 - score) * 5, 0, 10)` unchanged. The client is created on first use and released by a `@PreDestroy` that marks the bean destroyed before it waits, and `analyzeSentiment` tests that flag before it queues on the read lock as well as after, so a call arriving during a shutdown is rejected before it queues, under a ten-second per-attempt and thirty-second total `AnalyzeSentiment` deadline (DL-288, DL-268) |
| `service/NotionService.java` | `services/notion_service.py:L12-38` — `store_tweet` and `get_tweets`, plus the absent `update_tweet_response` called at `tasks/response_generation.py:L30` | Three operations over `RestClient`; Notion stays the secondary mirror and the relational database the system of record (DL-013, DL-088, DL-089). A mirror write is retried within a bounded budget with doubling backoff for a retryable answer only — 429, any 5xx and a transport failure — and the backoff cannot overflow (DL-253). A rejection is recorded with the status, the shaped `code` member, the provider request identifier and the character count of the provider explanation, never any run of its text (DL-084, DL-269) |
| `service/LlmService.java` | `services/llm_service.py:L6-32` — the whole module | Chat Completions replacing the removed `Completion.create(engine="text-davinci-002", …)`, with the source `max_tokens`, `temperature` and `n` carried as configuration (DL-011, DL-032, DL-033, DL-034, DL-145). The prompt template and the interpolated post body are verbatim — nothing is cut, folded or escaped (DL-035) — while the `Context:` clause carries at most ten AI tool names and two hundred characters (DL-265). `scanner.openai.n` must be exactly one, since only the first choice is consumed (DL-267). The client is created on first use and released by a `@PreDestroy` that marks the bean destroyed before it waits, so work arriving during a shutdown is rejected and a completion already in flight is awaited for up to thirty seconds (DL-085, DL-266) |
| `service/ResponseService.java` | The `ResponseService` imported at `api/responses.py:L3` — a class that did not exist | Four operations whose signatures the call sites dictate, plus the two background entry points `generateResponseIfAbsent(String)` and `generateResponseIfAbsentFor(Tweet)` (DL-076, DL-081, DL-086, DL-120, DL-211, DL-226). Every wire literal and status is preserved, and nothing here publishes to X. Generation is claimed in process, the cross-replica existence check runs before the paid provider call with no database connection held across it and again inside the storing transaction, and the parent-row lock is bounded at both statement and transaction level — the query-timeout hint on the locked read and a five-second transaction, the storing path holding a bounded copy of the injected `TransactionTemplate` so the shared bean is not mutated, and contention yields nothing stored (DL-195, DL-246, DL-252). A page is read as the `ResponseRow` projection and mapped in bounded chunks (DL-245, DL-249) |
| `service/SettingsService.java` | *Net-new class, derived from* the two call sites at `api/settings.py:L10,L20`; the class was imported at `:L3` and never existed (D4) | Instance methods on an injected bean, plus insert-only per-key seeding (DL-039/DL-040/DL-043/DL-073/DL-159) |
| `service/AnalyticsService.java` | The `AnalyticsService` imported at `api/analytics.py:L3` — a class that did not exist | Both zero-argument methods, computed as aggregates over the four existing tables only: no column, index, view or cache is added (DL-041, DL-042, DL-180, DL-213). The summary is three statements — one projection per table read — and the trends series is bounded to `[now - window, now]` from a validated finite positive `scanner.analytics.trend-window-days` (DL-247) |

### 2.6a Background tasks

| Target file | Source construct | Notes |
|-------------|------------------|-------|
| `task/BackgroundOwnership.java` | *No source construct — net-new* — DL-281. `main.py:L41-48` started both background paths in every process and coordinated nothing | The `SmartLifecycle` lease that admits one process at a time to the two background paths, held as one row of the existing `settings` table keyed `background_owner` — no table, column, index or constraint added (DL-281). A claim is a JPQL compare-and-set on the value just read; the mechanism is identical on every supported vendor and names no quoted column (DL-061). Every failure of a claim — a rejected update, an insert race, or any `DataAccessException` — leaves `isOwner()` false, so ownership is never inferred from a failure to disprove it. The phase orders the lease ahead of the stream client, the renewal is registered by `config/AsyncSchedulingConfig` and starts or stops the stream as ownership changes, and the release is itself a compare-and-set on the value this process wrote. Records name a correlation token for the instance identity, never the identity (DL-119) |
| `task/TweetStreamClient.java` | `tasks/tweet_monitoring.py:L36-55` and the `pass`-stub `stream_tweets` at `services/twitter_service.py:L16-23` | A lifecycle-managed client consuming the X API v2 filtered stream as chunked NDJSON with an app-only bearer token, replacing the retired v1.1 `statuses/filter` call (DL-012, DL-045, DL-046). The rule set is the configured base terms union the projected `ai_tools` names, validated against the one shared grammar of `util/StreamRuleTerms`, bounded to the provider rule cap and mutated in batches, with counts logged and no term value (DL-044, DL-052, DL-254, DL-257). A rule the endpoint refuses is reported with the summary counts and a fingerprint of the reflected expression and not the expression itself (DL-275), and every failure a record names is rendered through the shared log guard (DL-197). Reconciliation preserves each rule's identifier and tag and replaces a rule whose expression or tag differs (DL-261). No expansion is requested that the listener does not read (DL-262). Dispatch is sequential at a prefetch of one (DL-258), start and stop are serialised through one lock which drains in-flight dispatch before disposing (DL-259), the body carries a signal-idle bound (DL-256), malformed and over-long records are reported by windowed counters (DL-222, DL-260), and the client starts only in a process whose `scanner.background` switches both hold (DL-250) |
| `task/TweetStreamListener.java` | `tasks/tweet_monitoring.py:L8-34` — a class that never subclassed a tweepy listener, so `on_status` was never invoked | The four steps of the source handler with both of its deferred-work tags closed: the gate, a real `TweetRepository.save`, the secondary Notion mirror and the response generation (DL-040, DL-049, DL-195, DL-224). A record whose required wire member is absent or mistyped is named at `WARN` and skipped with no neutral value substituted (DL-080, DL-223), a provider timestamp in the future is refused (DL-247), and the popularity threshold may be supplied once per cycle through the second overload (DL-255) |
| `task/ResponseGenerationScheduler.java` | `tasks/response_generation.py:L35-50` — `schedule_response_generation`, absorbing the body of the Celery task at `:L10-33` | A completion-based `TriggerTask` registered by `config/AsyncSchedulingConfig.configureTasks` — the single carrier of `@EnableScheduling` — in place of `while True` plus the twice-broken `time.sleep(...)` at `:L50` (DL-047, DL-227, DL-228, DL-251). The unanswered backlog is drained in bounded keyset batches within one pass, with the persistence context cleared between batches, so no pass materialises the whole backlog (DL-248). Each candidate is handed on without a second read (DL-226), and a stored reply whose mirror was rejected after its retries is counted separately in the pass summary (DL-253) |

### 2.7 Tests

The `Cases` column records the number of tests Surefire executes for the class, measured on the
delivered suite and not estimated; the forty-six rows below sum to the 2464 cases
`mvn clean verify` runs. Nested classes are counted inside their parent. `task/TweetStreamClient`
carries two classes — protocol and lifecycle are asserted separately, which is DL-214.

| Target file | Source construct | Cases | Notes |
|-------------|------------------|-------|-------|
| `BuildDependencyContractTest.java` | *No source construct — net-new* — DL-169/DL-170/DL-240/DL-241. The retired tree carried no dependency manifest at all | 16 | The declared dependency set against the module's own contract: the four overridden version properties and the floor each must hold, the six explicitly pinned coordinates, the coordinates left BOM-managed, the scope every runtime and test dependency carries, and the support-horizon pointer the manifest states for the pinned Boot line (DL-003) |
| `DecisionLogCitationTest.java` | *No source construct — net-new* — DL-216. The retired tree carried no decision log | 10 | The log and every citation of it held to one another: the unbroken identifier sequence, four populated columns per row, every citation resolvable, the population both documents state, the pointer enumeration, no pointer cited from outside the log, the delivered-Java-file inventory with its case total, the character-column mapping, at least one row cited from the tree, and no decision rationale in any comment of `src` or the POM (Rule 1, DL-058) |
| `DecisionPointerIntegrityTest.java` | *No source construct — net-new* — DL-058/DL-099/DL-216 | 3 | Every `DL-` pointer written in a delivered file resolves to a row that exists, the identifiers are unique and contiguous from one, and the scan is proved to have reached the delivered tree and not passed on an empty set |
| `DocumentationConsistencyTest.java` | *No source construct — net-new* — DL-216 | 10 | The two Rule 1 artifacts against the repository: the retired-source inventory, the delivered-target inventory, the per-class case counts, the coverage summary and the delivery-state prose, every figure derived from the tree and not restated from a plan; every repeated population claim measured at each of its occurrences, so a contradictory restatement fails; and this file held to the wording set of DL-058 |
| `OperationsContractTest.java` | The four operations files this migration edits — `infrastructure/docker/Dockerfile.backend`, `.github/workflows/ci.yml`, `.github/workflows/cd.yml` and `scripts/deploy.sh` | 16 | Digest-pinned base images (DL-106) and the patched Java runtime (DL-243), the unprivileged runtime identity and the jar mode (DL-107), the container health probe and heap sizing (DL-218), JDK 21 with `mvn -B clean verify` in place of the five retired Python steps with every frontend step left standing (DL-004, DL-005, DL-057), the 40-hex commit-SHA form of the one action reference this migration authored and its absence from the CD workflow (DL-103), the retired workflow's `node-version` input carried forward unchanged, declared exactly once and carrying no decision pointer (DL-074, DL-285), the `-f` build-context edit as the only CD change and the absence of any out-of-inventory hygiene file (DL-056, DL-215, DL-276), and the Maven package line of the deployment script (DL-053) |
| `ScannerApplicationTests.java` | `tests/test_api.py` — the file that could not be collected | 60 | The widest test in the suite: the whole application context under the `test` profile with nothing mocked, driven through `MockMvc` in a `MOCK` web environment (DL-274). It asserts the composition root (DL-209), one bean of each declared type, a bare 401 on each of the eleven pre-existing routes, `POST /auth/token` answering 200, an authenticated read, the finite page-size bound both list routes serve on the wire (DL-123), ingestion reporting stopped, the error-dispatch shape (DL-183), the published pool and the bounded graceful shutdown (DL-270, DL-271), and the profile's bound values |
| `TraceabilityCoverageTest.java` | *No source construct — net-new* — DL-058/DL-216. The retired tree carried no traceability matrix | 2 | This file against the delivered tree in both directions: every delivered class is named by exactly one row, and every Java path this file names exists on disk |
| `api/AnalyticsControllerTest.java` | `api/analytics.py:L7-25` and `tests/test_api.py:L47-59` | 14 | Both zero-argument routes through `@WebMvcTest`: the summary metric set, the day-bucketed trends envelope, and that neither route accepts a query parameter (DL-041/DL-042) |
| `api/AuthControllerAdviceTest.java` | *No source construct — net-new* — DL-019/DL-117. The source registered no token route at all | 5 | A failure of the authentication provider behind `POST /auth/token` is rendered by `api/GlobalExceptionHandler`, not by the controller, so the 500 body stays the one literal of `main.py:L35-37` |
| `api/AuthControllerTest.java` | *No source construct — net-new* — DL-019 | 111 | The token route end to end through `@WebMvcTest` with the real `SecurityConfig` imported: issuance without an `Authorization` header, the credential-length ceiling, the empty 401 for every rejection, the bearer challenge, scheme casing, the body-size limit, and the bcrypt configuration matrix including the unresolved-placeholder guard, and the bound on the credential verifications in progress — the permit count, the 401 a request receives while every permit is held, the permit returned on each of the three exits, the over-length credential that seeks no permit, and the measured peak under a load of more callers than permits (DL-116/DL-118/DL-189/DL-272) |
| `api/GlobalExceptionHandlerTest.java` | `main.py:L31-37` and `tests/test_api.py` | 134 | Both error envelopes byte-for-byte, every per-route literal, the three-way split of the `HttpMessageConversionException` hierarchy driven through the framework's own converter and resolver (DL-092/DL-188), the servlet `ERROR` dispatch through the `ErrorAttributes` bean and the two published statics — including that no nested type of the handler is an `ErrorController` or carries a request mapping (DL-183) — and the catch-all record: bounded type chain, originating frame and correlation token with no throwable attached, five hostile failure messages proved absent from it, and the sanitized detail present at `DEBUG` and absent at `INFO` (DL-197) |
| `api/ResponseControllerTest.java` | `api/responses.py:L8-65` and `tests/test_api.py` | 111 | All four routes with every status code and literal, `page` and `per_page` bound as text with the 1 and 10 fallbacks, an unparseable identifier answering 404 and not 400 (DL-048/DL-217), and the update body's two outcomes — a usable carried member forwarded as the value it carries and an unusable one answered 400 while the body is bound (DL-082/DL-231/DL-244) |
| `api/SettingControllerTest.java` | `tests/test_api.py:L36-44` and `api/settings.py:L7-24` | 59 | A `@WebMvcTest` slice over both routes, running behind the real imported `SecurityConfig` chain: the array shape of `GET /settings` asserted as a parsed `JsonNode` that is an array and not an object, the three-member element, no member keyed by a setting name, the empty table, the 200 update, the two service arguments captured verbatim, the `No value provided` 400 with no field name and no `ProblemDetail` member, the empty string and a JSON boolean and a JSON number all accepted, the `Setting not found` 404, validation before lookup, an underscored, a numeric-looking and a hyphenated key each reaching the service as a `String` with 404 and never 400 or 500, the `Internal server error` 500 on both routes, a bare 401 with an empty body and a `Bearer` challenge on both routes, the `Bad request` 400 for an unbindable or repeated-member body, 415, 405 with `Allow`, 406, the trailing-slash paths, and the absence of any prefixed path (DL-021/DL-039/DL-043/DL-048/DL-050/DL-092/DL-188) |
| `api/TweetControllerTest.java` | `api/tweets.py:L9-55` and `tests/test_api.py` | 54 | The three routes, the pagination envelope, degenerate `page` values normalised to the first page, the `Tweet not found` literal and the analyze result shape (DL-037/DL-048/DL-217). Every addressed row carries a doubt rating, which `dto/TweetDto` requires (DL-080) |
| `config/AsyncSchedulingConfigTest.java` | `tasks/response_generation.py:L41-50` — the `while True` loop | 37 | The completion-based `TriggerTask`: the next instant is the previous completion plus the effective delay, so pacing is fixed **delay** and not fixed rate; the `settings` row overrides configuration, and one scheduler is published, named for this application's scheduled work, that awaits a running pass at shutdown (DL-047/DL-251) |
| `config/BuildProfileGuardTest.java` | *No source construct — net-new* — DL-279. The retired tree declared no profiles | 14 | The guard's decision matrix: the build-scoped `test` profile refuses startup outside the build and is accepted under it, so the H2 URL and the fixed signing key cannot reach a deployed revision |
| `config/ContainerErrorResponseConfigTest.java` | *No source construct — net-new* — DL-237/DL-238 | 42 | The container-level error surface: the status and literal matrix, a bodyless 401 and 403, a non-error status and an already-written response left alone, the shared header policy applied against the rejected-request view, the CORS parity headers with `Vary` never duplicated, `Strict-Transport-Security` only for a request the configuration reports as secure, an unresolvable CORS policy, the absence of HTML, of a server token and of any failure detail, and the customizer replacing the container's own valve |
| `config/DataSourceConfigTest.java` | *No source construct — net-new* — DL-027/DL-270/DL-271 | 36 | The published pool: the JDBC URL and credential pair taken from the translated `scanner.database-url`, the eight allowlisted `scanner.datasource.pool.*` settings a deployment configures reaching the pool with their declared defaults and range checks, the sixteen `spring.datasource.*` connection-identity keys refusing to start, any other key under that unread prefix being warned about and ignored, and an unsupported scheme refusing to start |
| `config/DatabaseUrlTranslatorTest.java` | *No source construct — net-new* — DL-027/DL-064/DL-071/DL-072/DL-187 | 119 | Translation, MariaDB-to-MySQL mapping, the refusal of an h2 scheme, credential extraction/rejection on both paths, every identity and primary password property name, the refusal of a secret-bearing property on both paths under both vendor aliases with every offender named and no value reproduced, the benign vendor properties that still round-trip, look-alike properties, ports, schemes and redaction (DL-072) |
| `config/HttpChainIntegrationTest.java` | *No source construct — net-new* — DL-051/DL-183/DL-237/DL-238/DL-240 | 7 | The running chain on a real connector: an oversized header and an invalid request target each answer the sanctioned JSON envelope, and the CORS and transport-security headers apply to a connector rejection as well as to a controller answer |
| `config/MainProfileConfigurationContractTest.java` | `core/config.py:L4-15` and the `.env` convention at `:L13-15` | 16 | The production `application.yml` through Spring's own YAML loader, placeholder resolver and binder: the nested-default Twitter aliases, the pinned port, the explicit servlet web type and the schema-bootstrap setting (DL-026, DL-027, DL-029, DL-030, DL-031) |
| `config/RequestMediaTypeConfigTest.java` | *No source construct — net-new* — DL-235/DL-236 | 53 | The media-type predicate over wildcard, unparseable, concrete and blank values; the filter withholding the header and passing a concrete one through untouched; every other header and the body reachable downstream; the header copy every component of the request path performs no longer raising; and the registration ordered ahead of the security filter chain |
| `config/RestClientConfigTest.java` | `services/notion_service.py:L8` — `Client(auth=…)` | 33 | The Notion base URL, the version header actually sent from configuration and the bearer token, plus the CR/LF guard on a configured header value (DL-013) |
| `config/WebClientConfigTest.java` | `tasks/tweet_monitoring.py:L45-51` — the tweepy `Stream` construction | 5 | The production X API `WebClient` bean through a recording exchange function: each consumer path resolves against the API host root, JSON `Accept` and the backend user agent are installed as defaults, and no credential or request content type is preinstalled (DL-012/DL-045/DL-046) |
| `repository/JpaMappingIntegrationTest.java` | `db/models.py` | 48 | `@DataJpaTest` over table names, column names, physical JDBC metadata, the generated character type on all three dialects, exact association ordering, real response update mapping and two-service-instance locked storage (DL-061/DL-068/DL-069/DL-082/DL-195) |
| `security/CachedBodyRequestTest.java` | *No source construct — net-new* — DL-118 | 8 | The re-readable request body wrapper: the body is readable twice and the cached copy is bounded |
| `security/JwtAuthenticationFilterTest.java` | The bare `@jwt_required` sites, which enforced nothing | 17 | The guard the source lacked: a valid token authenticates, a token naming a principal the credential store no longer holds does **not**, an absent or malformed header continues unauthenticated, and no request method, URI or principal reaches the log |
| `security/JwtServiceTest.java` | `core/security.py:L6-12` | 100 | Mint/parse round trip, expiry offset, algorithm matrix, and the key-material contract: a Base64 and a Base64URL encoding are both read, an encoding of exactly 32 bytes is accepted, a passphrase whose decoded material falls short is refused, a value in neither alphabet is refused, an encoding of no bytes is read as an unconfigured secret, and surrounding whitespace is trimmed (DL-014 … DL-018, DL-186) |
| `security/RequestBodyLimitIntegrationTest.java` | *No source construct — net-new* — DL-118 | 5 | The one request-body bound the chain enforces, against a running server: an oversized `POST /auth/token` body is refused before any credential comparison, a body far larger than that bound reaches a pre-existing route unbounded, the same large body carrying no token is answered with the chain's bare 401, and a read route carrying no body is unaffected |
| `service/AnalyticsServiceTest.java` | *No source construct — net-new* — DL-041/DL-042 | 42 | The summary metric set and the day-bucketed trend series over the four existing tables |
| `service/LlmServiceTest.java` | `tests/test_services.py:L44-48` | 172 | The delivered Chat Completions call, String generation result, unusable-output matrix and lazy-client behaviour (DL-081/DL-083/DL-085), plus the two wire records' null contract asserted component by component — every field `schema/response.py:L5-9` and `schema/tweet.py:L6-14` declare required is rejected with the wire key in the message, and a null `quoted_tweet_id` is accepted (DL-080) |
| `service/NotionServiceTest.java` | `tests/test_services.py:L28-38` | 113 | All three operations plus transport failures, unconfigured database id, null bodies and cursor handling, and the read-path skip of DL-219: a page omitting any one of the five required mirrored properties is left out, and a response holding one incomplete page still yields the complete ones |
| `service/ResponseServiceTest.java` | *No source construct — net-new* — DL-096, DL-211 | 136 | The four route operations plus the background-only generation operation, canonical identifier claims, parent-lock orchestration, writable-value updates including the ten carried values the record refuses and the explicit `null` it admits, real mapper coverage, the absence of any publish path, and the finite page-size bound of DL-123 (DL-081/DL-082/DL-122/DL-123/DL-195/DL-231/DL-244) |
| `service/SentimentAnalysisServiceTest.java` | `tests/test_services.py:L58-65` | 60 | Finite and clamping vectors, NaN and both infinities, null input, propagated client failure and client lifecycle |
| `service/SettingsServiceSeedingIntegrationTest.java` | *No source construct — net-new* — DL-040 | 11 | A real Boot context over H2 publishing a real `ApplicationReadyEvent`; idempotence and no-overwrite |
| `service/SettingsServiceTest.java` | `tests/test_api.py:L36-44` and `tests/test_services.py` | 73 | Both operations through the real `SettingMapper`, plus the seeding-normalisation matrix |
| `service/TwitterServiceTest.java` | `services/twitter_service.py:L42-50` and `tests/test_services.py:L12-22` | 149 | The popularity gate: the inclusive boundary at 99/100/101 against the configured default, the same boundary at two other thresholds, the `settings` row overriding configuration, the configured fallback for a row holding no integer, whitespace discarded, a threshold at or below zero honoured as stored, and an absent like count answered without reading the table (DL-040); and the finite page-size bound of DL-123 — a size within the maximum restated as requested and a larger one served and restated as the maximum |
| `task/BackgroundOwnershipTest.java` | *No source construct — net-new* — DL-281 | 40 | The lease against a hand-advanced clock and an inline transaction template: the claim on an absent, null-valued, unreadable and lapsed row, the refusal of a live foreign lease, the renewal of its own, the compare-and-set a competing claimant loses, the insert race, the fail-closed answer to an unreachable store, the local term lapsing, the release by compare-and-set and the untouched lease of a process that lost it, the stream started on takeover and stopped on loss, the switch matrix `isAutoStartup()` reports, the phase ordering against the stream client, and that no record carries the raw instance identity |
| `task/ResponseGenerationSchedulerTest.java` | `tests/test_tasks.py` and `tasks/response_generation.py:L35-50` | 22 | One pass draining the whole backlog as bounded keyset batches of `findUnansweredBatchAfter(Integer, Pageable)` with the persistence context cleared between them (DL-248), per-candidate isolation so one failure does not end the pass, the skip when a reply appeared after the query ran, the pass summary (DL-195), and the rejection a contentless reply now raises at record construction before anything is mirrored (DL-080) |
| `task/TweetStreamClientLifecycleTest.java` | `tasks/tweet_monitoring.py:L36-55` — the blocking `filter(track=…)` call | 65 | The `SmartLifecycle` contract: `start`, `stop`, `isRunning`, idempotence, and that `start()` returns without a blocking read, with composition subscribed on `boundedElastic` (DL-214) |
| `task/TweetStreamClientTest.java` | `tasks/tweet_monitoring.py:L36-55` and `services/twitter_service.py:L16-23` | 139 | The protocol through a stubbed `ExchangeFunction`: app-only token exchange, rule reconciliation, NDJSON reassembly across chunk boundaries, `429` with `x-rate-limit-reset`, backoff with jitter, reconnect on a clean end and the blank-credential stop (DL-207/DL-214) |
| `task/TweetStreamListenerTest.java` | `tests/test_tasks.py` and `tasks/tweet_monitoring.py:L8-34` | 45 | The four ingestion steps in order, persistence closing the deferred-work tag at `:L29`, the guarded generation trigger closing the one at `:L32`, required-field rejection before save, a stored entity mapped through the real `TweetMapper`, and the rejection a contentless reply now raises at record construction before the mirror is reached (DL-080/DL-195/DL-199) |
| `util/ConfiguredValuesTest.java` | *No source construct — net-new* — DL-287 | 25 | The single definition of the unresolved-placeholder shape and the trim that keeps all three call sites symmetric |
| `util/LogSafeTest.java` | *No source construct — net-new* — DL-208 | 64 | Every log-metadata primitive: the correlation token keyed with this process's own secret — stable within the process and unrelated to the unkeyed digest of the same value — a failure's simple type name, its bounded cause chain, its originating frame and its sanitized bounded detail, with no control character or line break rendered by any of them (DL-119, DL-197) |
| `util/QueryParametersTest.java` | `api/tweets.py:L12-13` and `api/responses.py:L11-12` | 69 | The conversion matrix of DL-217 — absent, blank, whitespace, non-numeric, signed, overflowing and non-ASCII digit input against the declared defaults — and the finite page-size bound of DL-123: a size within the bounds served unchanged, a larger one reduced to the maximum, a size below the minimum served the route default, and the declared minimum and maximum themselves |
| `util/StreamRuleTermsTest.java` | *No source construct — net-new* — DL-257. `tasks/tweet_monitoring.py:L53-55` left the keyword set undefined and validated nothing | 94 | The one X rule grammar both callers hold to: the character allowlist, the letter-or-digit edges, the 128-character bound measured after trimming, a letter or digit of any script admitted, the quoting of a phrase, the 512-segment split bound and the agreement between `countUsable` and the per-term decision (DL-257) |

### 2.8 Documented constructs not introduced

These rows record an absent target as a decision and not as a coverage gap. Each
names a construct some document in the repository describes, which the retired Python tree never
implemented and the Java service does not add.

| Documented construct | Where documented | Delivered state |
|----------------------|------------------|-----------------|
| `GET /api/aitools`, `POST /api/aitools` and `PUT /api/aitools/{id}` — a third AI-tool route group | `documentation/Technical Specifications.md:L390`, `:L391`, `:L392` | **Not introduced.** No blueprint served them: `main.py:L26-29` registers exactly four blueprints and none declares an AI-tool route, so the retired tree never implemented them either. The delivered route count stays at the eleven of §1.2 plus the one net-new route of DL-019. `dto/AiToolDto` has no controller consumer: it exists for `repository/AiToolRepository`, which supplies `ai_tools.name` values to the streaming rule set (DL-044), and the `ai_tools` table itself is preserved by §1.3 |
| The `/api` path prefix on every documented route | `documentation/Technical Specifications.md:L386-387` and `frontend/src/services/api.ts:L25` | **Not introduced.** Routes stay unprefixed exactly as `api/*.py` declared them (§1.2); the divergence is defect A15 and is recorded as unreconciled in DL-059 |

### 2.9 Operations files edited outside `backend/`

Line ranges name the *original* lines that changed, so each row reads as a source-to-target edit.

| Target file | Original lines changed | Source construct | Notes |
|-------------|------------------------|------------------|-------|
| `infrastructure/docker/Dockerfile.backend` | `L2`, `L8-11`, `L14`, `L20`, `L22-28` | The Python image, the `pip install` from a manifest that never existed, `CMD ["python","app.py"]` against a file that never existed, and the assistance-banner block | Multi-stage build on `maven:3.9.16-eclipse-temurin-21` plus an `eclipse-temurin:21.0.11_10-jre` runtime, both pinned by patch tag and `sha256` digest, with the digest-refresh cadence, the advisory counts measured at the review cutoff and the applicability exceptions recorded in DL-106; running the Boot jar as the unprivileged `10001:10001` identity (DL-107) on the checksum-verified Temurin 21.0.12+8 runtime that replaces the base image's own (DL-243), with the container health check and container-aware heap sizing of DL-218. `EXPOSE 5000` unchanged (DL-005/DL-029) |
| `.github/workflows/ci.yml` | `L16-19`, `L26-29`, `L35-39`, `L47-50`, `L56-59` | `setup-python` 3.9, `pip install -r backend/requirements.txt`, `flake8`, `mypy`, `pytest`, `python -m build` | JDK 21 (Temurin) with Maven caching in place of the Python toolchain step, and the single command `mvn -B clean verify` with `working-directory: ./backend` in place of the four retired Python steps (DL-004/DL-005/DL-057). One value inside `L16-19` carries a further change: the toolchain action is `actions/setup-java` pinned to a 40-hex commit SHA on the `node24` runtime line with its resolved release beside it (DL-103). Every other line is byte-identical to the source branch: the single `build-and-test` job, `actions/checkout@v2`, `actions/setup-node@v2` with `node-version: '14'` and no comment (DL-074, DL-285), `npm ci`, the frontend lint and type-check step whose scripts the manifest does not declare, `npm test`, `npm run build` and the staging placeholder; no `permissions` key, no `persist-credentials` input and no job split (DL-059/DL-104/DL-215) |
| `.github/workflows/cd.yml` | `L36` only | `docker build -t $BACKEND_IMAGE ./backend`, which found no Dockerfile | `-f infrastructure/docker/Dockerfile.backend` added, context unchanged (A5, DL-056). No other line of the file changes: the `gcr.io` image name, `actions/checkout@v2`, `google-github-actions/setup-gcloud@v0.2.1`, `gcloud auth configure-docker`, `docker push $BACKEND_IMAGE`, `gcloud run deploy code-skeptic-backend --image $BACKEND_IMAGE … --allow-unauthenticated`, the frontend `npm install`/`npm run build` pair, `gsutil -m rsync`, the CDN backend-bucket creation, the empty post-deployment test and the Slack notification all stand as the source branch wrote them, and the pre-existing `workflow_run.workflows` name mismatch is retained (DL-102/DL-103/DL-104/DL-105/DL-136/DL-215) |
| `scripts/deploy.sh` | `L18` only | `npm run build`, executed inside `cd backend`, which was wrong for a Python backend and is wrong for a Java one | `mvn clean package` (DL-004/DL-053). The following `gcloud builds submit --tag …` line is left standing, and the container-build mechanism here differs from the `docker build`/`docker push` pair `cd.yml` uses — this migration's scope reaches only the backend-build line of this file, and the divergence is argued in DL-215 |

`infrastructure/terraform/**`, `scripts/setup_environment.sh` (DL-054), `infrastructure/docker/Dockerfile.frontend`, `frontend/**` (DL-059), `documentation/**` and `README.md` are unchanged.

These four files are the whole of the operations delivery. No file outside the plan's inventory is
added at the repository root or under `.github/`: the `.yamllint.yml` and `.github/dependabot.yml` an
earlier revision delivered are withdrawn and absent from the tree, which `OperationsContractTest`
asserts (DL-103/DL-276). Each row above is the line-level carve-out the plan authorises and nothing
wider — no registry, secret-binding, revision-scaling, CPU-allocation, artifact-handoff, job-splitting
or deployment-probe change is present (DL-215).



---

## 3. Targets not delivered at this checkpoint

**None.** Every target named anywhere in this file — each main source class, each resource, each build
file and each test class — exists on disk, verified by enumerating `backend/src/main/java`,
`backend/src/test/java`, `backend/src/main/resources`, `backend/src/test/resources`, `backend/docs` and
the three build files at the repository path. No row above carries the `PLANNED` status and this section
lists nothing.

The six test classes an earlier revision of this section listed as pending are delivered and carry rows
in §2.7, which is the one place their case counts are stated: `api/TweetControllerTest`,
`api/ResponseControllerTest`, `api/AnalyticsControllerTest`, `ScannerApplicationTests`,
`task/ResponseGenerationSchedulerTest` and `task/TweetStreamListenerTest`. Their arrival closes
the consequence that revision recorded: `api/TweetController`, `api/ResponseController`,
`api/AnalyticsController`, `task/TweetStreamListener` and `task/ResponseGenerationScheduler` each now
carry a committed test of their own and are not covered only indirectly. The suite is
forty-six classes running 2464 cases, measured from the delivered Surefire run; `api/ErrorDispatchControllerTest`
is the one class an earlier revision counted that the tree no longer holds: DL-183 withdrew the
controller it covered. The additions beyond the frozen test inventory are enumerated and
authorised in DL-216.

## 4. Coverage summary

Every figure in this table was obtained by enumerating this repository, not copied from a planning
document. The `Required` column counts constructs read back from the retired files at commit
`80f1d53d^`; the delivered Java counts come from listing `backend/src/main/java` and
`backend/src/test/java`; the case count comes from `backend/target/surefire-reports`. A reviewer can
reproduce every row.

| Coverage set | Required | Covered by a row | Delivered | Planned |
|--------------|----------|------------------|-----------|---------|
| Retired Python files (§1.1) | 20 | 20 | 20 fully | 0 |
| HTTP routes (§1.2) | 11 preserved + 1 net-new | 12 | 12 end to end | 0 |
| Tables (§1.3) | 4 | 4 | 4 | 0 |
| Columns (§1.3) | 20 | 20 | 20 | 0 |
| Associations (§1.3) | 1 | 1 | 1 | 0 |
| Business rules (§1.4) | 2 | 2 | 2 | 0 |
| Source configuration keys (§1.5) | 15 | 15 | 15 | 0 |
| Target configuration surface (§1.5.1) | 51 placeholders + 9 literal settings | 60 | 60 | 0 |
| Seeded `settings` rows (§1.5.1) | 3 | 3 | 3 | 0 |
| External integrations (§1.6) | 4 | 4 | 4 | 0 |
| Retired PyPI packages (§1.7) | 15 | 15 | 15 | 0 |
| Named defects D1–D6 (§1.8) | 6 | 6 | 6 | 0 |
| Additional defects A1–A32 (§1.8) | 32 | 32 | 14 `Delivered`, 18 `Retained by decision` in out-of-scope `frontend/**` carrying the 22 confirmed client-seam items | 0 |
| Scaffolding markers (§1.9) | 23 | 23 | 23 | 0 |
| Python test files (§1.10) | 3 | 3 | 1 fully, 2 partly | 0 |
| Delivered main Java classes (§2.2–§2.6) | 68 | 68 | 68 | — |
| Delivered test Java classes (§2.7) | 46 | 46 | 46 | — |
| Delivered resources and build files (§2.1) | 7 | 7 | 7 | — |
| Operations files edited (§2.9) | 4 | 4 | 4 | — |
| Source body expectations (§1.11) | 3 | 3 | 1 honoured, 2 discounted by decision | 0 |
| Documented constructs not introduced (§2.8) | 2 | 2 | 0 introduced, 2 recorded | 0 |
| Delivered test cases (§2.7) | — | 46 classes | 2464 cases, 0 failing | — |
| **Delivered artifacts under `backend/`** | **121** | **121** | **121** | **0** |
| Planned targets (§3) | 0 | — | — | 0 |

Every construct in every required coverage set has a row, and every row is `Delivered`,
`Partly delivered`, `Retired` for a construct this migration removed or `Retained by decision` for one it
leaves standing — no row is `PLANNED`. Both directions are complete: §1 maps every source
construct forward, and §2 maps each of the one hundred and twenty-one delivered artifacts under `backend/`,
plus the four operations files edited outside it, back to a source construct or marks it net-new with the
decision that authorises it. Each of those one hundred and twenty-one appears exactly once in §2 — no
artifact is missing and none is listed twice. Every `DL-` identifier cited in this file resolves to a row
in `docs/DECISION_LOG.md` carrying all four of its content columns, and no identifier cited here is a
superseded pointer standing in for the entry that owns the subject.

This file carries mappings and decision-log pointers only. It states no rationale: where a row involves a
choice open to a competent engineer, it names the identifier and stops, leaving
`docs/DECISION_LOG.md` as the single source of truth for why.

Marker count check: scanning `backend/**` for either scaffolding token — the assistance banner or the
deferred-work tag defined in §1.9 — returns nothing. That covers `backend/src/**`, `backend/pom.xml`,
`backend/docs/DECISION_LOG.md` and this file, against twenty and three occurrences respectively in the
retired Python tree. §1.9 accounts for all twenty-three by kind, source location and resolution without
reproducing either token, so the inventory is complete and the scan stays clean.
