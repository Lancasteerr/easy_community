# Backend Code Organization and Architecture

## Article search and global feed v1

`article.search` owns title/tag queries, PIT cursors, ranking and reliable index synchronization. Content transactions append the metadata version and Outbox event on create, edit and first hide. New main-database mappers use the existing sqlSessionTemplate and mapper/main/article/search XML directory. Consumers read a short RR snapshot and apply conditional ES scripts; hidden documents retain their version tombstones. Statistics do not advance metadata versions.

Search and feed share public card hydration, preserving ES order with batch cache/database reads. Known invalid items are skipped without an additional authoritative visibility query; author-management semantics remain unchanged. Counts and user-specific like state are assembled only for final response items. Ranking reads persisted seven-day views and uses date candidates plus daily calibration, without distributed ranking locks or persistent batch state.

Relay and ranking have separate single-thread schedulers while the default scheduler remains explicit. Non-web maintenance supports import, audit, bounded replay and maintenance-window alias switching. See the [operations guide](../../../operations/search/zh-CN/article-search-operations.md).

[简体中文](../zh-CN/backend-architecture.md) · [Startup, API, and deployment guide](../../../../../backend/README.md)

## Find Code by Business Responsibility

The backend remains a single Maven project. Find the business package first, then look for its HTTP endpoints, workflows, or database operations. A user feature can usually be understood within `user`, rather than across global Controller, Service, and Mapper directories.

The root package is `com.febrie.backend`:

```text
backend
├─ BackendApplication
├─ article
│  ├─ common
│  ├─ content
│  │  ├─ application
│  │  │  └─ dto
│  │  ├─ infrastructure
│  │  │  ├─ cache
│  │  │  ├─ config
│  │  │  ├─ maintenance
│  │  │  └─ persistence
│  │  │     ├─ entity
│  │  │     └─ mapper
│  │  └─ web
│  │     ├─ request
│  │     └─ response
│  ├─ like
│  │  ├─ application
│  │  │  └─ maintenance
│  │  ├─ domain
│  │  ├─ infrastructure
│  │  │  ├─ config
│  │  │  ├─ maintenance
│  │  │  ├─ messaging
│  │  │  ├─ observability
│  │  │  ├─ persistence
│  │  │  │  └─ mapper
│  │  │  └─ redis
│  │  └─ web
│  ├─ tag
│  │  ├─ application
│  │  │  └─ dto
│  │  ├─ infrastructure
│  │  │  └─ persistence
│  │  │     ├─ entity
│  │  │     └─ mapper
│  │  └─ web
│  │     └─ request
│  └─ view
│     ├─ application
│     └─ infrastructure
│        ├─ cache
│        ├─ persistence
│        │  ├─ entity
│        │  └─ mapper
│        └─ scheduling
├─ auth
│  ├─ application
│  │  └─ dto
│  ├─ infrastructure
│  │  ├─ config
│  │  ├─ ratelimit
│  │  ├─ scheduling
│  │  ├─ security
│  │  └─ session
│  └─ web
│     ├─ request
│     ├─ response
│     ├─ security
│     └─ session
├─ media
│  ├─ application
│  │  ├─ lifecycle
│  │  ├─ reference
│  │  └─ upload
│  │     └─ dto
│  ├─ domain
│  ├─ infrastructure
│  │  ├─ config
│  │  ├─ persistence
│  │  │  ├─ entity
│  │  │  ├─ mapper
│  │  │  └─ notification
│  │  ├─ ratelimit
│  │  ├─ scheduling
│  │  └─ storage
│  └─ web
│     ├─ notification
│     └─ request
├─ shared
│  ├─ error
│  ├─ management
│  ├─ persistence
│  ├─ ratelimit
│  └─ web
└─ user
   ├─ application
   │  └─ dto
   ├─ domain
   ├─ infrastructure
   │  ├─ bootstrap
   │  └─ persistence
   │     ├─ entity
   │     └─ mapper
   └─ web
      └─ request
```

