# Revoke queue: replacing the console → sender HTTP call

**Date:** 2026-09-09
**Status:** implemented on `audit-trail-and-revocation`. Not yet deployed or run against Google.
**Supersedes:** the "console asks the sender to push" decision in `docs/decisions.md`.

## Why the current bridge cannot work

The console calls the sender's web app over HTTPS, forwarding the staff member's identity with
`ScriptApp.getOAuthToken()`. It returns **HTTP 401 on every request**, and the page comes from
Google's auth frontend before any script runs. Measured with `testSenderLink()`:

```
as: vamsi1306@gmail.com
GET  /exec        -> HTTP 401  HTML
POST /exec        -> HTTP 401  HTML
POST /exec/revoke -> HTTP 401  HTML
```

All three fail identically, so the `/exec/revoke` path is not the cause and neither is the HTTP
method. The cause is the token: `ScriptApp.getOAuthToken()` mints one carrying the **console's**
authorized scopes, but invoking a web app requires a token authorized for the **sender's** script
project. That grant does not exist across two separate projects, and no scope added to the console's
manifest creates it. The documented workaround — one shared standard GCP project with matching
scopes — couples the two projects that were deliberately separated (SYSTEM.md §2.3).

A shared secret was considered and rejected: it would need a *second* sender deployment set to
"Anyone" (the existing one requires a Google account, which is what is failing, and cannot be
switched because the compose UI needs the sign-in to identify staff), and it degrades attribution to
"the console says it was X" rather than anything verified.

## Decision

**The console writes a queue node; a frequent trigger in the sender drains it.** No HTTP between the
projects, no token, no shared secret, no second deployment. The console still never touches FCM.

Latency goes from "seconds" to "about a minute", which is immaterial: revocation is explicitly not
access control — every notice is published publicly on the website — and the fallback was previously
a whole day.

Attribution is unaffected. Who revoked and when is written to `/audit` by the console, exactly as it
is today; the queue only carries the mechanism.

## Schema

A new top-level node, written by the console, drained by the sender:

```
/revokeQueue/{CODE}
  at   1757413380000     epoch millis, when the console revoked it
  by   "ravi@…"          carried for the log line only; the audit entry is the record
```

Keyed by code, so revoking the same code twice collapses to one pending entry rather than queueing
two. Inherits the root's deny-by-default, so phones cannot read it. No `database.rules.json` change.

`/codes` is **not** touched. A `revokePending` field there would need its own `.validate` rule or the
`$other: false` catch-all breaks the app's claim write for every unclaimed slip — the same trap that
put `/audit` in its own node.

## Console changes — `docs/apps-script/console/Code.gs`

`revokeCode` replaces the HTTP call with a queue write:

```javascript
firebase_('put', '/revokeQueue/' + code + '.json', { at: Date.now(), by: by });
audit_(code, 'revoked', by);
```

The `pushed` / `pushError` fields come off the audit entry. Whether the broadcast landed is
operational, not historical: the audit records the revocation, and a queue entry that is still
present a few minutes later is the signal that the trigger is not running.

`unrevokeCode` should delete any pending entry (`firebase_('delete', '/revokeQueue/' + code +
'.json')`). Revoking and restoring inside one minute would otherwise push a revoke for a code that is
live again — harmless, because the device re-verifies and resumes, but it would cause a visible
flicker on the phone for no reason.

Delete `pushRevokeToSender_`, `testSenderLink`, and the `SENDER_URL` Script Property along with its
setup notes and the `userinfo.email` manifest step. Leave `ALLOWED_EDITORS` alone — it is what gates
the console itself.

## Sender changes — new file `docs/apps-script/sender/Revoker.gs`

A separate file, following the naming discipline `Poller.gs` sets out: Apps Script gives every file
in a project one shared global scope, so everything here is prefixed `revoker`, and the only names
reused from `Code.gs` are the shared plumbing — `property_`, `accessToken_`, `firebase_`, `TOPIC`,
`TOPIC_OVERRIDE`, `pushRevoke_`.

```
revokerDrain_()          read /revokeQueue, push each, delete on success
revokerInstallTrigger()  every-minute trigger, run once from the editor
revokerDryRun()          read and log what would be pushed, send nothing
```

Rules for the drain:

- **Delete only after the FCM push returns.** A failed push leaves the entry for the next run.
- **A repeat push is harmless.** `ActivationRepository.suspendClaim()` is idempotent, and
  `announceRevocation()` is guarded by the `local-revoked` Room id, so a device cannot be notified
  twice. This is what makes at-least-once delivery acceptable here.
- **Cap the run** at 20 entries, mirroring `POLLER_MAX_PER_RUN`. A ceiling, not a throttle: if
  something goes wrong upstream the damage is bounded and the rest stay visible in the queue, which
  is a problem someone can look at rather than one that has already happened.
- **Do not reuse `sendNotice`.** It calls `requireEditor_()` and `checkPin_()`, and a trigger has no
  signed-in caller and cannot type a PIN — the same reasoning `Poller.gs` gives for its own delivery
  path. `pushRevoke_` already exists and takes neither.

`doPost` and `isValidRevokeCode_` are deleted with the HTTP bridge. That leaves the sender with no
POST surface at all, which is a small security improvement: `/exec` then answers `doGet` only.

## Trigger cadence

Every minute. The read is `null` on almost every run, so the cost is one small REST call per minute —
comfortably inside the Apps Script quota, and far below what the 15-minute poller already does with
its feed fetch.

## Tests

`docs/apps-script/sender/Tests.gs`: exercise `revokerDrain_` against a seeded `/revokeQueue/{CODE}`
under `TEST_TOPIC`, asserting the entry is deleted after a successful push and left in place when the
push fails. Remove `t_doPost_` and `t_isValidRevokeCode_` with the code they cover.

`docs/apps-script/console/Tests.gs`: no new pure logic to cover; the queue write is a one-line REST
call. Leave the file as it is.

## Docs to update when this lands

- `docs/decisions.md` — supersede the console→sender decision with this one, keeping the 401 evidence
  so nobody retries the token approach.
- `SYSTEM.md` §2.3 — the sender has no `doPost`; describe `Revoker.gs` and its trigger.
- `SYSTEM.md` §3 — add `/revokeQueue` to the data model.
- `SYSTEM.md` §2.2 — drop `SENDER_URL` and the manifest scope from the console's setup.
- `docs/sender-contract.md` — the revoke envelope is unchanged; correct how it is triggered.
- `SYSTEM.md` §9 item 0 — replace the `SENDER_URL` / `oauthScopes` setup steps with
  `revokerInstallTrigger()`.

## Verification when this lands

1. `revokerDryRun()` with a seeded queue entry — logs it, sends nothing.
2. `revokerInstallTrigger()`, then revoke a test code from the console. Within a minute the queue
   entry is gone and the phone shows the banner.
3. Revoke and immediately restore. Confirm no revoke is pushed, and the phone is unaffected.
4. Break it deliberately: set `PROJECT_ID` to nonsense, revoke, confirm the entry stays in the queue
   and reappears on the next run rather than being lost.

## Optional, not required

The console could show "push pending" on a row whose code has a `/revokeQueue` entry, since
`listCodes` could read that node in the same pass it already reads `/audit`. Worth it only if the
trigger turns out to stall in practice.
