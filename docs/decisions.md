# Decisions

Standing decisions and the questions that prompted them. Newest first.

## Disable pauses a code, Unlink recycles it

*Decided 2026-10-28.*

**Question.** Should a phone that has been unlinked be able to claim its own code again? Until now it
could not: the app answered "same code" itself and never asked the server, on the thinking that
unlinking should keep that install out for good.

**Decision.** It can. The two console actions now mean two different things:

- **Disable** pauses the code. `usedBy` keeps the phone's UID, `revoked` is set, and the server
  refuses the code to every phone until Enable. This is the only way to keep someone out.
- **Unlink** recycles it. `usedBy`, `activatedAt` and `revoked` are all cleared, and the code is free
  exactly as an unclaimed slip is. Whichever phone types it first claims it, the old one included.

**Why the block on the old install was worth nothing.**

- A code unlocks notices that are public on the website anyway. `/status` returns only the code's own
  fields and the dispensary's public info, and holder details never leave the console. The gate is
  cooperative, not access control (SYSTEM.md §6).
- The block held only for the one install that had just been unlinked, and only while it kept its
  data. A reinstall or a data clear gives a new UID, and the slip works again. Any other phone holding
  the slip claims it by design.
- The code is not secret from that phone. The revoked banner prints it, and so does the code screen.
- It was enforced only in the app. The server never knew which UID had held the code.

**What allowing it buys.** The real case is staff unlinking a phone that was not gone, which the
console warns about. That person now recovers by retyping their own slip, with no trip to the counter.
The rule is also simpler: unlinked means free, for every phone, decided in one place, the server.

**Consequences.**

- In the app, `RedeemPolicy.localAnswer` answers "same code" only for a phone that is not revoked. A
  revoked phone's own code goes to the server: an unlinked code is claimed again, and a refusal (still
  disabled, or taken by another phone) is shown as the same-code message. An activation link on a
  revoked phone always opens the code screen, its own code included.
- **A lost or stolen phone is Disabled, not unlinked**, and its holder gets a new slip. Unlinking it
  would only start a race for the freed code. The console's Unlink confirmation and SYSTEM.md say so.
- Considered and not built: on unlink, record the old UID (or a hash of it) and have `/activate`
  refuse it. That keeps a UID in the database after the unlink, and still falls to a reinstall.

## Topics are split by audience, and the backend moves to Cloud Functions

*Decided 2026-10-27. Built: the two triggers (`functions/src/control.ts`, deployed by the workflow,
runbook section 8) and the app's subscriptions (`ControlTopics`). `control-v2` was added with the
kill switch below, 2026-10-28.*

**Question.** Every revoke and resume goes to `control-v1`, so all four hundred phones receive every
stopped code and test it against their own, like an ARP broadcast. But the address is already
known: `usedBy` holds the phone's UID. Unlink also sends nothing at all, because a code-addressed
message could cut off whoever claims the code next. Meanwhile the control drain runs on a one-minute
Apps Script trigger -- 1,440 runs a day against the account-wide UrlFetch quota that ran out in
September.

**Decision.** One topic family per audience, named `<family>-v<N>`, with an id after the version
where there is one:

| Topic | Carries | Subscribed by |
|---|---|---|
| `control-v2` | "Remote Config is stale", for the kill switch below. Nothing else. | Every install, claimed or not |
| `banner-v1-<dispensaryId>` | That dispensary's banner | Phones whose claim names that dispensary |
| `device-v1-<uid>` | Revoke (disable and unlink) and resume for this install | The install holding a claim, revoked or not |
| the dispensary's notice topics | Notices | Unchanged |

- **Device topic.** The UID is the Firebase anonymous UID exactly as issued: topics are
  case-sensitive, so it is never lowercased. A reinstall gets a new UID and so a new topic, which is
  what makes unlink safe to push -- the next holder of the code has a different UID and cannot
  receive the old phone's revoke. Dispensary topics hand-edited in RTDB must never start with
  `device-` or `banner-`.
