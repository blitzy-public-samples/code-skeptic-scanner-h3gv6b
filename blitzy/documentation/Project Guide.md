# 1. Executive Summary

## 1.1 Project Overview

Code Skeptic Scanner watches public posts on X for skeptical opinions about AI coding tools, rates the doubt each post expresses, drafts a reply for human approval and mirrors the record into Notion. Its server side has been re-platformed from Python and Flask onto Java 21 and Spring Boot, built by Maven from `backend/`, preserving the eleven HTTP routes, the four relational tables and the two business rules the previous service defined. Its users are the analysts who triage flagged posts through the existing single-page application and the operators who run the service. Scope was server-side only: the frontend, the planning documentation and the Terraform module were left untouched.

## 1.2 Completion Status

```mermaid
%%{init: {"theme": "base", "themeVariables": {"pie1": "#5B39F3", "pie2": "#FFFFFF", "pieStrokeColor": "#B23AF2", "pieOuterStrokeColor": "#B23AF2", "pieSectionTextColor": "#1A1A1A", "pieTitleTextColor": "#B23AF2", "pieLegendTextColor": "#B23AF2"}}}%%
pie title Completion 83.8 percent
    "Completed Work (456h)" : 456
    "Remaining Work (88h)" : 88
```

| Metric | Value |
|--------|-------|
| Total Hours | **544** |
| Completed Hours (AI + Manual) | **456** (456 autonomous + 0 manual) |
| Remaining Hours | **88** |
| Percent Complete | **83.8%** |

Completion is measured over the plan's own scope plus the standard work of getting it to production: 456 ÷ (456 + 88) = 83.8%. Every scoped deliverable is complete; the remaining 88 hours are credential provisioning, environment and release work, and three follow-ups recorded in §5.2.

## 1.3 Key Accomplishments

- ✅ All eleven original routes preserved unprefixed, plus a working token endpoint — twelve mappings.
- ✅ Four tables, twenty columns and one ordered association, with no constraint the old schema lacked.
- ✅ Doubt rating and popularity gate transcribed exactly, with their boundary values under test.
- ✅ The three services the previous code imported but never defined now implemented and serving.
- ✅ Ingestion consumes the X v2 filtered stream and persists what it admits.
- ✅ Response generation paces itself from a settings row an operator changes without a restart.
- ✅ 1,705 JUnit 5 cases run green offline from a clean checkout, with no database or credentials.
- ✅ A 310-entry decision log and a bidirectional traceability matrix cover every choice and mapping.

## 1.4 Critical Unresolved Issues

| Issue | Impact | Owner | ETA |
|-------|--------|-------|-----|
| Cloud Natural Language access is not granted for the runtime identity | `POST /tweets/{tweetId}/analyze` answers 500 and the doubt-rating write path cannot run end to end | Platform owner | 4h |
| X API v2 application credentials are not provisioned (filtered stream is a paid-tier capability) | Ingestion declines at start-up, so no post is captured | Platform owner | 6h |
| Managed database and the three mandatory secrets are not provisioned outside local development | The service fails fast without `DATABASE_URL`, `SECRET_KEY` and `AUTH_PASSWORD_HASH` | Platform owner | 12h |
| Deployment pushes and deploys an untagged image with no concurrency control | No immutable reference to roll back to, and no way to tell which commit is running | DevOps | 10h |
| Deployment authenticates with a long-lived service-account key and checks out the default ref | The deployed tree need not be the commit the build validated | DevOps | included above |
| Pipeline frontend lint, type-check and test steps precede the backend gate and fail | The backend build step is never reached on a real runner | DevOps / frontend owner | 8h |
| The stream client, the two client-configuration beans and the image contract are covered by no test | A later edit could regress ingestion, a client bean or the container identity silently | Backend owner | 12h |

## 1.5 Access Issues

| System/Resource | Type of Access | Issue Description | Resolution Status | Owner |
|-----------------|----------------|-------------------|-------------------|-------|
| Google Cloud Natural Language | API enablement + service-account role | The credential presented is rejected by the project (`PermissionDeniedException`) | Open | Platform owner |
| X (Twitter) API v2 | Paid-tier application key and secret | Not provisioned; the filtered stream is not available on the free tier | Open | Platform owner |
| OpenAI API | `OPENAI_API_KEY` | Not provisioned; response generation cannot call the provider | Open | Platform owner |
| Notion API | `NOTION_API_KEY` + `NOTION_DATABASE_ID` | Not provisioned; the secondary mirror is inactive | Open | Platform owner |
| Artifact registry and Cloud Run | Deploy identity | A long-lived service-account key is in use; federated identity is not configured | Open | DevOps |
| Managed database and secret store | `DATABASE_URL`, `SECRET_KEY`, `AUTH_PASSWORD_HASH` | Provisioned for local development only | Open | Platform owner |
| Continuous integration runner | Hosted runner execution | Neither workflow has ever executed, so pinned action references and credentials are unverified in flight | Open | DevOps |
| Maven Central | Anonymous artifact download | None — verified reachable, and the build also completes fully offline | Resolved | — |

## 1.6 Recommended Next Steps

1. **[High]** Grant Natural Language access and provision the X, OpenAI and Notion credentials, then drive each integration end to end (16h).
2. **[High]** Provision the managed database and the three mandatory secrets per environment, and decide how the schema evolves in production (12h).
3. **[High]** Add release hygiene to deployment — immutable image tag, concurrency group, federated identity, validated-commit checkout (10h).
4. **[High]** Execute the pipeline once on a real runner and settle the job ordering so the backend gate is reached (8h).
5. **[Medium]** Cover the stream client, the two client-configuration beans and the image contract with automated checks (12h).

# 2. Project Hours Breakdown

## 2.1 Completed Work Detail

Every row traces to a requirement of the plan — endpoint parity (G1), data-model parity (G2), business-rule parity (G3), integration parity (G4), dependency determinism (G5), configuration completeness (G6), defect closure (D1–D6), a working test suite (G8), operations alignment (G9) or the explainability artifacts (G10).

