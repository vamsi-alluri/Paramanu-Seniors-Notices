# Paramanu Seniors Notices — system reference

Everything needed to pick this project up cold: how the pieces fit, what is done, what is not, and
the traps that cost real time to find.

**Status (2026-09-09):** **approved and live in Play closed testing, with 14 testers.** The privacy
policy rejections are resolved. Source is at `versionCode 6`, `versionName 0.3.2`.

Two things this changed, both of which now matter:

- **Sends reach 14 real phones.** Closed testing subscribes to the same `notices-v1` topic as
  production — deliberate, but it stopped being theoretical the moment there were real testers.
  Anything sent from the sender, and anything the RSS poller picks up, goes to all of them. There is
  no separate testing audience; `TOPIC_OVERRIDE` and `TEST_TOPIC` exist for the Apps Script suite,
  not for staff sends.
- **Production is still ahead.** Closed-testing approval is not production approval. An individual
  developer account has to hold a group of testers for a continuous period before production access
  is granted, which is the likely reason for the 14.

Resolved, kept because it cost real time: the app was flagged under **News and Magazines policy** —
"app does not contain any news articles or app maybe incorrectly declared as News app". The category
was simply wrong; this is not a news publication. **The category is now Communication**, which also
clears the News declaration and its journalism attestations. Separately, Play requires the policy to
cover the *app*, and the site's own `/privacy-policy` is a website-only page that mentions no app,
no Android and no Firebase; the app's policy lives at `paramanuseniorshealth.org/privacy-policy-app/`
from `docs/privacy-policy.md`. Keep the two pages distinct.

| | |
|---|---|
| Name | **Paramanu Seniors Notices** — launcher, store title and privacy policy all agree |
| applicationId | `org.paramanuseniorshealth.notices` (permanent, never changeable) |
| Play category | Communication |
| minSdk / targetSdk | 26 / 36 |
| Firebase project | `paramanu-seniors` (separate from the personal `utils-5cb5b`) |
| RTDB region | `asia-southeast1` (Singapore — RTDB has no India region) |
| Play developer | US individual account |
| Distribution | India only |

---

## 1. How the pieces fit

```
   COUNTER                         NGO STAFF                        SUBSCRIBER
      │                                │                                │
      │  paper slip with code          │                                │
      ├───────────────────────────────────────────────────────────────► │
      │                                │                                │
┌─────┴──────────────┐      ┌──────────┴───────────┐                    │
│  CONSOLE           │      │  SENDER              │                    │
│  Apps Script       │      │  Apps Script         │                    │
│                    │      │                      │                    │
│  generate codes    │      │  compose a notice    │                    │
│  print slips       │      │  saved-message chips │                    │
│  revoke / release  │      │  QR: open / closed   │◄──── bit.ly ◄──────┤ (staff scans)
│  notes per code    │      │  PIN on every send   │                    │
│  saved messages    │      │                      │                    │
└─────────┬──────────┘      └──────────┬───────────┘                    │
          │ service account            │ service account                │
          ▼                            ▼                                │
    ┌─────────────────────────────────────────────┐                     │
    │  FIREBASE  paramanu-seniors                 │                     │
    │                                             │                     │
    │  RTDB   /codes  /templates  /sent  /info    │                     │
    │  Auth   anonymous only                      │                     │
    │  FCM    topics notices-v1, status-v1        │                     │
    └───────────────────┬─────────────────────────┘                     │
                        │  data-only, priority high                     │
                        ▼                                               ▼
                  ┌──────────────────────────────────────────────────────┐
                  │  ANDROID APP                                         │
                  │  gate → save to Room → fetch art → post notification  │
                  └──────────────────────────────────────────────────────┘
```

There is **no server of ours**. The two Apps Scripts hold service-account credentials; the phone
talks to Firebase directly and is policed by security rules.

---

## 2. Components

### 2.1 Android app

Kotlin, Compose, Room, no DI framework (hand-rolled container in `NoticesApplication`).