This is practical layering. A business module does not need all four layers. Authentication currently has no separate pure business model, so there is no `auth.domain`. Future post and comment packages should also follow actual needs.

## What the Package Names Mean

| Package | What belongs here | Current example |
|---|---|---|
| `web` | Receive HTTP requests, read authenticated identities, and return results | `UserController`, `AuthController` |
| `application` | Coordinate an operation and its transactions; expose services to other modules | User creation, password changes, login, account queries |
| `domain` | Business types or rules independent of databases, HTTP, and frameworks | The public `Role` enum |
| `infrastructure` | Implement database access, Redis operations, and security configuration | Account SQL, rate counters, active session sets |
| `persistence` | Read and write database records | `AccountRepository` wraps MyBatis-Plus mappers |
| `dto` | Transfer data between services and callers; DTO means data transfer object | `UserActor`, `UserView`, `AccountAuthState` |
| `request` / `response` | HTTP request and response objects for an endpoint category | `LoginRequest`, `CsrfView` |
| `scheduling` | Decide when scheduled work runs | `SessionCleanupTask` |
| `shared` | Capabilities used by multiple modules without belonging to one business | `ApiException`, consistent error responses |

Read repeated names with their parent packages: `auth.web.session` handles HTTP sessions and cookies; `auth.infrastructure.session` manages active session sets in Redis. `auth.web.security` validates requests before controllers run, while `auth.infrastructure.security` integrates the password authentication provider.

## How the Current Flows Work

### Login and Request Validation

1. `SecurityRequestFilter` limits login requests before CSRF processing. It reads only the client IP already processed by the servlet container.
2. `AuthController` passes the username, original password, and client IP to `AuthService`.
3. `AuthService` trims leading and trailing username whitespace while preserving case, checks failure limits, and calls the Spring Security authentication manager. Only authentication failures increment the failure counters; database and Redis failures propagate.
4. `LocalAuthenticationProvider` calls the user module through `AccountQueryService`. A single JOIN reads the password hash, role, status, and security version. A missing account still causes one password hash comparison.
5. After successful authentication, `SessionService` rotates the HTTP session, immediately saves the authentication snapshot, and then registers its Redis set membership. The controller returns user information without credentials.
6. For subsequent protected requests, `SessionValidation` checks the absolute expiry, primary database account status, security version, and set membership. It neither reloads roles nor upgrades session versions.

`AccountPolicy` validates usernames against `[A-Za-z0-9_]{4,32}`. User creation, administrator bootstrap, and login all preserve case. `Alice_1` and `alice_1` are independent accounts; the existing `utf8mb4_bin` collation on username and local identity fields makes lookups and unique constraints case-sensitive. Account failure counters use the trimmed, case-preserved username, while IP limits remain shared. This change updates V1 schema comments directly for new databases; it provides no migration or compatibility handling for existing data or Flyway history.

`AccountSnapshot` contains a password hash. It is for internal authentication only and must never become an HTTP response or a Session attribute. `SessionPrincipal` contains the identity and expiry fixed at login, without a password. HTTP user information uses `UserView`, with user IDs represented as strings and usernames preserving their stored case.

### User Changes and Session Cleanup

User controllers convert the authenticated principal to `UserActor`. User services receive only the user ID, role, and security version; clients cannot supply the actor identity. Password changes, deletion, and internal role changes still update the account and increment its version in the same SQL transaction. Administrator bootstrap retains its original isolation level.

After a successful password change, the user Web layer calls `SessionService` to log out the current device. If Redis fails during logout, the committed SQL version change remains effective, and subsequent authentication rejects other old sessions. `SessionCleanupTask` only triggers scheduled work; scanning, expiry checks, and failure logging remain in `ActiveSessions`.

## Dependency Boundaries and Design Choices