- **Pushed from database triggers, not a queue.** A Cloud Function on `/codes/{code}` compares the
  node before and after the write: `usedBy` X to empty is a revoke to `device-v1-X`, `revoked`
  becoming true is a revoke to the holder, `revoked` true to false is a resume. The UID comes from
  the before-image, so the console never writes it anywhere and the function must not log it. A
  second trigger on `/dispensaries/{id}/info` sends the banner. `/controlQueue`, `queueControl_`,
  `clearCodeControl_` and the sender's `Control.gs` all go. Events can arrive out of order, so each
  message is stamped with the event's own time and the phone's `acceptControl` drops the older; the
  function re-reads the node before sending, as the drain does now.
- **Push is a courtesy, the API is the truth.** Once the remaining RTDB reads move behind
  `codeManagement`, the app checks the API for its state and relies on push only to hear about a
  change sooner. The periodic check stays, rare, as the net for a phone that was off past the
  message TTL.
- **`control-v1` is retired, with a bridge.** Older builds only listen on `control-v1`. Until the
  database rules close, the triggers also send a copy there, in the same envelope, so those builds
  keep hearing about a disable, an enable and a banner while they age out (amended 2026-10-28; first
  decided as a clean break). The copy is only what the old drain sent: never an unlink or a delete,
  because a code-addressed revoke for a freed code can reach the next phone to claim it, from FCM's
  queue if it was offline. Nothing new is ever added to `control-v1`: a version number means one
  envelope and one meaning. The new builds unsubscribe from it. The bridge goes when the rules
  close (plan phase 5).

**Three levels of update pressure, and a kill switch.** *Built 2026-10-28 for 0.8.4; first designed
here 2026-10-27 as a deferred version floor plus a `current_version` nudge.* Code in `update/`.

- **Nudging is Play's release priority, not Remote Config.** Each release carries an in-app update
  priority, 0 to 5, set through the Play Developer API when the release is created and fixed after
  that (runbook section 9). `UpdatePrompt` maps it: 0-2 Play's flexible prompt, once per version;
  3-4 flexible on every open; 5 Play's immediate full-screen flow, every open. "Every open" is at
  most once an hour, so a return from the external PDF viewer does not raise it again. A flexible
  download is installed when the app next goes to the background, where Play does it silently.
  Replaces `current_version`: the priority travels with the release, and the prompt starts only
  once the staged rollout has reached the phone, which is when the phone can act on it anyway.
  Play's prompts appear only on a build installed from Play; a debug build never shows them, and
  testing needs internal app sharing.
- **`min_version_supported`** (Remote Config, Number, a versionCode -- as text 0.8.10 sorts below
  0.8.9). Below it: one line and an Update button to the Play listing, no way past it, no
  notifications, and every topic but `control-v2` dropped. For a build broken badly enough that it
  must not run, which Play's priority cannot force.
- **`app_killed`** (Remote Config, Boolean). Every version: one line and a button to the website,
  nothing else, no notifications, every topic but `control-v2` dropped, no background checks. For
  the day the service has to stop -- a compromised build, the programme ending. It must stay
  Boolean-typed in the console: `getBoolean` coerces a String parameter, so `1` or `yes` typed into
  one would kill every phone. Killed wins over the version check.
- **Recovery is correcting the value**, with nothing remembered on the phone, so there is no
  separate un-kill to get wrong. A closed gate forces a fetch past the interval on every resume, so
  a mistake lifts itself within minutes of being corrected, without anybody reinstalling.
- **Defaults are open** (0 and false), so a phone that has never fetched, or cannot, is never
  locked out by a missing value. A failed fetch keeps the last activated value.
- Read from Remote Config because it already exists for the analytics switch, is fetched on every
  foreground, and costs no database connection and no function execution.
- **`control-v2` carries `configStale`** on every Remote Config publish, from the `configStale`
  function (`functions/src/config.ts`). In a release build a fetch is skipped for six hours after
  the last one, so a kill would land only at a phone's first foreground after its window -- and a
  phone nobody opens never fetches, so it would go on posting notifications. The message enqueues
  `ConfigRefreshWorker`: `NetworkType.CONNECTED`, a unique name with `KEEP`, a random 0-15 minute
  delay so four hundred phones do not fetch in the same second, and a fetch with no interval. A
  Remote Config fetch is not a database connection. If the work cannot run, the stale mark makes
  the next foreground fetch skip the interval too. Firebase documents this pattern. Every install
  holds the topic, claimed or not, and a closed gate keeps it: it is how the gate is lifted.