```
activation/  ActivationCode        Crockford Base32 + check character (pure, tested)
             ActivationState       NotActivated | Active | Revoked | Unknown
             ActivationRepository  redeem, verify, subscribe, forget
             Subscription          NOTICES / STATUS: topic + channel + default
data/        NoticeEntity/Dao/Repository/NoticesDatabase   Room v2
             InfoRepository        /info, cached in SharedPreferences
fcm/         NoticeMessagingService  the delivery gate
             NoticeImageStore        download, downsample, prune
             PdfPageRenderer         PDF page 1 → letterboxed bitmap (pure geometry, tested)
             NoticeNotifications     channels + tray UI
ui/          ActivationScreen  NoticeListScreen  SettingsScreen  NoticeViewerScreen
             NoticeViewModel   BodyText (tested)  LinkedText  CodeDashTransformation
             NotificationAccess  ShareText (tested)  theme/
```

**Tests:** 67 JVM unit tests. `GeneratedCodeCompatibilityTest` is the important one — it pins the
Kotlin validator against codes produced by the JavaScript generator. If those two drift, every
printed slip is rejected on every phone with a message blaming the user for mistyping, discovered
at a counter by someone in their eighties.

### 2.2 Console (Apps Script) — `docs/apps-script/console/`

Generates codes, prints cut-out slips, revokes, restores, releases, per-code notes, the office-hours
banner, and the saved messages the sender offers. The codes table sorts on every column, pages
locally, and shows each code's last change with its full history.

Script Properties: `SERVICE_ACCOUNT_JSON`, `DATABASE_URL`, `ALLOWED_EDITORS`. `Tests.gs` covers the
pure helpers only.

Revoke writes `/revokeQueue/{CODE}`; the sender's trigger broadcasts it. Restore and Release both
delete any pending entry, so a revoke cannot fire for a code that is live again or back in the pool.
If that trigger is not installed, Revoke still works — it is recorded in `/codes` and `/audit`, and
phones act on it at their next daily check. Only the speed depends on it.

### 2.3 Sender (Apps Script) — `docs/apps-script/sender/`

`Code.gs`, `Index.html` (compose), `Status.html` (QR confirmation), `Poller.gs` (RSS trigger),
`Tests.gs`. Script Properties: `SERVICE_ACCOUNT_JSON`, `DATABASE_URL`, `PROJECT_ID`, `STAFF_PIN`,
`ALLOWED_EDITORS`, optionally `REQUIRE_STAFF_PIN` and `ALERTS_FEED_URL`.

Deployed **Execute as: User accessing** / **Anyone with a Google account** — *not* "Anyone with the
link", which this document claimed until the QR path was retired. `requireEditor_()` refuses an
address outside `ALLOWED_EDITORS`, and returns the caller's email so every send records `sentBy`.

**There is no `doPost`.** The sender exposes no POST surface at all; `/exec` answers `doGet` only.

`Revoker.gs` broadcasts revocations for the console on a **one-minute trigger**: it reads
`/revokeQueue`, re-reads each code to confirm it is still revoked, calls `pushRevoke_`, and deletes
the entry only after FCM accepts it. Install with `revokerInstallTrigger()`; inspect with
`revokerDryRun()`, which sends nothing.

An earlier design had the console POST here with the staff member's OAuth token. It returns HTTP 401
on every request — `ScriptApp.getOAuthToken()` cannot authorize a call into another project's web
app — and no manifest scope fixes it. **Do not re-investigate the 401**; the evidence is in
`docs/decisions.md`.

Separate project from the console so issuing codes and sending messages are separate jobs.

### 2.4 Privacy policy and contact

`docs/privacy-policy.md` is the source text, published at
https://paramanuseniorshealth.org/privacy-policy-app/ -- a separate page from the site's own
`/privacy-policy`, which covers the website only. Play requires a hosted URL; an in-app page does
not satisfy it. Settings links to it, alongside the organisation's address, phone and email taken
from paramanuseniorshealth.org/contact/.

Every claim in the policy was checked against the code, not asserted. If the app starts collecting
anything -- the aggregate topic counters discussed for a later release included -- the policy and
the Play Data Safety form both need revisiting.

### 2.5 bit.ly QR links — RETIRED, pending removal

`bit.ly/dispensary-closed` → `<sender>/exec?status=closed`, and an equivalent for `open`.

The short link is indirection that makes the QR reprintable: if the deployment id changes, repoint
the bit.ly rather than reprinting the poster. **The QR carries no authority** — scanning only opens
a confirmation page; sending needs the PIN.