- Controllers call application services or necessary HTTP adapters, without accessing repositories, Redis, or rate limit storage. The authentication filter and `AuthService` may use the module's `RequestLimits`; controllers do not use it directly.
- Application code may use Spring transactions, security abstractions, shared business exceptions, and its own infrastructure. It must not depend on Servlet or controllers. `AccountPolicy` remains in the application layer because it currently expresses HTTP business errors through `ApiException`.
- Other modules read accounts through `user.application.AccountQueryService`. User application code does not depend on authentication. Authentication calls user services rather than user infrastructure.
- Public application DTOs, `AccountPolicy`, and `user.domain.Role` may cross module boundaries. A snapshot containing a password hash remains internal even though it lives in a DTO package.
- User Web code may use `auth.application.dto.SessionPrincipal` and `auth.web.session.SessionService`. These are explicit integration points for reading authenticated identity and ending a browser session after a password change. They must not spread into user application code.
- Configuration classes wire components and may reference their module's Web adapters. `RuntimeConfiguration` currently checks both the runtime environment and authentication settings, so it remains under authentication configuration until another business need warrants separation.
- `shared` must not depend on `user` or `auth`. Move code there only when multiple modules actually use it and it has no module-specific meaning. Authentication-only Redis helpers stay in authentication.

There is no full domain model or dependency inversion interface for every component. A single implementation does not require a Service/ServiceImpl pair. Packages alone do not enforce dependency boundaries, so code review must check references. Account status still uses database strings; a dedicated status type and more general runtime configuration can evolve when needed.

## Database Access

Repositories retain business-oriented methods and use MyBatis-Plus internally. BaseMapper and LambdaWrapper handle ordinary single-table operations; XML contains joins, row locks, state transitions, authentication-version increments, and maintenance queries. Application services own transactions. Do not add generic IService/ServiceImpl layers or expose mappers to controllers.

Persistence entities stay in infrastructure. Users and identities use AUTO IDs; files use INPUT binary UUIDs converted by MediaIds at repository boundaries. Reference mappers match all three columns of the composite key. Clearing avatars and collection schedules writes NULL explicitly rather than relying on entity update defaults.

Main mappers explicitly bind to sqlSessionTemplate and load mapper/main XML. Notifications privately own a separate pool, MyBatis-Plus factory, and SqlSessionTemplate, loading only mapper/notification. These are not additional DataSource or session-factory beans. The notification pool keeps auto-commit, its 250ms acquisition wait, one-second statement timeout, and existing network limits. Timed-out SQL connections are evicted; the original update is not retried.

Notification success requires a confirmed READY commit. An SQL timeout means success was not confirmed; it does not prove that the update was rolled back, and connection eviction cannot provide that guarantee. Clients request a new file ID after failure. The old file may remain ISSUED with its original collection time or become READY with an unreferenced collection time. Existing GC handles both outcomes and protects bound files, without an additional confirmation transaction or immediate storage compensation. This SQL race does not lose the file's tracking record; an extremely late remote copy completing after GC can still leave a residual object, as already accepted by the design.

Both executors use STATEMENT local-cache scope and disable second-level caching, preserving fresh authentication versions and READ_COMMITTED reference queries. No global logical-delete, optimistic-lock, or automatic-fill policy changes existing state transitions or database defaults. Test-only mappers and XML provide fixtures, cleanup, and database assertions without expanding production repository APIs.

## Where New Classes Belong

| New capability | Placement and reason |
|---|---|
| Profile editing endpoint | `user.web`; HTTP fields in `user.web.request`, workflow in `user.application` |
| Database query for a user list | SQL in `user.infrastructure.persistence`, with an application service entry point for other modules |
| User rule independent of databases | `user.domain`; keep rules coupled to HTTP errors or application conventions in the application layer |
| Authentication rate limit rule | `auth.infrastructure.ratelimit`, called by authentication workflows or filters |
| Article display cache | `article.content.infrastructure.cache`; per-article detail/list projections, with no page cache or scheduled compensation framework |