- **The messaging service checks the gate before anything else**, from the activated values with no
  network, so a notice arriving while the app is closed does not wait on a fetch. Control messages
  other than `configStale` are ignored while the gate is closed; the daily check catches up after.
- Only builds that carry the gate can be killed or floored: 0.8.4 is the first. Older builds go on
  as they are whatever Remote Config says.

**The backend moves to Cloud Functions; the pages stay where they are.** The sender and console keep
their Apps Script URLs and HtmlService pages. What moves is the server side behind them, one piece at
a time, each shipping on its own: control first (above), then sending a notice, then the RSS poller
(a scheduled function, off the Apps Script quota), then the console's issue, disable, unlink, purge
and audit. Each Apps Script server function becomes a thin call to the function. How the page proves
the staff member's identity to the function is the open question -- the `getOAuthToken()` route
returned 401 (below), so the candidate, a Google ID token checked against `ALLOWED_EDITORS` inside
the function, needs a spike before anything depends on it.

## What a phone can receive comes from its code's dispensary

**Question.** "What you receive" was compiled into the app as Notices and Daily open and closed. The
daily status is no longer wanted, and the NGO may one day run more than one dispensary. How does the
list become configuration rather than a redesign?

**Decision.**

- A dispensary record at `/dispensaries/{id}` holds its `name`, its `topics` (topic name, label,
  explainer, `defaultOn`, `importance`, `order`) and its banner under `info`. Today there is one:
  **BARC Vashi Dispensary**, `barc-vashi`.
- Each code carries `dispensary`, written by the console from its `DISPENSARY_ID` Script Property.
  The phone reads it during verification and draws Settings from that dispensary's topics.
- Notices carry `topic`. The phone drops one its dispensary does not offer or its user has switched
  off, and takes the channel from the topic's importance. Channels are one per importance, never per
  topic, so data cannot add entries to Android's notification settings.
- The sender sends to the dispensary's first topic by `order`. There is no picker: a dispensary that
  offers several gets its own page and deployment, which is less work than retrofitting this one.
- Banner pushes name their dispensary; a phone applies only its own. The banner moved from `/info` to
  `/dispensaries/{id}/info`.
- Daily open and closed, its QR route, `Status.html`, `/status` and `status-v1` are removed entirely.

**Consequence.** A breaking change, accepted during closed testing: phones on the old build stop
receiving until they update, and keep a dangling `status-v1` subscription that nothing sends to. The
migration is a handful of data-viewer steps (SYSTEM.md §10). It stays on Realtime Database: the reason
§6a gave for Firestore was a topics map growing on each code, and this adds one fixed string instead.

## Holders live apart from codes, and On hold is derived

**Question.** Staff need the name, CHSS number and telephone number of the person each code was given
to — and one of them could not find a code again after releasing it. Where do those details go, what
happens to them when someone leaves, and how do the buttons stop being confused with each other?

**Finding.**

- `/codes/{CODE}` is readable by any signed-in phone that knows the code, and a new field there needs a
  rules change or the app's claim write fails. Neither is acceptable for a person's details.
- `/audit` is never erased, so a name written into it could never be removed.
- "Revoke" and "Restore" sat next to each other, and "Restore" was about to mean undoing a removed
  holder too. "Release" suggested the person or the note was affected, when only the phone was.
- A CHSS card may be shared across a family — public sources do not settle it — so the number cannot
  be an identifier.

**Decision.**

- Holders live at `/holders/{id}`, which no rule opens to phones, keyed by their own id so a person
  keeps their record when moved to a new code. One active holder per code. Each holder has its own
  history with before-and-after values; `/audit` records only the holder's id.
- Names: **Disable / Enable** for the phone, **Unlink phone** for a gone phone, **Add / Edit / Move /
  Remove holder** and **Restore holder**, **Delete code**. Stored field and event names are unchanged.