**The NGO no longer wants the QR.** Daily status goes out through the sender's saved-message
buttons instead. The code is still present and callable — `doGet(?status=)`, `sendStatus`,
`Status.html` — and removing it is separate work that has not been done, so this section stays until
it is.

---

## 3. Data model (RTDB)

```
/codes/{CODE}       issued, usedBy, activatedAt, revoked, note
/audit/{CODE}/{id}  at, by, event, to                  (console only; phones cannot read it)
/revokeQueue/{CODE} at, by                             (console writes, sender's trigger drains)
/templates/{id}     label, title, body, updated       (console-managed, app cannot read)
/sent/{logId}       title, body, category, sentAt, sentBy, fcmName, error
/info               heading, lines                     (app reads; "lines" is ONE newline string)
```

`/audit` is append-only and deliberately **not** part of `/codes`. A new field on `/codes/{CODE}`
needs its own `.validate` rule or the `$other: false` catch-all refuses the app's claim write for
every unclaimed slip — which is what adding `note` did once (§6a). Keeping the history in a separate
node means it needs no rules change at all.

`event` is one of `issued`, `printed`, `revoked`, `restored`, `released`, `note`, `deleted`. An
eighth, `claimed`, is **synthesised** by the console from `activatedAt` rather than stored: the
phone cannot write to `/audit` and must not be able to. Codes issued before this existed are not
backfilled, so they read as never printed.

The audit is also where the **printed** state lives, as `codeIsPrinted_` replaying the timeline —
`printed` sets it, `released` clears it. Deriving it costs nothing (the timeline is already built
for the table) and avoids a new field on `/codes`.

A `deleted` code keeps its `/audit` node after `/codes` is gone, so nothing lists it any more but
the history remains for anyone who looks the code up directly.

Rules (`database.rules.json`): deny by default. `/codes/$code` is readable and claim-writable by an
authenticated user; `/codes` itself is **not** readable, so codes cannot be enumerated. `/info` is
readable. `/templates` and `/sent` are invisible to phones.

---

## 4. Message contract

```json
{ "message": { "topic": "notices-v1", "android": { "priority": "high" },
  "data": { "logId": "1788248869397", "title": "...", "body": "...",
            "category": "NOTICES", "imageUrl": "...", "pdfUrl": "..." } } }
```

- **Never include a `notification` block.** See quirk 5.1.
- All values must be strings; omit a key rather than sending null.
- `logId` is epoch millis: idempotency key *and* the notice's timestamp.
- `imageUrl` and `pdfUrl` are not alternatives; both may be present.

---

## 5. Quirks and traps

These each cost real time. They are written down so they cost it once.

### 5.1 A `notification` block silently disables everything
With one present, the FCM SDK draws the tray notification itself while backgrounded,
`onMessageReceived` never runs, and three things stop happening at once: the entitlement gate, the
Room write, and the artwork fetch. It presents as "notifications work but the app is empty".

### 5.2 `/exec` serves the deployed version, never HEAD
Every script edit needs an explicit new version. `testWebhook`-style functions run from the editor
execute HEAD and therefore **cannot detect a stale deployment** — they report success while `/exec`
serves months-old code. Prefer *Manage deployments → edit → New version* over a new deployment, so
the URL stays stable and the QR never needs reprinting.

### 5.3 Scriptlet delimiters inside an HTML comment break the template
Apps Script scans the whole template for `<?` … `?>` and does **not** skip HTML comments. A comment
mentioning them literally compiles to an empty scriptlet and throws
`SyntaxError: Unexpected token ';'` — attributed to generated code, so the reported file and line
point at neither the template nor the fault. This is what broke the QR page for hours.

### 5.4 Contextual escaping breaks values injected into `<script>`
`<?= JSON.stringify(x) ?>` inside a script block arrives still carrying its quote characters.
Pass values through a `data-` attribute and read them with `getAttribute` instead.

### 5.5 `UrlFetchApp` rejects raw quotes in a URL
RTDB wants `orderBy` as a JSON string, so the quotes are part of the value and must be
percent-encoded. Unencoded, the failure is `Invalid argument`, which reads like a credentials
problem.

### 5.6 Import JSON is a replace, not a merge
Importing at the root **deletes everything else**, including the `usedBy` claims live phones depend
on. This has happened once. Select the child node first. `docs/rtdb-rules-explained.md` has the
recovery procedure, including recovering a device's UID with
`adb shell run-as … cat shared_prefs/activation.xml`.