| Component | Hours | Description |
|-----------|-------|-------------|
| Maven module and dependency contract [G5] | 10 | `backend/pom.xml` under the Spring Boot 3.5.16 parent with Java 21: 17 coordinates, 5 explicit pins, 12 managed by the bill of materials, one inherited plugin, no `latest` anywhere, plus the ignore files and the executable-jar packaging. Replaces a project that had no dependency manifest at all. |
| Configuration surface [G6, TR-2] | 16 | Bound configuration root, `application.yml` and a test profile covering 43 environment variables — the seven the old settings class declared, the eight it referenced but never declared, and the authorised additions. Port pinned to 5000, servlet web type explicit, three credential aliases resolved through nested defaults. |
| Persistence and schema parity [G2, IR3, IR4] | 44 | Four entities / twenty columns / one association ordered by identifier, four repositories including windowed reads, the delimited-column converter, a pooled dialect-agnostic datasource, `DATABASE_URL`-to-JDBC translation for four vendors, schema bootstrap, and a start-up diagnostic that names the offending key without leaking a secret. |
| HTTP surface and wire contract [G1, IR1, IR5, IR9] | 52 | Six controllers serving the eleven preserved routes, sixteen DTO records with snake_case names and string identifiers, three mappers replacing a serialiser the old code called but never defined, the central error advice reproducing both envelopes and all eleven literals, permissive CORS, the 1-based pagination envelope, and 404-for-non-numeric-identifier parity. |
| Authentication and token issuance [D6, IR6] | 28 | `POST /auth/token`, HS256 minting, a bearer filter that actually enforces, the filter chain, a configuration-backed principal with bcrypt verification, and a bounded admission path that answers an availability condition as one. Replaces a decorator that enforced nothing and a minting function with no callers. |
| Ported business and integration services [G3, G4] | 40 | Tweet service with the popularity gate, sentiment service with the doubt-rating arithmetic, an OpenAI adapter on the current chat API, and a Notion adapter over a REST client — one adapter per external system, with no provider type crossing the boundary and the relational store authoritative. |
| Completed net-new services [D4] | 34 | Response generation, settings with idempotent seeding and row-over-property precedence, and analytics with seven summary members plus a day-bucketed trend series. These three were imported by the old controllers and did not exist. |
| X ingestion pipeline [D1, TR-11] | 36 | Stream client performing app-only token exchange, rule reconciliation, newline-delimited stream consumption with fragment reassembly, idle timeout and jittered backoff, a blank-credential guard, and a listener that persists what the gate admits — closing the write the old code abandoned at a comment. |
| Scheduling and background lifecycle [D2, IR10] | 20 | A completion-based trigger preserving fixed-delay pacing, the interval re-read once per pass, a per-pass candidate ceiling with set-aside, bounded threads and cooperative shutdown. Replaces a broker-less task wrapped in an endless loop that raised an error on its own last line. |
| Test suite [G8] | 96 | Nineteen JUnit 5 classes, 1,705 cases across controller slices, a data-JPA mapping suite, service and configuration units — 24,846 lines replacing 183 lines of Python tests that could not import. |
| Retirement of the Python package | 4 | Twenty source and test files removed, each with a traceability row naming its successor. |
| Operations alignment [G9] | 20 | Two-stage container build on a Java 21 runtime with port 5000 preserved, an unprivileged runtime identity and a health probe; the pipeline moved to JDK 21 and `mvn -B clean verify`; deployment trigger, image path and packaging command corrected. |
| Explainability artifacts [G10] | 26 | A 310-entry decision log with alternatives, rationale and risks per entry, and a 389-row bidirectional traceability matrix whose counts are re-derived from the delivered tree. |
| Hardening and cross-vendor verification | 30 | Schema parity measured on three dialects, bounded reads for very large pages, provider retry and deadline bounds, log hygiene, four database engines exercised live, and container runtime verification. |
| **Total** | **456** | |

## 2.2 Remaining Work Detail