- **Remove holder** is a soft delete and puts the code **On hold**: stopped (`revoked: true`) and
  refused every action. Restore puts the code back as it was, Disabled included (kept as
  `wasDisabled`). On hold is derived from the audit, like Printed, so `/codes` and the rules are
  untouched.
- A trigger on the 1st of each month (every 30 days until 2026-10-13, changed so the console can
  name the day) erases holders removed at least 30 days earlier and returns their codes to
  Unused.
- Unlink phone no longer clears Printed; the slip stays with its holder.
- A code with a holder, a phone, or On hold cannot be deleted. Move the holder first.
- The CHSS number is free text, searchable, not unique. Every allowed editor sees every field.
- Printing is optional, switched by the `PRINTING_ENABLED` Script Property.

**Consequence.** A person's details can genuinely be erased, and the history still says what
happened to the code. Removing a holder by mistake costs a click to undo for 30 days. Launch-day
clean-up has to deal with holders as well as codes (SYSTEM.md §10).

## Revoke, resume and the banner share one control topic

*Superseded 2026-10-28 by "Topics are split by audience": `control-v1` is retired.*

**Question.** A banner edited in the console did not reach a phone for days: the app read `/info`
only when its view model was created, and a phone left in recents never recreated it. Revoke already
rode on `notices-v1`. Where do banner updates go, and how does a restore reach a revoked phone
without waiting a day?

**Finding.** Three separate problems with one shape.

- Revoke on `notices-v1` never reaches a user who has switched notices off, and a revoked phone has
  left that topic, so nothing pushed can ever tell it to come back.
- A push that merely says "the banner changed" makes four hundred phones fetch `/info` together —
  SYSTEM.md §5.14 again.
- FCM does not promise order, so any pushed pair of opposite instructions can arrive reversed.

**Decision.** One topic, `control-v1`, for everything that manages the app rather than informs the
user, told apart by `type`. A phone holds it for as long as it holds a claim, revoked included, and
it never appears in Settings. A revoked phone leaves every notice topic and ignores banner pushes,
but still acts on a resume for its own code.

Every control message carries `at`, the time staff acted, and a phone ignores one no newer than the
last it applied. The banner's html travels inside the message, capped at 3500 UTF-8 bytes to fit
FCM's 4096-byte payload. The console queues all three in `/controlQueue`; `Control.gs` replaces
`Revoker.gs` and drains it.

Considered and rejected: a server or function app between the database and the phones. At this
scale — and at the ~8k ceiling if every dispensary adopted the app — the phone path needs no logic the
security rules do not already enforce, and FCM is already the buffer. The pain of several Apps
Scripts is on the staff side, which is where a function app would go if it is ever built.

**Consequence.** A restore lands within about a minute instead of a day. The daily verification
stays as the safety net for a phone that was off. Phones on 0.4.0 do not listen on `control-v1` and
are deliberately not catered for: closed testing has fourteen testers, who are updated by hand. A
banner over the limit is refused at save, with the byte count, instead of silently failing to reach
phones.

## The console queues a revoke; a trigger in the sender drains it

*Superseded 2026-10-28: the queue, `Control.gs` and its trigger are removed, and database triggers in
`functions/src/control.ts` push instead. Kept for the `getOAuthToken()` evidence below.*

**Question.** The decision below — the console POSTs to the sender's web app, forwarding the staff
member's identity with `ScriptApp.getOAuthToken()` — does not work. What replaces it?

**Finding.** Every request returns **HTTP 401** from Google's auth frontend, before any script runs:

```
as: vamsi1306@gmail.com
GET  /exec        -> HTTP 401  HTML
POST /exec        -> HTTP 401  HTML
POST /exec/revoke -> HTTP 401  HTML
```

All three fail identically, so neither the path nor the method is at fault. `getOAuthToken()` mints a
token carrying the *console's* authorized scopes, but invoking a web app needs one authorized for the
*sender's* script project. That grant does not exist across two separate projects and no manifest
scope creates it. The documented workaround — one shared standard GCP project with matching scopes —
couples the projects that §2.3 deliberately separated.