### 5.7 Losing local storage orphans a claim
The phone stores the code and anonymous UID locally and they are its only proof of ownership.
Uninstall, data clear or a new phone strands the code as "in use" with nobody able to claim it —
hence **Release** in the console. Releasing a code a working phone still holds cuts that phone off.

### 5.8 `currentUser != null` proves nothing
A cached ID token stays valid after the anonymous user is deleted. Only
`getIdToken(forceRefresh = true)` discovers it.

### 5.9 Notification bitmaps cross a ~1MB Binder transaction
`ARGB_8888` is 4 bytes/pixel, so 1024×768 alone is 3MB and throws `TransactionTooLargeException`,
losing the **whole notification**, not just the picture. Renders are 800×400 `RGB_565` (640KB).

### 5.10 `BigPictureStyle` centre-crops
It wants 2:1 landscape; notices are A4 portrait. Uncorrected it removes the letterhead and the
signature — the parts that make a notice look official. `PdfPageRenderer` letterboxes deliberately.

### 5.11 A missing `values-night` theme gives white-on-white
Compose followed the system into dark mode while the window stayed pinned to the Light platform
theme. Fixed with a night variant *and* a `Surface` wrapper so Compose paints the background itself.

### 5.12 `Date.now()` alone collides
Two sends inside one millisecond meant Room's unique index silently dropped the second — the notice
vanished with no error anywhere. `nextLogId_()` increments past any id already in `/sent`.

### 5.13 Crockford Base32 excludes I, L, O, U
`normalise()` folds `O`→`0` and `I`/`L`→`1`. A payload containing `L` has no check character at all.

### 5.14 RTDB allows 100 *simultaneous* connections, and this is a broadcast app — FIXED
The limit is about concurrency, not users. When a notice reached 400 phones they all woke at once
and every one called `verify()`, opening an RTDB websocket — roughly 400 connections against a cap
of 100, on every send. Connections past the limit are refused.

It degraded gracefully only by accident: a refused connection throws, which became
`ActivationState.Unknown`, which permits delivery. So users saw everything and nothing looked broken
— but **the entitlement gate stopped working during exactly the event it exists for**.

**Fixed.** The gate now answers from persisted state with no network at all
(`ActivationRepository.gate`), and the network half runs in `ActivationRefreshWorker`, jittered
across 0–15 minutes and calling `goOffline()` so the socket is not held open idling for a minute
afterwards. Steady state is about one connection per device per day. See `docs/decisions.md`.

Keep this entry: the trap returns the moment anything calls `verify()` from `onMessageReceived`.

### 5.15 A Play category can drag in a whole declaration regime
Choosing News and Magazines produced a journalism questionnaire (editorial guidelines, standards
bodies, awards) and then a policy violation for having no news articles. Categories are not just
shelf labels; some of them carry attestations. Communication and Productivity carry none, Medical
and Health & Fitness carry the health-apps declaration. Pick for what the app *is*, and read what
the choice pulls in before answering anything.

### 5.16 WebFetch caches for 15 minutes
Re-fetching the same URL to check a redeploy can return a stale response and send you chasing a
problem that is already fixed.

---

## 6. Behavioural decisions worth preserving

- **`Unknown` entitlement permits delivery.** Only a definitive `Revoked` suppresses a notice.
  Failing closed would mean someone in their eighties misses a closure notice because of a weak
  signal — worse than a revoked device seeing one more public announcement.
- **Revocation is pushed, and cooperative.** The console asks the sender to broadcast a data-only
  `{"type":"revoke","code":…}`; the holding device applies it on receipt, within seconds. It remains
  cooperative — the payload reaches the device and the app declines to act — and it is still **not
  access control**. Every notice is public anyway. A daily verification catches any phone that was
  switched off when the broadcast went out.
- **Revocation suspends the claim; it does not surrender it.** The code and anonymous UID are kept,
  so a console **Restore** brings the device back on its own. Discarding them stranded the user
  permanently: the rules refuse to write `usedBy` on a code that already carries one, so the same
  slip could never be retyped and Restore had nothing to restore.
