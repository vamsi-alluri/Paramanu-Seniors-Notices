# Decisions

Standing decisions and the questions that prompted them. Newest first.

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
- A trigger every 30 days erases holders removed at least 30 days earlier and returns their codes to
  Unused.
- Unlink phone no longer clears Printed; the slip stays with its holder.
- A code with a holder, a phone, or On hold cannot be deleted. Move the holder first.
- The CHSS number is free text, searchable, not unique. Every allowed editor sees every field.
- Printing is optional, switched by the `PRINTING_ENABLED` Script Property.

**Consequence.** A person's details can genuinely be erased, and the history still says what
happened to the code. Removing a holder by mistake costs a click to undo for 30 days. Launch-day
clean-up has to deal with holders as well as codes (SYSTEM.md §10).

## Revoke, resume and the banner share one control topic

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
