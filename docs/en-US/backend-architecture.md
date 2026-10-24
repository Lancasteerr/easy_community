# Backend Code Organization and Architecture

[简体中文](../zh-CN/backend-architecture.md) · [Startup, API, and deployment guide](../../backend/README.md)

## Find Code by Business Responsibility

The backend remains a single Maven project. Find the business package first, then look for its HTTP endpoints, workflows, or database operations. A user feature can usually be understood within `user`, rather than across global Controller, Service, and Mapper directories.

The root package is `com.febrie.backend`:

```text
backend
├─ BackendApplication
├─ user
│  ├─ web
│  │  └─ request
│  ├─ application
│  │  └─ dto
│  ├─ domain
│  └─ infrastructure
│     ├─ persistence
│     └─ bootstrap
├─ auth
│  ├─ web
│  │  ├─ request
│  │  ├─ response
│  │  ├─ session
│  │  └─ security
│  ├─ application
│  │  └─ dto
│  └─ infrastructure
│     ├─ config
│     ├─ security
│     ├─ session
│     ├─ ratelimit
│     ├─ redis
│     └─ scheduling
├─ media
│  ├─ web
│  │  └─ request
│  ├─ application
│  │  └─ dto
│  ├─ domain
│  └─ infrastructure
│     ├─ config
│     ├─ storage
│     ├─ persistence
│     └─ scheduling
└─ shared
   ├─ error
   └─ web
```

This is practical layering. A business module does not need all four layers. Authentication currently has no separate pure business model, so there is no `auth.domain`. Future post and comment packages should also follow actual needs.

## What the Package Names Mean

| Package | What belongs here | Current example |
|---|---|---|
| `web` | Receive HTTP requests, read authenticated identities, and return results | `UserController`, `AuthController` |
| `application` | Coordinate an operation and its transactions; expose services to other modules | User creation, password changes, login, account queries |
| `domain` | Business types or rules independent of databases, HTTP, and frameworks | The public `Role` enum |
| `infrastructure` | Implement database access, Redis operations, and security configuration | Account SQL, rate counters, active session sets |
| `persistence` | Read and write database records | `AccountRepository` uses JdbcTemplate |
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

## Where New Classes Belong

| New capability | Placement and reason |
|---|---|
| Profile editing endpoint | `user.web`; HTTP fields in `user.web.request`, workflow in `user.application` |
| Database query for a user list | SQL in `user.infrastructure.persistence`, with an application service entry point for other modules |
| User rule independent of databases | `user.domain`; keep rules coupled to HTTP errors or application conventions in the application layer |
| Authentication rate limit rule | `auth.infrastructure.ratelimit`, called by authentication workflows or filters |
| Post cache and expiry task | Create `post.infrastructure.cache` and `post.infrastructure.scheduling` when implemented, rather than global utility packages |

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

The new `media` module uses `web/request`, `application/dto`, `domain`, `infrastructure/config`, `infrastructure/storage`, `infrastructure/persistence`, and `infrastructure/scheduling`. See the [media service guide](../zh-CN/media-service.md) for deployment, protocols, and the article integration contract.

- `ObjectStorageProvider` is an application-layer contract with MinIO and COS implementations; business modules never depend on SDKs. `FileContentValidator` is reserved only, with no V1 implementation or invocation.
- `MediaService` issues upload grants, confirms HEAD metadata, and prepares copies outside business transactions. `MediaReferenceService` updates references inside the business transaction. `MediaLifecycleService` recovers copies and reclaims unreferenced objects.
- References are entity/file sets, not individual body occurrences. An entity/file unique index deduplicates article images; a generated-column unique index allows at most one current avatar per user. Cover and body sharing one image still produce one association.
- Binding and cleanup lock the same file rows in ID order. Maintenance uses READ_COMMITTED and reads the latest committed references after acquiring the file lock, preventing stale-snapshot deletion.
- `UserProfileService` prepares avatars outside the transaction, then locks the user before files and updates the profile field and association together. User deletion detaches the avatar through this service. User application services may depend on `media.application`; media does not depend back on user infrastructure.
- `UserProfileController` handles live profiles and avatars; `UserController` retains password changes. `GET /api/users/me` reads the database. Session snapshots and login responses remain identity-only; avatar changes do not increment the authentication version or rewrite sessions.
- Future articles prepare files first, then lock their article row in an explicit READ_COMMITTED transaction, save body/cover data, and synchronize the full deduplicated set. No article module is introduced in V1.

Flyway V2 adds these structures without changing V1. Tests additionally use the dedicated `easy_community_media_test` database. Real provider tests require explicit `IT_MINIO_*` or `IT_COS_*` variables. Non-web initialization commands do not run media cleanup, and normal startup never changes bucket policies.

## Session Migration and Verification

Moving `SessionPrincipal` and `Role` changes the type names stored by Java serialization. The Session namespace changes from `${app.auth.redis-prefix}:session` to `${app.auth.redis-prefix}:session:v2` to avoid reading the old types. Other Redis keys, TTLs, SQL schemas, and API behavior remain unchanged.

Stop all old application instances before starting the new version uniformly. Existing logins expire from the application's perspective; browsers fetch a fresh CSRF token and log in again. Old session data expires naturally. Do not clear shared Redis or add a legacy type compatibility layer. A rollback also requires a uniform instance and configuration switch; restoring the old namespace can expose unexpired old sessions again, so assess login state separately.

Run `mvn test` for each batch and `mvn clean verify` at completion. Real integration tests require explicit `IT_DB_PASSWORD`, a dedicated `easy_community_auth_test` database, and a random Redis prefix. Supply the Redis host, port, and password through test environment variables. Never point tests at the business database. See the backend README for startup steps and the Windows JDK temporary socket workaround.

Verify consistent account snapshots and fresh versions, controller actor conversion, login ordering and failure counters, and existing cookie, CSRF, role, password change, deletion, concurrency, revocation, and cleanup behavior. This refactor does not add a session namespace isolation scenario test. Read credentials only from local configuration or environment variables, never from committed code or documentation.