- **A revoked user keeps their notices**, behind a banner saying no more will arrive and to call the
  helpdesk with their code. Hiding them protected nothing — the code screen was lifted by *any*
  valid slip, not just their own — and it cost them everything they had already received.
- **The PIN is a second factor, not the only one.** Every entry point calls `requireEditor_()`
  first, and the web apps are deployed "Anyone with a Google account" against an `ALLOWED_EDITORS`
  allowlist. `REQUIRE_STAFF_PIN=false` drops the PIN and relies on the allowlist alone.
- **The console has no FCM credentials and is not given any.** It asks the sender to broadcast a
  revoke over HTTPS, forwarding the staff member's own OAuth token so the sender checks the same
  allowlist. Issuing codes and reaching 400 phones stay separate jobs (§2.3).
- **The PIN is never stored in the browser.** Typed per send, on purpose.
- **Dynamic colour is off.** It derives the palette from the wallpaper; contrast is not negotiable
  for this audience.
- **Turning notices off keeps the claim.** Surrendering it would be irreversible — the code is
  already used and a fresh anonymous sign-in gets a different UID.
- **Text is mandatory, artwork is not.** An A4 page in a notification is ~4pt text.
- **A welcome notice is written locally on activation.** Without it a new user types a code and gets
  an empty screen, which reads as failure rather than as "no news yet" -- and it is what a Play
  reviewer sees, which invites a minimum-functionality flag. Id `local-welcome`, never pushed.
- **The testing channel is created lazily.** Android lists every channel an app has ever created in
  system settings, so creating it eagerly would put a "Testing" entry in four hundred people's
  notification settings. It appears only after the hidden unlock (seven taps on the version number
  in Settings) or on a device already subscribed.
- **The test button proves the channel, not delivery.** It posts locally. It answers "can this app
  notify me?", which is the failure users actually hit; it does not exercise FCM.
- **No entitlement layer.** Every code is equal. The NGO does not provision which topics a code
  may receive; the user alone chooses in Settings. Considered and rejected: provisioning at the
  counter means staff conducting a preferences interview with an 85-year-old while a queue forms.
- **Aggregate counters, never per-user preferences.** For learning what people actually want,
  the agreed shape is a single integer per topic — no identifier, nothing that maps back to a
  person. Not yet built. Note FCM itself reports nothing useful here: campaign reporting only
  covers `notification` payloads sent from the composer, and this app is data-only by necessity.
- **The store title, the launcher label and the privacy policy must name the same app.** They
  disagreed once ("Paramanu Notices" in the app, "Paramanu Seniors Notices" in the Console).
  Harmless in isolation, but a user told one name at the counter who sees another on their home
  screen has a real "is this the right app?" moment. The list header now says **Notices** — a screen
  title, not a third repetition of the app name.
- **No advertising ID, enforced in the manifest.** `firebase-analytics` was removed (it pulled in
  `AD_ID`, which would have forced a "yes" on the Data Safety advertising-ID question and
  contradicted the listing), and the permission is additionally stripped with `tools:node="remove"`
  so a transitive dependency cannot quietly reintroduce it. Release manifest holds six permissions:
  INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, WAKE_LOCK, C2DM RECEIVE, READ_GSERVICES.

---

## 6a. Datastore: RTDB was inherited, not chosen

RTDB came from the Notifier fork and was never justified against Firestore. Firestore would suffice
and is better on four specific points:

1. **No simultaneous-connection cap** — see quirk 5.14, which RTDB is already over on every send.
2. **`affectedKeys().hasOnly(['usedBy','activatedAt'])`** says "the app may only ever change these
   two fields", covering every field added later. RTDB needs a `.validate` per child plus an
   `$other: false` catch-all, which is why adding `note` silently broke the claim write.
3. **`allow list: if false`** is explicit. In RTDB, "codes cannot be enumerated" is an emergent
   consequence of granting read on the child but not the parent — true, subtle, easy to break.
4. **Counter deltas can be bounded in rules** (`count == resource.data.count + 1`), so a
   compromised client cannot set a stat to 40,000. RTDB transactions give atomicity but no bound.

Also: Firestore has a Mumbai region; RTDB has no India region at all.