Unit test packages generally mirror their subjects. Cross-module HTTP integration tests remain at the application root. Do not create empty packages, interfaces, or utility layers for hypothetical future needs.

### Writing Package Comments

Each business root and actual subpackage maintains a Chinese `package-info.java`. Start with concrete work, then add an example or dependency boundary when useful. Describe existing behavior only; one or two sentences are enough for small packages. Avoid descriptions consisting only of terms such as persistence capabilities or use-case orchestration.

The following is the actual Chinese package comment, retained as source code rather than translated into a second code-comment language:

```java
/**
 * 接收用户和管理员的 HTTP 请求，将已认证身份转换成操作人后调用用户服务。
 * 改密成功后通过认证模块的会话适配器退出当前登录，不直接操作数据库。
 */
package com.febrie.backend.user.web;
```

It describes receiving user and administrator requests, converting the authenticated identity into an actor, calling user services, and ending the current session after a password change without accessing the database directly.

Maintain these architecture documents separately under `zh-CN` and `en-US`, with matching sections and rules. Code comments remain Chinese. Update package comments and both documents whenever structure or boundaries change.

## Files and Live Avatar Profiles

The media module now implements V2. See the [media guide](../../../guides/media/zh-CN/media-service.md) for the protocol and deployment.

- MediaService inserts ISSUED records and issues POST Policies. UploadStorage isolates provider signing, one copy, and idempotent deletion; there is no HEAD confirmation.
- FinishUpload shares identity checks, a monotonic deadline, moderation, copy, and conditional READY commit. ContentModeration only returns a decision: real synchronous moderation in production and an immediate development bypass. Notification SQL uses a separate bounded connection pool.
- Storage notifications have a stateless security chain and synchronous bounded body reading with one deadline recorded at filter entry. Expired or explicitly timed-out calls stop further processing; production returns an empty HTTP 504 for COS to turn into upload-side 203. No application timer preempts the response. Production verifies COS signatures; development verifies MinIO Bearer tokens. The development Caddy proxy forwards uploads and normalizes JSON without waiting for publication.
- FileRecord maps exactly eight columns; references have three columns. MediaIds handles standard BINARY(16) UUID conversion; MediaLocation derives extension-free keys from the configured prefix.
- MediaReferenceService requires explicit READ_COMMITTED. Callers first lock the business entity, then files in ID order. Reference checks and the current next_action_at are re-read under file locks. User-row transactions enforce avatar uniqueness; article body and cover form one ARTICLE_IMAGE reference set.
- UserProfileService binds only READY files and updates the avatar and references atomically. Deleting a user detaches their avatar. Avatar edits do not update authentication versions. Media does not depend on user infrastructure.
- MediaLifecycleService only reclaims ISSUED, unreferenced READY, and DELETING records. It never resumes publication. Object deletion happens outside the short transaction and precedes metadata deletion; failures use a fixed rescheduling interval.
- FixedWindowCounter is a shared Redis primitive. Authentication rules stay in auth and media consumes its own upload admission rule. Shared code never depends on business modules.

The rewritten Flyway V2 definition is for new databases only. Old complete/prepare endpoints, leases, intermediate states, and compatibility modes are removed. Articles are introduced through the additive V3 migration; content and references share one RC transaction after locking the article row. Non-web initialization does not run cleanup, and startup does not mutate bucket configuration.

## Article writes, reads, and cache

See the [article contract and recovery guide](../../../guides/article/zh-CN/article-crud-implementation.md). Deletion sets an irreversible hidden flag and preserves media references. Author management SQL selects only that author's public articles; there is no recycle bin, restore API, or private hidden-row hydration.

