# Code Audit Trail and Revocation Rework — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the NGO a per-code audit trail it can read without a developer, attribute every broadcast to a person, and make revocation land in seconds while removing ~400 simultaneous database connections from the message path.

**Architecture:** Three phases. Phase 1 adds an append-only `/audit` node written by the console and a `sentBy` field on `/sent`. Phase 2 bridges console to sender over HTTPS so a Revoke becomes an FCM push, authenticated by the caller's own Google OAuth token against the sender's existing `ALLOWED_EDITORS` allowlist. Phase 3 reworks the Android side: the delivery gate reads persisted state with no network, a WorkManager job refreshes that state off the message path, a pushed revoke applies instantly, and a revoked device keeps its history behind an explanatory banner instead of being thrown back to the code screen.

**Tech Stack:** Google Apps Script (console + sender), Firebase Realtime Database REST, FCM HTTP v1, Kotlin/Compose/Room, WorkManager.

**Spec:** `docs/superpowers/specs/2026-09-09-code-audit-trail-design.md` and `docs/decisions.md`

## Global Constraints

- **Never add a sibling field to `/codes/{CODE}`.** `$other: { ".validate": false }` means an unruled field breaks the app's claim write for every unclaimed slip. This has happened once (`note`). The audit lives at `/audit`.
- **Never include a `notification` block in an FCM payload.** It bypasses `onMessageReceived` entirely — no gate, no Room write, no artwork. (SYSTEM.md §5.1)
- **`/exec` serves the deployed version, not HEAD.** Every Apps Script change needs *Manage deployments → edit → New version*. Editor-run test functions execute HEAD and cannot detect a stale deployment. (SYSTEM.md §5.2)
- **Import JSON at the root deletes everything.** Never do it. (SYSTEM.md §5.6)
- **`ActivationState.Unknown` permits delivery.** Only a definitive `Revoked` suppresses a notice. Do not "fix" this.
- Audit timestamps are **epoch millis** (matching `/codes.issued` and `activatedAt`), not ISO.
- `event` is a closed set: `issued`, `revoked`, `restored`, `released`, `note`.
- Apps Script here is **ES5** — `var`, no arrow functions, no `const`/`let`, no template literals.
- Android `minSdk 26`. No DI framework; wiring goes in `NoticesApplication`.

## Deviations from the spec, decided while planning

1. **The spec said the console gets no test harness.** This plan adds `docs/apps-script/console/Tests.gs` covering the *pure* helpers only (`auditTimeline_`), following the sender's existing `t_ok_`/`t_eq_` style. Ordering logic that renders a legal record should not be verified by eye.
2. **`docs/decisions.md` leaves a gap:** a revoked device unsubscribes from all topics, so no message ever arrives to enqueue the one-time refresher, and a *restore* would only be discovered when the app is opened — which, for this audience, may be never. Task 8 therefore adds a **`PeriodicWorkRequest` every 24 hours** alongside the message-triggered one-time request. This matches the doc's own stated consequence ("roughly one connection per device per day"). Task 12 records it in `docs/decisions.md`.
3. **The revoke bridge is console→sender over HTTPS**, per the user, not "the sender pushes" as `decisions.md` implies. The console never gains FCM access, preserving the separation in SYSTEM.md §2.3.

---

## Phase 1 — Audit trail and send attribution

### Task 1: Audit helper and console lifecycle writes

**Files:**
- Modify: `docs/apps-script/console/Code.gs` (add helper; `createCodes:174`, `setNote:206`, `releaseCode:226`, `revokeCode:251`, `unrevokeCode:258`)

**Interfaces:**
- Produces: `audit_(code, event, by, extra)` — appends one entry via REST POST. `AUDIT_EVENTS` array.
- Consumes: `requireEditor_()` (returns lowercased email), `firebase_(method, path, payload)`.

- [ ] **Step 1: Add the helper and the event whitelist**

Insert after `firebase_` (currently ends line 165):

```javascript
// ---------------------------------------------------------------- Audit

/**
 * The only events the audit may contain. A closed set on purpose: a typo that invents a sixth
 * event would produce a row nobody ever queries, and the log is the record the NGO is meant to
 * trust without asking a developer.
 */
var AUDIT_EVENTS = ['issued', 'revoked', 'restored', 'released', 'note'];

/**
 * Appends one entry to a code's history.
 *
 * POST rather than PATCH so Realtime Database mints the key. A client-generated key from
 * Date.now() is exactly what silently lost a notice in SYSTEM.md quirk 5.12: two actions inside
 * one millisecond and the second overwrites the first with no error anywhere.
 *
 * This never writes to /codes. Adding a field there needs a matching .validate rule or the app's
 * claim write starts failing for every unclaimed slip -- see the note on `note` in SYSTEM.md 6a.
 */
function audit_(code, event, by, extra) {
  if (AUDIT_EVENTS.indexOf(event) < 0) throw new Error('Unknown audit event: ' + event);

  var entry = { at: Date.now(), by: by, event: event };
  if (extra) {
    for (var key in extra) {
      if (extra.hasOwnProperty(key) && extra[key] !== undefined && extra[key] !== null) {
        entry[key] = extra[key];
      }
    }
  }
  firebase_('post', '/audit/' + code + '.json', entry);
}
```

- [ ] **Step 2: Attribute the four single-code operations**

Each already calls `requireEditor_()` and validates the code. Capture the email and append after the `/codes` patch succeeds — never before, so a failed patch cannot leave a lie in the history.

```javascript
function setNote(code, note) {
  var by = requireEditor_();
  if (!isValidCode(code)) throw new Error('Not a valid code: ' + code);
  note = (note || '').toString().trim().slice(0, 120);
  firebase_('patch', '/codes/' + code + '.json', { note: note || null });
  audit_(code, 'note', by, { to: note });
  return listCodes();
}

function releaseCode(code) {
  var by = requireEditor_();
  if (!isValidCode(code)) throw new Error('Not a valid code: ' + code);
  firebase_('patch', '/codes/' + code + '.json', { usedBy: null, activatedAt: null, revoked: null });
  audit_(code, 'released', by);
  return listCodes();
}

function revokeCode(code) {
  var by = requireEditor_();
  if (!isValidCode(code)) throw new Error('Not a valid code: ' + code);
  firebase_('patch', '/codes/' + code + '.json', { revoked: true });
  audit_(code, 'revoked', by);
  return listCodes();
}

function unrevokeCode(code) {
  var by = requireEditor_();
  if (!isValidCode(code)) throw new Error('Not a valid code: ' + code);
  firebase_('patch', '/codes/' + code + '.json', { revoked: null });
  audit_(code, 'restored', by);
  return listCodes();
}
```

- [ ] **Step 3: Attribute bulk issue with one multi-path PATCH**

200 sequential POSTs would be slow and fragile. A fresh code has an empty log, so the shared `issued` millis is a safe key. Replace the write at the end of `createCodes`:

```javascript
  firebase_('patch', '/codes.json', updates);

  // One multi-path PATCH rather than up to 200 POSTs. Safe to use a client-generated key here
  // precisely because these codes did not exist a moment ago, so their logs are empty and cannot
  // collide. Every other audit write uses POST and lets the server mint the key.
  var auditUpdates = {};
  for (var i = 0; i < created.length; i++) {
    var entry = { at: issued, by: by, event: 'issued' };
    if (note) entry.to = note;
    auditUpdates[created[i] + '/' + issued] = entry;
  }
  firebase_('patch', '/audit.json', auditUpdates);

  return created;
```

and change its first line from `requireEditor_();` to `var by = requireEditor_();`.

- [ ] **Step 4: Verify by hand against the live database**

In the Apps Script editor run `createCodes(2, 'plan task 1')`, then `revokeCode`, `unrevokeCode`, `releaseCode` and `setNote` on one of them. In the Firebase console open `/audit` and confirm five entries in order with your email on each.

Expected: every entry has `at`, `by`, `event`; the `issued` and `note` entries carry `to`.

- [ ] **Step 5: Confirm activation still works**

This task does not touch `/codes` structure or the rules, but activation is the thing this project has broken before and it costs one slip to be certain. Type the other new code into a phone.

Expected: the code is accepted and the welcome notice appears.

- [ ] **Step 6: Commit**

```bash
git add docs/apps-script/console/Code.gs
git commit -m "Console: append an audit entry for every code lifecycle action"
```

---

### Task 2: Join the audit into listCodes

**Files:**
- Modify: `docs/apps-script/console/Code.gs` (`listCodes:234`)
- Create: `docs/apps-script/console/Tests.gs`

**Interfaces:**
- Produces: `auditTimeline_(entries, node)` — pure; returns an array sorted by `at` ascending, with the synthesised claim event merged in. `listCodes()` rows gain `audit` (that array) and `lastChange` (the newest entry, or `null`).
- Consumes: `audit_` from Task 1.

- [ ] **Step 1: Write the failing test**

Create `docs/apps-script/console/Tests.gs`:

```javascript
/**
 * Tests for the console's pure helpers, runnable from the Apps Script editor.
 *
 * Only the pure logic is covered. Everything else in Code.gs talks to Realtime Database with a
 * service account, and a mock of that would test the mock. The timeline is here because it renders
 * the record the NGO is meant to trust, and "looks right" is not a way to verify an ordering.
 *
 * Run `runConsoleTests` and read the execution log.
 */

var TC_RESULTS = [];

function tc_ok_(name, condition, detail) {
  TC_RESULTS.push({ name: name, pass: !!condition, detail: condition ? (detail || '') : ('FAILED ' + (detail || '')) });
}

function tc_eq_(name, actual, expected) {
  var pass = String(actual) === String(expected);
  tc_ok_(name, pass, pass ? String(actual) : ('expected ' + expected + ', got ' + actual));
}

function tc_auditTimeline_() {
  var entries = {
    k1: { at: 300, by: 'ravi@x.org', event: 'revoked' },
    k2: { at: 100, by: 'asha@x.org', event: 'issued' }
  };

  var plain = auditTimeline_(entries, {});
  tc_eq_('timeline sorts ascending by at', plain[0].event + ',' + plain[1].event, 'issued,revoked');
  tc_eq_('timeline keeps who', plain[1].by, 'ravi@x.org');

  // A claim sits between the two console actions and is synthesised, not stored.
  var withClaim = auditTimeline_(entries, { activatedAt: 200 });
  tc_eq_('claim is interleaved', withClaim.length, 3);
  tc_eq_('claim lands in the middle', withClaim[1].event, 'claimed');
  tc_eq_('claim has no email', withClaim[1].by, '');

  // An unclaimed code has no claim event at all.
  tc_eq_('no claim when never activated', auditTimeline_(entries, {}).length, 2);

  // A code with no history yet must not throw.
  tc_eq_('empty audit is empty', auditTimeline_(null, {}).length, 0);
  tc_eq_('claim alone still renders', auditTimeline_(null, { activatedAt: 50 })[0].event, 'claimed');
}

function runConsoleTests() {
  TC_RESULTS = [];
  tc_auditTimeline_();

  var failed = 0;
  for (var i = 0; i < TC_RESULTS.length; i++) {
    var r = TC_RESULTS[i];
    if (!r.pass) failed++;
    Logger.log((r.pass ? 'ok   ' : 'FAIL ') + r.name + (r.detail ? '  -- ' + r.detail : ''));
  }
  Logger.log(failed === 0 ? ('All ' + TC_RESULTS.length + ' passed.') : (failed + ' of ' + TC_RESULTS.length + ' FAILED.'));
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run `runConsoleTests` from the editor.
Expected: it throws `ReferenceError: auditTimeline_ is not defined`.

- [ ] **Step 3: Implement the timeline helper**

Add to `Code.gs` beneath `audit_`:

```javascript
/**
 * A code's stored history plus the one event that is never stored: the device's own claim.
 *
 * The phone cannot write here. Letting it would mean opening /audit to 400 devices, and the audit
 * is only trustworthy because nothing but the console can reach it. So the claim is reconstructed
 * from /codes/{CODE}.activatedAt at render time.
 *
 * Known limit: activatedAt holds only the most recent claim, so a released-and-reclaimed code
 * shows just the latest. The console actions either side still show the shape of what happened.
 */
function auditTimeline_(entries, node) {
  var out = [];

  for (var key in entries) {
    if (!entries.hasOwnProperty(key)) continue;
    var e = entries[key] || {};
    out.push({ at: e.at || 0, by: e.by || '', event: e.event || '', to: e.to || '' });
  }

  if (node && node.activatedAt) {
    out.push({ at: node.activatedAt, by: '', event: 'claimed', to: '' });
  }

  out.sort(function (a, b) { return a.at - b.at; });
  return out;
}
```

- [ ] **Step 4: Run the tests and make sure they pass**

Run `runConsoleTests`.
Expected: `All 6 passed.`

- [ ] **Step 5: Join it into listCodes**

```javascript
function listCodes() {
  requireEditor_();
  var all = firebase_('get', '/codes.json') || {};
  var audit = firebase_('get', '/audit.json') || {};
  var rows = [];
  for (var code in all) {
    if (!all.hasOwnProperty(code)) continue;
    var node = all[code] || {};
    var timeline = auditTimeline_(audit[code], node);
    rows.push({
      code: code,
      formatted: code.substring(0, 4) + '-' + code.substring(4),
      issued: node.issued || 0,
      claimed: !!node.usedBy,
      claimedAt: node.activatedAt || 0,
      revoked: node.revoked === true,
      note: node.note || '',
      audit: timeline,
      lastChange: timeline.length ? timeline[timeline.length - 1] : null
    });
  }
  rows.sort(function (a, b) { return b.issued - a.issued; });
  return rows;
}
```

- [ ] **Step 6: Commit**

```bash
git add docs/apps-script/console/Code.gs docs/apps-script/console/Tests.gs
git commit -m "Console: return each code's audit timeline from listCodes"
```

---

### Task 3: Render the audit in the admin table

**Files:**
- Modify: `docs/apps-script/console/Index.html` (codes table header ~line 113-127; row rendering ~line 300-320; `sortCodes` ~line 263)

**Interfaces:**
- Consumes: `row.audit` (array of `{at, by, event, to}` ascending) and `row.lastChange` from Task 2.

- [ ] **Step 1: Add the sortable column and widen the loading row**

In the `<thead>`, after the `Issued` header:

```html
            <th class="sort" onclick="sortCodes('lastChange')">Last change<span class="arrow" id="ac-lastChange"></span></th>
