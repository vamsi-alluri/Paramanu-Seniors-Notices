# `code_management` Endpoint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the phone's activation write and verification read off Realtime Database and behind an HTTPS Cloud Function, so the handset stops being a database client.

**Architecture:** A Cloud Function in the existing Firebase project reads and writes RTDB with Application Default Credentials and answers two POST routes. The client gains a seam in the shape the codebase already uses for analytics: a pure interface plus one Android-only implementation holding the HTTP and JSON, so every parsing and mapping decision stays JVM-testable. Migration is two independently shippable steps — activation, then foreground — with the security rules left deployed throughout.

**Tech Stack:** Node 22 + TypeScript, `firebase-functions` v2 `onRequest`, `firebase-admin`, Vitest. Client: Kotlin, OkHttp (promoted to a direct dependency; it is already on the classpath transitively via `coil-network-okhttp`), `org.json` confined to the Android-only layer, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-23-code-management-endpoint-design.md`

## Global Constraints

- **Identity comes from a verified Firebase ID token, never the request body.** Every route verifies with `admin.auth().verifyIdToken()` and uses the uid from the result.
- **`note` is never present in any response body.** It is opt-in holder data.
- **Three outcomes stay distinguishable end to end:** active, revoked, inconclusive. Inconclusive permits delivery; it is not a failure to design away.
- **No fallback to a direct RTDB read.** An unreachable endpoint degrades to cache only, reported as `ActivationState.Unknown`.
- **No control message triggers an HTTPS call.** Triggers are app open and foreground only.
- **Every call is logged to analytics with its outcome**, successes and failures alike.
- **Hard ceiling: 2,000,000 function executions per month** (~165/device/day across 400 devices).
- **`database.rules.json` is not modified by this plan.** Not one line.
- Staff operations (revoke, restore, dispensary and banner edits) are untouched.

## Wire Contract

Both routes: `POST`, `Authorization: Bearer <idToken>`, JSON body `{"code": "ABCD-1234"}`.

`POST /status` — always `200` for a verified token:
```json
{"status": "active", "code": {"code":"...","dispensary":"...","revoked":false,"activatedAt":0},
 "dispensary": { /* the /dispensaries/{id} node verbatim, including info */ }}
