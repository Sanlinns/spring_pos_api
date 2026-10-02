# Frontend integration still outstanding

Inspected read-only: sibling `web_dashboard/pos-web-dashboard/lib/next-auth.ts`,
`lib/backend-api.ts`, `lib/auth.ts`, and the NextAuth route layout. This frontend is
outside this task's workspace roots. No frontend files were changed; browser or
multi-tab integration has not been verified.

The active NextAuth flow uses Credentials and JWT sessions. `authorize()` calls
Spring from the Next.js server, sends only Content-Type, and reads only JSON.
It does not retain Spring's Set-Cookie. Its JWT callback clears an expired access
token. `backendRequest()` sends a stored bearer and immediately clears auth and
redirects on 401. Merely adding `credentials: include` to the server fetch does
not transfer a browser cookie or persist the backend response cookie.

## Required implementation for this flow

1. Persist one random installation UUID in a frontend host cookie or local
   storage, shared across tabs and Owner/Staff login. Send it through Credentials
   to the BFF, then as `X-Device-ID`; send a bounded human-readable
   `X-Device-Name`. Never generate a fresh ID for every server request/login.
   Preserve passwords exactly; normalize only username and shop code.
2. Use a server-configured `BACKEND_TRUSTED_ORIGIN`, such as
   `http://localhost:3000` locally, matching Spring's `CORS_ALLOWED_ORIGINS`.
   Set it on server-side login, Staff login, refresh and logout. Do not derive
   this trusted value from an arbitrary inbound Origin or Host. The BFF itself
   must validate browser Origin/CSRF on state-changing session endpoints.
3. Keep refresh credentials exclusively in a server-side BFF session store
   keyed by an opaque frontend HttpOnly session cookie. Encrypt credentials at
   rest; never expose them through NextAuth's session callback, JSON, local
   storage or logs. Parse only the backend `pos_refresh` cookie and its expiry.
   Bind the BFF session to the NextAuth session; never use a process-global cookie
   jar shared by users. NextAuth JWT callbacks refer to this server-side session.
   Use a host-only frontend cookie with `Path=/`, HttpOnly, SameSite=Lax and Secure
   on HTTPS (local HTTP exception only). Do not forward backend Domain, Path,
   SameSite or Secure attributes blindly. Clear using the identical cookie scope.
   New routes can live at `/api/session/refresh` and `/api/session/logout`, avoiding
   the existing `/api/auth/[...nextauth]` route namespace.
4. Serialize refresh by BFF session ID using a shared store lock/version check
   across server instances. Inside the lock, re-read the current token generation;
   if another request already refreshed, reuse that result. Persist the rotated
   cookie and access token atomically before releasing the lock. An in-memory
   Promise alone does not coordinate separate instances or tabs. Browser tabs
   can additionally use Web Locks plus BroadcastChannel to distribute the new
   access token/auth state; they must re-check the generation after acquiring the
   lock. Refresh requests omit Authorization. Never retry refresh with a consumed
   credential after a timeout/lost response; require sign-in if rotation state
   cannot be recovered. The backend deliberately revokes on refresh replay.
5. Update NextAuth JWT/session handling to use the BFF's current access token and
   AuthResponse metadata instead of immediately expiring the login at access JWT
   expiry. Update `backend-api.ts` to refresh once on an expiry response, then
   retry an eligible protected request at most once. A second 401 ends recovery;
   403 is authorization/feature denial and does not trigger refresh. Do not send
   tokens to arbitrary absolute URLs. Share one refresh outcome across callers.
6. Default automatic replay to GET/HEAD. Do not automatically replay payments,
   receipt creation, stock operations or other mutations. An explicitly opted-in
   endpoint may retry only after verifying its idempotency contract and retaining
   the exact original request ID and payload; never generate a new request ID for
   a retry. A timeout is an uncertain payment outcome, not proof of failure.
7. Logout calls the BFF first; under the same session lock, obtain a valid access
   token if necessary, POST `/api/auth/logout`, then remove the BFF credentials
   and expire its cookie and NextAuth cookies using NextAuth sign-out. Coordinate
   logout with refresh so a late response cannot restore authentication. If the
   backend is unreachable, clear local credentials and report that remote
   revocation was not confirmed rather than claiming success.
8. Before redirecting on auth failure, preserve the cart and pending operation
   identifiers under the shop/account context. Clear only auth keys, not all
   storage. On a different account/shop login, do not submit the old cart or
   automatically retry a pending payment. Restore after the user confirms the
   correct shop and any uncertain payment has been reconciled.
9. Staff's display role may say ADMIN but is not Owner authorization. Use the
   authenticated account type and server permissions; retain separate user/staff
   identities. Handle device-limit 409 without deleting the cart. Owner device
   listing/revocation uses the existing `/api/owner/shops/{shopId}/devices` routes.

Acceptance checks: exact-space passwords; Owner and Staff login; refresh-cookie
scope on the actual frontend HTTPS host; simultaneous requests from two tabs and
two BFF instances; expired/revoked sessions; refresh response loss; logout racing
refresh; no double payment after 401 or timeout; cart preservation and shop-switch
isolation. These checks remain outstanding.

## Existing password compatibility

Existing hashes are not rewritten. Accounts originally registered through a
trimming path must enter the historically hashed (trimmed) password; extra spaces
are now significant and fail. Accounts reset or created with significant spaces
can log in with that exact string. Do not add a trimmed-password fallback: it
would restore ambiguous authentication. Users who cannot reproduce the stored
credential can use the existing reset flow. Password policy still rejects blank
or too-short registration credentials and enforces the existing reset policy.