**Do not migrate for its own sake.** Live claims are in RTDB, the awkward parts are documented and
tested, and moving `/codes` with `usedBy` intact is the kind of migration that silently revokes
every installed phone if it goes wrong. **Do migrate if dynamic topics get built** — that work
touches the data model anyway, and reason 2 above stops being theoretical the moment a `topics` map
starts growing on the code node.

---

## 7. UI

```
Activation (PIN gate)  ── first launch and after reset only
        │  code redeemed
        ▼
Notice list  ── office-hours header (links tappable) + notice cards
        │           tap a card      → expands inline: linked body, image, PDF render
        │           long-press      → selection mode (delete)
        │           tap artwork     → full-screen zoomable viewer
        │           cog, top right  → only when nothing is selected
        ▼
Settings  ── What you receive (Notices / Daily open and closed / Testing when unlocked)
            Check notifications (posts a local test)
            Your code
            Reset this app
            Contact: address, tappable phone and email, website, privacy policy
            Version (seven taps unlocks Testing)
```

Deliberate: 28sp monospace code field with an auto `XXXX-XXXX` dash; large type throughout,
following the system font scale; permission requested only on reaching the notice list, never on
the code screen.

---

## 8. Done

- Android app, forked from `Notifier`, renamed and rebuilt around activation + subscriptions.
- Activation via RTDB security rules as the validator — no backend, project stays on Spark.
- Attachments (image + PDF), inline expansion, tap-to-highlight, link detection.
- Office-hours header from `/info`, cached offline, links tappable.
- Three subscriptions/topics/channels: NOTICES (high), STATUS (low), TESTING (low, lazily
  created, hidden behind seven taps on the version number).
- Welcome notice written locally on activation, so the list is never empty.
- "Check notifications" button, contact section, privacy policy link.
- Analytics and `AD_ID` removed; six permissions in the release manifest.
- Console: codes, slips, notes, revoke, restore, release, saved messages, sortable paged table,
  per-code audit history.
- Sender: compose with live notification preview, saved-message chips, PIN, QR status pair,
  `sentBy` attribution, and the revoke endpoint the console calls.
- Pushed revocation applied on receipt; entitlement verification moved off the message path into
  WorkManager; a revoked device keeps its notices behind a banner and recovers on Restore.
- 67 Android unit tests. Apps Script tests run from the editor (`runAllTests` in the sender,
  `runConsoleTests` in the console) and have **not** been run since these changes — see §9.
- Play assets: 512 icon, 1024×500 feature graphic, 4 screenshots, listing copy.
- Verified end to end on an SM-S928U1 (Android 16) at `versionCode 3`: notices delivered, office
  header renders, dark and light themes correct, dash formatting and keyboard behaviour correct.

---

## 9. Pending

**Before production**

0. **Deploy and verify the audit / revocation work.** All of it is written and the Android half is
   covered by unit tests, but none of it has run against Google's infrastructure or on a phone:
   - ~~Paste `console/Tests.gs`, run `runConsoleTests`~~ — **done, `All 34 passed.`**
   - Paste `sender/Revoker.gs`, run `revokerDryRun()` (sends nothing), then
     `revokerInstallTrigger()`. No new Script Properties, and no redeploy needed — a trigger is not
     served by `/exec`.
   - Sender: run `runAllTests`. Redeploy only if `Code.gs` changed; `doPost` has been removed, so
     the web app now answers `doGet` alone.
   - Revoke a test code and watch `/revokeQueue`: the entry should appear and be gone within a
     minute. Then revoke another and Restore it immediately — no revoke should be broadcast at all.
   - Issue two codes, then revoke / restore / release / re-note them and check the history renders
     in order with the right email on each.
   - On a phone: activate, revoke from the console, confirm the tray notification arrives within
     seconds, the banner appears above the office hours with the dashed code, the old notices are
     still listed, and a notice sent while revoked does not appear. Then Restore and confirm
     delivery resumes and the banner and its history row both disappear.
   - Confirm a *fresh* code can still be claimed. Nothing here touches `/codes` or the rules, but
     activation is what this project has broken before and it costs one slip to be sure.

1. ~~**Publish `docs/privacy-policy.md`**~~ — **done.** Live at
   paramanuseniorshealth.org/privacy-policy-app/, alongside the website-only policy. Keep both.