| Category | Hours | Priority |
|----------|-------|----------|
| External provider credentials and live integration verification (Natural Language, X, OpenAI, Notion) | 16 | High |
| Managed database provisioning, secret wiring and the production schema-management decision | 12 | High |
| Automated coverage for the stream client, the two client-configuration beans and the image contract | 12 | Medium |
| Deployment release hygiene: immutable image tag, concurrency control, federated identity, validated-commit checkout | 10 | High |
| First live pipeline execution and the job-ordering decision | 8 | High |
| Production operability: log aggregation and alerting, health exposure, rollback runbook | 8 | Medium |
| Deployment-script review outside the backend build region | 6 | Medium |
| Frontend/backend contract decision (path prefix, field naming, absent auth module) | 6 | Medium |
| Terraform variable declaration so a plan can run | 4 | Low |
| Dependency shadow removal at the next framework parent upgrade (§5.2 #1) | 3 | Low |
| Base-image record monitoring cadence (§5.2 #4) | 3 | Low |
| **Total** | **88** | |

## 2.3 Hours Reconciliation

| Check | Result |
|-------|--------|
| §2.1 completed total | 456h |
| §2.2 remaining total | 88h |
| §2.1 + §2.2 | 544h = Total Hours in §1.2 |
| Completion | 456 ÷ 544 = **83.8%**, the figure used in §1.2, §7 and §8 |
| Remaining by priority | High 46h, Medium 32h, Low 10h (46 + 32 + 10 = 88) |

Confidence is high on the completed figure — every deliverable is present in the tree and the suite was run to completion here. Confidence is medium on the remaining figure, because provider provisioning and the first pipeline run depend on accounts and a runner that this environment does not supply; those two rows carry the widest range.

# 3. Test Results

The suite was executed from a clean target with `cd backend && mvn -o -B clean verify`: **BUILD SUCCESS in 38.8 s, 1,705 cases, 0 failures, 0 errors, 0 skipped**, with zero `[WARNING]` and zero `[ERROR]` lines, and the executable jar repackaged. The run needs no database, no credentials and no network — the test profile uses in-memory H2 with create-drop and a fixed test signing key — which is what makes a clean-checkout build green with no manual step.

| Area / Category | Framework | Tests | Passed | Failed | Coverage | What This Proves |
|-----------------|-----------|-------|--------|--------|----------|------------------|
| HTTP contract and error envelopes | JUnit 5 + Spring MockMvc | 368 | 368 | 0 | Not instrumented | The eleven preserved routes answer with the original statuses, snake_case bodies and both error envelopes, including 404 rather than 400 for a non-numeric identifier |
| Business and provider services | JUnit 5 + Mockito | 528 | 528 | 0 | Not instrumented | Sentiment arithmetic, the popularity gate and all three provider adapters behave correctly on success, refusal, timeout, malformed output and retry |
| Completed net-new services | JUnit 5 + Mockito | 250 | 250 | 0 | Not instrumented | Response generation, settings seeding with row-over-property precedence, and the seven analytics members plus trend buckets work as the controllers expect |
| Authentication and token issuance | JUnit 5 + MockMvc + Spring Security Test | 218 | 218 | 0 | Not instrumented | Tokens mint and parse with the expected claims and lifetime, protected routes refuse an absent, expired or duplicated bearer header, and admission pressure is answered as availability |
| Configuration and database URL translation | JUnit 5 | 137 | 137 | 0 | Not instrumented | A connection string for any supported vendor becomes a valid JDBC URL with credentials split out, and an unsupported one fails fast with a message that names the supported set |
| Background tasks | JUnit 5 + Mockito | 99 | 99 | 0 | Not instrumented | The generation pass honours its interval, ceiling and set-aside, and the ingestion listener applies the gate, records the doubt rating and isolates a failing record |
| Application context and start-up | JUnit 5 + Spring Boot Test | 53 | 53 | 0 | Not instrumented | The full context loads with no credentials present, background work stays contained, the trigger is registered, and the start-up diagnostic reports without leaking a secret |
| Persistence and schema parity | JUnit 5 + Spring Data JPA slice | 52 | 52 | 0 | Not instrumented | The generated schema is four tables, twenty columns and one association ordered by identifier, with no capacity bound or constraint the previous schema lacked |
| **Total** | | **1,705** | **1,705** | **0** | | |

Coverage is reported as *not instrumented* because no coverage plugin is configured — a deliberate omission, since the previous project had none and adding one was out of scope. The suite is 24,846 lines against 16,094 lines of production code, a 1.54:1 ratio, and of the 58 production types only the four listed below are not exercised by it.

**Not Covered** — delivered behaviour that no automated test exercises, and what a human should check before release:

- **The X stream client** (`backend/src/main/java/com/codeskeptic/scanner/task/TweetStreamClient.java`) has no test class of its own. Its protocol behaviour — app-only token exchange, rule reconciliation, fragment reassembly, idle timeout, jittered reconnection and the blank-credential decline — was proven by driving it against a controlled endpoint, and the listener it feeds has 47 cases, but a change to the client itself would fail no build. Add a class for it before the next change lands there, and soak the live stream once credentials exist.
- **The two provider client-configuration beans** (`config/WebClientConfig.java`, `config/RestClientConfig.java`) are referenced by no test; their base URL, header and timeout settings are exercised only indirectly at run time. Assert them directly, or assert the outbound request they shape.
- **One DTO no route serialises** (`dto/AiToolDto.java`). The plan requires the type for the `ai_tools` table, and that table has no route of its own in either the old service or this one — its rows feed the stream keyword set and the analytics count instead. Nothing to fix; decide whether the type stays.
- **The container image contract** — unprivileged identity, exposed port 5000, health-probe target — is asserted by no test. The image was built and inspected by hand, so an edit dropping `USER` or breaking the probe target would pass the build. Assert it in the build.
- **Both workflow files and the deployment script** are read by no test. Their action pins, trigger name and control flow were checked statically and under command doubles; run the pipeline once for real.
- **`POST /tweets/{tweetId}/analyze` end to end**, which needs a Natural Language grant. The arithmetic it persists is covered by 64 cases and its response shape by 10 controller assertions, but the route's own success path has never completed. Drive it once the grant exists and confirm `doubt_rating` on the row.
- **The application entry point's listener registration** in `ScannerApplication.main`, which a context test does not execute; the analysis logic it registers is covered by five cases.

# 4. Runtime Validation & UI Verification

The packaged jar was run against PostgreSQL 16.14 and driven over HTTP. Start-up: the connection string was translated to a JDBC URL, the pool opened, and Tomcat bound port 5000 with context path `/` — **application ready in 5.835 s**. The service has no user interface of its own; the single-page application is out of scope and was not modified, so all evidence below is HTTP responses, database state, container metadata and log records.

- ✅ **Start-up and shutdown** — Boots on port 5000 against the translated connection string; on signal it closes the provider client, the persistence factory and the pool in order and exits cleanly.
- ✅ **Token issuance** — `POST /auth/token` returns 200 with exactly `access_token`, `token_type: bearer` and `expires_in: 3600`; a wrong password returns 401.
- ✅ **Authorisation** — Every other route returns 401 with a zero-byte body without a bearer token; a duplicated `Authorization` header is refused whichever order it arrives in.
- ✅ **Tweet routes** — Listing returns the envelope `{page, per_page, total, total_pages}`, defaulting to 1 and 10 and echoing `per_page` when supplied; identifiers serialise as strings and `media` as a JSON array; an absent *and* a non-numeric identifier both return 404 `{"error":"Tweet not found"}`.
- ✅ **Response routes** — Listing, fetch, creation and partial update answer 200/200/201/200, with `{"error":"Tweet ID is required"}`, `{"error":"Update data is required"}` and `{"error":"Response not found"}` returned verbatim on the negative paths.
- ✅ **Settings routes** — Listing returns the three seeded rows as `{key, value, description}` in ascending key order; update returns the changed row, an empty body returns `{"error":"No value provided"}` and an unknown key returns `{"error":"Setting not found"}`.
- ✅ **Analytics routes** — Summary returns all seven members and trends a day-bucketed series, both computed as database-side aggregates.
- ✅ **Response generation** — Registered against a completion-based trigger that re-reads its interval from the settings row before each pass; cadence follows a changed row without a restart, and an unusable value is refused in favour of the configured default.
- ⚠ **X ingestion** — Starts only with credentials. With them blank it declines at start-up with one warning naming the two keys it needs and leaves the service fully operational; the protocol path itself was exercised against a controlled endpoint, never against the live API, because the filtered stream needs a paid tier.
- ❌ **Sentiment analysis** — `POST /tweets/{tweetId}/analyze` returns 500 `{"error":"Internal server error"}` here: Natural Language refuses the runtime identity. This is the only failing route, and the only route whose success path has never completed.

Two further checks are worth recording. **Log hygiene**: across a full session the log contained two ERROR records, both from that one refused provider call, and zero occurrences of the JDBC URL, the database password, the credential hash or the string `Authorization`. **Container**: `docker build -f infrastructure/docker/Dockerfile.backend ./backend` completes, and inspection of the built image reports an unprivileged runtime identity (10001:10001), port 5000 exposed, a health check present, and `java -jar app.jar` as the command.

Never exercised at run time: both workflow files (no runner is available, so pinned action references and the deploy credential are unproven in flight) and the deployment script beyond command doubles (no cloud call was made). OpenAI and Notion were exercised against the real client and controlled endpoints respectively, but not against live accounts.

# 5. Compliance & Quality Review

## 5.1 Compliance Matrix

Each row is a deliverable of the plan, with the state it stands in now and the evidence a reader can open.

| Deliverable | Benchmark | Status | Evidence |
|-------------|-----------|--------|----------|
| Endpoint parity | Eleven original routes preserved unprefixed, same methods, path variables, query names and defaults, same statuses | ✅ Pass | Twelve mappings across `backend/src/main/java/com/codeskeptic/scanner/api/`; 368 contract cases; every route driven at run time |
| Data-model parity | Four tables, twenty columns, one ordered association, no added constraint or capacity bound | ✅ Pass | `entity/{Tweet,Response,AiTool,Setting}.java`; the entity package contains no `nullable`, `unique`, `@Size`, `@Min` or `@Max`; 52 mapping cases across three dialects |
| Business-rule parity | Doubt rating `clamp((1 − score) × 5, 0, 10)`; popularity gate inclusive at the threshold, default 100 | ✅ Pass | `service/SentimentAnalysisService.calculateDoubtRating`, `service/TwitterService.meetsPopularityThreshold`; boundary vectors under test |
| Integration parity | One adapter per external system, no provider type crossing the boundary, relational store authoritative | ✅ Pass | `service/{TwitterService,SentimentAnalysisService,LlmService,NotionService}.java` and `task/TweetStreamClient.java`; 528 service cases |
| Never publish to X | No code path may post a reply; approval remains a flag a human reads | ✅ Pass | The only X path literals in the whole production tree are `/oauth2/token`, `/2/tweets/search/stream` and `.../rules`; app-only credentials carry no publish capability |
| Dependency determinism | Every coordinate pinned or managed, nothing floating | ✅ Pass | `backend/pom.xml`: 17 coordinates, 5 explicit pins, 12 managed, 1 inherited plugin, zero `latest`; the build resolves offline |
| Configuration completeness | Every key any code path reads is declared, including the eight the old settings class omitted | ✅ Pass | `application.yml` declares 43 environment variables; only `DATABASE_URL`, `SECRET_KEY` and `AUTH_PASSWORD_HASH` have no default, and each fails fast |
| Defect closure D1–D6 | Streaming with a real write, a working scheduler, all configuration declared, three absent services, a modern provider call, a token path | ✅ Pass | One deliverable each in `task/`, `config/AsyncSchedulingConfig.java`, `application.yml`, `service/`, `service/LlmService.java`, `api/AuthController.java` |
| Working test suite | JUnit 5 with Boot slices; `mvn clean verify` green from a clean checkout with no manual step | ✅ Pass | 19 classes, 1,705 cases, 0 failures, run offline with no database or credentials |
| Operations alignment | Java 21 runtime image, JDK 21 pipeline, port 5000 preserved | ✅ Pass | `infrastructure/docker/Dockerfile.backend` (two-stage, unprivileged, `EXPOSE 5000`, health probe); `.github/workflows/ci.yml` runs `mvn -B clean verify` |
| Explainability (decision log + traceability) | Rationale for every non-trivial decision; a bidirectional matrix at full coverage; no rationale in code | ✅ Pass | `backend/docs/DECISION_LOG.md` — 310 entries with alternatives, rationale and risks; `backend/docs/TRACEABILITY_MATRIX.md` — 389 rows both directions; comments carry provenance only |
| Scope discipline | Frontend, planning documentation, Terraform, the frontend image and the environment script untouched | ✅ Pass | Those paths are byte-identical to the baseline; the change set is 84 additions, 20 deletions and 4 modifications |

## 5.2 AAP & Rule Divergences and Gaps

Eight divergences were identified. None blocks release; three carry a follow-up, which appears in §2.2.

| What the AAP/Rule Required | What Was Delivered Instead | Why It Diverged | Impact | Remediation |
|----------------------------|---------------------------|-----------------|--------|-------------|
| Versions managed by the framework's bill of materials "must not be pinned in the POM" | Four version properties shadow the parent: netty 4.1.136.Final, jackson 2.21.5, PostgreSQL driver 42.7.12, Tomcat 10.1.57 | Each managed version sat inside a published advisory range for which a patch release already existed, and a documented-but-open advisory in an internet-facing runtime is not a closed one | Those four coordinates are no longer governed by the parent, and no build check detects a stale shadow | Delete each shadow the new bill of materials has caught up with at the next parent upgrade (3h, §2.2) |
| The operations carve-out permitted one line each in the deployment workflow and the deployment script | The workflow changed at six places; the script across a six-line region | As delivered, neither could work: the workflow listened for a build name that does not exist, and the script uploaded a build context containing no container file | Both files now differ from their originals more than the carve-out anticipated | None required; the changes are recorded in the decision log |
| Fixed-delay pacing expressed as a scheduled annotation | A completion-based trigger registered through the scheduling configurer; no scheduled annotation remains | An annotation resolves its interval once, at registration, and the plan also requires the settings row to override the configured default — both cannot hold | A reader looking for the annotation to learn the cadence will not find one | None required; fixed-delay semantics are preserved exactly |
| The runtime base image is fixed by the plan; hardening advice suggested a smaller base and removing utilities | Base image unchanged, no utility removed | The plan names that base image verbatim, and the retained HTTP utility is what the health probe uses | 41 medium and 6 low operating-system records with no published fix remain, as they would on any snapshot of this base | Re-scan and rebuild when the vendor publishes fixes (3h, §2.2) |
| The container file's change list covered base image, dependency steps, copy, port and command | Also an unprivileged account, a runtime identity, a health check and an operating-system update layer | That list describes translating the retired file; these four apply to the artifact that actually ships and to the platform it ships to | A strictly safer image, but the probe target is now load-bearing: it is the one route that answers without a credential | Keep the probe target and the identity under test (folded into the 12h coverage row, §2.2) |
| Rationale must not live in code comments | Comments carry a source pointer, a faithful-port or net-new marker and a decision-log identifier | The prohibition targets reasoning, not provenance; the plan itself sanctions this split so the log is reachable from the code | None — reasoning exists only in the decision log | None (sanctioned) |
| Prose counts of 47 production classes and 16 test classes, and a fixed configuration key list | 58 production classes, 19 test classes and 51 configuration keys | The same sections enumerate 58 and 19 file by file, and delivery matched the enumeration; the extra keys arrived with the admission bound, the ingestion limits and the provider timeouts | The surface is larger than a prose reading suggests; every addition is traceable | None required |
| Only 200 and 401 were enumerated for the token route | Overload now answers 503 with `Retry-After: 1` and an empty body | Concurrent credential verifications are deliberately bounded, but answering that with 401 tells an operator holding the correct credential that their password is wrong, and 401 is not retryable | One status code the plan never enumerated; clients must treat 503 as retryable | None required; note it in the client contract |

**Dependency shadows.** Four properties in `backend/pom.xml` — `netty.version`, `jackson-bom.version`, `postgresql.version`, `tomcat.version` — override what the framework parent resolves, each raised to the first release carrying a published fix rather than to the newest of its line. The alternative of raising the parent was refused because the plan fixes the framework at 3.5.16. The cost is real: once a future parent resolves past these values, the properties silently pin older releases and nothing in the build notices. The decision log states the removal condition; apply it at the next parent upgrade and re-check the ranges. Verify by reading the nested library versions in the packaged jar, not the property values.

**Operations carve-out.** The carve-out was written to bound a translation of retired files, and read that way it holds. But the delivered artifacts had to work: the deployment workflow triggered on a build name the integration workflow does not use, so it never fired, and the deployment script uploaded a build context with no container file in it, so it could not produce an image at all. Both now build through the same container-file contract from the repository root, so the script and the pipeline cannot drift apart. Nothing further is required, but a reader comparing these two files with their originals will see a wider diff than the plan describes.

**Scheduler mechanism.** `config/AsyncSchedulingConfig.java` registers one trigger task whose next instant is the previous pass's completion plus the interval in force, and `task/ResponseGenerationScheduler.java` resolves that interval from the `response_generation_delay` settings row before each pass, falling back to the configured default. A scheduled annotation cannot do this: its placeholder resolves once at start-up, which would leave an editable row that changes nothing. Fixed-delay semantics are preserved exactly — never a fixed rate, so no pass overlaps its predecessor, and the first pass still runs at the start-up instant. The behaviour was measured by reading generation timestamps as the row changed, and it followed without a restart.

**Base image records.** The runtime base is the image the plan names, and it carries 41 medium and 6 low operating-system records for which the vendor publishes no fix; the build applies every fix that does exist. Removing utilities was refused because the health probe uses one of them. This is an accepted, documented posture rather than a closed one: put the image on a re-scan cadence and rebuild when fixes appear. Nothing in the application is exposed by these records more than by any other snapshot of the same base.

**Container hardening.** Beyond the translation the plan lists, `infrastructure/docker/Dockerfile.backend` creates a system account, runs as 10001:10001, applies vendor security updates and declares a health check. The probe is a cross-origin preflight against the settings route — the one request the service answers successfully without a credential — so no secret is baked into the image and no route was added for it. That makes the probe target load-bearing: if the route surface changes, the probe must be revisited. No test asserts any of this today, which is why the coverage row in §2.2 includes an image-contract check.

**Comment convention.** Every translated class carries a comment naming the file and line range it came from, or marking itself net-new, plus a decision-log identifier. No comment argues for a choice. This is the split the plan itself adopts to satisfy the explainability rule while keeping the code navigable, and the log holds every "why" — 310 entries, each with alternatives, rationale and risks, with all identifiers cited in the tree resolving to a populated entry.

**Inventory and key counts.** The plan's prose says 47 production classes and 16 test classes while enumerating 58 and 19; the tree holds 58 and 19, matching the enumeration file for file. Configuration stands at 51 keys, wider than the planned list because the credential-verification bound, ingestion limits, provider timeouts and background switches each needed one — every key traceable, and every one defaulted except the three that must fail fast. Nothing was silently dropped: the eight keys the old settings class referenced but never declared are all present, three of them as aliases resolving to the canonical credential pair.

**Token-route status code.** `api/AuthController.java` bounds how many credential verifications run at once, because bcrypt is deliberately expensive. That bound is right; answering it with 401 was not, because a correct credential then looks wrong and the caller has no reason to retry. Overload returns 503 with `Retry-After: 1` and an empty body, and no verification is started for a request that gets no permit; both the permit count and the wait are configurable, with the default preserving host-derived sizing. Clients written against the plan's status list should treat 503 as retryable.

# 6. Risk Assessment

These are forward-looking: what could still go wrong once this service runs in a real environment.

| Risk | Category | Severity | Probability | Mitigation | Status |
|------|----------|----------|-------------|------------|--------|
| Natural Language access is not granted, so sentiment analysis and the doubt-rating write path cannot complete | Integration | High | High | Enable the API and grant the runtime identity, then drive `POST /tweets/{tweetId}/analyze` and read `doubt_rating` back from the row | Open — owner action (§2.2, 16h row) |
| X credentials at a tier that includes the filtered stream are not provisioned, so ingestion never captures a post | Integration | High | High | Provision the tier and set the two credential keys, then soak the stream and confirm rows appear; until then the client declines at start-up and the rest of the service is unaffected | Open — owner action (§2.2, 16h row) |
| Deployment publishes and deploys an untagged image with no concurrency control | Operational | High | Medium | Tag by commit or digest, add a concurrency group, and confirm a rollback target exists before the first production run | Open (§2.2, 10h row) |
| Deployment uses a long-lived key and checks out the default ref, so the deployed tree need not be the validated commit | Security | Medium | Medium | Move to federated identity and check out the commit the build validated | Open (§2.2, 10h row) |
| Four version properties shadow the framework parent; once it catches up they silently pin older releases | Technical | Medium | Medium | Apply the recorded removal rule at the next parent upgrade and confirm versions from the packaged jar rather than the properties | Accepted with a follow-up (§5.2 #1, §2.2, 3h) |
| Production schema evolution rests on the framework's automatic update mode, with no migration tool by design | Technical | Medium | Medium | Decide before the first production data load: adopt a migration tool, or gate schema changes and apply them deliberately | Open decision (§2.2, 12h row) |
| Ingestion client, the two provider client beans and the image contract are covered by no test, so a later edit can regress them silently | Technical | Medium | Medium | Add a stream-client test class, cover the two beans, and assert the image identity, port and probe target in the build | Open (§2.2, 12h row) |
| Residual posture: one shared configuration-backed credential with no rotation without redeploy, and vendor-unfixed base-image records | Security | Low | Medium | Substitute a database-backed user store behind the same interface when per-user accounts are needed; re-scan and rebuild the image when fixes publish | Accepted and monitored (§5.2 #4) |

Two conditions bound how much these risks can surprise anyone. The service fails fast and reports which key is at fault when its database or signing secret is missing, so a misconfigured environment does not start half-working. And ingestion is inert without credentials rather than partially active, so the highest-severity integration risks manifest as an absence of data, announced in the log at start-up, rather than as silent corruption.

# 7. Visual Project Status

**Hours delivered against hours remaining** — Completed in Blitzy dark blue (#5B39F3), Remaining in white (#FFFFFF).

```mermaid
%%{init: {"theme": "base", "themeVariables": {"pie1": "#5B39F3", "pie2": "#FFFFFF", "pieStrokeColor": "#B23AF2", "pieOuterStrokeColor": "#B23AF2", "pieSectionTextColor": "#1A1A1A", "pieTitleTextColor": "#B23AF2", "pieLegendTextColor": "#B23AF2"}}}%%
pie title Project Hours Breakdown 83.8 percent complete
    "Completed Work" : 456
    "Remaining Work" : 88
```

**Remaining work by priority** — 88 hours in total: 46 High, 32 Medium, 10 Low.

```mermaid
%%{init: {"theme": "base", "themeVariables": {"pie1": "#5B39F3", "pie2": "#A8FDD9", "pie3": "#FFFFFF", "pieStrokeColor": "#B23AF2", "pieOuterStrokeColor": "#B23AF2", "pieSectionTextColor": "#1A1A1A", "pieTitleTextColor": "#B23AF2", "pieLegendTextColor": "#B23AF2"}}}%%
pie title Remaining Work by Priority
    "High (46h)" : 46
    "Medium (32h)" : 32
    "Low (10h)" : 10
```

**Remaining hours by category** — the eleven rows of §2.2, summing to 88.

```mermaid
xychart-beta
    title "Remaining Hours by Category"
    x-axis ["Credentials", "Database", "Coverage", "Release", "Pipeline", "Operability", "Deploy script", "Frontend", "Terraform", "Deps", "Base image"]
    y-axis "Hours" 0 --> 20
    bar [16, 12, 12, 10, 8, 8, 6, 6, 4, 3, 3]
```

| Dimension | Delivered | Outstanding |
|-----------|-----------|-------------|
| Engineering hours | 456 | 88 |
| Scoped deliverables | 10 of 10 goals, 6 of 6 named defects | 0 |
| Routes serving | 12 of 12 mappings | 1 route unverifiable without a provider grant |
| Automated cases | 1,705 passing | 7 uncovered items listed in §3 |
| Change set | 84 added, 20 deleted, 4 modified paths | — |

# 8. Summary & Recommendations

The server side of Code Skeptic Scanner now runs on Java 21 and Spring Boot, built by Maven from `backend/`, and the Python package it replaces is gone — twenty files retired, eighty-four added, four operations files adjusted, +42,903 lines against −864. The contract the old service defined is preserved rather than reinterpreted: the same eleven routes at the same unprefixed paths with the same statuses and the same error literals, the same four tables and twenty columns with no constraint or capacity bound added, and the doubt-rating and popularity-gate arithmetic transcribed with their boundaries under test. What the old code only declared is now real: the three services its controllers imported but never defined, the ingestion write it abandoned at a comment, the scheduler that raised an error on its own last line, the token path with no callers, and the dependency manifest that never existed. Against the plan's own definition of done — a green `mvn clean verify` from a clean checkout with no manual step — the project is **83.8% complete**, 456 hours delivered of 544.

Verification was carried out in three layers, and each says something the others cannot. The suite is 1,705 cases across nineteen classes, running offline with no database and no credentials, which is what makes a clean checkout reproducible; it was executed here and passed in full with no warnings. The service was then run against PostgreSQL and driven route by route: twelve mappings, the pagination envelope, all eleven error literals byte-for-byte, 404 rather than 400 for a non-numeric identifier, a zero-byte 401 body, permissive cross-origin behaviour, and a log containing no secret, no connection string and no authorisation header. Finally the generated schema was inspected in the database itself — four tables, twenty columns, one foreign key, four primary keys, no unique constraint — and the image was built and inspected, running unprivileged on port 5000 with a working health probe.

Three things stand between this and production, and none of them is code. Providers need credentials: Natural Language refuses the runtime identity today, which is why the analyze route is the one route whose success path has never completed, and the filtered stream needs a paid tier, which is why ingestion declines at start-up rather than capturing posts. Environments need a managed database and the three secrets that deliberately have no defaults. And deployment needs release hygiene before it is pointed at production — an immutable image tag, a concurrency group, federated identity in place of a long-lived key, and a checkout of the commit the build validated. The pipeline has also never run on a real runner, and its frontend steps sit ahead of the backend gate and fail on scripts the frontend does not declare, so the job ordering is a decision waiting to be taken.

Eight divergences from the plan are recorded in §5.2, and the shape of them matters more than the count. Six are cases where following the plan literally would have shipped something worse: four version properties that close published advisories, two operations files repaired so they actually work, a scheduling mechanism that lets an editable setting take effect, a hardened image, and a token route that answers overload as overload instead of as a bad password. Two are bookkeeping — the plan's prose counts against its own enumerations, and a configuration surface that grew as bounds and timeouts were added. Three carry a follow-up, all small and all in §2.2. The explainability requirement is met not as an afterthought but as an artifact: 310 decision entries with alternatives, rationale and risks, a bidirectional traceability matrix at 389 rows, and code comments that carry provenance and nothing else.

Production readiness: **ready for a controlled environment, not yet for production traffic.** The recommendation is to work §2.2 in priority order — credentials first, because they unblock the two capabilities that cannot be judged without them; then the database and secrets; then release hygiene and one live pipeline run; then the three uncovered surfaces, which are the only places where a future edit could regress something silently. Success is measurable and worth stating plainly: every one of the twelve routes answering correctly against a real database, a post captured from the live stream with its doubt rating persisted, a generated reply awaiting approval, and a deployment identified by an immutable tag that can be rolled back.

# 9. Development Guide

Every command below has been exercised against this checkout. Comments after `#` show the expected output, and every figure quoted in prose is a measurement taken here.

### System prerequisites

| Requirement | Version used | Notes |
|-------------|--------------|-------|
| JDK | OpenJDK 21.0.11 | Java 21 is the language and release level; nothing older compiles |
| Maven | Apache Maven 3.9.9 | No wrapper is committed, by design; use a system Maven |
| Docker | 29.7.0 (overlay2) | Only needed for the database container and the image build |
| Database | PostgreSQL 16 (MySQL 8 also supported) | Not needed to build or test — the suite uses in-memory H2 |
| Memory | 2 GB for the build (`MAVEN_OPTS=-Xmx2g`), 512 MB for the service | The build downloads roughly 470 MB of dependencies on first run |

```bash
java -version     # openjdk version "21.0.11"
mvn -v            # Apache Maven 3.9.9
docker --version  # Docker version 29.7.0
```

### Build and test

```bash
cd backend
mvn -B clean verify
```

Observed: `BUILD SUCCESS` in 38.8 s, `Tests run: 1705, Failures: 0, Errors: 0, Skipped: 0`, no warnings, and `target/code-skeptic-scanner-backend-0.0.1-SNAPSHOT.jar` produced. Add `-o` to build offline once the local repository is warm. The suite needs no database, no credentials and no network.

### Environment

Three variables have no default and the service refuses to start without them. Keep every value **single-quoted**: a bcrypt hash contains `$2a$10$`, which an unquoted shell would expand and turn into a start-up failure.

```bash
cat > /opt/scanner-env/scanner-runtime.env <<'ENV'
DATABASE_URL='postgresql://scanner:s3cret@localhost:55432/codeskeptic'
SECRET_KEY='a-signing-key-of-at-least-32-bytes-for-hs256'
AUTH_USERNAME='admin'
AUTH_PASSWORD_HASH='$2a$10$replace-with-a-real-bcrypt-hash'
PORT='5000'
SCANNER_LOG_LEVEL='INFO'
ENV
chmod 600 /opt/scanner-env/scanner-runtime.env
set -a; . /opt/scanner-env/scanner-runtime.env; set +a
```

Generate the hash with any bcrypt tool, for example:

```bash
python3 -c "import bcrypt; print(bcrypt.hashpw(b'ChangeMe!2026', bcrypt.gensalt(10)).decode())"
```

`DATABASE_URL` takes a SQLAlchemy-style connection string and is translated at start-up; `postgresql://`, `postgres://`, `mysql://`, `mariadb://`, `h2://`, a `+driver` suffix and a ready-made `jdbc:` URL are all accepted. A MariaDB *server* is refused deliberately, whichever spelling reaches it.

### Database

```bash
docker run -d --name codeskeptic-pg -p 55432:5432 \
  -e POSTGRES_DB=codeskeptic -e POSTGRES_USER=scanner -e POSTGRES_PASSWORD=s3cret \
  postgres:16
docker exec codeskeptic-pg pg_isready -U scanner -d codeskeptic   # expect: accepting connections
```

Tables are created on first start-up and three settings rows are seeded idempotently, so no migration step is needed for development.

### Run

```bash
cd backend
set -a; . /opt/scanner-env/scanner-runtime.env; set +a
java -jar target/code-skeptic-scanner-backend-0.0.1-SNAPSHOT.jar
# or, for a development loop:
mvn -B spring-boot:run
```

Observed start-up sequence: `Translated DATABASE_URL to a JDBC URL for vendor 'postgresql'` → `HikariPool-1 - Start completed` → `Tomcat started on port 5000 (http) with context path '/'` → `Started ScannerApplication in 5.835 seconds`. Two further lines are expected and healthy: the scheduler announcing a completion-based trigger, and ingestion declining because no X credentials are set.

### Verify

```bash
BASE=http://localhost:5000

# 1. Protected routes refuse an anonymous caller: 401 with an empty body
curl -s -o /dev/null -w '%{http_code} %{size_download}\n' $BASE/tweets        # 401 0

# 2. Mint a token
TOKEN=$(curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"ChangeMe!2026"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')

# 3. Drive the read surface
for R in /tweets /responses /settings /analytics/summary /analytics/trends; do
  printf '%-20s ' "$R"; curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" "$BASE$R"
done                                                                          # 200 for all five

# 4. Contract spot-checks
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/tweets?page=1&per_page=3" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["pagination"])'
#   {'page': 1, 'per_page': 3, 'total': ..., 'total_pages': ...}
curl -s -H "Authorization: Bearer $TOKEN" $BASE/tweets/abc                    # {"error":"Tweet not found"}
curl -s -H "Authorization: Bearer $TOKEN" $BASE/nosuchroute                   # {"error":"Not found"}
```

### Example usage

```bash
# Read the seeded settings and change the generation cadence with no restart
curl -s -H "Authorization: Bearer $TOKEN" $BASE/settings | python3 -m json.tool
curl -s -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"value":"30"}' $BASE/settings/response_generation_delay
#   {"key":"response_generation_delay","value":"30","description":"..."}

# Draft a reply for a stored post (needs OPENAI_API_KEY); returns 201 with is_approved false
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"tweet_id":"1"}' $BASE/responses

# Approve a draft — approval is a flag a human sets; nothing is ever published to X
curl -s -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"is_approved":true}' $BASE/responses/1
```

### Container

```bash
docker build -f infrastructure/docker/Dockerfile.backend -t code-skeptic-backend ./backend
docker image inspect code-skeptic-backend \
  --format 'User={{.Config.User}} Ports={{.Config.ExposedPorts}} Cmd={{.Config.Cmd}}'
#   User=10001:10001 Ports=map[5000/tcp:{}] Cmd=[java -jar app.jar]
docker run -d --name scanner -p 5000:5000 --env-file /opt/scanner-env/scanner-runtime.env code-skeptic-backend
docker inspect --format '{{.State.Health.Status}}' scanner   # expect: starting, then healthy
```

The build context is `./backend` while the container file lives outside it, so the `-f` flag is required — the pipeline and the deployment script use exactly this form.

### Troubleshooting

| Symptom | Cause | Resolution |
|---------|-------|------------|
| Start-up fails naming `scanner.database-url` / `DATABASE_URL` | The value is absent, malformed or the server is unreachable | Read the printed report — it names the key and the remedy and leaks no secret — then fix the value or start the database |
| Start-up fails on the credential hash | An unquoted hash was mangled by the shell on source | Single-quote every value in the environment file |
| `Unsupported database scheme` | A vendor other than PostgreSQL, MySQL or H2, or a MariaDB server | Use a supported vendor, or supply a ready-made `jdbc:` URL |
| Port already in use | Another process holds 5000 | Set `PORT`; note the container exposes 5000 |
| Every route returns 401 | Token absent, expired after 60 minutes, or two `Authorization` headers sent | Mint a fresh token and send exactly one header |
| `POST /auth/token` returns 503 with `Retry-After` | The bounded set of concurrent credential verifications is saturated | Retry, or raise `AUTH_VERIFICATION_PERMITS` / `AUTH_VERIFICATION_WAIT_MILLIS` |
| `POST /tweets/{id}/analyze` returns 500 | Natural Language access is not granted to the runtime identity | Enable the API and grant the service account |
| Ingestion warns and does not start | `TWITTER_API_KEY` / `TWITTER_API_SECRET` are blank | Set both; the filtered stream also requires a paid API tier |
| A very large `per_page` value | Reads are windowed in 500-row chunks on purpose | No action — a full-table request completes without exhausting the heap |
| A test run that touches the network | It should not; the suite is offline by design | Re-run with `-o`; if it then fails, a new dependency was added |

# 10. Appendices

## A. Command Reference

| Purpose | Command | Directory |
|---------|---------|-----------|
| Build, test and package | `mvn -B clean verify` | `backend/` |
| Same, offline | `mvn -o -B clean verify` | `backend/` |
| Install to the local repository | `mvn -B clean install` | `backend/` |
| Run the packaged jar | `java -jar target/code-skeptic-scanner-backend-0.0.1-SNAPSHOT.jar` | `backend/` |
| Run for development | `mvn -B spring-boot:run` | `backend/` |
| One test class | `mvn -B -Dtest=JpaMappingIntegrationTest test` | `backend/` |
| Load the runtime environment | `set -a; . <env-file>; set +a` | any |
| Build the image | `docker build -f infrastructure/docker/Dockerfile.backend -t code-skeptic-backend ./backend` | repository root |
| Start a development database | `docker run -d --name codeskeptic-pg -p 55432:5432 -e POSTGRES_DB=codeskeptic -e POSTGRES_USER=scanner -e POSTGRES_PASSWORD=s3cret postgres:16` | any |
| Mint a token | `curl -s -X POST localhost:5000/auth/token -H 'Content-Type: application/json' -d '{"username":"admin","password":"…"}'` | any |
| Check the change set | `git diff --name-status origin/main...HEAD` | repository root |

## B. Port Reference

| Port | Service | Notes |
|------|---------|-------|
| 5000 | Application HTTP | The previous service's default, preserved; `EXPOSE 5000` in the image; override with `PORT` |
| 55432 | Development PostgreSQL | Host port mapped to the container's 5432 |
| in-memory | H2 | Test profile only, create-drop per run |

## C. Key File Locations

| Path | Role |
|------|------|
| `backend/pom.xml` | Dependency contract: 17 coordinates, 5 pins, 1 plugin |
| `backend/src/main/resources/application.yml` | Every configuration key, 43 environment variables |
| `backend/src/test/resources/application-test.yml` | Test profile: H2, create-drop, fixed test signing key |
| `backend/src/main/java/com/codeskeptic/scanner/api/` | Six controllers serving the twelve mappings |
| `.../service/` and `.../service/mapper/` | Seven services and three entity-to-wire mappers |
| `.../entity/` and `.../repository/` | Four entities, four repositories |
| `.../security/` | Filter chain, token minting, bearer filter |
| `.../task/` | Stream client, stream listener, generation scheduler |
| `.../config/` | Properties root, datasource, URL translation, clients, scheduling, CORS |
| `backend/src/test/java/com/codeskeptic/scanner/` | Nineteen test classes, 1,705 cases |
| `backend/docs/DECISION_LOG.md` | 310 entries: decision, alternatives, rationale, risks |
| `backend/docs/TRACEABILITY_MATRIX.md` | 389 rows mapping source to target and back |
| `infrastructure/docker/Dockerfile.backend` | Two-stage build, Java 21 runtime, unprivileged, health probe |
| `.github/workflows/ci.yml` | Build and test on JDK 21 |
| `.github/workflows/cd.yml` | Image build, push and deploy after a successful build |
| `scripts/deploy.sh` | Workstation deployment path |

## D. Technology Versions

Read from the packaged jar, which carries 156 libraries.

| Component | Version |
|-----------|---------|
| Java | 21.0.11 (release level 21) |
| Spring Boot | 3.5.16 |
| Spring Framework (web, webflux) | 6.2.19 |
| Spring Security | 6.5.11 |
| Hibernate ORM | 6.6.53.Final |
| Jakarta Persistence | 3.1.0 |
| Embedded Tomcat | 10.1.57 |
| Jackson Databind | 2.21.5 |
| Netty codec-http / Reactor Netty | 4.1.136.Final / 1.2.18 |
| HikariCP | 6.3.3 |
| PostgreSQL driver | 42.7.12 |
| MySQL connector | 9.7.0 |
| H2 | 2.3.232 |
| JJWT | 0.13.0 |
| Google Cloud Natural Language | 2.96.0 |
| OpenAI Java | 4.49.0 |
| SLF4J / Logback | 2.0.18 / 1.5.34 |
| Maven | 3.9.9 |

## E. Environment Variable Reference

Mandatory — no default, start-up fails fast:

| Variable | Purpose |
|----------|---------|
| `DATABASE_URL` | Connection string, translated to a JDBC URL at start-up |
| `SECRET_KEY` | HS256 signing key, at least 32 bytes |
| `AUTH_PASSWORD_HASH` | bcrypt hash of the single application principal's password |

Commonly set:

| Variable | Default | Purpose |
|----------|---------|---------|
| `PORT` | `5000` | HTTP listener |
| `AUTH_USERNAME` | `admin` | Application principal |
| `SCANNER_LOG_LEVEL` | `INFO` | Log level for the application packages |
| `TWEET_POPULARITY_THRESHOLD` | `100` | Ingestion gate; a settings row overrides it |
| `RESPONSE_GENERATION_DELAY` | `60` | Seconds between generation passes; a settings row overrides it |
| `AUTH_VERIFICATION_PERMITS` / `AUTH_VERIFICATION_WAIT_MILLIS` | host-derived / `10000` | Bound on concurrent credential verification |
| `SCANNER_BACKGROUND_ENABLED`, `TWITTER_STREAM_ENABLED`, `RESPONSE_GENERATION_ENABLED` | `true` | Background switches |

Provider credentials — the service starts without them and the dependent capability stays inert:

| Variable | Purpose |
|----------|---------|
| `TWITTER_API_KEY`, `TWITTER_API_SECRET` | X app-only token exchange; `TWITTER_API_SECRET_KEY`, `TWITTER_CONSUMER_KEY`, `TWITTER_CONSUMER_SECRET`, `TWITTER_ACCESS_TOKEN`, `TWITTER_ACCESS_TOKEN_SECRET` are declared and resolve to these |
| `OPENAI_API_KEY` | Reply drafting; model, token ceiling, temperature and retries are also configurable |
| `NOTION_API_KEY`, `NOTION_DATABASE_ID` | Secondary mirror; timeouts and retries are configurable |
| Application Default Credentials | Natural Language sentiment analysis — no key property; supplied by the platform |

## F. Developer Tools Guide

| Task | How |
|------|-----|
| Understand why something is built as it is | `backend/docs/DECISION_LOG.md`; a code comment names the entry that governs it |
| Trace a retired Python construct to its successor | `backend/docs/TRACEABILITY_MATRIX.md` §1; §2 reads the other way, and net-new files are marked as such |
| See what a class was ported from | The header comment names the source file and line range, or marks the class net-new |
| Check the wire contract | `dto/` records carry the JSON names; `api/GlobalExceptionHandler.java` holds both error envelopes |
| Inspect the generated schema | Start against a database and read `information_schema`, or run the mapping test, which asserts it |
| Verify no reply can be published | Search the production tree for X paths; only the token, stream and rules paths exist |
| Confirm what shipped | Read library versions from the packaged jar rather than from the build properties |

## G. Glossary

| Term | Meaning |
|------|---------|
| Doubt rating | A 0–10 score derived from provider sentiment as `clamp((1 − score) × 5, 0, 10)`, stored on the tweet row |
| Popularity gate | The ingestion admission test: like count at or above the threshold in force, default 100 |
| Filtered stream | The X endpoint delivering posts matching registered rules as newline-delimited JSON |
| App-only credentials | A token obtained from an application key and secret; it carries no user context and cannot publish |
| Settings row | A persisted key/value/description row; where one exists it overrides the corresponding configured default |
| Fixed delay | Pacing measured from the end of one pass to the start of the next, so passes never overlap |
| Set-aside | A generation candidate deferred within a pass without consuming a provider call |
| Secondary mirror | Notion, which receives a copy of the record; the relational database remains authoritative |
| Wire contract | The externally visible shape: paths, methods, status codes, snake_case field names, string identifiers, error envelopes |
