# Plan: every database call behind Cloud Functions

*Drafted 2026-10-28. Not started beyond phase 0. Direction recorded in `docs/decisions.md`, "Topics
are split by audience, and the backend moves to Cloud Functions".*

**Goal.** Nothing outside `functions/` touches the Realtime Database. Phones already go through
`codeManagement`; the sender and the console still call the database's REST API directly with a
service-account key held in Script Properties. At the end, the database rules deny every client,
the key is gone from Apps Script, and the two Apps Script projects are only their pages: the
`ALLOWED_EDITORS` check, the PIN, and one call per action to a function.

**The pages stay where they are.** The sender and console keep their Apps Script URLs and
HtmlService pages throughout; staff notice nothing but speed. Each phase ships on its own and leaves
everything working.

## Where things stand

| Caller | Database access today | Status |
|---|---|---|
| App | None live. `DispensaryRepository.refresh()` still reads `/dispensaries/{id}`, but nothing calls it since PR #14 | Dead code, phase 1 |
| Control pushes | Database triggers `codeControl`, `bannerControl` | **Done** (phase 0) |
| Sender: compose | `/templates` read, `/dispensaries/{id}` read (topic), `/sent` id probe, put and two patches, FCM send, link probing | Phase 2 |
| Sender: poller | `/alertsSeen` read and write, `/sent` writes, FCM send, feed fetch | Phase 3 |
| Console | `/codes`, `/audit`, `/holders` (+ history), `/templates`, `/dispensaries/{id}/info`: about 30 call sites; monthly purge trigger | Phase 4 |
| Rules | Phones may read `/codes/$code` and `/dispensaries/$id`, and write a claim | Phase 5 |

Two facts that shape the sizing below, both checked 2026-10-28:

- **The project is on Blaze** (billing enabled), which Cloud Functions requires. Blaze's database
  ceiling is 200,000 simultaneous connections, not Spark's 100. Several older comments (`MAX_INSTANCES`
  in `functions/src/index.ts`, SYSTEM.md 5.14) size things against 100; they are conservative, not
  wrong, and need not change. The free tier still bounds cost: 2M invocations a month.
- **UrlFetch quota is account-wide, 20k a day**, and ran out once in 2026-09. Every phase here cuts
  it: a console action that costs a token fetch plus three database calls today becomes one call.

## The one open question: how Apps Script proves itself to a function

`codeManagement` is public and checks a phone's anonymous ID token. A staff endpoint must not be
reachable that way. Two routes:

**A. Private function, called with a service-account ID token (recommended first).**
Deploy the staff function without the public invoker binding, so Cloud Run refuses anyone without
`roles/run.invoker`. A new service account, `apps-script-caller`, gets that role on the staff service
alone and nothing else. Apps Script signs a JWT with its key and exchanges it at
`oauth2.googleapis.com/token` for a Google-signed ID token with `target_audience` set to the function
URL -- the same JWT code `accessToken_()` already has, with one claim changed. Cache it in
`CacheService` for 55 minutes, so it is one token fetch an hour, not one per call.
The staff member's email travels in the request body. It is trusted because only these two scripts
hold a key that can invoke, and they set it from `requireEditor_()`, which already enforces
`ALLOWED_EDITORS`.

What it gains on its own: the key in Script Properties stops being a database admin key. Today a
leak of `SERVICE_ACCOUNT_JSON` is full read and write on every node; afterwards it can only invoke
the staff endpoints, which do only what they are written to do.

**B. Per-user identity token** (`ScriptApp.getIdentityToken()`), verified in the function against
`ALLOWED_EDITORS`. It removes the key from Apps Script entirely, but needs the scripts moved to a
standard GCP project, the `openid` and `email` scopes, and an audience check against the script's
OAuth client. The `getOAuthToken()` attempt returned 401 (decisions.md), so this needs a spike before
anything depends on it.

Ship on A. Try B as a spike after phase 4; if it works, swap the caller helper and delete the key.

**Spike for A, before phase 2 (an hour):** deploy a `staff` function with one `GET /whoami` route,
`invoker: "private"`; from the sender's editor, mint the ID token and call it. Expect 200 with the
email echoed. A 403 from Cloud Run means the invoker binding is missing; a 401 means the audience is
not the exact function URL.

## Phase 0 -- control pushes (done)

`codeControl` and `bannerControl`, `ControlTopics` in the app, `/controlQueue` and `Control.gs`
removed. Live steps are in runbook section 8: deploy, prove, stop the old trigger, paste the
scripts, delete `/controlQueue`.

## Phase 1 -- the app stops linking the database SDK

Small, ships with the next app build.

- Delete `DispensaryRepository.refresh()` and its `FirebaseDatabase` imports; keep `apply()`.
- Drop `implementation libs.firebase.database` and the catalog entry. Check the APK no longer
  contains `com.google.firebase.database`.
- Nothing else changes: `/status` and `/activate` already carry the dispensary node.

## Phase 2 -- sending a notice

New function `staff` (onRequest, private, region `asia-southeast1`, `maxInstances` 2), its own file
per area: `functions/src/staff/send.ts`, `templates.ts`, and so on. Shared send core in
`functions/src/fcm.ts`, which `control.ts` can move onto too.

Routes the sender calls:

| Route | Replaces | Notes |
|---|---|---|
| `GET /templates` | `listTemplates` | |
| `GET /sent?limit=` | `listSent` | |
| `POST /send` `{title, body, by}` | `sendNotice` after the PIN | Topic from `/dispensaries/{id}/topics`, log id allocated in a transaction rather than the probe loop, `/sent` written before the send, FCM through the Admin SDK |
| `POST /send/test` | `TestSends.gs` paths | Same core, test topics only |