- ArticleService rejects surrounding transactions, prepares canonical JSON, safe HTML, summary and media through ArticleContentRenderer, then calls the independent ArticleCommitService proxy for one short RC transaction. Article locks precede media locks. Bounded post-commit cache maintenance cannot override confirmed SQL success.
- Article permissions and hidden state are checked under the row lock. MediaReferenceService remains the binding gate. SQL is not automatically retried; metadata versions and Outbox events commit with content, then reach ES through MQ. Per-call in-memory commit observation classifies proxy failures; it is not persistent business state.
- ArticleQueryService and ArticleListHydrator reject surrounding transactions. After obtaining cache locks they invoke ArticleReader to establish a short read-only RR snapshot. List SQL excludes large content fields. AccountQueryService.displays batches only IDs and usernames, including historical attribution for soft-deleted accounts.
- Detail/list DTOs contain coverUrl, author name and tag names. Batch hydration separates cache checks, non-blocking lock acquisition, rechecks, combined SQL and per-token publication. It never blocks on another lock while holding one.
- article.content.infrastructure.cache uses Spring Data Redis and fixed Lua resources directly. Three-state projections, the shared five-second lock, double invalidation and persistent tombstones stay article-specific; no generic scripting framework or second lock library is added.
- ArticleRequestFilter bounds save bytes before conversion and sets no-store plus request correlation IDs. Only single-article GET is public; edit GET stays protected, and writes retain Session/CSRF.
- ArticleCacheRebuild runs only through an explicitly enabled non-web command after traffic and old instances stop. It rebuilds tombstones into a fresh namespace, rejects used versions, and never runs FLUSHDB.

Classes follow actual responsibilities within the existing layers, without empty domain packages or Service/ServiceImpl pairs. Used packages retain Chinese descriptions. Web does not access Mapper/Redis, and article does not access user/media infrastructure.

## Atomic Redis Operations

Three short Lua scripts implement window increments, account/IP login failure recording, and active session registration. Resources live in `backend/src/main/resources/redis/` and are called directly by FixedWindowCounter, RequestLimits, and ActiveSessions. The generic RedisAtomic transaction callback is removed; no script execution framework or dependency is added.

Java owns keys, windows, business thresholds, and positive integer TTLs in milliseconds. Scripts perform only bounded atomic operations. Spring Data Redis creates singleton RedisScript instances, caches their SHA, and falls back from EVALSHA to EVAL on a cache miss. Counters return strings parsed as long to preserve integer precision; other scripts return an explicit success marker. Every write refreshes the TTL, duplicate session registration succeeds, and rejected requests still increment the counter.

Redis errors and invalid results retain existing dependency failure handling without allowing the request or automatically retrying writes with uncertain results. Lua does not provide error rollback. This migration keeps keys, TTLs, data formats, and the Session namespace unchanged; no data clearing is needed, and the application can be rolled back directly. Runtime accounts must permit EVAL/EVALSHA and the commands used by scripts. Deployment remains a single Redis instance; the two-key script does not add Cluster hash-slot support.

Set `IT_REDIS=true` and run `mvn '-Dtest=RedisLuaIntegrationTests' test`, with `IT_REDIS_HOST/PORT/PASSWORD` supplying connection settings. These tests connect only to real Redis without loading MySQL, Web, or object storage. They isolate and clean up their own random key prefix without FLUSHDB or SCRIPT FLUSH.

## Session Migration and Verification

Moving `SessionPrincipal` and `Role` changes the type names stored by Java serialization. The Session namespace changes from `${app.auth.redis-prefix}:session` to `${app.auth.redis-prefix}:session:v2` to avoid reading the old types. Other Redis keys, TTLs, SQL schemas, and API behavior remain unchanged.

Stop all old application instances before starting the new version uniformly. Existing logins expire from the application's perspective; browsers fetch a fresh CSRF token and log in again. Old session data expires naturally. Do not clear shared Redis or add a legacy type compatibility layer. A rollback also requires a uniform instance and configuration switch; restoring the old namespace can expose unexpired old sessions again, so assess login state separately.

