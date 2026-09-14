# Paramanu Seniors Notices — system reference

Everything needed to pick this project up cold: how the pieces fit, what is done, what is not, and
the traps that cost real time to find.

**Status (2026-09-13):** **approved and live in Play closed testing, with 14 testers.** The privacy
policy rejections are resolved. Source is at `versionCode 9`, `versionName 0.5.0` — dispensaries and
their topics, the control topic (pushed revoke, resume and banner), sent time in the tray, and Reset
removed. **Breaking:** it needs the new database shape, rules and scripts (§10, "Move to
dispensaries"); phones on 0.4.0 stop receiving until they update.

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
│  disable / unlink  │      │  RSS poller          │                    │
│  holders, notes    │      │  PIN on every send   │                    │
│  saved messages    │      │                      │                    │
└─────────┬──────────┘      └──────────┬───────────┘                    │
          │ service account            │ service account                │
          ▼                            ▼                                │
    ┌─────────────────────────────────────────────┐                     │
    │  FIREBASE  paramanu-seniors                 │                     │
    │                                             │                     │
    │  RTDB   /codes  /dispensaries  /sent        │                     │
    │  Auth   anonymous only                      │                     │
    │  FCM    notices-v1, control-v1              │                     │
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
             Dispensary            the topics the code's dispensary offers (pure, tested)
data/        NoticeEntity/Dao/Repository/NoticesDatabase   Room v2
             DispensaryRepository  /dispensaries/{id}: topics and banner in one read, cached
             InfoRepository        the banner, cached; also applies pushed banners
fcm/         NoticeMessagingService  the delivery gate
             NoticeImageStore        download, downsample, prune
             PdfPageRenderer         PDF page 1 → letterboxed bitmap (pure geometry, tested)
             NoticeNotifications     channels + tray UI
ui/          ActivationScreen  NoticeListScreen  SettingsScreen  NoticeViewerScreen
             NoticeViewModel   BodyText (tested)  LinkedText  CodeDashTransformation
             NotificationAccess  ShareText (tested)  theme/
```

**Tests:** 84 JVM unit tests. `GeneratedCodeCompatibilityTest` is the important one — it pins the
Kotlin validator against codes produced by the JavaScript generator. If those two drift, every
printed slip is rejected on every phone with a message blaming the user for mistyping, discovered
at a counter by someone in their eighties.

### 2.2 Console (Apps Script) — `docs/apps-script/console/`

Generates codes, prints cut-out slips, disables, enables and unlinks phones, per-code notes, holders
(name, CHSS number, telephone), the office-hours banner, and the saved messages the sender offers.
The codes table sorts on every column, pages locally, and shows each code's last change with its
full history.

Script Properties: `SERVICE_ACCOUNT_JSON`, `DATABASE_URL`, `ALLOWED_EDITORS`, and optionally
`PRINTING_ENABLED` (`false` disables the Print controls; the Printed status is still derived and
shown). `Tests.gs` covers the pure helpers only. `purgeInstallTrigger()` installs the 30-day holder
purge; `purgeDryRun()` lists what it would erase.

**Names on the page are not the stored names.** Staff see Disable, Enable and Unlink phone; `/codes`
still carries `revoked`, the audit still records `revoked`, `restored` and `released`, and the push
is still `revoke` / `resume`. Only words changed, so the app and the rules did not have to.

**Statuses:** Unused, Printed, In use, Disabled, **On hold**. On hold means the holder was removed:
the code is stopped (`revoked: true`, so to the phone it is simply revoked) and the console refuses
every action on it until the holder is restored. Restore puts the code back as it was, Disabled
included. The purge erases a holder 30–60 days after removal and returns the code to Unused.

Disable, Enable, removing or restoring a holder, and banner changes write `/controlQueue`; the
sender's trigger broadcasts them on `control-v1` (§2.3). Unlink phone, Delete code and the purge
clear anything pending, so nothing fires at the next phone to type the code. If that trigger is not installed, all three still work — they are recorded in the
database, and phones catch up at their next daily check or app open. Only the speed depends on it.

The codes table has a search box (a code with or without its dash, a name, CHSS number, telephone
digits, or note text) and a Status filter in the column header. Neither affects printing, which always covers every eligible code.

### 2.3 Sender (Apps Script) — `docs/apps-script/sender/`

`Code.gs`, `Index.html` (compose), `Poller.gs` (RSS trigger),
`Control.gs` (control queue trigger), `Tests.gs`. Script Properties: `SERVICE_ACCOUNT_JSON`, `DATABASE_URL`, `PROJECT_ID`, `STAFF_PIN`,
`ALLOWED_EDITORS`, `DISPENSARY_ID`, optionally `REQUIRE_STAFF_PIN` and `ALERTS_FEED_URL`. Notices,
from the compose page and the poller alike, go to the dispensary's first topic by `order` — read from
`/dispensaries/{DISPENSARY_ID}/topics` at send time. There is no topic picker and no status route.

Deployed **Execute as: User accessing** / **Anyone with a Google account** — *not* "Anyone with the
link", which this document claimed until the QR path was retired. `requireEditor_()` refuses an
address outside `ALLOWED_EDITORS`, and returns the caller's email so every send records `sentBy`.

**There is no `doPost`.** The sender exposes no POST surface at all; `/exec` answers `doGet` only.

`Control.gs` broadcasts for the console on a **one-minute trigger**: it reads `/controlQueue`,
re-reads the code or the dispensary's banner to confirm the request still applies, calls `pushControl_`, and clears
the entry only after FCM accepts it — and only if the
console has not replaced it in the meantime. Install with `controlInstallTrigger()`, which also
removes the old `revokerRun` trigger; inspect with `controlDryRun()`, which sends nothing. It replaced
`Revoker.gs`, which must be deleted from the project.

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

### 2.5 Dispensaries

Every code belongs to one dispensary: the console writes `/codes/{CODE}/dispensary` from its
`DISPENSARY_ID` Script Property when the slip is generated. The dispensary's record at
`/dispensaries/{id}` holds its `name`, the `topics` it offers, and its banner under `info`. There is
one today, **BARC Vashi Dispensary** (`barc-vashi`), seeded from `docs/dispensary-barc-vashi.json`.

The phone reads the code's dispensary during verification and the record on activation, on coming
to the foreground and at the daily check — never from the message path. **Settings → What you
receive** is drawn from the record's topics, so a dispensary can offer a new topic, or retire one,
without an app release; the phone remembers what it subscribed to and unsubscribes what is no longer
offered. Notification channels are one per importance (`notices_default` high, `notices_low` low,
created on first use), not one per topic, so data can never add entries to Android's settings.

A second dispensary is a new record and a new console and sender deployment with its own
`DISPENSARY_ID`. The phone side needs nothing. This is what the offering controls, not what a phone
could technically receive: FCM topics cannot be locked down, the same cooperative model as revoke.

The daily open and closed messages, the QR route that sent them and `status-v1` are **gone**, with no
fallback. Phones still on an older build keep a dangling `status-v1` subscription that nothing sends
to.

---

## 3. Data model (RTDB)

```
/codes/{CODE}       issued, usedBy, activatedAt, revoked, note, dispensary
/audit/{CODE}/{id}  at, by, event, to, holder          (console only; phones cannot read it)
/holders/{id}       code, name, chss, phone, createdAt, createdBy,
                    removedAt, removedBy, wasDisabled   (console only; erased by the purge)
/holders/{id}/history/{id}  at, by, event, changes {field: {from, to}}
/controlQueue/code_{CODE}  type, code, at, by          (console writes, sender's trigger drains)
/controlQueue/banner       at, by
/templates/{id}     label, title, body, updated       (console-managed, app cannot read)
/sent/{logId}       title, body, topic, dispensary, sentAt, sentBy, fcmName, error
/dispensaries/{id}  name, topics/{key}: topic, label, explainer, defaultOn, importance, order
/dispensaries/{id}/info  heading, lines, html, htmlUpdated   (app reads; "lines" is ONE newline string)
```

`/audit` is append-only and deliberately **not** part of `/codes`. A new field on `/codes/{CODE}`
needs its own `.validate` rule or the `$other: false` catch-all refuses the app's claim write for
every unclaimed slip — which is what adding `note` did once (§6a). Keeping the history in a separate
node means it needs no rules change at all.

`event` is one of `issued`, `printed`, `revoked`, `restored`, `released`, `note`, `deleted`, or a
holder event: `holder-added`, `holder-edited`, `holder-moved-in`, `holder-moved-out`,
`holder-removed`, `holder-restored`, `holder-purged`. Holder events carry only the holder's id (and
for a move, the other code in `to`) — never a name or number, because `/audit` is never erased and
the purge has to be able to remove a person. One more, `claimed`, is **synthesised** by the console from `activatedAt` rather than stored: the
phone cannot write to `/audit` and must not be able to. Codes issued before this existed are not
backfilled, so they read as never printed.

The audit is also where the **printed** state lives, as `codeIsPrinted_` replaying the timeline —
`printed` sets it, `holder-purged` clears it. Unlink phone does **not** clear it: the slip still
belongs to the holder, who is usually the one on the new phone. **On hold** is derived the same way
by `codeIsOnHold_`: `holder-removed` sets it, `holder-restored` and `holder-purged` clear it. Deriving it costs nothing (the timeline is already built
for the table) and avoids a new field on `/codes`.

A `deleted` code keeps its `/audit` node after `/codes` is gone, so nothing lists it any more but
the history remains for anyone who looks the code up directly.

Rules (`database.rules.json`): deny by default. `/codes/$code` is readable and claim-writable by an
authenticated user; `/codes` itself is **not** readable, so codes cannot be enumerated. `/dispensaries/{id}` is
readable. `/templates`, `/sent`, `/audit`, `/controlQueue` and `/holders` are invisible to phones —
which is why holder details live in `/holders` and never on `/codes/{CODE}`, which any signed-in
phone holding the code can read.

---

## 4. Message contract

```json
{ "message": { "topic": "notices-v1", "android": { "priority": "high" },
  "data": { "logId": "1788248869397", "title": "...", "body": "...",
            "topic": "notices-v1", "imageUrl": "...", "pdfUrl": "..." } } }
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
hence **Unlink phone** in the console. Unlinking a code a working phone still holds cuts that phone off.

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
- **Revocation and restore are both pushed, and cooperative.** The console queues a data-only
  `{"type":"revoke"|"resume","code":…,"at":…}` for `control-v1`; the holding device applies it on
  receipt, within about a minute. A revoked phone leaves every notice topic but keeps `control-v1`,
  which is what lets a resume reach it. `at` orders them: FCM does not promise order, and a phone
  ignores a stamp no newer than the last one it applied. It remains cooperative — the payload reaches
  every device and only the holder acts — and it is still **not access control**. Every notice is
  public anyway. A daily verification catches any phone that was switched off when it went out.
- **The banner is pushed with its html inside the message**, not as a prompt to fetch it: four
  hundred phones fetching at once is §5.14 again. That caps a banner at 3500 UTF-8 bytes, enforced in
  the console. The push names its dispensary and a phone applies only its own. A phone also rereads
  the dispensary when it comes to the foreground, for the one that was off when the push went out. A
  revoked phone ignores banner pushes.
- **What a phone can receive comes from its code's dispensary** (§2.5), not from the app build. Every
  notice carries `topic`; a notice on a topic the dispensary does not offer, or the user has switched
  off, is dropped.
- **Tray notifications show when a notice was sent** (`setWhen` from `logId`), not when the phone
  posted it, so a phone that came online late shows the same time as everyone else's.
- **Revocation suspends the claim; it does not surrender it.** The code and anonymous UID are kept,
  so a console **Enable** brings the device back on its own. Discarding them stranded the user
  permanently: the rules refuse to write `usedBy` on a code that already carries one, so the same
  slip could never be retyped and Restore had nothing to restore.
- **A revoked user keeps their notices**, behind a banner saying no more will arrive and to call the
  helpdesk with their code. Hiding them protected nothing — the code screen was lifted by *any*
  valid slip, not just their own — and it cost them everything they had already received.
- **The PIN is a second factor, not the only one.** Every entry point calls `requireEditor_()`
  first, and the web apps are deployed "Anyone with a Google account" against an `ALLOWED_EDITORS`
  allowlist. `REQUIRE_STAFF_PIN=false` drops the PIN and relies on the allowlist alone.
- **The console has no FCM credentials and is not given any.** It writes `/controlQueue` and the
  sender's trigger broadcasts. Issuing codes and reaching 400 phones stay separate jobs (§2.3).
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
Activation (PIN gate)  ── first launch, or a revoked user entering a new code
        │  code redeemed
        ▼
Notice list  ── office-hours header (links tappable) + notice cards
        │           tap a card      → expands inline: linked body, image, PDF render
        │           long-press      → selection mode (delete)
        │           tap artwork     → full-screen zoomable viewer
        │           cog, top right  → only when nothing is selected
        ▼
Settings  ── What you receive (the dispensary's topics / Testing when unlocked)
            Check notifications (posts a local test)
            Your code
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
- Office-hours header from `/dispensaries/{id}/info`, cached offline, links tappable, pushed live.
- Topics offered per dispensary from the database; channels per importance; TESTING (low, lazily
  created, hidden behind seven taps on the version number).
- Welcome notice written locally on activation, so the list is never empty.
- "Check notifications" button, contact section, privacy policy link.
- Analytics and `AD_ID` removed; six permissions in the release manifest.
- Console: codes, slips, notes, disable, enable, unlink phone, holders with their own history, On
  hold, the 30-day purge, search and a status filter, saved messages, sortable paged table, per-code
  audit history.
- Sender: compose with live notification preview, saved-message chips, PIN, `sentBy` attribution,
  sends to the dispensary's topic, and the control queue drain the console relies on.
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
   - Paste `console/Tests.gs`, run `runConsoleTests` — expect `All 44 passed.` (34 when it was
     first run; the code-generation and printed-state rules added ten more since.)
   - Paste `sender/Control.gs` and delete `Revoker.gs` from the project; run `controlDryRun()`
     (sends nothing), then `controlInstallTrigger()`. No new Script Properties, and no redeploy for
     the trigger — it is not served by `/exec` — but save `Code.gs` too, since the trigger calls it.
   - Sender: run `runAllTests`. Redeploy only if `Code.gs` changed; `doPost` has been removed, so
     the web app now answers `doGet` alone.
   - Revoke a test code and watch `/controlQueue`: the entry should appear and be gone within a
     minute. Then revoke another and Restore it immediately — one resume should go out, no revoke.
     Save the banner with the app open on a phone and watch the header change within a minute.
   - Issue two codes, then revoke / restore / release / re-note them and check the history renders
     in order with the right email on each.
   - On a phone: activate, Disable from the console, confirm the tray notification arrives within
     a minute, the banner appears above the office hours with the dashed code, the old notices are
     still listed, and a notice sent while disabled does not appear. Then Enable and confirm
     delivery resumes within a minute, without opening the app, and the banner and its history row
     both disappear.
   - Add a holder to that code, Remove it: the code shows On hold, every button is gone, and the
     phone stops. Restore it from Removed holders: the code comes back as it was. Repeat with the
     code Disabled first, and confirm Restore leaves it Disabled.
   - Move a holder to a fresh code: the old code shows no holder and, once its phone is unlinked,
     offers Delete code.
   - Confirm a *fresh* code can still be claimed. Nothing here touches `/codes` or the rules, but
     activation is what this project has broken before and it costs one slip to be sure.

1. ~~**Publish `docs/privacy-policy.md`**~~ — **done.** Live at
   paramanuseniorshealth.org/privacy-policy-app/, alongside the website-only policy. Keep both.
2. ~~**Upload and resubmit.**~~ — **done.** Approved in closed testing.
3. ~~**Device-verify.**~~ — **done.** `versionCode 8` was exercised on hardware before release:
   codes, revoke, restore, release, printing and the attachment work. What remains unverified is
   the Apps Script side until it is pasted and deployed — see item 0.
4. **Confirm `/codes/P1AYREVQ` exists and is unclaimed** after the launch-day clear-out. It was
   destroyed by a root import once already. If the reviewer cannot get past the code screen,
   nothing else matters.
5. **App access code for review:** `P1AYREVQ` is reserved and must never be handed out. It is left
   off the print sheet so a batch print cannot put it on a slip; keeping it out of a Delete is what
   its `note` is for. It has no repeated characters, so the generator cannot recreate it — it is
   hand-written or it is gone.
6. **Written authorisation from the NGO** before `BARC` appears in listing text.
7. **Real office hours** in `/dispensaries/barc-vashi/info` — currently placeholders.
8. **Restrict console access** to named people; Script Properties are readable by any editor, so
   the editor list *is* the list of people who can send.
9. **Separate Google account** for the console.

**Agreed but not built — aggregate topic counters**

Topics are now data-driven per dispensary (§2.5). Still agreed and not built: the aggregate topic
counters. See section 6a on where they should live if they are built.

**Design decisions still open**

10. ~~**Multiple dispensaries.**~~ **Settled** — topics per dispensary, decided by the code (§2.5).
11. ~~**STATUS uptake.**~~ **Gone** — the daily status was removed.
12. **Three-screen idea** (Notifications / Notices / Home). Notes: the site has **no RSS feed**, so
   a feed reader has nothing to read; a WebView "Home" risks Play's minimum-functionality policy;
   and "Notifications" vs "Notices" is not a distinction a user can act on.

**Smaller**

13. ~~STATUS default~~ — gone with the daily status.
14. ~~STATUS-titled notices via the QR~~ — gone with the daily status.
15. Dark-mode logo treatment (the transparent PNG helps, but the mark still assumes a light ground).
16. The banner's plain `heading` and `lines` are still hand-edited in the data viewer; only the rich
    banner has a console editor. A dispensary editor waits for a second dispensary.
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
Unlink phone does not clear it — the slip is still with its holder — and only the purge does, when
the code goes back to Unused for somebody new. Tick "include already printed" to reprint a lost one.

**Printing can be switched off** with the `PRINTING_ENABLED` Script Property set to `false`. The
Print button and the checkbox stay visible but disabled, with a line saying why; statuses are
unaffected.

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

**Remove codes generated by mistake** — console → Delete code, offered only on a code with no phone
and no holder that is not On hold. `deleteCode` refuses anything else regardless of what the page
shows: deleting a code with a phone would cut that phone off at its next check with no explanation,
since a missing node reads as a withdrawn claim. Use Disable for that instead, which is deliberate
and reversible.

**Record who a code was given to** — console → Add holder on the row: name (required), CHSS number,
telephone. The CHSS number is kept exactly as typed; a ten-digit telephone number loses spaces and a
leading +91 or 0. Edit holder changes them, and every change is in the row's history under
"Changes to …", with what it was before.

**Someone is given a new code** — Move holder on their old code, and type the new one. The old code
is left with no holder. If a phone is still on it, Unlink phone; it then offers Delete code.

**Someone stops using the service** — Remove holder. The code goes On hold: its phone stops within a
minute and every action on the code is locked. A mistake is undone from **Removed holders**, below
the codes table, with Restore holder — for 30 days. Restore puts the code back exactly as it was,
including Disabled if it was.

**The purge** — `purgeInstallTrigger()` once, from the editor. Every 30 days it erases holders
removed at least 30 days earlier: the `/holders` record goes, the code is unlinked and un-stopped
and goes back to Unused, Printed is cleared, and the note stays. `purgeDryRun()` lists what is due
and changes nothing.

**Unclaimed is not unprinted.** A slip for the deleted code may already be in somebody's pocket, and
they will be told at the counter that their code was not accepted. The `/audit` entry is kept when
the code is deleted — it is then the only record the code ever existed, and the only way to answer
"why did this slip stop working".

**Cut a phone off** — console → Disable. The phone applies it within seconds of the push, keeps its
notices behind a banner telling the user to call the helpdesk with their code, and stops receiving.
The code stays claimed by that device and cannot be handed to anyone else.

**Let somebody back in** — console → Enable, on the *same* code. The phone resumes on its own
within about a minute, pushed over `control-v1`; a phone that was switched off catches up when the
app is opened, or within a day. Do not issue a fresh slip for this; a revoked
code is not consumed, and the user keeps their history. For a phone that is genuinely gone —
uninstalled, replaced, data cleared — use Unlink phone instead, and the same code can be typed on
the new phone.

**Read a code's history** — console → the Last change column, then `history` on the row. Every
issue, disable, enable, unlink, note and holder change, with who did it and when.

**Clear the slate for the production launch** — there is no test environment by choice; production
*is* the environment, and the only isolation is `TEST_TOPIC` for the Apps Script suite. So launch
day means clearing the testing data out of the live database.

Done from the console and the data viewer, **not** by importing anything. Nothing here goes near the
root, so quirk 5.6 never comes into it:

1. **Codes** — staff delete each unused or unlinked code from the console. Every code is named in
   its `note`, so this is read-and-decide, not a sweep. A tester's code has to lose its phone and
   its holder first — **Unlink phone**, and **Move holder** or **Remove holder** — because
   `deleteCode` refuses a code with either. A removed holder leaves the code On hold until the purge;
   for launch day, delete the `/holders` records by hand instead and Unlink the phone.
2. **`/sent`** — deleted by hand in the data viewer. The phones' own notice history is local and
   unaffected.
3. **`/controlQueue`** — deleted by hand. Anything missed is harmless anyway: the drain re-reads
   `/codes` and drops an entry that no longer applies rather than broadcasting it.
4. **`/audit` stays.** It is the record of what was done and to which code, and it survives the
   codes themselves — `deleteCode` deliberately keeps it. Orphaned entries do not show in the table,
   which joins on `/codes`.
5. **`/dispensaries` and `/templates` are untouched.** They are configuration — the dispensary, its
   topics and office hours, and the saved messages staff have built up — not test data.

**`P1AYREVQ` survives because nobody deletes it.** Its note says what it is. It cannot be
regenerated — no repeated characters, so `generateCode_` will never produce it — so if it does go, it
has to be hand-written back by selecting `/codes` (never the root) and importing
`{ "P1AYREVQ": { "issued": <epoch millis> } }`. It is also left off the print sheet, so a batch print
cannot put it on a slip.

**What the 14 testers will see.** Unlinking their codes withdraws each claim, so the next check
reads it as revoked and raises the banner telling them to ring the helpdesk. They are not stuck —
Settings → **Enter a new code** takes a fresh slip and keeps their notice history — but tell them
first, or they will do what the banner says.

**Watch as this repeats.** `/audit` is never cleared, and `listCodes` reads the whole node on every
console load. A few hundred codes' history is well under 200KB, but after several launch-day cycles
it is worth checking; the fix is fetching a code's history when its row is expanded.

**Change the office hours** — the rich banner from the console. The plain `heading` and `lines`:
select `/dispensaries/barc-vashi/info` in the Firebase data viewer and edit them there. Never import
at the root.

**Move to dispensaries (once, before shipping the build that needs it)** — this is a breaking change
with no fallback; phones on the old build stop receiving until they update.

1. In the data viewer, create `/dispensaries/barc-vashi`, **select that node** (never the root) and
   Import JSON `docs/dispensary-barc-vashi.json`. Its placeholder `info` is fine; step 3 replaces it.
2. Console project: set `DISPENSARY_ID = barc-vashi` in Script Properties, and paste the new
   `Code.gs`, `Index.html` and `Tests.gs`.
3. From the console editor, with nobody using the console: run `migrateToDispensariesDryRun()` and
   read the log, then `migrateToDispensaries()`. It copies the live `/info` into
   `/dispensaries/barc-vashi/info` and gives every code `dispensary: "barc-vashi"` through a
   one-field-per-code update that leaves claims untouched. A second run changes nothing.
4. Deploy `database.rules.json`: it opens `/dispensaries/{id}` to signed-in users, protects the new
   field, and drops the `/info` rule.
5. Delete `/info` and `/status` in the data viewer.
6. Deploy the console as a new version. Sender project: set `DISPENSARY_ID = barc-vashi`, paste the
   new scripts, delete `Status.html` and `Revoker.gs`, run `controlInstallTrigger()`, and deploy a new
   version.
7. Ship the app build. Then delete the migration section from the console's `Code.gs`.

**Deploy rules** — `firebase deploy --only database`, or paste `database.rules.json` into the Rules
tab. Rules-only; it does not touch data.

**Ship an app update** — bump `versionCode`, `./gradlew :app:bundleRelease`, upload to the closed
testing track.
