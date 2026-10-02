# Persistent session integration

Current review follow-up: see [FRONTEND_SESSION_REQUIREMENTS.md](FRONTEND_SESSION_REQUIREMENTS.md) for the inspected NextAuth flow and exact outstanding frontend requirements. The backend now uses AccountPrincipal/AccountContextService; V29 preserves USER creator IDs and adds separate STAFF creator columns. Review verification is recorded in REVIEW_FIXES.md. Earlier verification counts below are historical.

Owner authentication uses `USER` plus `users.id`; Staff uses `STAFF` plus `staff.id` (the table primary key, distinct from the existing Staff login/business ID). JWTs carry `accountId`, `shopId`, and `sid`. Owner JWT subjects are user IDs; Staff subjects retain the existing `staff:<shopId>:<staff.id>` format. Names remain response/display data. Existing AuthResponse fields, registration, plan feature gates, and SUPER_ADMIN authentication are preserved. Staff roles including `ADMIN` never grant Owner authorities.

Every protected USER/STAFF request checks the database session and current account/shop. Legacy tokens without `sid` require login again. Revocation invalidates subsequent access requests as well as refresh. A request already authenticated before a revocation commits may finish.

## Migration and configuration

`V28__persistent_login_sessions.sql` is the next migration after V27. It creates `login_sessions` and `consumed_refresh_tokens`, with UUID sessions, account and shop identifiers, installation metadata, SHA-256 refresh hashes, timestamps, and revocation reasons. Raw refresh credentials are never stored in either table or returned in JSON.

Flyway applies V28 through the existing startup configuration. Existing migrations were not edited. The integration test executes V28 on a disposable PostgreSQL database with prerequisite entity tables; it does not test rebuilding every historical migration from an empty production schema.

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `JWT_SECRET` | Existing required setting | Base64 HMAC key, at least 32 decoded bytes |
| `JWT_EXPIRATION_MS` | `900000` | Access token lifetime: 15 minutes |
| `REFRESH_TOKEN_TTL_SECONDS` | `2592000` | Absolute session lifetime: 30 days |
| `SESSION_COOKIE_SECURE` | `true` | Set `false` only for local HTTP development |
| `SESSION_COOKIE_SAME_SITE` | `Lax` | `Lax`, `Strict`, or `None`; `None` requires Secure |
| `SESSION_COOKIE_PATH` | `/api/auth` | Must cover the auth routes; host-only cookie, no Domain attribute |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` in application.properties | Comma-separated exact browser origins, without trailing slash; no wildcard or `null` |
| `FRONTEND_URL` | `http://localhost:3000` | Existing password-reset email frontend URL, unchanged |

Local frontend/backend configuration uses `http://localhost:3000` and `http://localhost:8080`. The inspected Next.js config resolves `NEXT_PUBLIC_API_BASE_URL`, then `REMOTE_API_BASE_URL`, then localhost:8080; client helpers also use `NEXT_PUBLIC_API_URL`. Keep these consistent. Configure the actual browser origins in `CORS_ALLOWED_ORIGINS`, including when Next.js proxies requests. For HTTPS frontend and API on the same site, keep Secure=true and SameSite=Lax. For genuinely cross-site HTTPS, use Secure=true and SameSite=None; browser third-party-cookie policy may still block cookies, so a same-site proxy is preferable.

Login, Staff login, refresh, and logout require an exact allowed `Origin`, including for non-browser clients. Missing/untrusted origins return 403. This protects cookie authentication and login against CSRF. CORS permits credentials and `X-Device-ID` / `X-Device-Name`. Other protected APIs authenticate with explicit Bearer headers, not cookies. SUPER_ADMIN login retains its existing flow.

## API examples

Generate and persist one random installation ID per browser/app installation (for example `crypto.randomUUID()`). Reuse it across tabs, users, and Owner/POS dashboards in that installation. Device ID is metadata, never a credential. Login still verifies account credentials; supplying another device's ID cannot adopt its sessions, access its account, or revoke its sessions. Counts are distinct active device IDs per shop. This is a client-declared installation limit, not hardware attestation.

Owner login:

```http
POST /api/auth/login
Origin: http://localhost:3000
Content-Type: application/json
X-Device-ID: 05d3b8e5-eacc-4bf0-9cd4-c38b2140cd20
X-Device-Name: Front desk Chrome

{"username":"owner","password":"your-password","shopCode":"SHOP"}
```

Staff login uses `POST /api/auth/staff/login`, the same headers, and the existing body:

```json
{"staffId":900,"password":"staff-password","shopCode":"SHOP"}
```

