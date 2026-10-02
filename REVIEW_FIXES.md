# Review follow-up — 2026-10-02

## Starting state and reproduction

The working tree already contained uncommitted session security work, typed
AccountPrincipal/AccountContextService, consumer changes, exact-password changes
and an untracked V29 migration when this turn began. These changes were preserved.
The original Staff/whitespace failures therefore were not reproduced on an
unmodified historical checkout, and are not claimed as newly reproduced here.
The current tree failed compilation because TimecardService lacked HttpStatus
and ResponseStatusException imports. Its tests also still depended on the old
username principal/ProductService constructor. These were corrected before
running the real application regressions.

A new test reproduced a remaining password-policy regression: AdminShopService
accepted eight spaces as a registration password after trimming was removed.
It failed with “Expecting code to raise a throwable.” Adding isBlank validation
fixed it without transforming valid passwords. The test now passes and the
existing admin registration test verifies a password with significant spaces.

## Final behavior and affected files

The original identity defect came from treating Staff subjects as users.username.
AccountPrincipal contains accountType, accountId, shopId and sessionId, and is
created by JwtAuthFilter only after signed JWT/current account/shop/DB-session
validation. AccountContextService resolves USER and STAFF by their own primary
keys. There is no authenticated username fallback. Staff subjects and staff-only
authorities remain unchanged; SUPER_ADMIN retains its separate flow.

Identity consumers in the current fix include PosReceiptController,
ProductService, ShopSettingsService, ShopFeatureController, MeService,
OwnerService and ReceiptSettingService. TimecardService and
TimecardScheduleService no longer use a username fallback for missing shop
context. Owner profile/POS paths accept the typed principal. Shop settings and
receipt settings writes explicitly require an Owner USER account.

Product and receipt entities/DTOs/repository/service preserve created_by_user_id
for USER and store STAFF in created_by_staff_id. V29__staff_creator_identity.sql
is the additive migration after V28; it adds separate nullable staff columns,
a receipt index and constraints prohibiting both creator namespaces on one row.
Existing creator IDs are not rewritten. The integration test applies actual
V28/V29 to isolated prerequisite tables and asserts historical USER IDs survive.
No additional duplicate V30 migration was needed for the already-present V29.

AuthService registration/login/Staff login and AdminShopService registration use
exact passwords. Reset, Staff password updates and SUPER_ADMIN password paths
were inspected for transformations; no additional password trim was found.
There is no separate Owner change-password endpoint in this backend. No stored
passwords were rewritten. Compatibility implications are documented in
FRONTEND_SESSION_REQUIREMENTS.md.

Files changed specifically during this follow-up:

- src/main/java/com/binhlaig/pos/timecard/service/TimecardService.java — imports.
- src/main/java/com/binhlaig/pos/admin/AdminShopService.java — reject blank passwords.
- src/test/java/com/binhlaig/pos/auth/session/SessionIntegrationTest.java — real
  receipt/product/settings paths and repositories, V29 preservation fixtures,
  subject collision, account/shop sid mismatch, exact whitespace reset/login and
  registration, concurrent reset single-use/revocation tests.
- src/test/java/com/binhlaig/pos/admin/AdminShopServiceTest.java — exact/blank passwords.
- src/test/java/com/binhlaig/pos/modules/product/ProductAvailabilityServiceTest.java,
  ProductBarcodeServiceTest.java, StockIntegrationTest.java — account context fixtures.
- src/test/java/com/binhlaig/pos/owner/OwnerServiceTest.java — typed principal fixture.
- SESSION_AUTH.md, FRONTEND_SESSION_REQUIREMENTS.md, REVIEW_FIXES.md — documentation.

## Verification

Disposable PostgreSQL 16 containers bound only to loopback:

```powershell
docker run --detach --rm --name pos-review-session-tests --publish 127.0.0.1:55438:5432 --env POSTGRES_DB=session_test --env POSTGRES_PASSWORD=session-test-only postgres:16-alpine
docker run --detach --rm --name pos-review-stock-tests --publish 127.0.0.1:55439:5432 --env POSTGRES_DB=stock_test --env POSTGRES_HOST_AUTH_METHOD=trust postgres:16-alpine
$env:SESSION_TEST_URL='jdbc:postgresql://127.0.0.1:55438/session_test'
$env:STOCK_TEST_URL='jdbc:postgresql://127.0.0.1:55439/stock_test'
.\mvnw.cmd -q '-Dtest=*,!PosApplicationTests' test
.\mvnw.cmd -q '-DskipTests' compile
git diff --check
docker stop pos-review-session-tests pos-review-stock-tests
```

Final regression run: 102 tests, zero failures/errors/skips, across 18 test
classes, including 19 session integration tests, 12 stock integration tests and
3 stock migration tests. Compilation passed. Git diff whitespace check passed.
An old PosApplicationTests report in target was excluded from these counts.
The final test command excludes that default-datasource context smoke test to
avoid accessing an uncontrolled database. Intermediate new migration fixtures
needed explicit non-null stock fields; those test fixture errors were fixed
before the final passing run.

Session checks retain device-limit concurrency, refresh rotation/replay,
remote/server logout, origin/cookie rules, password reset transaction rollback,
reset racing login/refresh and SUPER_ADMIN acceptance. New tests cover Staff
product creation/read and receipt creation/list, a different-shop USER named
exactly like the Staff subject, Owner POS/profile, Staff settings write denial,
exact-password reset-to-login and concurrent reset consumption/revocation.

## Limits

Frontend is a sibling outside the supplied workspace roots. Its active NextAuth
flow was inspected read-only; no frontend integration completion is claimed.
FRONTEND_SESSION_REQUIREMENTS.md specifies device metadata, trusted Origin,
BFF credential/cookie handling, refresh coordination across instances/tabs,
bounded safe retries, logout and cart preservation. Browser E2E remains pending.

Tests use real identity/session/POS database paths but mock feature gates, email
delivery and file storage. They do not verify external services or rebuilding
every historical migration from an empty production schema. No production data
was accessed; no push or deployment occurred. The pre-existing pos_db container
was not used or changed.