A shared secret was considered and rejected. It needs a second sender deployment set to "Anyone",
because the existing one requires a Google account and that is precisely what is failing, and it
cannot be switched because the compose UI needs the sign-in to identify staff. It also degrades
attribution to "the console says it was X".

**Decision.** No HTTP between the projects. The console writes `/revokeQueue/{CODE}` to the database
it already owns, and `Revoker.gs` in the sender drains it on a one-minute trigger: read, broadcast,
delete. No token, no shared secret, no second deployment, and the console still never touches FCM.

Delivery is at-least-once: an entry is deleted only after FCM accepts it, so a failure is retried
next minute rather than lost. A phone receiving the same revoke twice is harmless — `suspendClaim()`
is idempotent and `announceRevocation()` is guarded by the `local-revoked` row id.

The drain **re-reads `/codes/{CODE}` before broadcasting** and drops the entry if it is no longer
revoked. The console already clears the queue on Restore and Release, but a released code returns to
the pool, and a stale revoke firing afterwards would cut off whoever claimed it next — they would
type a fresh slip and be told at once that they had been removed.

**Consequence.** Latency is about a minute rather than seconds. Immaterial: revocation is explicitly
not access control, every notice is public, and the previous fallback was a whole day. Attribution is
unaffected — who revoked and when is written to `/audit` by the console either way. `doPost` is gone,
so the sender has no POST surface at all.

## The console asks the sender to push; it never gets FCM itself
**SUPERSEDED — see above. Kept for the reasoning; the mechanism does not work.**

**Question.** "Revocation is pushed" leaves out who pushes it. Revoke is a *console* action, but
the console has no `PROJECT_ID` and no FCM credentials — and §2.3 of SYSTEM.md says that split is
deliberate, so that issuing codes and broadcasting to four hundred phones stay separate jobs.

**Finding.** Three ways to bridge it. Give the console FCM, which undoes the separation. Have the
sender's existing 15-minute poller scan for newly revoked codes, which preserves it but costs up to
a quarter of an hour. Or have the console call the sender as an API.

**Decision.** The console POSTs `{"action":"revoke","code":…}` to the sender's `/exec`, forwarding
the staff member's own OAuth token in the `Authorization` header. The sender's `requireEditor_()`
resolves that token to a person and checks it against the same `ALLOWED_EDITORS` allowlist as every
other entry point, then sends the fixed revoke envelope.

No new shared secret. A secret sitting in two Script Properties would be one more thing that can
leak, and it would say nothing about *who* acted — which is the point, since the same work adds an
audit trail. The console needs `userinfo.email` in its manifest `oauthScopes` for this to resolve.

**Consequence.** The console still cannot broadcast anything: the endpoint sends one fixed envelope
with no free text. `pushRevokeToSender_` never throws — the revocation is already in `/codes` and
`/audit` by the time it runs, and the daily verification finds it regardless, so a failure is a
delay rather than a lost revocation, and is recorded on the audit entry as `pushed: false`.

## The daily check is periodic, not triggered by a message

**Question.** "Un-revoking cannot be pushed, and still waits for the app to be opened or for the
daily verification." Which daily verification? A refresher enqueued from the message path never
runs on a revoked device: it has unsubscribed from every topic, so no message ever arrives to
enqueue it.

**Finding.** That leaves app-open as the only route back for a restored code. The people this is
built for may not open the app for weeks, and the revocation banner tells them to ring the helpdesk
— so the NGO restores the code and, from the user's side, nothing happens.

**Decision.** A `PeriodicWorkRequest` every 24 hours, unique-named with `KEEP`, enqueued at
activation and on every app start while a claim is held — revoked included. The message-triggered
`OneTimeWorkRequest` stays for the staleness case on a working device.

**Consequence.** This is the load the earlier decision already predicted: roughly one connection per
device per day, spread across the jitter window. `KEEP` matters — restarting the schedule on every
launch would push the next run a day further out each time the app was opened, which on a phone
that is opened daily means it never runs at all.

## Revocation is pushed, and verification is the fallback