Login and refresh return the existing AuthResponse shape (values below are illustrative; Staff also receives its business `staffId`):

```http
HTTP/1.1 200 OK
Set-Cookie: pos_refresh=<opaque-value>; Path=/api/auth; Max-Age=2591999; Secure; HttpOnly; SameSite=Lax
Cache-Control: no-store
```

```json
{
  "token":"<access-jwt-with-sid>",
  "tokenType":"Bearer",
  "username":"owner",
  "role":"ADMIN",
  "shopId":10,
  "shopCode":"SHOP",
  "businessType":"SUPERMARKET",
  "staffId":null,
  "imageUrl":null,
  "shopStatus":"ACTIVE",
  "subscriptionPlan":"TRIAL",
  "subscriptionEndDate":"2026-10-16",
  "features":{},
  "limits":{}
}
```

`features` and `limits` above stand for the existing populated DTOs; their fields are preserved. No JSON field contains a refresh token. With local Secure=false, the Secure cookie attribute is omitted.

Refresh (no request body and **no Authorization header**; an expired Bearer header would be rejected by the JWT filter):

```http
POST /api/auth/refresh
Origin: http://localhost:3000
Cookie: pos_refresh=<browser-managed-value>
```

Returns 200 with AuthResponse and a rotated HttpOnly cookie. Session expiry is absolute; rotation does not extend it. Reusing any consumed refresh token revokes that session and returns 401, clearing the cookie. Revocation commits despite the rejection. Invalid, expired, revoked, deleted-account, inactive-Staff, or unavailable-shop refreshes return 401. History is retained so older rotations remain detectable.

Current session logout:

```http
POST /api/auth/logout
Origin: http://localhost:3000
Authorization: Bearer <access-token>
```

Returns 204 and expires `pos_refresh`. If access has expired, refresh first, then logout. Logging out affects the current session; Owner remote logout affects every active session on the selected device in that shop.

Owner device management:

```http
GET /api/owner/shops/10/devices
Authorization: Bearer <owner-access-token>
```

```json
[
  {
    "sessionId":"2aee775b-32ce-4315-9e1b-8ed1488bab06",
    "accountType":"USER",
    "accountId":7,
    "deviceId":"05d3b8e5-eacc-4bf0-9cd4-c38b2140cd20",
    "deviceName":"Front desk Chrome",
    "expiresAt":"2026-11-01T00:00:00Z",
    "lastSeenAt":"2026-10-02T00:00:00Z"
  }
]
```

The list contains active sessions; group by `deviceId` to display installations with their accounts. No token hashes are exposed.

```http
DELETE /api/owner/shops/10/devices/05d3b8e5-eacc-4bf0-9cd4-c38b2140cd20
Authorization: Bearer <owner-access-token>
```

Returns 204. Staff and another shop's Owner receive 403; a device not active in the authenticated Owner's shop returns 404. Revoking the current device also signs the requesting Owner out. A login that would exceed `maxDevices` returns 409. Repeated same-account/same-device login replaces that account's old session without increasing device count. Different accounts on one installation count as one device.

Existing password reset request/response formats are unchanged. Successful reset updates the password, consumes reset tokens, and revokes every session for that USER atomically. Confirmation email remains an after-commit action; Staff sessions and other USER accounts remain unaffected.

`GET /api/me/plan` now includes `usage.deviceCount`. Existing admin plan usage `deviceCount` also reflects distinct unexpired/unrevoked installations. Monthly usage reset retains the live count.

## Concurrency and maintenance

Session mutations lock the shop database row, so device enforcement works across application instances. Login, refresh, and reset acquire account locks before shop locks; credential checking after the account lock prevents old-password logins from surviving reset. Both refresh transaction boundaries explicitly commit replay revocation while rolling back ordinary failures.

Session validity and last-seen tracking use database queries on protected requests; there is no revocation cache delay. Account deletion prevents authentication even if historical sessions remain. No automatic history purge is added: plan retention for expired/revoked sessions and consumed hashes. Do not delete consumed hashes for sessions that could still be accepted. Installation IDs can be copied by clients and are not proof of a physical device.

## Frontend changes still required

