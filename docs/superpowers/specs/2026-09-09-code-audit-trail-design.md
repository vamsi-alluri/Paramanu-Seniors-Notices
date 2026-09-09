# Code audit trail and send attribution — design

**Date:** 2026-09-09
**Status:** approved for planning

## Problem

The NGO is about to hand out roughly 400 activation codes and manage them from the console. Today
the console records almost nothing about what it did:

- `/codes/{CODE}` carries `revoked` as a bare boolean. Revoke, Restore and Release leave no
  timestamp and no attribution.
- `/sent/{logId}` records what was broadcast but not who broadcast it.

Both gaps end the same way: somebody asks "when did this happen, and who did it?", the database
cannot answer, and the NGO comes back to a developer. The goal is that it never has to.

## Decisions

1. **A full append-only log per code**, not a last-change-wins record. A flat
   `revokedAt`/`restoredBy` shape was considered and rejected: a second revoke would overwrite the
   first, and a Release followed by a re-issue would attribute the previous holder's events to the
   new one. Append-only keeps a code's whole life readable top to bottom.
2. **The log lives in a new top-level `/audit`, never on `/codes`.** `/codes/{CODE}` is governed by
   `$other: { ".validate": false }`, so any new sibling field needs its own validate rule or the
   app's claim write starts failing. This has happened once already — adding `note` broke
   activation, which is why `database.rules.json` carries a `note` validate rule today (SYSTEM.md
   §6a). Keeping the audit outside `/codes` means **no rules change at all** and no possibility of
   breaking activation for every unclaimed slip.
3. **Timestamps are epoch milliseconds**, matching `/codes.issued` and `activatedAt`, because the
   console renders audit entries and code fields in the same table with the same formatter.
   (`/sent.sentAt` is an ISO string; that is pre-existing and is not changed here.)
4. **Entry ids are server-generated push keys** via REST `POST`, which is chronologically sortable
   and collision-free. Client-generated `Date.now()` keys are exactly what silently lost a notice
   in quirk 5.12, and that mistake is not worth repeating. The one exception is bulk issue — see
   "Bulk issue" below.
5. **Device actions are not written to `/audit`.** Letting the app write there would mean loosening
   the deny-by-default that currently makes the audit unreachable from 400 phones. The console
   synthesises the claim event at render time instead.
6. **No free-text reason on Revoke or Release.** Decided against; `note` events carry text, and the
   confirm dialogs stay as they are.
7. **Send attribution is a single `sentBy` field** on the existing `/sent` write. Every human send
   path already calls `requireEditor_()`, which returns an allowlisted email, so no new identity
   mechanism is needed.

## Schema

A new top-level node:

```
/audit/{CODE}/{entryId}
  at      1757413380000            epoch millis
  by      "ravi@example.org"       lowercased email, or "poller"
  event   "issued" | "revoked" | "restored" | "released" | "note"
  to      "Mrs Rao, ward 3"        note events only: the new note text
```

`event` is a closed set. The console rejects anything outside it rather than allowing a typo to
create a category nobody will ever query.

`/audit` inherits the root's `".read": false` / `".write": false`, so phones cannot see it. The
console reaches it with a service account, which bypasses rules entirely.

Existing codes are **not backfilled**. Their logs start empty and fill from their next action.
Fabricating history the database never recorded would be worse than having none.

## Console changes — `docs/apps-script/console/Code.gs`

A new helper:

```
audit_(code, event, extra)   POST /audit/{code}.json  { at: Date.now(), by: <email>, event, ...extra }
```

Each operation captures the email `requireEditor_()` already returns and appends one entry:

| Function | Line today | Entry |
|---|---|---|
| `revokeCode` | 251 | `revoked` |
| `unrevokeCode` | 258 | `restored` |
| `releaseCode` | 226 | `released` |
| `setNote` | 206 | `note`, with `to` = the new text |

Release needs no special handling any more: it appends like anything else, and the next holder's
activity appends after it.

### Bulk issue

`createCodes` (line 174) creates up to 200 codes in one call. Two hundred sequential `POST`s would
be slow and fragile, so issue is the one case that does not use push keys: a single multi-path
`PATCH` at `/audit.json` with keys of the form `{CODE}/{issued}`, reusing the `issued` millis the
function already computes. Uniqueness is guaranteed because a freshly created code has an empty
log. This mirrors the existing `PATCH` at `/codes.json` on line 195 and its reasoning.