**Question.** Rather than every device asking the database whether its code still stands, could
the sender push a message that revokes a code -- a message that shows nothing to the user and
exists only to invalidate? The way a gratuitous ARP updates caches, or a DHCP server hands down
a lease.

**Finding.** It fits the sender as it already is. `docs/sender-contract.md` sends data-only,
high-priority, topic-addressed messages, so a revoke is another `type` in the same envelope --
`{"type": "revoke", "code": "..."}` with no title or body -- and the messaging service returns
before it reaches the notice path. Nothing new is needed on either side.

Two things it cannot do:

- **It cannot be authoritative.** There is no per-device addressing: `onNewToken` is
  deliberately not overridden and everything is addressed by topic. So a revoke is a broadcast
  that every device tests against its own code, and FCM delivery is best-effort. A phone that is
  off past the topic-message TTL never receives it and keeps working indefinitely. This is why
  a DHCP lease expires on its own rather than trusting that the client will be told.
- **It publishes the revoked code** to every subscribed device. Harmless -- a code with
  `revoked = true` is useless to whoever reads it -- but it is what is being sent.

**Decision.** Push the revoke, and keep the periodic verification as the safety net.

- A revoke message writes `Revoked` locally and unsubscribes. No database connection at all.
  This covers effectively every real revocation, within seconds.
- The background verification stays, but because it is now only catching devices that missed a
  broadcast, its freshness window widens from one hour to **24 hours**.

**Consequence.** Steady-state load falls to roughly one connection per device per day, spread
across the jitter window -- far below the Spark ceiling -- and revocation lands faster than
polling ever managed. A revoked device has unsubscribed, so it will not receive later topic
messages: un-revoking cannot be pushed, and still waits for the app to be opened or for the
daily verification.

## Verification runs off the message path

**Question.** When a notification goes out to roughly 400 installed devices, how many Realtime
Database connections does that create, and does it fit inside the Spark plan?

**Finding.** Every arriving notice called `ActivationRepository.verify()`, which refreshes the
ID token and reads `/codes/<code>`. That opens a websocket per device, all within a few seconds
of the FCM fan-out -- up to ~400 at once, against a Spark cap of 100 simultaneous connections.
Over the cap the connection is refused and `verify()` returns `Unknown`, so the notice still
shows -- `Unknown` is deliberately not treated as revoked -- but the check silently stops
working for most of the audience. The SDK also holds the socket open for about 60 seconds of
idleness afterwards, so a device occupies its slot long after it has its answer.

The app-open path (`NoticeViewModel.refreshActivation`) performs the same check, but app opens
are naturally spread out and it needs no change. It also cannot be relied upon: the users this
is built for may never open the app at all.

**Decision.** Stay on Spark. Split the fused check in two and take the network off the message
path entirely.

- **A gate** answers "may I show this notice?" from the last persisted state. No network, no
  connection, instant. Nothing stored yet, or a stale entry, shows the notice -- the same
  reasoning that already permits `Unknown`.
- **A refresher** does the token refresh and the read in a `OneTimeWorkRequest`, constrained to
  `NetworkType.CONNECTED`, enqueued under a unique name with `KEEP` so a burst of notices does
  not queue several. `Result.retry()` on an inconclusive answer picks up WorkManager's default
  exponential backoff.

Because the work no longer sits inside `onMessageReceived`, the jitter is not bounded by FCM's
~10 second window: the initial delay is randomised across **0-15 minutes**. An earlier draft of
this decision capped jitter at 7 seconds to fit inside that window and skipped verification
within the hour; both are superseded -- 7 seconds plus the 4 second `VERIFY_TIMEOUT_MS` risked
overrunning the window in any case, since the path blocks on `runBlocking`.

The refresher calls `goOffline()` when it finishes, so the socket is dropped rather than idling
open for a minute holding a slot it no longer needs.

**Consequence.** The result of a verification lands in time for the *next* notice rather than
the one that triggered it, so a revoked device can see one more notice. That costs nothing:
this gate is not access control -- every notice is published publicly on the website -- and the
push revocation above closes the gap in the normal case anyway.

Needs the WorkManager dependency; it is not in `gradle/libs.versions.toml` yet.