- **Port with tests**: validation limits (title 120, body 900), `messageAttachment_` link probing
  (Node `fetch` with a HEAD and a size cap, the PDF and image detection), the data-only envelope. The
  sender-contract.md payload is the contract; the emulator suite pins it.
- **Stays in Apps Script**: `requireEditor_()`, `checkPin_()` with its lockout, the page.
- `DISPENSARY_ID` becomes a function parameter (`defineString`), not a Script Property read by the
  function.
- Sender `Code.gs` keeps one helper, `staffCall_(method, path, body)`, and loses `firebase_`,
  `databaseUrl_` and the FCM call.
- Done when: a send from the page arrives on a test phone, `/sent` shows it with `sentBy`, the
  Apps Script execution log shows one UrlFetch for it, and `runAllTests` passes against the new path.

## Phase 3 -- the RSS poller

A scheduled function (`onSchedule`, Cloud Scheduler) replaces `Poller.gs` and its trigger, taking the
poller off the Apps Script quota entirely.

- Same cadence as the trigger installed today (check the sender's Triggers page; the installer in
  `Poller.gs` creates 15 minutes).
- Port feed fetch and parse, the guid hashing for `/alertsSeen` keys (keep the hash identical, or
  every old item is sent again on the first run -- the emulator test seeds `/alertsSeen` from a real
  export to prove it), and the send through phase 2's core.
- Cutover: deploy the scheduled function **paused**, run it once by hand against a copy of
  `/alertsSeen`, then stop the Apps Script trigger and resume the schedule in that order. Never both
  at once: two pollers race on `/alertsSeen` and send a notice twice.
- Cloud Scheduler bills past three free jobs per billing account; this is one.

## Phase 4 -- the console

The largest, and best done in slices that each replace a group of console functions:

1. **Read**: `GET /codes` returning what `listCodes` builds today (codes, timelines, holders, removed
   holders, printing flag) in one call instead of four reads.
2. **Codes**: create (port `generateCode_`, `checkCharacter`, the reserved list and the repeat rule;
   `GeneratedCodeCompatibilityTest.kt` vectors become a TS test too, so the three implementations
   cannot drift), note, printed, disable, enable, unlink, delete. Each writes `/codes` and `/audit`
   in one multi-path update instead of two calls, so a failure can no longer leave a change without
   its audit line.
3. **Holders**: add, edit, move, remove, restore, with their history.
4. **Templates**: save, delete.
5. **Banner**: get, save, remove. Port `sanitiseBanner_` with its tests; the app sanitises again, as
   now. `bannerControl` keeps pushing, since it watches the write, not the caller.
6. **Purge**: an `onSchedule` monthly function replacing `purgeRun` and its trigger, with the same
   paused-then-swap cutover as the poller.
7. **Delete the one-off migration** (`migrateToDispensaries*`, the `/info.json` read) rather than
   porting it.

Console `Code.gs` ends as `requireEditor_()`, `doGet()`, and the same `staffCall_` helper.

## Phase 5 -- close the database

- Rules become deny-all for clients: drop the `/codes/$code` read and claim write and the
  `/dispensaries/$dispensary` read. **Breaking for old builds**, as agreed: anything before the API
  move reads the database directly and will find it refused. 0.7.2 (production) reads `/codes` for its
  check; a refused read should come back as Unknown, which still shows notices, but confirm that
  against the 0.7.2 source (`git show e563207`) before closing, since a refusal read as Revoked
  would cut those phones off. Either way it can no longer activate a code.
  Decide the date against the Play rollout of the API build.
- Remove the `control-v1` bridge in `functions/src/control.ts` (`LEGACY_CONTROL_TOPIC`,
  `legacyCodePush` and its two call sites) in the same change: the builds it serves are the ones
  closing the rules cuts off.
- Delete `SERVICE_ACCOUNT_JSON`'s admin key once route B lands, or keep the `apps-script-caller` key
  (invoker only) if it does not. Delete `DATABASE_URL` from both projects either way.
- `docs/rtdb-rules-explained.md` and SYSTEM.md section 3 rewritten for the closed rules.

## Cross-cutting

- **Logging.** The same rule as `codeManagement` and `control.ts`: named fields only, never a code,
  a UID, holder details, a note, a PIN or an error message. Staff email is fine; it is already in
  `/audit` and `/sent`.
- **Deploy.** Each new function is added to the workflow's `--only` list in the PR that creates it.
  A private function must not get the public invoker binding, so set `invoker: "private"` explicitly
  and check after the first deploy that `allUsers` is absent:
  `gcloud run services get-iam-policy staff --region asia-southeast1 --project paramanu-seniors`.
- **Tests.** Each port carries its Apps Script tests over as emulator tests. The Apps Script
  `Tests.gs` files shrink to "the call reaches the function and the page renders".
- **Rollback.** Until phase 5, every phase can be undone by pasting the previous Apps Script back:
  the database shape does not change. That is why the rules close last.
- **The deployer's IAM.** Once the triggers have deployed (phase 0), consider dropping
  `roles/firebase.managementServiceAgent` from `code-management-deployer` (runbook section 2). A
  private function's first deploy does not need project IAM changes.

## Order and size, roughly

| Phase | Size | Depends on |
|---|---|---|
| 0 Control | done | -- |
| 1 App drops the database SDK | an hour | nothing |
| Spike A | an hour | nothing |
| 2 Send | a day or two | spike A |
| 3 Poller | a day | phase 2's send core |
| 4 Console | several days, in slices | spike A |
| 5 Close rules | an hour, plus waiting on rollout | phases 1-4 and the API build on most phones |
| Spike B | half a day | after phase 4 |