```

and change the loading placeholder from `colspan="5"` to `colspan="6"`.

- [ ] **Step 2: Add the history styles**

Alongside the existing `.pill` / `.revoked` rules:

```css
  .hist { background: #f6f8fc; }
  .hist td { padding: 10px 14px 14px; }
  .hist ol { margin: 0; padding-left: 18px; }
  .hist li { padding: 2px 0; }
  .hist .who { color: #5a6478; }
  .toggle { font: inherit; font-size: 13px; background: none; border: 0; padding: 0;
            color: #14315c; cursor: pointer; text-decoration: underline; }
```

- [ ] **Step 3: Render the cell, the toggle and the expandable row**

Add these helpers to the script block:

```javascript
  // Expansion is keyed by code rather than by row index so it survives a re-sort and a page turn.
  var OPEN_HISTORY = {};

  var EVENT_LABEL = { issued: 'Issued', revoked: 'Revoked', restored: 'Restored',
                      released: 'Released', note: 'Note set', claimed: 'Claimed' };

  function auditWhen_(at) {
    if (!at) return '';
    var d = new Date(at);
    return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short' }) + ' ' +
           d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  }

  function auditLine_(entry) {
    var who = entry.by ? esc(entry.by) : '(device)';
    var label = EVENT_LABEL[entry.event] || esc(entry.event);
    var extra = entry.to ? ' &mdash; &ldquo;' + esc(entry.to) + '&rdquo;' : '';
    return label + ' ' + auditWhen_(entry.at) + ' <span class="who">' + who + '</span>' + extra;
  }

  function toggleHistory(code) {
    if (OPEN_HISTORY[code]) delete OPEN_HISTORY[code]; else OPEN_HISTORY[code] = true;
    renderCodes();
  }
```

In the row builder, add the cell after the `Issued` cell:

```javascript
    var last = r.lastChange
      ? auditLine_(r.lastChange) +
        ' <button class="toggle" onclick="toggleHistory(\'' + r.code + '\')">' +
        (OPEN_HISTORY[r.code] ? 'hide' : 'history') + '</button>'
      : '<span class="sub">&mdash;</span>';
```

and append it as a `<td>`. After each `<tr>`, when expanded, emit:

```javascript
    if (OPEN_HISTORY[r.code] && r.audit && r.audit.length) {
      var items = r.audit.map(function (entry) { return '<li>' + auditLine_(entry) + '</li>'; }).join('');
      html += '<tr class="hist"><td colspan="6"><ol>' + items + '</ol></td></tr>';
    }
```

- [ ] **Step 4: Teach the sorter about the new column**

In `sortCodes`, the comparator for `lastChange` sorts on the timestamp, with codes that have no history last:

```javascript
      if (key === 'lastChange') {
        return (a.lastChange ? a.lastChange.at : 0) - (b.lastChange ? b.lastChange.at : 0);
      }
```

- [ ] **Step 5: Deploy and check by hand**

*Manage deployments → edit → New version.* Open the console. Revoke and restore a test code.

Expected: the Last change cell reads `Restored <date> <your email>`; the `history` toggle opens a numbered list oldest-first, including a `Claimed (device)` line for any claimed code; sorting by Last change works; expansion survives a sort and a page turn.

- [ ] **Step 6: Commit**

```bash
git add docs/apps-script/console/Index.html
git commit -m "Console: show each code's last change and full history in the table"
```

---

### Task 4: Attribute every send

**Files:**
- Modify: `docs/apps-script/sender/Code.gs` (`sendNotice:250`, `listSent:290`, `sendStatus:368`)
- Modify: `docs/apps-script/sender/Poller.gs` (its delivery function)
- Modify: `docs/apps-script/sender/Index.html` (history rendering)
- Modify: `docs/apps-script/sender/Tests.gs`

**Interfaces:**
- Produces: `/sent/{logId}.sentBy` — a lowercased allowlisted email, or the literal `"poller"`.

- [ ] **Step 1: Write the failing tests**

Add to `Tests.gs`, and call `t_sentBy_()` from `runAllTests`:

```javascript
function t_sentBy_() {
  var before = TOPIC_OVERRIDE;
  TOPIC_OVERRIDE = TEST_TOPIC;
  var logId = null;
  try {
    var result = sendNotice('Audit plan test', 'Ignore this.', 'NOTICES', property_('STAFF_PIN'));
    logId = result.logId;
    var row = firebase_('get', '/sent/' + logId + '.json');
    t_ok_('sendNotice records sentBy', !!row.sentBy, String(row.sentBy));
    t_eq_('sentBy is the caller', row.sentBy, requireEditor_());
  } finally {
    TOPIC_OVERRIDE = before;
    if (logId) firebase_('delete', '/sent/' + logId + '.json');
  }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run `t_sentBy_` from the editor.
Expected: `FAILED undefined` on `sendNotice records sentBy`.

- [ ] **Step 3: Write sentBy on both send paths**

In `sendNotice`, change `requireEditor_();` to `var by = requireEditor_();` and add the field to the `PUT`:

```javascript
  firebase_('put', '/sent/' + logId + '.json', { title: title, body: body, category: category, sentAt: sentAt, sentBy: by, scriptVersion: SCRIPT_VERSION });
```

Do the same in `sendStatus`. The QR path is being retired, but while the function is callable, an unattributed row is worse than one extra line.

- [ ] **Step 4: Run the test and make sure it passes**

Run `t_sentBy_`.
Expected: both assertions `ok`.

- [ ] **Step 5: Attribute the poller and surface the field**

In the poller's own delivery function, include `sentBy: 'poller'` in its `/sent` write. It must not borrow the trigger owner's identity — an owner-installed trigger would misattribute every automated send to whoever last reinstalled it, which the file's own header already argues.

In `listSent`, add `sentBy: all[logId].sentBy || ''` to the pushed row, and render it in `Index.html`'s history list next to the timestamp.

- [ ] **Step 6: Run the whole suite**

Run `runAllTests`.
Expected: all pass, including the pre-existing 43.

- [ ] **Step 7: Commit**

```bash
git add docs/apps-script/sender/
git commit -m "Sender: record who sent each notice"
```

---

## Phase 2 — The revoke bridge

### Task 5: A revoke endpoint on the sender

**Files:**
- Modify: `docs/apps-script/sender/Code.gs` (add `doPost`, `pushRevoke_`)
- Modify: `docs/apps-script/sender/Tests.gs`

**Interfaces:**
- Produces: `pushRevoke_(code, by)` — sends the data-only revoke to `TOPIC`, returns the FCM message name. `doPost(e)` — accepts `{"action":"revoke","code":"…"}`, authenticates via `requireEditor_()`, returns `{ok:true, fcmName:…}` or `{ok:false, error:…}` as JSON.

**Message contract (add to `docs/sender-contract.md`):**

```json
{ "message": { "topic": "notices-v1", "android": { "priority": "high" },
  "data": { "type": "revoke", "code": "A1B2C3D4" } } }
```

No `title`, no `body`, no `notification` block. Every device receives it and tests the code against its own; only the holder acts on it.

- [ ] **Step 1: Write the failing test**

```javascript
function t_pushRevoke_() {
  var before = TOPIC_OVERRIDE;
  TOPIC_OVERRIDE = TEST_TOPIC;
  try {
    var name = pushRevoke_('ZZZZZZZZ', requireEditor_());
    t_ok_('pushRevoke_ returns an FCM name', String(name).indexOf('projects/') === 0, String(name));
  } finally {
    TOPIC_OVERRIDE = before;
  }

  t_throws_('doPost refuses an unknown action', function () {
    doPost({ postData: { contents: JSON.stringify({ action: 'nonsense' }) } });
  }, 'action');
}
```

Call `t_pushRevoke_()` from `runAllTests`.

- [ ] **Step 2: Run it to make sure it fails**

Expected: `ReferenceError: pushRevoke_ is not defined`.

- [ ] **Step 3: Implement the push and the endpoint**

```javascript
/**
 * Broadcasts a revocation so the holder stops delivery within seconds instead of waiting for its
 * next scheduled verification.
 *
 * This is a broadcast, not per-device addressing: onNewToken is deliberately not overridden and
 * everything here is topic-addressed, so every subscribed phone receives it and tests the code
 * against its own. That publishes the revoked code to all of them, which is harmless -- a code
 * carrying revoked = true is useless to whoever reads it -- but it is what is being sent.
 *
 * It cannot be authoritative. FCM is best-effort and a phone that is off past the message TTL
 * never sees it, which is why the periodic verification on the device remains the safety net.
 */
function pushRevoke_(code, by) {
  var message = { message: {
    topic: TOPIC_OVERRIDE || TOPIC,
    android: { priority: 'high' },
    data: { type: 'revoke', code: String(code) }
  } };

  var response = UrlFetchApp.fetch(
    'https://fcm.googleapis.com/v1/projects/' + property_('PROJECT_ID') + '/messages:send',
    { method: 'post', contentType: 'application/json',
      headers: { Authorization: 'Bearer ' + accessToken_() },
      payload: JSON.stringify(message), muteHttpExceptions: true });

  if (response.getResponseCode() >= 300) {
    throw new Error('FCM refused the revoke: ' + response.getContentText());
  }
  return JSON.parse(response.getContentText()).name || '';
}

/**
 * The console calls this to have a revocation pushed. The console cannot reach FCM itself, and
 * deliberately so: issuing codes and broadcasting to four hundred phones are separate jobs held in
 * separate projects (SYSTEM.md 2.3).
 *
 * Authentication is the caller's own Google identity, forwarded as a Bearer token, checked against
 * the same ALLOWED_EDITORS allowlist as every other entry point. No new shared secret is
 * introduced -- a secret in two Script Properties would be one more thing that can leak and one
 * more thing that says nothing about who acted.
 *
 * No PIN. The PIN guards paths where arbitrary text reaches every phone; this one sends a fixed
 * envelope that only invalidates.
 */
function doPost(e) {
  var out = { ok: false };
  try {
    var caller = requireEditor_();
    var payload = JSON.parse((e && e.postData && e.postData.contents) || '{}');

    if (payload.action !== 'revoke') throw new Error('Unknown action: ' + payload.action);
    if (!/^[0-9A-Z]{8}$/.test(String(payload.code || ''))) throw new Error('Not a valid code.');

    // The caller may state who authorised it, but the token is what is believed. A mismatch is
    // recorded rather than refused: it means the console is misconfigured, not that the request is
    // forged, and refusing would leave a revocation half-applied.
    out.fcmName = pushRevoke_(payload.code, caller);
    out.by = caller;
    if (payload.by && payload.by !== caller) out.claimedBy = payload.by;
    out.ok = true;
  } catch (err) {
    out.error = String(err && err.message ? err.message : err);
  }
  return ContentService.createTextOutput(JSON.stringify(out))
    .setMimeType(ContentService.MimeType.JSON);
}
```

- [ ] **Step 4: Run the tests and make sure they pass**

Run `t_pushRevoke_`, then `runAllTests`.
Expected: all pass. The revoke goes to `TEST_TOPIC`, which no phone subscribes to.

- [ ] **Step 5: Deploy a new version**

*Manage deployments → edit → New version.* `/exec` serves the deployed version; the editor tests above ran HEAD and cannot tell you the deployment is stale.

- [ ] **Step 6: Commit**

```bash
git add docs/apps-script/sender/Code.gs docs/apps-script/sender/Tests.gs docs/sender-contract.md
git commit -m "Sender: accept a revoke from the console and push it to the phones"
```

---

### Task 6: Call the endpoint from the console

**Files:**
- Modify: `docs/apps-script/console/Code.gs` (`revokeCode`, `property_` usage)

**Interfaces:**
- Consumes: the sender's `doPost` from Task 5.
- Produces: `pushRevokeToSender_(code, by)` — returns `{ok, fcmName}` or `{ok:false, error}`; never throws.
- New Script Property: `SENDER_URL` — the sender's `/exec` URL.
- New OAuth scope on the console manifest: `https://www.googleapis.com/auth/userinfo.email`.

- [ ] **Step 1: Add the caller**

```javascript
/**
 * Asks the sender to broadcast a revocation.
 *
 * Never throws. A revocation is already recorded in /codes and /audit by the time this runs, and
 * the phone's periodic verification will find it within a day regardless -- so a failure here is a
 * delay, not a lost revocation, and must not present to the staff member as a failed Revoke.
 *
 * ScriptApp.getOAuthToken() forwards the signed-in staff member's own identity, so the sender's
 * requireEditor_ sees the person who clicked, not this script. That needs userinfo.email in the
 * console manifest's oauthScopes; without it the sender answers "cannot identify you".
 */
function pushRevokeToSender_(code, by) {
  var url = PropertiesService.getScriptProperties().getProperty('SENDER_URL');
  if (!url) return { ok: false, error: 'No SENDER_URL is set, so the revoke was not pushed.' };

  try {
    var response = UrlFetchApp.fetch(url, {
      method: 'post',
      contentType: 'application/json',
      headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
      payload: JSON.stringify({ action: 'revoke', code: code, by: by }),
      muteHttpExceptions: true,
      followRedirects: true
    });
    return JSON.parse(response.getContentText());
  } catch (err) {
    return { ok: false, error: String(err && err.message ? err.message : err) };
  }
}
```

- [ ] **Step 2: Push on revoke and record the outcome**

```javascript
function revokeCode(code) {
  var by = requireEditor_();
  if (!isValidCode(code)) throw new Error('Not a valid code: ' + code);
  firebase_('patch', '/codes/' + code + '.json', { revoked: true });

  // The database is the truth; the push is only how the phone hears about it sooner. So the audit
  // entry is written whether or not the push lands, and records which happened.
  var push = pushRevokeToSender_(code, by);
  audit_(code, 'revoked', by, { pushed: push.ok ? true : false, pushError: push.ok ? null : push.error });

  return listCodes();
}
```

- [ ] **Step 3: Configure and grant**

In the console project: add `SENDER_URL` to Script Properties, add `"oauthScopes"` to `appsscript.json` including `https://www.googleapis.com/auth/script.external_request` and `https://www.googleapis.com/auth/userinfo.email`, then run any function once from the editor to re-consent.

- [ ] **Step 4: Verify end to end**

Revoke a test code from the console. Check the sender's `/exec` executions log for the POST, and `/audit/{CODE}` for `pushed: true`.

Expected: the newest audit entry has `pushed: true` and a `by` matching the person who clicked, not the script owner.

- [ ] **Step 5: Verify it degrades safely**

Temporarily set `SENDER_URL` to a wrong URL and revoke another test code.

Expected: the console still reports success, `/codes` still shows `revoked: true`, and the audit entry carries `pushed: false` with a `pushError`. Restore the correct URL afterwards.

- [ ] **Step 6: Commit**

```bash
git add docs/apps-script/console/Code.gs
git commit -m "Console: ask the sender to push a revoke, and record whether it landed"
```

---

## Phase 3 — Android

### Task 7: Persisted state, the no-network gate, and a suspended claim

**Files:**
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/activation/ActivationRepository.kt`
- Create: `app/src/main/java/org/paramanuseniorshealth/notices/activation/ActivationFreshness.kt`
- Create: `app/src/test/java/org/paramanuseniorshealth/notices/ActivationFreshnessTest.kt`

**Interfaces:**
- Produces:
  - `ActivationFreshness.STALE_AFTER_MS: Long` (24 hours) and `ActivationFreshness.isStale(lastVerifiedAt: Long, now: Long): Boolean`
  - `ActivationRepository.gate(): ActivationState` — no network, reads persisted state
  - `ActivationRepository.recordVerification(state: ActivationState)` — persists state + timestamp
  - `ActivationRepository.lastVerifiedAt: Long`
  - `ActivationRepository.suspendClaim()` — unsubscribes, **keeps** code and UID, raises the revoked flag
  - `ActivationRepository.isRevoked: Boolean` — the persistent banner flag
  - `ActivationRepository.clearRevokedFlag()` — used when a restore is discovered
- Consumes: `ActivationState`, `Subscription`.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.paramanuseniorshealth.notices

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.activation.ActivationFreshness

class ActivationFreshnessTest {

    @Test
    fun `never verified is stale`() {
        assertTrue(ActivationFreshness.isStale(lastVerifiedAt = 0L, now = 1_000L))
    }

    @Test
    fun `just verified is fresh`() {
        val now = 1_000_000_000L
        assertFalse(ActivationFreshness.isStale(lastVerifiedAt = now, now = now))
    }

    @Test
    fun `fresh up to the boundary`() {
        val now = 1_000_000_000L
        val edge = now - ActivationFreshness.STALE_AFTER_MS + 1
        assertFalse(ActivationFreshness.isStale(lastVerifiedAt = edge, now = now))
    }

    @Test
    fun `stale once the window has passed`() {
        val now = 1_000_000_000L
        assertTrue(ActivationFreshness.isStale(now - ActivationFreshness.STALE_AFTER_MS, now))
    }

    @Test
    fun `a clock that has gone backwards counts as stale`() {
        // Rather than trusting a future timestamp and never checking again.
        assertTrue(ActivationFreshness.isStale(lastVerifiedAt = 5_000L, now = 1_000L))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ActivationFreshnessTest*"`
Expected: compilation failure — `ActivationFreshness` unresolved.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
package org.paramanuseniorshealth.notices.activation

/**
 * How old a verification may be before the device asks the server again.
 *
 * Pure and separate from the repository so it can be tested without Android. The window is a day
 * because verification is no longer how a revocation normally arrives -- a pushed revoke handles
 * that within seconds -- so this only has to catch a device that was switched off when the
 * broadcast went out. See docs/decisions.md.
 */
object ActivationFreshness {

    const val STALE_AFTER_MS: Long = 24L * 60 * 60 * 1000

    fun isStale(lastVerifiedAt: Long, now: Long): Boolean {
        if (lastVerifiedAt <= 0L) return true
        // A timestamp in the future means the clock moved, not that the answer is fresh. Trusting
        // it would stop the device ever checking again.
        if (lastVerifiedAt > now) return true
        return now - lastVerifiedAt >= STALE_AFTER_MS
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*ActivationFreshnessTest*"`
Expected: 5 tests pass.

- [ ] **Step 5: Add the persisted state and the gate to `ActivationRepository`**

Add the keys to the companion object:

```kotlin
        private const val KEY_LAST_STATE = "last_state"
        private const val KEY_LAST_VERIFIED_AT = "last_verified_at"
        private const val KEY_REVOKED = "revoked"
```

and the members:

```kotlin
    /** Raised when a revocation is known, and cleared only when the server says Active again. */
    val isRevoked: Boolean get() = prefs.getBoolean(KEY_REVOKED, false)

    val lastVerifiedAt: Long get() = prefs.getLong(KEY_LAST_VERIFIED_AT, 0L)

    /**
     * The delivery gate: may this notice be shown?
     *
     * Answers from the last persisted result, with no network at all. This exists because the old
     * design called verify() from onMessageReceived, so a broadcast to 400 phones opened ~400
     * Realtime Database sockets within a few seconds against a Spark cap of 100 -- and the check
     * silently stopped working during exactly the event it exists for. See docs/decisions.md.
     *
     * Nothing stored yet, or anything unrecognised, permits the notice. That is the same reasoning
     * that already permits Unknown: a missed closure notice is worse than a revoked device seeing
     * one more public announcement.
     */
    fun gate(): ActivationState {
        if (!isActivated) return ActivationState.NotActivated
        if (isRevoked) return ActivationState.Revoked
        return when (prefs.getString(KEY_LAST_STATE, null)) {
            ActivationState.Revoked.name -> ActivationState.Revoked
            ActivationState.Active.name -> ActivationState.Active
            else -> ActivationState.Unknown
        }
    }

    /** Stores a definitive answer. Unknown is not persisted: it is the absence of an answer. */
    fun recordVerification(state: ActivationState) {
        if (state == ActivationState.Unknown) return
        prefs.edit()
            .putString(KEY_LAST_STATE, state.name)
            .putLong(KEY_LAST_VERIFIED_AT, System.currentTimeMillis())
            .apply()
    }
```

- [ ] **Step 6: Replace claim surrender with claim suspension**

`clearRevoked()` threw away the code and the UID, which made the console's Restore button unable to restore anybody: the rules refuse a re-claim because `usedBy` is still set, so the device was stranded on a code it could never use again. Replace it:

```kotlin
    /**
     * Stops delivery without surrendering the claim.
     *
     * The code and the UID are kept on purpose. Revoking sets `revoked: true` and leaves `usedBy`
     * in place, and the rules refuse to write `usedBy` on a code that already carries one -- so a
     * device that had forgotten its code could never come back, and the console's Restore had
     * nothing to restore. Keeping them means a restore is discovered by the next verification and
     * the phone simply resumes.
     */
    suspend fun suspendClaim() {
        unsubscribeAll()
        prefs.edit()
            .putBoolean(KEY_REVOKED, true)
            .putString(KEY_LAST_STATE, ActivationState.Revoked.name)
            .putLong(KEY_LAST_VERIFIED_AT, System.currentTimeMillis())
            .apply()
    }

    /** The server says the claim stands again. Resume delivery. */
    suspend fun resumeClaim() {
        prefs.edit().putBoolean(KEY_REVOKED, false).apply()
        recordVerification(ActivationState.Active)
        syncSubscriptions()
    }
```

Delete `clearRevoked()` and `consumeRevokedNotice()`, and remove `KEY_REVOKED_NOTICE`: the banner is persistent, so a one-shot toast flag has nothing left to do. Extend `forgetClaim` (still used only by `resetByUser`) to also remove `KEY_REVOKED`, `KEY_LAST_STATE` and `KEY_LAST_VERIFIED_AT`.

- [ ] **Step 7: Persist every verification**

In `verify()`, before each `return`, route definitive answers through `recordVerification(...)`. The simplest shape is to compute the state into a local and record it once at the end of the `try`, leaving the `Unknown` paths untouched.

- [ ] **Step 8: Run the whole unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all pass. Compilation will fail until Tasks 9-11 remove the remaining `clearRevoked`/`consumeRevokedNotice` call sites — do those before claiming this step.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/activation/ app/src/test/java/org/paramanuseniorshealth/notices/ActivationFreshnessTest.kt
git commit -m "Activation: persist state, gate without the network, suspend rather than surrender"
```

---

### Task 8: The refresher, off the message path

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle`
- Create: `app/src/main/java/org/paramanuseniorshealth/notices/activation/ActivationRefreshWorker.kt`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/NoticesApplication.kt`

**Interfaces:**
- Consumes: `ActivationRepository.verify()`, `recordVerification`, `suspendClaim`, `resumeClaim`, `lastVerifiedAt`, `ActivationFreshness.isStale`.
- Produces: `ActivationRefreshWorker.enqueueIfStale(context)`, `ActivationRefreshWorker.ensurePeriodic(context)`.

- [ ] **Step 1: Add the dependency**

In `gradle/libs.versions.toml` under `[versions]` add `workManager = "2.10.0"`, and under `[libraries]`:

```toml
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "workManager" }
```

In `app/build.gradle`, beside the Room lines: `implementation libs.androidx.work.runtime.ktx`

- [ ] **Step 2: Write the worker**

```kotlin
package org.paramanuseniorshealth.notices.activation

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.database.FirebaseDatabase
import org.paramanuseniorshealth.notices.NoticesApplication
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Refreshes the entitlement state away from the message path.
 *
 * Every arriving notice used to call verify(), so a broadcast to 400 phones opened roughly 400
 * Realtime Database websockets inside a few seconds against a Spark cap of 100 simultaneous
 * connections. Over the cap the connection is refused, verify() returns Unknown, the notice shows
 * anyway -- and the gate quietly stopped working during the one event it exists for.
 *
 * Because this no longer runs inside onMessageReceived, it is not bounded by FCM's ~10 second
 * window, so the start is spread across a quarter of an hour rather than seconds.
 */
class ActivationRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as NoticesApplication
        val activation = app.activationRepository

        if (!activation.isActivated) return Result.success()

        return try {
            when (val state = activation.verify()) {
                ActivationState.Revoked -> {
                    activation.suspendClaim()
                    app.announceRevocation()
                    Result.success()
                }
                ActivationState.Active -> {
                    // Covers the restore the console cannot push: a revoked device has
                    // unsubscribed, so no broadcast can reach it and only this ever notices.
                    if (activation.isRevoked) activation.resumeClaim()
                    else activation.recordVerification(state)
                    Result.success()
                }
                ActivationState.NotActivated -> Result.success()
                ActivationState.Unknown -> Result.retry()
            }
        } finally {
            // Otherwise the SDK holds the socket open for about a minute of idleness, occupying a
            // connection slot long after the answer arrived.
            runCatching { FirebaseDatabase.getInstance().goOffline() }
                .onFailure { Log.w(TAG, "goOffline failed", it) }
        }
    }

    companion object {
        private const val TAG = "ActivationRefresh"
        private const val ONE_TIME = "activation-refresh"
        private const val PERIODIC = "activation-refresh-daily"
        private const val MAX_JITTER_MINUTES = 15L

        private val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * Enqueued from the message path when the stored answer has aged out. KEEP so a burst of
         * notices does not queue several.
         */
        fun enqueueIfStale(context: Context) {
            val app = context.applicationContext as NoticesApplication
            val activation = app.activationRepository
            if (!activation.isActivated) return
            if (!ActivationFreshness.isStale(activation.lastVerifiedAt, System.currentTimeMillis())) return

            val request = OneTimeWorkRequestBuilder<ActivationRefreshWorker>()
                .setConstraints(constraints)
                .setInitialDelay(Random.nextLong(0, MAX_JITTER_MINUTES * 60), TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * The daily fallback, and the only thing that ever notices a restore.
         *
         * A revoked device has unsubscribed from every topic, so nothing arrives to trigger the
         * one-time path above, and an eighty-year-old told to ring the helpdesk may never open the
         * app again. Without this, a code the NGO has restored stays dark indefinitely.
         */
        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ActivationRefreshWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(Random.nextLong(0, MAX_JITTER_MINUTES * 60), TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
```

- [ ] **Step 3: Schedule it**

In `NoticesApplication.onCreate`, after `syncSubscriptions()`:

```kotlin
        if (activationRepository.isActivated) ActivationRefreshWorker.ensurePeriodic(this)
```

Add to `NoticesApplication` the hook the worker calls, implemented fully in Task 11:

```kotlin
    /** Posts the tray notification and writes the history row when a revocation is discovered. */
    suspend fun announceRevocation() { /* Task 11 */ }
```

Also call `ActivationRefreshWorker.ensurePeriodic(context)` at the end of a successful `redeem()`, so a freshly activated phone is scheduled without waiting for a restart.

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle app/src/main/java/org/paramanuseniorshealth/notices/
git commit -m "Move entitlement verification off the message path into WorkManager"
```

---

### Task 9: Handle the pushed revoke, and gate without the network

**Files:**
- Create: `app/src/main/java/org/paramanuseniorshealth/notices/fcm/RevokeMessage.kt`
- Create: `app/src/test/java/org/paramanuseniorshealth/notices/RevokeMessageTest.kt`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/fcm/NoticeMessagingService.kt`

**Interfaces:**
- Produces: `RevokeMessage.codeIn(data: Map<String, String>): String?` — the revoked code when this is a revoke envelope, else null.
- Consumes: `ActivationRepository.gate()`, `suspendClaim()`, `ActivationRefreshWorker.enqueueIfStale`.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.paramanuseniorshealth.notices.fcm.RevokeMessage

class RevokeMessageTest {

    @Test
    fun `reads the code from a revoke envelope`() {
        assertEquals("A1B2C3D4", RevokeMessage.codeIn(mapOf("type" to "revoke", "code" to "A1B2C3D4")))
    }

    @Test
    fun `an ordinary notice is not a revoke`() {
        assertNull(RevokeMessage.codeIn(mapOf("title" to "Closed Friday", "logId" to "123")))
    }

    @Test
    fun `a revoke without a code is ignored`() {
        assertNull(RevokeMessage.codeIn(mapOf("type" to "revoke")))
        assertNull(RevokeMessage.codeIn(mapOf("type" to "revoke", "code" to "   ")))
    }

    @Test
    fun `the code is normalised the way a typed code is`() {
        // Crockford folds O to zero and I/L to one; the sender should never send a lowercase or
        // dashed code, but a mismatch here would silently fail to revoke the right device.
        assertEquals("A1B2C3D4", RevokeMessage.codeIn(mapOf("type" to "revoke", "code" to "a1b2-c3d4")))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*RevokeMessageTest*"`
Expected: compilation failure — `RevokeMessage` unresolved.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
package org.paramanuseniorshealth.notices.fcm

import org.paramanuseniorshealth.notices.activation.ActivationCode

/**
 * The revoke envelope: `{"type": "revoke", "code": "..."}`, data-only, no title and no body.
 *
 * It is a topic broadcast, so every subscribed phone receives every revoke and compares the code
 * with its own. See docs/sender-contract.md.
 */
object RevokeMessage {

    private const val TYPE = "revoke"

    fun codeIn(data: Map<String, String>): String? {
        if (data["type"] != TYPE) return null
        val raw = data["code"]?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return ActivationCode.normalise(raw).takeIf { it.isNotEmpty() }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*RevokeMessageTest*"`
Expected: 4 tests pass.

- [ ] **Step 5: Handle it first in the messaging service**

At the very top of `onMessageReceived`, before the `title` lookup that would `return` on a bodyless payload:

```kotlin
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data

        // Before anything else: a revoke carries no title, so the notice path below would drop it.
        RevokeMessage.codeIn(data)?.let { revokedCode ->
            runBlocking {
                val app = applicationContext as NoticesApplication
                if (app.activationRepository.storedCode == revokedCode) {
                    Log.i(TAG, "Revoke received for this device")
                    app.activationRepository.suspendClaim()
                    app.announceRevocation()
                }
            }
            return
        }

        val title = data["title"] ?: message.notification?.title ?: return
        ...
```

- [ ] **Step 6: Replace the network gate with the persisted one**

Rewrite `isEntitled` so it performs no network I/O, and enqueue the refresher instead:

```kotlin
    /**
     * The client-side entitlement gate.
     *
     * Answers from the last persisted state, with no network at all -- see ActivationRepository.gate
     * and docs/decisions.md. The refresh it schedules lands in time for the *next* notice rather
     * than this one, so a revoked device can see one more. That costs nothing: this is not access
     * control, every notice is published publicly, and a pushed revoke closes the gap in the normal
     * case anyway.
     */
    private fun isEntitled(app: NoticesApplication): Boolean {
        ActivationRefreshWorker.enqueueIfStale(applicationContext)

        return when (app.activationRepository.gate()) {
            ActivationState.Active, ActivationState.Unknown -> true
            ActivationState.Revoked -> {
                Log.i(TAG, "Notice dropped: activation revoked")
                false
            }
            ActivationState.NotActivated -> {
                Log.i(TAG, "Notice dropped: not activated")
                false
            }
        }
    }
```

`isEntitled` is no longer `suspend`, and the `NotActivated` branch no longer unsubscribes inline — the worker owns network work now.

- [ ] **Step 7: Run the whole unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all pass.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/fcm/ app/src/test/java/org/paramanuseniorshealth/notices/RevokeMessageTest.kt
git commit -m "Apply a pushed revoke instantly and gate notices without the network"
```

---

### Task 10: Keep the user on their notices when revoked

**Files:**
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/ui/NoticeViewModel.kt` (`init:112`, `refreshActivation:165`, `reset:255`)

**Interfaces:**
- Produces: `NoticeViewModel.revoked: StateFlow<Boolean>`.
- Consumes: `ActivationRepository.gate()`, `verify()`, `suspendClaim()`, `resumeClaim()`, `isRevoked`.

- [ ] **Step 1: Expose the flag**

```kotlin
    private val _revoked = MutableStateFlow(activation.isRevoked)
    val revoked: StateFlow<Boolean> = _revoked.asStateFlow()
```

Remove the `consumeRevokedNotice()` block from `init` — the banner is persistent, so a one-shot toast is gone.

- [ ] **Step 2: Rewrite the app-open check**

```kotlin
    /**
     * Asks the backend whether this install's claim still stands.
     *
     * App opens are naturally spread out, so this one still talks to the network directly; it is
     * the per-notice check that had to move to WorkManager. It is also the fastest way a restored
     * code comes back to life for someone who does open the app.
     *
     * Being revoked no longer returns the user to the code screen. Their notices stay readable
     * behind an explanation, because hiding them protected nothing -- any valid slip lifted that
     * gate, and it did not have to be theirs.
     */
    fun refreshActivation() {
        viewModelScope.launch {
            when (activation.verify()) {
                ActivationState.Revoked -> {
                    activation.suspendClaim()
                    _revoked.value = true
                }
                ActivationState.Active -> {
                    if (activation.isRevoked) activation.resumeClaim()
                    else activation.recordVerification(ActivationState.Active)
                    _revoked.value = false
                }
                ActivationState.NotActivated -> {
                    _revoked.value = false
                    _screen.value = Screen.Activation
                }
                ActivationState.Unknown -> Unit
            }
        }
    }
```

- [ ] **Step 3: Clear the flag on reset and on redeem**

In `reset()`, after `activation.resetByUser()` add `_revoked.value = false`. In `redeem()`'s `Success` branch, add the same — a device that resets and redeems a fresh code must not inherit the banner.

- [ ] **Step 4: Build and run the unit suite**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/ui/NoticeViewModel.kt
git commit -m "Keep a revoked user on their notice list instead of the code screen"
```

---

### Task 11: The banner, the tray notification and the history row

**Files:**
- Create: `app/src/main/java/org/paramanuseniorshealth/notices/ui/RevokedBanner.kt`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/ui/NoticeListScreen.kt:158,177`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/MainActivity.kt:147`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/NoticesApplication.kt` (`announceRevocation`)
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `NoticeViewModel.revoked`, `ActivationRepository.storedCode`, `ActivationCode.format`, `NoticeRepository.save`, `NoticeNotifications.post`.

- [ ] **Step 1: Add the strings**

```xml
    <string name="revoked_banner_title">You will not receive any more alerts.</string>
    <string name="revoked_banner_body">The notices below are still yours to read. If you expected this, you can uninstall the app.</string>
    <string name="revoked_banner_helpdesk">If not, please call the helpdesk and give them your code %1$s.</string>
    <string name="revoked_notification_title">You have been removed from the notices list</string>
    <string name="revoked_notification_body">You will not receive any more alerts. Your saved notices are still in the app.</string>
```

Remove `toast_access_removed` and its remaining references — it claimed access "has been removed" while the notices sat untouched behind a gate, and the banner now says what is actually true.

- [ ] **Step 2: Write the banner**

```kotlin
package org.paramanuseniorshealth.notices.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.paramanuseniorshealth.notices.R
import org.paramanuseniorshealth.notices.activation.ActivationCode

/**
 * Shown above everything when this device's code has been revoked.
 *
 * Not dismissible, and it offers no way to type another code. Re-entry is the NGO's decision, made
 * by restoring this code in the console -- at which point the device resumes on its own. A "have a
 * new code" button here would just be the loophole that made the old code screen pointless.
 *
 * The code is printed because Settings is where it normally lives, and somebody who has just been
 * cut off should not have to go hunting for the thing the helpdesk will ask for.
 */
@Composable
fun RevokedBanner(code: String?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = stringResource(R.string.revoked_banner_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.revoked_banner_body),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (code != null) {
                Text(
                    text = stringResource(R.string.revoked_banner_helpdesk, ActivationCode.format(code)),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
```

- [ ] **Step 3: Pin it above the office-hours header**

`NoticeListScreen` takes two new parameters, `revoked: Boolean` and `activationCode: String?`. In the empty-list branch (line 158) place `if (revoked) RevokedBanner(activationCode)` immediately before `OfficeInfoHeader`. In the populated branch (line 177) add it as its own item *before* the `office-info` item:

```kotlin
                    if (revoked) item(key = "revoked-banner") { RevokedBanner(activationCode) }
```

`MainActivity` passes `viewModel.revoked.collectAsStateWithLifecycle()` and the stored code down at line 147.

- [ ] **Step 4: Implement the announcement**

In `NoticesApplication`, replacing the Task 8 stub:

```kotlin
    /**
     * Says out loud that access has ended: once in the tray, and once in the notice list.
     *
     * Reuses the NOTICES channel deliberately. Android lists every channel an app has ever
     * created, so a dedicated one would put a permanent extra entry in four hundred people's
     * notification settings for something that happens to almost none of them.
     *
     * Guarded by the logId: Room ignores a duplicate, so a repeated broadcast cannot post twice.
     */
    suspend fun announceRevocation() {
        val isNew = repository.save(
            title = getString(R.string.revoked_notification_title),
            body = getString(R.string.revoked_notification_body),
            logId = REVOKED_LOG_ID,
        )
        if (!isNew) return

        NoticeNotifications.post(
            context = this,
            title = getString(R.string.revoked_notification_title),
            body = getString(R.string.revoked_notification_body),
            logId = REVOKED_LOG_ID,
            image = null,
            subscription = Subscription.NOTICES,
        )
    }

    private companion object {
        /** `local-` keeps it out of the sender's numeric namespace, as with `local-welcome`. */
        const val REVOKED_LOG_ID = "local-revoked"
    }
```

- [ ] **Step 5: Let a re-revocation announce itself again**

`local-revoked` is ignored by Room on a second insert, so a restore-then-revoke would be silent. In `resumeClaim()`'s caller path, delete the row: add to `NoticeRepository`

```kotlin
    /** Removes the local revocation notice, so a later revocation can announce itself again. */
    suspend fun clearRevokedNotice() {
        dao.byLogId("local-revoked")?.let { clear(listOf(it)) }
    }
```

and call it from `NoticeViewModel` and `ActivationRefreshWorker` wherever `resumeClaim()` is invoked.

- [ ] **Step 6: Build and run the unit suite**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Verify on hardware**

Install on the SM-S928U1. Activate with a fresh code. From the console, Revoke it.

Expected, within seconds of the push: a tray notification arrives; opening the app shows the banner above the office-hours header with the dashed code; the previous notices are all still listed; Settings still shows the code; a notice sent while revoked does **not** appear.

Then Restore in the console and reopen the app.

Expected: the banner disappears, delivery resumes, and the local revocation row is gone.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/ app/src/main/res/values/strings.xml
git commit -m "Explain a revocation instead of hiding the notices behind the code screen"
```

---

### Task 12: Documentation

**Files:**
- Modify: `docs/SYSTEM.md`, `docs/decisions.md`, `docs/sender-contract.md`

- [ ] **Step 1: Correct what is now wrong in SYSTEM.md**

- §3: add `/audit/{CODE}/{entryId}  at, by, event, to`.
- §2.3 and §6: the sender is **not** "Anyone with the link". It is "Anyone with a Google account" with an `ALLOWED_EDITORS` allowlist, and the PIN is optional via `REQUIRE_STAFF_PIN`. Correct the same stale claim in the `checkPin_` comment in `sender/Code.gs`.
- §5.14: the ~400-connections-per-broadcast trap is fixed; rewrite it as solved, pointing at `docs/decisions.md`.
- §6: replace "Revocation is cooperative, not enforced" with the current behaviour — pushed, applied on receipt, verified daily as a fallback, and it suspends the claim rather than surrendering it so a console Restore can bring the device back.
- §2.5 and the daily-status runbook: mark the QR as retired pending removal.
- Runbooks: "Cut a phone off" now lands within seconds and is reversible with Restore; add where to read a code's history.

- [ ] **Step 2: Record the two planning decisions in decisions.md**

Add the daily `PeriodicWorkRequest` (a revoked device unsubscribes, so nothing else would ever notice a restore) and the console→sender HTTPS bridge authenticated by the caller's OAuth token (the console never gains FCM, preserving SYSTEM.md §2.3).

- [ ] **Step 3: Add the revoke envelope to sender-contract.md**

- [ ] **Step 4: Commit**

```bash
git add docs/
git commit -m "Docs: audit trail, pushed revocation, and the sender access model"
```

---

## Self-review

**Spec coverage.** `/audit` schema → Task 1. Closed event set → Task 1. Bulk-issue exception → Task 1 Step 3. Timeline with synthesised claim → Task 2. Sortable Last change column and colspan history → Task 3. `sentBy` on both send paths, `listSent` and the poller → Task 4. No rules change → nothing touches `database.rules.json`; the revocation-eligibility requirement is documented in Task 12. No backfill → not implemented anywhere, by design. From `decisions.md`: pushed revoke → Tasks 5, 6, 9; gate/refresher split → Tasks 7, 8, 9; 24-hour window → Task 7; `goOffline` → Task 8; 0-15 minute jitter → Task 8; WorkManager dependency → Task 8.

**Naming consistency.** `audit_`, `auditTimeline_`, `AUDIT_EVENTS`, `pushRevokeToSender_`, `pushRevoke_` in Apps Script. `ActivationFreshness.isStale`/`STALE_AFTER_MS`, `gate()`, `recordVerification`, `suspendClaim`, `resumeClaim`, `isRevoked`, `lastVerifiedAt`, `RevokeMessage.codeIn`, `ActivationRefreshWorker.enqueueIfStale`/`ensurePeriodic`, `announceRevocation`, `clearRevokedNotice`, `RevokedBanner` in Kotlin. Each is defined once and used under the same name everywhere else.

**Known gap, deliberately left.** `clearRevoked()` and `consumeRevokedNotice()` are deleted in Task 7 but their call sites are removed in Tasks 9, 10 and 11, so the tree does not compile between Task 7 and Task 10. The alternative is one enormous task; the compile break is loud and local, and Task 7 Step 8 says so.