When `createCodes` is given a note, it writes that note onto every code it creates. The `issued`
entry therefore carries `to` with the note text, rather than emitting a second `note` event that
records a change nobody made.

### Reading

`listCodes` (line 234) adds one `GET /audit.json` and joins by code. Roughly 400 codes with a few
entries each is well under 200KB in one fetch. If it ever grows heavy, the fix is fetching a code's
log when its row is expanded; building that now would be speculative.

Each returned row gains an `audit` array sorted by `at`, with the synthesised claim event
interleaved by timestamp:

```
Claimed   2 Sep 08:31   (device)      from /codes/{CODE}.activatedAt
```

**Known limit:** `activatedAt` holds only the most recent claim, so if a code is released and
re-claimed, earlier claims are not recoverable. The console actions bracketing them still show the
shape of what happened. Recording claims properly would require the phone to write history, which
is not worth the rule change.

## Console changes — `docs/apps-script/console/Index.html`

The code row keeps its existing status pill. Beneath it:

- the newest audit event inline — `Restored 9 Sep 11:40 asha@…`
- a `history` toggle revealing the full interleaved list

Existing `.pill` / `.revoked` styles are reused. Note that `Index.html` has uncommitted local
changes; the implementation works from the working tree, not from `HEAD`.

## Sender changes — `docs/apps-script/sender/`

- `sendNotice` (`Code.gs:250`): capture `requireEditor_()`'s return and add `sentBy` to the `PUT`
  at line 268.
- `sendStatus` (`Code.gs:368`): the same. The QR path is being retired, but while the function is
  still callable, leaving it unattributed is worse than one extra line.
- `listSent` (`Code.gs:290`): include `sentBy` in the returned rows; surface it in the history UI.
- `Poller.gs`: its own delivery function writes `sentBy: "poller"` alongside the feed guid it
  already tracks. It deliberately does not borrow the trigger owner's identity — the file already
  argues that case in its header, and an owner-installed trigger would silently misattribute every
  automated send to whoever last reinstalled it.

## Security rules

**No change to `database.rules.json`.**

The requirement that a revoked code stays revoked, usable only by the device already holding it
until it is released or replaced, is already enforced by the existing write rule:

```
".write": "auth != null && data.exists() && data.child('issued').exists()
           && !data.child('usedBy').exists() && data.child('revoked').val() !== true"
```

A claimed code cannot be re-claimed by anyone, including the device that holds it; a revoked code
cannot be claimed at all. This wants documenting, not coding.

## Documentation

- `SYSTEM.md` §3: add `/audit` to the data model.
- `SYSTEM.md` §2.3 and §6 are **stale** on sender access. The sender is no longer deployed "Anyone
  with the link"; `requireEditor_()` requires "Anyone with a Google account" and an
  `ALLOWED_EDITORS` allowlist, and the PIN is optional via `REQUIRE_STAFF_PIN`. The comment inside
  `checkPin_` repeats the stale claim and should be corrected with it.
- `SYSTEM.md` §2.5 and the daily-status runbook: mark the QR path as retired pending removal.
- Runbooks: note that revoke/restore/release are now attributable, and where to read the history.

## Testing

- `docs/apps-script/sender/Tests.gs` (43 e2e tests today): add cases asserting `sentBy` is written
  by `sendNotice` and `sendStatus`, and that the poller attributes to `"poller"`.
- The console has **no test harness**, and this design does not invent one. Verification there is
  manual against the live database: issue two codes, revoke one, restore it, release it, change a
  note, and confirm the rendered history matches the actions in order.
- Regression check after deploy: claim a fresh code from a phone. Nothing here touches `/codes` or
  the rules, but activation is the failure this project has broken before, and it costs one slip to
  be sure.

## Out of scope

- Removing the QR path, `sendStatus`, `Status.html` and the bit.ly indirection. Retiring a live
  deployment's entry point is separate work.
- The Android revoked-banner change (revocation suspending the claim rather than surrendering it).
  Designed separately, not yet approved, and not required by anything here.
- Backfilling audit history for codes already issued.