```
`status` is `"active"` or `"revoked"`. On `"revoked"`, `dispensary` is omitted. `401` for an unverifiable token, `400` for a malformed body, `5xx` for a server fault — the client maps all three, and any transport failure, to inconclusive.

`POST /activate` — `200` with the same body as `/status` on success. `409` with `{"status":"refused","reason":"unknown_code"|"not_issued"|"already_claimed"|"revoked"}` when the claim is refused. Everything else maps to `RedeemResult.Offline`, so the user is told to retry rather than to seek a new slip.

## Review Focus

- **A code whose node has been deleted outright** must read as revoked, not inconclusive — otherwise an erased claim keeps working. Covered in Task 3.
- **An expired or unverifiable token** must read as inconclusive, never revoked. Mapping it to revoked would lock out the whole fleet on a token-handling bug. Covered in Tasks 2 and 6.
- **Two devices activating the same code at once** — exactly one wins, and the loser is refused rather than overwriting. Covered in Task 4.
- **Activate retried by the uid that already holds the code** (dropped response on a flaky connection) must succeed, not report "already used". Covered in Task 4.
- **A dispensary payload with a malformed topic entry** must not replace the cached list with nothing; one bad entry is skipped, a missing record keeps the cache. Covered in Task 5.

## File Structure

| File | Responsibility |
|---|---|
| `functions/src/auth.ts` | Verify the bearer token, return the uid. |
| `functions/src/codes.ts` | Read a code, run the claim rules, perform the atomic claim. |
| `functions/src/index.ts` | The two routes and their HTTP shapes. |
| `functions/test/*.test.ts` | Vitest, against the Firebase emulator for the RTDB-touching parts. |
| `.github/workflows/deploy-function.yml` | Path-filtered deploy on push to `main`. |
| `app/.../analytics/AnalyticsEvent.kt` | New `CodeManagementCall` variant. |
| `app/.../activation/CodeManagementApi.kt` | Pure interface + pure response types + pure map→domain parsing. |
| `app/.../activation/HttpCodeManagementApi.kt` | The only file with OkHttp and `org.json`. |
| `app/.../data/InfoRepository.kt` | Change `applySnapshot(DataSnapshot)` to a map-based entry point. |
| `app/.../activation/ActivationRepository.kt` | `redeem()` then `verify()` call the API. |

---

### Task 1: Function scaffold and health route

**Files:**
- Create: `functions/package.json`, `functions/tsconfig.json`, `functions/src/index.ts`, `functions/test/health.test.ts`, `functions/.gitignore`
- Modify: `firebase.json` (add a `functions` entry)

**Interfaces:**
- Produces: a deployable `codeManagement` v2 `onRequest` function, and `npm test` in `functions/`.

- [ ] **Step 1: Write the failing test** — `functions/test/health.test.ts`: a `GET /health` returns 200 and `{"ok": true}`.
- [ ] **Step 2: Run `npm test` in `functions/`** — Expected: FAIL, module not found.
- [ ] **Step 3: Scaffold the package** — Node 22, TypeScript strict, `firebase-functions` v2, `firebase-admin`, Vitest. Export `codeManagement` from `functions/src/index.ts` as an `onRequest` handler routing on `req.path`. Region must match the RTDB region. `.gitignore` excludes `node_modules` and `lib`.
- [ ] **Step 4: Run `npm test`** — Expected: PASS.
- [ ] **Step 5: Commit** — `git add functions firebase.json && git commit -m "feat(functions): scaffold code_management with a health route"`

---

### Task 2: Token verification

**Files:**
- Create: `functions/src/auth.ts`, `functions/test/auth.test.ts`

**Interfaces:**
- Produces: `verifyBearer(header: string | undefined): Promise<string>` — resolves to the uid, rejects with an `AuthError` carrying `status: 401`.

- [ ] **Step 1: Write the failing tests** — `verifyBearer` rejects with 401 for: a missing header, a header without the `Bearer ` prefix, and a token `verifyIdToken` rejects. Resolves to the uid from a stubbed `verifyIdToken`. Stub `admin.auth()` rather than minting real tokens.
- [ ] **Step 2: Run the tests** — Expected: FAIL.
- [ ] **Step 3: Implement `verifyBearer` in `functions/src/auth.ts`** — the uid comes from the decoded token only; the request body is never consulted here or anywhere downstream.
- [ ] **Step 4: Run the tests** — Expected: PASS.
- [ ] **Step 5: Commit** — `feat(functions): verify the caller's Firebase ID token`

---

### Task 3: Reading a code, and the status route

**Files:**
- Create: `functions/src/codes.ts`, `functions/test/status.test.ts`
- Modify: `functions/src/index.ts`

**Interfaces:**
- Consumes: `verifyBearer` (Task 2).
- Produces: `readCode(code: string): Promise<CodeRecord | null>` and `statusFor(uid: string, code: string): Promise<StatusResponse>`, where `StatusResponse` is `{status: "active"|"revoked", code?: PublicCode, dispensary?: unknown}` and `PublicCode` is `{code, dispensary, revoked, activatedAt}` — no `note` field exists on the type.

- [ ] **Step 1: Write the failing tests** — `statusFor` returns `revoked` when: the node is absent entirely, `revoked` is true, `issued` is absent, `usedBy` is null, or `usedBy` differs from the uid. Returns `active` with the dispensary node attached when `usedBy` matches and `issued` is present. A `note` present in RTDB never appears in the response. Run against the Firebase emulator with seeded data.
- [ ] **Step 2: Run the tests** — Expected: FAIL.
- [ ] **Step 3: Implement `readCode` and `statusFor` in `functions/src/codes.ts`** — mirror `database.rules.json` exactly; the five revoked conditions above are the same five `verify()` applies today. Build `PublicCode` by naming its fields, never by spreading the RTDB node.
- [ ] **Step 4: Wire `POST /status` in `functions/src/index.ts`** — `400` for a missing or malformed `code`, `401` from `verifyBearer`, otherwise `200` with the `StatusResponse`.
- [ ] **Step 5: Run the tests** — Expected: PASS.
- [ ] **Step 6: Commit** — `feat(functions): answer the status route from the code and dispensary records`

---

### Task 4: The activate route

**Files:**
- Modify: `functions/src/codes.ts`, `functions/src/index.ts`
- Create: `functions/test/activate.test.ts`

**Interfaces:**
- Produces: `claim(uid: string, code: string): Promise<StatusResponse | RefusalReason>` where `RefusalReason` is `"unknown_code" | "not_issued" | "already_claimed" | "revoked"`.

- [ ] **Step 1: Write the failing tests** — `claim` refuses with the matching reason for an absent node, a node without `issued`, a node already claimed by a *different* uid, and a revoked node. **Succeeds when the node is already claimed by this same uid** (the retry case) without rewriting `activatedAt`. On success writes `usedBy` and `activatedAt` and leaves `issued`, `revoked`, `note` and `dispensary` untouched. **Two concurrent `claim` calls with different uids: exactly one resolves to success, the other refuses with `already_claimed`.**
- [ ] **Step 2: Run the tests** — Expected: FAIL.
- [ ] **Step 3: Implement `claim` in `functions/src/codes.ts`** — the check and the write must be one atomic operation against concurrent callers; use an RTDB transaction on the code node, and decide the refusal from the value the transaction read, not from a prior `get()`.
- [ ] **Step 4: Wire `POST /activate` in `functions/src/index.ts`** — `200` with the same body `/status` returns, or `409` with `{"status":"refused","reason":...}`.
- [ ] **Step 5: Run the tests** — Expected: PASS.
- [ ] **Step 6: Commit** — `feat(functions): claim a code atomically on the activate route`

---

### Task 5: Make the dispensary and banner parsers take plain maps

**Files:**
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/data/InfoRepository.kt`, `app/src/main/java/org/paramanuseniorshealth/notices/data/DispensaryRepository.kt`
- Create: `app/src/test/java/org/paramanuseniorshealth/notices/InfoRepositoryParseTest.kt`

**Interfaces:**
- Produces: `InfoRepository.apply(info: Map<*, *>?)` replacing `applySnapshot(DataSnapshot)`. `OfficeInfo` and `applyPushed` are unchanged. `DispensaryConfig.parse(id, value)` already accepts `Any?` and is not modified.

This is the seam that lets one set of parsers serve both the RTDB path and the HTTP path. Nothing about behaviour changes.

- [ ] **Step 1: Write the failing test** — `apply` with a map of `heading`/`lines`/`html`/`htmlUpdated` produces the same `OfficeInfo` the snapshot path produced; an all-blank map with `htmlUpdated` absent is ignored; a pushed banner newer than the map's `htmlUpdated` is kept. Covers the Review Focus item on malformed payloads: a map missing `topics` entirely still yields a `Dispensary` with an empty topic list rather than null.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*InfoRepositoryParseTest*'` — Expected: FAIL.
- [ ] **Step 3: Change `applySnapshot` to `apply(info: Map<*, *>?)`** — read each field from the map with the same defaults and the same `htmlUpdated` ordering rule the current code applies.
- [ ] **Step 4: Update the one call site** in `DispensaryRepository.refresh` to pass `(snapshot.child("info").value as? Map<*, *>)`.
- [ ] **Step 5: Run** the full unit suite — Expected: PASS, no regressions.
- [ ] **Step 6: Commit** — `refactor: parse the banner from a plain map, not a DataSnapshot`

---

### Task 6: The client API seam

**Files:**
- Create: `app/src/main/java/org/paramanuseniorshealth/notices/activation/CodeManagementApi.kt`, `app/src/main/java/org/paramanuseniorshealth/notices/activation/HttpCodeManagementApi.kt`
- Create: `app/src/test/java/org/paramanuseniorshealth/notices/CodeManagementApiTest.kt`
- Modify: `app/build.gradle`, `gradle/libs.versions.toml` (promote OkHttp to a direct dependency)

**Interfaces:**
- Produces:
  - `sealed interface CodeManagementResult { data class Active(val code: PublicCode, val dispensary: Map<*, *>?) ; data object Revoked ; data class Refused(val reason: String) ; data object Inconclusive }`
  - `data class PublicCode(val code: String, val dispensary: String?, val revoked: Boolean, val activatedAt: Long?)`
  - `interface CodeManagementApi { suspend fun status(code: String): CodeManagementResult ; suspend fun activate(code: String): CodeManagementResult }`
  - `object CodeManagementResponse { fun parse(httpStatus: Int, body: Map<String, Any?>?): CodeManagementResult }` — pure, JVM-testable, the whole of the mapping policy.

The split follows `AnalyticsSink`: the pure file holds the taxonomy and every decision; `HttpCodeManagementApi` is the only file importing OkHttp and `org.json`, and does nothing but token retrieval, the request, and JSON→`Map`.

- [ ] **Step 1: Write the failing tests** for `CodeManagementResponse.parse` — `200` + `status=active` → `Active` carrying the dispensary map; `200` + `status=revoked` → `Revoked`; `409` + a reason → `Refused(reason)`; **`401` → `Inconclusive`, not `Revoked`** (Review Focus); `500` → `Inconclusive`; a null body → `Inconclusive`; `200` with an unrecognised `status` string → `Inconclusive`.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*CodeManagementApiTest*'` — Expected: FAIL.
- [ ] **Step 3: Implement `CodeManagementResponse.parse`** in `CodeManagementApi.kt`.
- [ ] **Step 4: Implement `HttpCodeManagementApi(context, baseUrl)`** — obtains the token via `FirebaseAuth.getInstance().currentUser?.getIdToken(true)`, POSTs with an `Authorization: Bearer` header, a 6 second call timeout matching the existing client budget, converts the body with `org.json` to a `Map<String, Any?>`, and hands both to `parse`. Any exception, timeout or absent user returns `Inconclusive` — it never throws.
- [ ] **Step 5: Run** the unit suite — Expected: PASS.
- [ ] **Step 6: Commit** — `feat: add the code_management client seam`

---

### Task 7: Log every call to analytics

**Files:**
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/analytics/AnalyticsEvent.kt`
- Modify: `app/src/test/java/org/paramanuseniorshealth/notices/AnalyticsEventTest.kt`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/activation/HttpCodeManagementApi.kt`

**Interfaces:**
- Consumes: `CodeManagementResult` (Task 6), `Analytics.log` (existing).
- Produces: `AnalyticsEvent.CodeManagementCall(operation: String, outcome: String, durationMs: Long, httpStatus: Int?)`, event name `code_management_call`.

Done here rather than at the call sites so that one place reports every call, and a later caller cannot forget to.

- [ ] **Step 1: Write the failing tests** — params are `operation` (`"status"`/`"activate"`), `outcome`, `duration_ms`, and `http_status` **omitted entirely when null** (the taxonomy's existing rule: a null is recorded by omitting the key). Outcome vocabulary is `"active" | "revoked" | "refused" | "inconclusive"`, spelled out in a `when` with no `else` so a new `CodeManagementResult` variant fails to compile — the same treatment `RedeemResult.analyticsName()` gets.
- [ ] **Step 2: Run** `./gradlew :app:testDebugUnitTest --tests '*AnalyticsEventTest*'` — Expected: FAIL.
- [ ] **Step 3: Add the `CodeManagementCall` variant and a `CodeManagementResult.analyticsName()`** alongside the existing variants.
- [ ] **Step 4: Log from `HttpCodeManagementApi`** — time every call and log the event on every path, the inconclusive ones included. `Analytics.log` never throws and is gated by the kill switch, so no guard is needed at this call site.
- [ ] **Step 5: Run** the unit suite — Expected: PASS.
- [ ] **Step 6: Commit** — `feat(analytics): report every code_management call and its outcome`

---

### Task 8: Migration step 1 — activation calls the endpoint

**Files:**
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/activation/ActivationRepository.kt:194-265`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/NoticesApplication.kt` (construct the API)
- Create: `app/src/test/java/org/paramanuseniorshealth/notices/RecordingCodeManagementApi.kt`

**Interfaces:**
- Consumes: `CodeManagementApi` (Task 6).
- Produces: `ActivationRepository(context, dispensaries, api: CodeManagementApi)` — a constructor parameter, following how `AnalyticsConfig` takes its switch.

Ships on its own. `verify()` still reads RTDB after this task; the rules stay deployed.

- [ ] **Step 1: Write the failing tests** using `RecordingCodeManagementApi` — `Active` → `RedeemResult.Success` and the code, uid and dispensary are stored; `Refused` → `NotAccepted`; `Inconclusive` → `Offline`. The local `Mistyped` and `SameCode` answers still short-circuit before any call is made.
- [ ] **Step 2: Run** the unit suite — Expected: FAIL.
- [ ] **Step 3: Replace the `updateChildren` write and the follow-up dispensary read in `redeem()` with one `api.activate(code)`** — the `Active` result already carries the dispensary, so the second read goes. Everything after the claim is unchanged: clearing `KEY_REVOKED`, `recordVerification(Active)`, `syncSubscriptions()`, `ensurePeriodic()`.
- [ ] **Step 4: Run** the unit suite — Expected: PASS.
- [ ] **Step 5: Verify on a device** — redeem a real code against the deployed function; confirm the claim lands in RTDB and Settings draws the dispensary's topics.
- [ ] **Step 6: Commit** — `feat: redeem a code through code_management instead of writing to the database`

---

### Task 9: Migration step 2 — foreground calls the endpoint

**Files:**
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/activation/ActivationRepository.kt:295-345`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/data/DispensaryRepository.kt`

**Interfaces:**
- Produces: `DispensaryRepository.apply(id: String, node: Map<*, *>?): Dispensary?` — caches and publishes a dispensary already in hand, with no network. `refresh(id)` stays for now and is left unused by the client paths this task changes.

- [ ] **Step 1: Write the failing tests** — `verify()` maps `Active` → `ActivationState.Active`, `Revoked` → `Revoked`, **`Inconclusive` → `Unknown` (never `Revoked`)**, and persists the state only for the two definitive answers. The dispensary carried on an `Active` result is applied without a second call.
- [ ] **Step 2: Run** the unit suite — Expected: FAIL.
- [ ] **Step 3: Replace the RTDB read in `verify()` with `api.status(code)`** — drop the `getIdToken(true)` call and the `FirebaseAuthInvalidUserException` branch; the endpoint's token verification now covers both, and a rejected token arrives as `Inconclusive`.
- [ ] **Step 4: Add `DispensaryRepository.apply`** and have `verify()` call it with the dispensary from the result.
- [ ] **Step 5: Run** the unit suite — Expected: PASS.
- [ ] **Step 6: Verify on a device** — foreground the app, confirm one HTTPS call and no database socket; revoke the code from the console and confirm the next foreground reports revoked.
- [ ] **Step 7: Commit** — `feat: verify entitlement through code_management instead of the database`

---

### Task 10: A floor under the status call

**Files:**
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/activation/ActivationFreshness.kt`
- Modify: `app/src/test/java/org/paramanuseniorshealth/notices/ActivationFreshnessTest.kt`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/ui/NoticeViewModel.kt`

**Interfaces:**
- Produces: `ActivationFreshness.shouldCall(lastCalledAt: Long, now: Long): Boolean`.

The 2M/month ceiling is ~165 calls per device per day. A status call on every resume with no floor can exceed that on a phone someone picks up all day.

- [ ] **Step 1: Write the failing tests** — `shouldCall` is false inside the floor and true outside it; a floor of 15 minutes gives at most 96 calls a day, comfortably inside the budget with room for app-open on top.
- [ ] **Step 2: Run** the unit suite — Expected: FAIL.
- [ ] **Step 3: Implement `shouldCall`** next to the existing staleness helper.
- [ ] **Step 4: Gate the foreground path in `NoticeViewModel`** on it. App open is not gated — it is once per process.
- [ ] **Step 5: Run** the unit suite — Expected: PASS.
- [ ] **Step 6: Commit** — `feat: hold the status call to one per quarter hour`

---

### Task 11: Deploy pipeline

**Files:**
- Create: `.github/workflows/deploy-function.yml`
- Create: `docs/code-management-runbook.md`

This is the repository's first workflow. There is no staging and no manual gate, so the test step is the only thing between a merge and ~400 devices.

- [ ] **Step 1: Write the workflow** — trigger `push` to `main` with `paths: ['functions/**', '.github/workflows/deploy-function.yml']`. Steps: checkout, Node 22, `npm ci`, `npm test`, then authenticate and deploy. **The deploy step runs only if the tests passed** — do not use `continue-on-error` anywhere in this file.
- [ ] **Step 2: Authenticate with Workload Identity Federation** — `google-github-actions/auth` with `workload_identity_provider` and `service_account`. **No service-account JSON key, in the repo or in Secrets.** Record in the runbook the one-time `gcloud iam workload-identity-pool` and provider commands, the deploying service account, and the roles it is granted.
- [ ] **Step 3: Write `docs/code-management-runbook.md`** — the WIF setup commands, the rollback command naming the exact `gcloud functions` invocation to restore the previous revision, and how to read the `code_management_call` outcome mix from the GA4 BigQuery export to tell an outage from silence.
- [ ] **Step 4: Verify the pipeline** — push a trivial change under `functions/`, confirm the workflow runs, the tests gate the deploy, and the function updates. Then exercise the rollback once, as the runbook describes, and correct the runbook if the command was wrong.
- [ ] **Step 5: Commit** — `ci: deploy code_management on merge to main`

---

### Task 12: Server-side outage signal

**Files:**
- Modify: `functions/src/index.ts`
- Modify: `docs/code-management-runbook.md`

Analytics is behind the kill switch and off by default, so client telemetry cannot be the only signal: the case it misses is the endpoint being so broken that no client reaches it to report the fact.

- [ ] **Step 1: Add structured logging to every route outcome** — `functions.logger` with a `severity` that distinguishes a refusal (expected, not an error) from a server fault.
- [ ] **Step 2: Create a log-based alert** on the function's error rate, delivering to the project owner's email. Record the alert's name and threshold in the runbook.
- [ ] **Step 3: Verify** — force a server fault against the deployed function and confirm the alert fires.
- [ ] **Step 4: Commit** — `feat(functions): log route outcomes and alert on the error rate`