Run `mvn test` for each batch and `mvn clean verify` at completion. Real integration tests require explicit `IT_DB_PASSWORD`, a dedicated `easy_community_auth_test` database, and a random Redis prefix. Supply the Redis host, port, and password through test environment variables. Never point tests at the business database. See the backend README for startup steps and the Windows JDK temporary socket workaround.

Verify consistent account snapshots and fresh versions, controller actor conversion, login ordering and failure counters, and existing cookie, CSRF, role, password change, deletion, concurrency, revocation, and cleanup behavior. This refactor does not add a session namespace isolation scenario test. Read credentials only from local configuration or environment variables, never from committed code or documentation.

## Article Capabilities and Shared Transactions

Find article code by `content`, `view`, `tag`, `like`, or `search`, then by its technical layer. `article.common` contains only shared article errors and ID parsing. Content application services retain save, commit, rendering, reading, and cache maintenance responsibilities without a separate directory for every class. Public details record views; search and feed read counts in batches without adding views.

`TagService.requireAllExist` exposes catalog validation in the caller's transaction. Content no longer accesses tag infrastructure. Article-tag association replacement and display JOINs remain in content persistence. Article creation still initializes the like-count row in the same transaction as content, tags, and media references, even when likes are disabled. Failures roll back together; this documented shared data boundary is not replaced with asynchronous events.

`ArticleCacheRebuild` belongs to content infrastructure maintenance and retains its non-Web and unused-namespace requirements. Separate commit and short-read beans preserve Spring transaction proxies. The cross-capability view MySQL test stays in the content application package to access internal commit confirmation without widening production visibility.

Package moves update explicit Mapper scans, XML namespaces and result types, and qualified names in tests and scripts. Mapper XML is grouped under `mapper/main/article/content` and `view`; notification persistence stays outside the main scan. HTTP, SQL schema, configuration keys, Redis keys, and message protocols remain unchanged.

## Like Infrastructure and Management Ownership

Like infrastructure is grouped into `persistence`, `redis`, `messaging`, `config`, `observability`, and `maintenance`. Recovery and audit services live in `application.maintenance`, retaining dedicated connections, named locks, read-only snapshots, and writer shutdown requirements. Command-line entry points remain infrastructure adapters. Existing cross-component tests stay in the like test root so shared command fixtures need no wider visibility.

The management security chain belongs to `shared.management.ManagementSecurityConfiguration`. It still permits only local management-port GET requests for health/prometheus, with the same chain order and Bean method. Allocator health remains like observability with its existing component name. Like Mapper XML lives under `mapper/main/article/like`; messages, Bean names, queues, confirms, and retry protocols stay unchanged.

## Media Workflows and Public URLs

`media.application.upload` contains upload grants, notification publishing, moderation contracts, uploaded-object context, deadlines, and its DTOs. `reference` maintains references in the caller's transaction; `lifecycle` handles cleanup. File models, storage adapters, and persistence remain shared. `UploadStorage` and `MediaErrors` stay common media application types.

`MediaUrls` extracts fixed public-address conversion from the upload service for article rendering, article reads, user profiles, and upload grants. It computes addresses without checking READY state or accessing storage; callers retain their original visibility and reference preconditions. `MediaService` only handles upload admission. Java packages and constructor arguments change without old-package forwarding classes.

Notification filtering, signature verification, parsing, and responses are grouped in `media.web.notification`. Raw body/query verification, the shared deadline, and the separate security chain stay intact, as do the private notification pool, auto-commit, exclusion from main Mapper scans, reference lock order, and cleanup transactions. Cross-component media tests retain their shared fixtures; notification Filter tests mirror the notification package.

Validate package changes with the default build, isolated real-dependency integration tests, and before/after behavior comparisons. Historical results do not certify the current revision. See the [backend verification instructions (Chinese)](../../../../../backend/README.md#验证).