2. ~~**Upload and resubmit.**~~ — **done.** Approved in closed testing.
3. **Device-verify `versionCode 4`.** The welcome notice, the test button, the contact links and the
   absence of a "Testing" entry in system notification settings have **never run on hardware** — the
   phone was off the bridge when they were written.
4. **Recreate `/codes/P1AYREVQ` after the launch-day wipe**, and confirm it is unclaimed. It was
   destroyed by a root import once already, and the wipe will remove it again — see the wipe
   runbook in §10. If the reviewer cannot get past the code screen, nothing else matters.
5. **App access code for review:** `P1AYREVQ` is reserved and must never be handed out, printed on
   a slip, or deleted from the console. It has no repeated characters, so the generator cannot
   recreate it; it is hand-written or it is gone.
6. **Written authorisation from the NGO** before `BARC` appears in listing text.
7. **Real office hours** in `/info` — currently placeholders.
8. **Restrict console access** to named people; Script Properties are readable by any editor, so
   the editor list *is* the list of people who can send.
9. **Separate Google account** for the console.

**Agreed but not built — dynamic topics and counters**

The brainstorm settled that there is **no entitlement layer**: every code is equal, and the user
alone chooses what to receive. That is what the code already does, so nothing was needed. What is
*not* built is making topics data-driven (a `/topics` config, a Settings screen rendered from it,
labels fetched rather than compiled in). Until that lands, adding a topic needs an app release.
`Subscription` is still an enum: NOTICES, STATUS, TESTING.

Also agreed and not built: the aggregate topic counters. See section 6a for why both should land on
Firestore rather than RTDB if they are built.

**Design decisions still open**

10. **Multiple dispensaries.** The daily status is one global topic saying "the dispensary is open",
   which is ambiguous once there is more than one, and the QR encodes no site. Either per-site
   topics (correct, but topics are effectively permanent once phones subscribe) or one topic with
   the site named in the text and `?status=closed&site=…` in the QR. **Settle before production.**
11. **STATUS uptake.** Off by default and daily; realistically nobody finds the setting. The fix is
   to offer it at the counter when the slip is handed over.
12. **Three-screen idea** (Notifications / Notices / Home). Notes: the site has **no RSS feed**, so
   a feed reader has nothing to read; a WebView "Home" risks Play's minimum-functionality policy;
   and "Notifications" vs "Notices" is not a distinction a user can act on.

**Smaller**

13. Verify `Subscription.STATUS` really defaults to off — it appeared **on** in Settings on a fresh
    activation and was never confirmed as a manual toggle.
14. Confirm whether the two STATUS-titled notices in history arrived via the QR path; if they did
    while STATUS was off, the delivery gate has a hole.
15. Dark-mode logo treatment (the transparent PNG helps, but the mark still assumes a light ground).
16. `/info` editor in the console — currently hand-edited in the Firebase console.
17. Sender cannot yet attach an image or PDF; text only.
18. Wrap each Apps Script test in its own try/catch so one failure does not abort the suite.

---

## 10. Runbooks

**Issue codes** — console → count + optional note → Generate → Print slips.

Printing covers every code that is unclaimed, unrevoked **and not yet printed**, across all pages,
not only the page on screen. After the print dialog closes the console asks whether the slips came
out; saying yes marks them `printed`, so the next run does not repeat them. Saying no leaves them
unprinted and they come out again — the right way round, since an unmarked printed slip only wastes
paper whereas a marked unprinted one goes quietly missing from every future run.

Each slip carries the code at 30pt monospace, two lines of instruction, and a **15mm QR to the Play
listing** with "No app yet? Scan to install it." The QR is a base64 PNG embedded in `Index.html`,
not fetched from a chart service: the URL never changes, and a slip printed against a service that
happened to be slow would come out with an empty box that nobody notices until the sheets are cut
up. At 41 modules, 15mm gives 0.37mm per module — just above the ~0.33mm floor for reliable
scanning, and the smallest size that still decoded with blur and noise added.

**Printed** is a fourth state alongside Unused, In use and Revoked, and it is derived from the audit
rather than stored on `/codes` — a new sibling field there would break the app's claim write (§6a).
A **Release** clears it: the code returns to the pool for somebody new, who needs a fresh slip.
Tick "include already printed" to reprint a lost one.

Generated payloads always contain **at least two different characters that each appear more than
once** — `SCCM-7VV7`, `0V0J-RYRJ`, `AEE5-D1D6` — so staff have something to hold on to when reading
one across a counter or back over the phone. Adjacency is not required and is not the point.