1. Persist a random installation ID and send both device headers on Owner/Staff login. Send `credentials: "include"` (fetch) or `withCredentials: true` (Axios) for login, refresh, and logout. Browser code must not read or store refresh credentials.
2. Store/use the JSON access token as before, preferably in memory. On app initialization, refresh with credentials to restore login. Use the refreshed AuthResponse to restore existing profile, plan, and feature data.
3. Coordinate refresh across tabs so only one request consumes a refresh token. Concurrent reuse deliberately revokes the session. Do not automatically retry a refresh after a lost response using the old token; require login if state cannot be recovered.
4. On a protected-request expiry, refresh once, update the access token, and retry an eligible GET/HEAD once. Mutation replay requires explicit verified idempotency; do not automatically replay payments. On refresh 401, clear client authentication and redirect to login. Do not attach the old Bearer token to refresh.
5. Add Owner device listing/revocation UI and handle 409 device limits. Staff must not display Owner controls based on its informational `role` string. Use account type/established login flow and server authorization; a Staff role named ADMIN still has no Owner authority.
6. Call server logout before clearing local state. Password reset/remote logout becomes visible on the next protected request; clear local auth on the resulting rejection. Existing tokens require one new login after this backend update.

No frontend files were modified.

## Tests and files

Run the PostgreSQL integration tests only on a disposable local database named `session_test`; tests create/drop tables. Example PowerShell:

```powershell
docker run --detach --rm --name pos-session-tests --publish 127.0.0.1:55438:5432 --env POSTGRES_DB=session_test --env POSTGRES_PASSWORD=session-test-only postgres:16-alpine
$env:SESSION_TEST_URL='jdbc:postgresql://127.0.0.1:55438/session_test'
.\mvnw.cmd '-Dtest=*,!PosApplicationTests' test
docker stop pos-session-tests
```

The integration suite covers Owner/Staff authorization (including Staff named like the Owner with role ADMIN), stable identity, legacy tokens, cross-shop rejection, concurrent device limits, rotation/replay (including concurrent replay), remote/current logout, reset revocation/rollback/concurrency, active counts, current account/shop status, actual V28 execution, cookies/Origin protection, and unchanged SUPER_ADMIN acceptance. Existing feature/POS tests run alongside it.

Verified locally on 2026-10-02 with Java 21 and disposable PostgreSQL 16:

- `mvnw.cmd -q -DskipTests compile`: passed.
- `mvnw.cmd -q '-Dtest=*,!PosApplicationTests' test` with `SESSION_TEST_URL`: 82 passed, 0 failed, 15 skipped (12 existing StockIntegrationTest and 3 StockMigrationTest cases require their separate `STOCK_TEST_URL`).
- After the final refresh transaction and cookie-expiry adjustments, `SessionIntegrationTest,PasswordResetServiceTest`: all 27 passed (14 session integration tests and 13 reset unit tests), including compilation.
- `git diff --check`: passed.

The existing `PosApplicationTests` context smoke test was excluded to avoid its default application datasource. No deployment, push, or production data access was performed. No end-to-end browser UI tests were run. The expanded session test now exercises real Owner/profile, product, receipt, shop settings and receipt settings controllers/services and repositories through the authentication filter. File storage, email and feature-gate services are mocked; no external services are called.

Changed/new files for this implementation (paths relative to this repository):

- `src/main/resources/db/migration/V28__persistent_login_sessions.sql` (new)
- `src/main/java/com/binhlaig/pos/auth/session/SessionStore.java` (new)
- `src/main/java/com/binhlaig/pos/auth/session/SessionService.java` (new)
- `src/main/java/com/binhlaig/pos/auth/session/SessionCookies.java` (new)
- `src/main/java/com/binhlaig/pos/auth/session/DeviceController.java` (new)
- `src/main/java/com/binhlaig/pos/auth/AuthController.java`
- `src/main/java/com/binhlaig/pos/auth/AuthService.java`
- `src/main/java/com/binhlaig/pos/auth/JwtService.java`
- `src/main/java/com/binhlaig/pos/auth/PasswordResetService.java`
- `src/main/java/com/binhlaig/pos/auth/jwt/JwtAuthFilter.java`
- `src/main/java/com/binhlaig/pos/config/SecurityConfig.java`
- `src/main/java/com/binhlaig/pos/admin/PlanLimitService.java`
- `src/main/java/com/binhlaig/pos/me/MeService.java`
- `src/main/java/com/binhlaig/pos/me/dto/PlanUsageDto.java`
- `src/main/java/com/binhlaig/pos/timecard/service/TimecardService.java`
- `src/main/java/com/binhlaig/pos/timecard/schedule/TimecardScheduleService.java`
- `src/main/resources/application.properties`
- `src/test/java/com/binhlaig/pos/auth/PasswordResetServiceTest.java`
- `src/test/java/com/binhlaig/pos/auth/session/SessionIntegrationTest.java` (new)
- `SESSION_AUTH.md` (new)

Pre-existing local modifications were preserved, including `application.yml`, which this implementation did not edit. No AGENTS.md was found in this repository or the inspected parent directories.