An earlier version forced a contiguous run of three instead. It produced `GGGW-YBHZ` and
`EWNN-NMGA`: one repeated character, six unrelated ones, and a run that invites "was that two Gs or
three?". Staff rejected them on sight. Do not go back to it.

The generator rejection-samples rather than constructing a pattern, so every allowed payload stays
equally likely; about one draw in twelve qualifies. Only the sampling changed — the check-character
rule is untouched, so codes issued before this are still valid and the codes pinned in
`GeneratedCodeCompatibilityTest` must not be regenerated. It narrows the payload space from ~34
billion to ~2.9 billion, which against 400 live codes and an unlistable `/codes` is not a guessing
risk.

**Send a notice** — sender → pick a saved message or write one → check the preview → PIN → Send.

**Daily status** — scan the counter QR → confirm → PIN. A repeat within 10 minutes is refused.

**Remove codes generated by mistake** — console → Delete, offered only on a code nobody has ever
claimed. `deleteCode` refuses a claimed one regardless of what the page shows: deleting it would cut
that phone off at its next check with no explanation, since a missing node reads as a withdrawn
claim. Use Revoke for that instead, which is deliberate and reversible.

**Unclaimed is not unprinted.** A slip for the deleted code may already be in somebody's pocket, and
they will be told at the counter that their code was not accepted. The `/audit` entry is kept when
the code is deleted — it is then the only record the code ever existed, and the only way to answer
"why did this slip stop working".

**Cut a phone off** — console → Revoke. The phone applies it within seconds of the push, keeps its
notices behind a banner telling the user to call the helpdesk with their code, and stops receiving.
The code stays claimed by that device and cannot be handed to anyone else.

**Let somebody back in** — console → Restore, on the *same* code. The phone resumes on its own: at
once if the app is opened, otherwise within a day. Do not issue a fresh slip for this; a revoked
code is not consumed, and the user keeps their history. For a phone that is genuinely gone —
uninstalled, replaced, data cleared — use Release instead, which returns the code to the pool.

**Read a code's history** — console → the Last change column, then `history` on the row. Every
issue, revoke, restore, release and note change, with who did it and when.

**Wipe the slate for the production launch** — there is no test environment by choice; production
*is* the environment, and the only isolation is `TEST_TOPIC` for the Apps Script suite. So launch
day means clearing the testing data out of the live database.

**Never import at the root.** That is quirk 5.6, and it has already destroyed `/codes` here once,
including the reserved review code. Delete these four child nodes individually in the data viewer:

| Clear | Keep |
|---|---|
| `/codes` — all test codes and claims | `/info` — office hours (§9 still wants the real ones) |
| `/audit` — their history | `/templates` — saved messages staff have built up |
| `/revokeQueue` — any pending pushes | `/status` — the daily open/closed wording |
| `/sent` — the test broadcast log | |

Then, **before generating anything**, recreate the Play review code by hand. Select `/codes` — not
the root — and Import JSON:

```json
{ "P1AYREVQ": { "issued": 1757000000000 } }
```

It must exist and be unclaimed or the reviewer cannot get past the code screen and nothing else in
the submission matters. It has to be hand-written: `P1AYREVQ` has no repeated characters, so the
generator will never produce it, and `Delete` in the console would remove it as easily as any other
unused code.

**What the 14 testers will see.** Clearing `/codes` withdraws every claim, so each tester's next
check finds no node, reads it as revoked, and raises the banner telling them to ring the helpdesk.
They are not stuck — Settings → **Enter a new code** takes a fresh slip and keeps their notice
history — but tell them first, or they will do what the banner says.

A `/revokeQueue` entry missed in the wipe is harmless: the drain re-reads `/codes` and drops
anything that is no longer revoked rather than broadcasting it.

**Change the office hours** — select `/info` in the Firebase data viewer, then Import JSON with the
**inner object only** (`docs/info-node.json`). Never import at the root.

**Deploy rules** — `firebase deploy --only database`, or paste `database.rules.json` into the Rules
tab. Rules-only; it does not touch data.

**Ship an app update** — bump `versionCode`, `./gradlew :app:bundleRelease`, upload to the closed
testing track.
