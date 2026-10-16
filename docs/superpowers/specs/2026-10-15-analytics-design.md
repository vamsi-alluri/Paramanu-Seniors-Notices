# Launch analytics — design

**Date:** 2026-10-15
**Branch:** `analytics`, off `e563207` (0.7.2, versionCode 13)
**Status:** design agreed in conversation; awaiting review of this document

---

## 1. Why

Four engineering decisions are currently being made on intuition, and the launch window is the
only chance to settle them with evidence:

1. **Can the roaming / metered rule go?** `FetchPolicy` refuses every automatic fetch while
   roaming and caps metered fetches at 2MB. If almost nobody is ever on an unmetered connection,
   that rule is not protecting users from a bill, it is permanently denying them attachments.
2. **Is `MAX_JITTER_SECONDS = 60` the right number?** The comment in `AttachmentWorker` argues the
   jitter is now only insurance against leaving a CDN. If fetches still cluster in one second, or
   if they do not cluster at all, the constant should change or go.
3. **Is the notification the real entry point, or the app?** This decides whether effort belongs in
   the tray or in the list.
4. **What do people actually read?** Per-notice attention, and whether images and PDFs are opened
   at all.

Plus one question the rollout itself raises: **are the printed slips working at the counter?** An
activation that fails silently costs the NGO a code and a visit.

**This is a reversal of a documented decision.** `docs/SYSTEM.md` §5 records that
`firebase-analytics` was removed and `AD_ID` stripped from the manifest. That paragraph must be
rewritten, not deleted — the `AD_ID` guarantee survives this change intact.

## 2. What this is not

- **Not a permanent analytics capability.** It exists to answer §1 and is switched off afterwards.
- **Not per-user preference data.** SYSTEM.md commits to aggregate counters with no identifier for
  topic popularity. GA4 rows are per-install, so topic choices are deliberately **not** logged
  here; that remains future work on a different mechanism.
- **Not a record anyone can read as "what did this person read".** Notices are identified by
  `noticeId` / `logId` only. No title, no body, ever.

## 3. Approach

**Firebase Analytics (GA4) + Crashlytics, exported to the BigQuery sandbox, gated by a flag in
RTDB.**

Rejected alternatives, with the deciding reason:

- **Own event pipe in RTDB.** SYSTEM.md §6a cites the simultaneous-connection cap, which this
  project is already over on every send. Four hundred devices writing a clickstream would degrade
  the delivery path in order to measure it.
- **Device-local only.** Cannot answer anything about the users who are not in the room, which is
  all of them.
- **GA4 + a local ring buffer digest.** More code than the question is worth; revisit only if
  GA4's parameter ceiling proves too tight.

The decisive property is that §1's questions need **distributions, not totals** — which second did
fetches start in, how long did loads take, which device never saw wifi. GA4's own reports
aggregate and cardinality-cap; the BigQuery rows answer all four in SQL, and the link is available
on the Spark plan via the sandbox at no cost.

### Constraints this inherits

| Constraint | Consequence |
|---|---|
| BigQuery sandbox: daily export only, no streaming | Data is ~1 day stale; first export up to 48h |
| Sandbox tables expire after 60 days | Pull aggregates to local CSV before then; query results are tables too and also expire |
| GA4 does not backfill | The link must exist before the build ships |
| Spark plan must not change | No Blaze, no GCS, no Data Transfer Service |
| Dataset region is permanent | Created in Mumbai (`asia-south1`), 2026-10-15 |

**Day-to-day monitoring is therefore not BigQuery's job.** DebugView over adb during development,
GA4 Realtime and standard reports for "is anything arriving", BigQuery only for the four hard
questions.

## 4. The analytics surface

A single object, `analytics/Analytics.kt`, is the only thing in the app that knows Firebase
Analytics exists.

```kotlin
class Analytics(context: Context, private val scope: CoroutineScope) {
    fun log(event: AnalyticsEvent)
    fun setEnabled(enabled: Boolean)
}
```

- Constructed once in `NoticesApplication`, which is already a hand-rolled service locator
  (`repository`, `infoRepository`, `dispensaryRepository`, `activationRepository`, and an
  `appScope` of `SupervisorJob() + Dispatchers.IO` at line 34). Added as one more `by lazy`. The
  existing `appScope` is reused; a second scope is not created.
- **Every consumer already reaches `NoticesApplication`**, so no call site needs new plumbing:
  `NoticeViewModel` through its `viewModelFactory` (`APPLICATION_KEY` cast), `AttachmentWorker` and
  `NoticeMessagingService` through `applicationContext as NoticesApplication`, composables through
  `LocalContext.current`.
- `AnalyticsEvent` is a sealed interface, one variant per event in §5, each carrying its own typed
  parameters. The mapping to GA4 names and `Bundle`s happens in one place, so parameter-name typos
  are a compile error rather than a column that is silently always null.

### What must not happen

- **`FetchPolicy` is not touched.** Its KDoc states it is free of Android imports so the whole
  judgement is JVM-testable, and `FetchPolicyTest` depends on that. Logging happens at its callers
  in `AttachmentWorker`, where `notice`, `logId`, `metered`, `roaming` and the attachment kind are
  all already in scope.
- **`NetworkStatus` is not touched**, for the same stated reason.

## 5. Events

Thirteen event types. Parameter names stay under GA4's 40-character limit.

### Fetch policy and network — question 1

| Event | Parameters |
|---|---|
| `fetch_policy_decision` | `kind` (img/link/pdf_thumb/pdf), `metered`, `roaming`, `size_bytes`, `decision` (fetch/defer), `size_known` |
| `network_state_on_open` | `metered`, `roaming` |

`size_known` discriminates a real HEAD result from a sender-supplied `pdfBytes`;
`AttachmentWorker` synthesises a `Probe.Reachable(known)` when the payload carried the size, and
conflating the two would misreport how often the HEAD path runs.

"Who is *always* on mobile data" is this grouped by `user_pseudo_id` across the window: a device
with no unmetered row never saw wifi.

### Fetch timing and clustering — question 2

| Event | Parameters |
|---|---|
| `attachment_fetch_start` | `kind`, `size_bytes`, `jitter_seconds`, `enqueued_at_ms`, `started_at_ms`, `attempt` |
| `attachment_fetch_end` | `kind`, `size_bytes`, `duration_ms`, `outcome` (fetched/failed/gone/deferred), `http_status` |

Both timestamps are logged explicitly rather than relying on GA4's `event_timestamp`, which is
receipt time at Google, not device time.

### Entry point — question 3

| Event | Parameters |
|---|---|
| `notice_received` | `has_image`, `has_pdf`, `is_new`, `delivery_lag_ms`, `ms_since_process_start` |
| `notification_tapped` | `ms_since_shown`, `cold_start` |
| `app_open` | `source` (notification/launcher/link) |

"Notified but never opened" is not an event — it is the absence of a `notification_tapped` after a
`notice_received`, answered in SQL. Sessions and engagement time come from GA4 automatically.

### Reading — question 4

| Event | Parameters |
|---|---|
| `notice_card_visible` | `notice_id`, `visible_ms`, `has_image`, `has_pdf`, `position` |
| `image_viewer_closed` | `notice_id`, `duration_ms`, `max_scale` |
| `pdf_opened` | `notice_id`, `size_bytes`, `was_cached` |

**There is no "card expanded" event, because there is no such thing.**
`NoticeListScreen.kt:360`: "Every card is always open, so outside selection a plain tap has nothing
to do and is inert." Card attention is measured as time visible in the `LazyColumn`, which is the
honest proxy.

**PDF dwell time is not measured.** `AttachmentActions.open()` hands off via `ACTION_VIEW` with
`FLAG_ACTIVITY_NEW_TASK` to an external app; the open is observable, the close is not. Inferring
dwell from our own `onPause`/`onResume` gap would count a phone call and a night on the charger.

### Activation funnel

| Event | Parameters |
|---|---|
| `activation_shown` | `has_code` (re-entry from Settings vs fresh install) |
| `activation_submitted` | — |
| `activation_result` | `result`, `offline_cause` |

`result` maps the existing `RedeemResult` sealed type. `offline_cause` exists because **three
distinct failures all return `Offline`** — no uid from anonymous sign-in
(`ActivationRepository.kt:212`), a transport exception (`:264`), and the 20-second
`withTimeoutOrNull` elapsing (`:266`) — and telling them apart is most of the value of the funnel.

### Not collected, deliberately

Notice titles or bodies. Topic choices. Anything typed into the code field beyond the outcome.
Location, contacts, photographs, advertising ID.

## 6. Insertion points

Verified against the tree at `e563207`. Line numbers will drift; the enclosing function is the
durable reference.

| Event | File | Where |
|---|---|---|
| `fetch_policy_decision` | `fcm/AttachmentWorker.kt` | the `when (verdict(...))` arms in `fetchAttachments` (~281–333), **not** inside `decide` — that lambda is shared with `fetchNow`, which always returns FETCH and has no network facts. The roaming short-circuit (~132) never reaches `shouldFetch` and needs its own call. |
| `network_state_on_open` | `ui/NoticeViewModel.kt` | `onResumed`, before `enqueueCatchUp` |
| `attachment_fetch_start/end` | `fcm/NoticeImageStore.kt` | wrap the body of `download` (~346); `kind` is already a parameter at the `fetchPicture` call site |
| jitter + enqueue time | `fcm/AttachmentWorker.kt` | `enqueueFor` (~194): hoist the `Random.nextLong` out of the builder chain and add a second input-data key |
| `notice_received` | `fcm/NoticeMessagingService.kt` | just after `repository.save(...)` (~79–92), where `isNew` and `message.sentTime` are in scope |
| `notification_tapped` | `MainActivity.kt` | at the two `persistFromNotificationIntent` call sites — `onCreate` means cold start, `onNewIntent` means warm |
| `app_open` | `MainActivity.kt` | `onCreate` / `onNewIntent`, discriminating on `EXTRA_LOG_ID` present, `ACTION_VIEW`, or neither |
| `notice_card_visible` | `MainActivity.kt` | a `snapshotFlow` over `noticeListState.layoutInfo.visibleItemsInfo` beside the existing state at ~141. Index 0 is the office-info header; notice rows start at 1 and their keys are `Long`. |
| `image_viewer_closed` | `ui/NoticeViewerScreen.kt` | a new `DisposableEffect(Unit) { onDispose { … } }`, with max scale accumulated into a local |
| `pdf_opened` / `was_cached` | `fcm/NoticeImageStore.kt` | at the cache check (~232), where `length()` is already being paid for |
| `activation_*` | `ui/NoticeViewModel.kt` | `redeem`'s `when (val result = …)` — one place, both outcomes, instead of six returns in the repository |

### Three new pieces of state

1. **`NoticesApplication.processStartedAt`** — a single `val` set in `onCreate`. Nothing currently
   tracks process start, and this is what makes `cold_start` and `ms_since_process_start`
   answerable without adding `lifecycle-process`.
2. **A posted-at extra in the notification's `PendingIntent`** (`NoticeNotifications`, ~125) — the
   only way to get a true shown→tapped interval.
3. **A second input-data key on the per-notice work request** carrying enqueue time.

**No Room migration is required.** Delivery lag comes from `RemoteMessage.sentTime`, which is
already available and currently unused.

### The trap this avoids

`notice.receivedAt` looks like arrival time and is not. `NoticeRepository.kt:44` sets it from
`NoticeTime.sentAt(logId, …)`, parsed out of the sender's epoch-millis `logId` — so it is the
**send** instant and is **identical on every phone**. Any latency measured against it silently
includes delivery lag. It is useful precisely because it is a common origin across devices, but it
must never be called arrival time.

## 7. The kill switch

```
/config/analytics/enabled: true | false
```

- **Default off.** A failed read, a missing node, a first launch before the first read all collect
  nothing. The safe failure for analytics is silence. (The same hazard is documented for
  hand-edited dispensary records, where a missing key silently empties Settings.)
- **Read at process start and on each foreground**, cached in SharedPreferences so it survives
  offline.
- **Drives `setAnalyticsCollectionEnabled` too**, not only our own calls. Guarding the wrapper
  alone would leave GA4's automatic events flowing and make "off" a lie.
- **Drives Crashlytics as well**, through `isCrashlyticsCollectionEnabled`, from the same place in
  `NoticesApplication`. Both SDKs also ship disabled by manifest meta-data, because each
  initialises before `onCreate`; the runtime setter overrides the manifest in both directions.
- **Turning it off is not instantaneous.** Both SDKs persist the last runtime `true`, so after the
  owner flips the switch off, a device's next cold start can initialise with collection enabled
  until its refresh completes. The cached value closes that window quickly, not instantly, and a
  phone that never opens the app does not hear at all. The policy therefore says "within a short
  time", and nothing should be written that implies a stop at the moment of the flip.
- **Its own node**, not under `/dispensaries/…` — it is not dispensary-scoped, and putting it there
  would force the future multi-dispensary seam to carry it.
- `database.rules.json` grants world read, no client write.

## 8. Privacy, policy and Play

**Open testing is public.** The policy republish and the Data Safety update must be **live before
the analytics build is**, not alongside it. Data Safety is declared app-wide, not per track, so a
collecting build on open testing while the listing declares no collection is an inaccurate listing.

Three statements in `docs/privacy-policy.md` became false and were replaced, not softened (line
numbers are those of the pre-amendment text):
line 13 ("no advertising, no analytics, and no tracking"), line 50 ("No analytics or usage tracking
of individuals"), and line 44 ("the app does not send … any record of what you read") — the last
being the sharpest, since per-notice dwell time *is* a record of what you read.

Drafted replacement text is in `docs/privacy-policy-analytics-draft.md`, as eight
replace-this-with-that blocks covering both the repo copy and the published website copy. The
blocks have been applied to `docs/privacy-policy.md`; the published copy on the website is still
to be updated by hand. Of the three claims that had to be made true: the stated region is settled
(the BigQuery dataset was created in **Mumbai (`asia-south1`)** on 2026-10-15); the per-device
exclusion was **not built**, and the sentence was weakened to say that one server-side switch
governs every phone; and GA4 retention at 2 months with "Reset user data on new activity" off is
a console setting only the account owner can change, **not yet done**.

**Data Safety answers:** App activity → yes. App info & performance → yes. **Device or other IDs →
yes** (the GA4 app-instance id is a device identifier under Play's definition even though it is
random and unlinked — this is the line most often missed). **"Can users request that their data
be deleted" → no**: nothing can single out one installation's records, and answering yes would be a
false declaration; the policy states the truth instead (no key tying data to a person, a global
stop, expiry at two months). Advertising ID → **no**, unchanged; the
manifest keeps stripping `AD_ID` and the Gradle dependency excludes `play-services-ads-identifier`.

## 9. Testing

- `FetchPolicyTest` stays green and untouched. That is the test for not instrumenting `FetchPolicy`.
- New JVM tests: kill-switch off logs nothing **and** calls `setAnalyticsCollectionEnabled(false)`;
  event→parameter mapping; every parameter name within GA4's limit.
- Device verification before the build reaches anyone: DebugView over adb, walking one notice end
  to end — arrive, tap, read, open image, open PDF — confirming each event and its parameters.

### Known sources of dirty data, to be asserted on rather than discovered in SQL

1. **`NoticeImageStore.download` cannot be interrupted** by `withTimeoutOrNull` — it has no
   suspension points, and its own KDoc says a timed-out caller's download keeps running and still
   writes to disk. A `fetch_end` can therefore arrive after its caller logged a failure. Duplicate
   and out-of-order events are expected; queries must dedupe.
2. **`AttachmentWorker`'s `isStopped` early return (~63)** abandons a sweep mid-way. Anything
   buffered must be flushed there, or a truncated sweep reports as a clean one.
3. **The FCM path runs in `runBlocking`** on a service thread with a ~10–20s teardown window. A
   fire-and-forget log can be killed; writes go through `appScope` and must not block.

The rest were found while building it, and each is the difference between a correct query and a
confident wrong answer.

**What the dataset cannot see**

4. **Whether `first_open` is recorded is not established.** The manifest default does not appear
   to discard the install: a device capture of this build with collection off logged
   `FA: Persisting first open: 1792143140354` and `App measurement setting deferred collection:
   true`, and left `deferred_analytics_collection=true` in
   `com.google.android.gms.measurement.prefs.xml` (`task-12-report.md`). So the SDK seems to hold
   the first-open instant rather than drop it. Nothing is sent while the gate is shut, so the
   privacy position does not depend on this. What it leaves open is the dataset: whether
   `first_open` arrives on the first *enabled* session, and with what timestamp, is not known, so
   do not read acquisition counts as starting at either install or the first enabled session. What
   would settle it is observing the first enabled session on a device that was installed while the
   switch was off. Our own events are not retroactive: nothing the app logs while the switch is
   off is sent later.
5. **`delivery_lag_ms` can be negative**, meaning that handset's clock is behind FCM's. Filter
   `>= 0` before any transit-time percentile. Absence means FCM supplied no `sentTime`, not a bad
   one.

**Fetch policy and fetch timing**

6. **`fetch_policy_decision` rows are decisions, not attachments.** A deferred attachment is
   re-decided on every later sweep, so any ratio must be taken per decision, never per attachment.
7. **`probe` separates four cases:** `skipped` (the roaming short-circuit; nothing probed),
   `sender` (the payload supplied a size; no request made), `reachable` (a real HEAD succeeded)
   and `unreachable` (no answer). **`unreachable` rows are connectivity facts, not policy
   decisions, and must be excluded from any deferral ratio** — including them makes the ratio rise
   with network flakiness.
8. **`size_bytes` on roaming rows is sender-supplied or absent.** A null there says nothing about
   the file's size, unlike a null on a `reachable` row, which means the server withheld
   `Content-Length`.
9. **`size_known` is redundant.** It equals `size_bytes IS NOT NULL`, and `probe` carries the
   provenance it was once meant to indicate.
10. **`attachment_fetch_start` cannot separate sweeps, taps and the PDF tap.** All three emit no
    `jitter_seconds` and no `enqueued_at_ms`; only per-notice work carries them. Load-duration
    percentiles therefore blend foreground taps with background sweeps. A `jitter_seconds` of `0`
    is a genuine zero draw, not a missing value — absence is the missing case.

**Opens and entry points**

11. **`app_open_source`: `launcher` is a lower bound.** Warm foreground returns of a live task are
    not counted, and automatic detection was rejected because PDFs open in an external viewer, so
    every return from reading one would log a spurious launcher open.
12. **`source = notification` and `notification_tapped` are deliberately different denominators
    and must not be divided into one another.** The former includes local test and revocation
    notifications; the latter excludes them.
13. **`cold_start` means the first open in this process**, not that the activity was created. A
    process started by an incoming FCM message and then opened by a tap reports `true`;
    `ms_since_process_start` is what tells that apart from a user-initiated cold launch.

**Reading and dwell**

14. **`image_viewer_closed` durations must be SUMMED per `notice_id`, never maxed.** A rotation, or
    a trip out of the viewer and back, legitimately produces several rows for one reading session.
    Dwell is lost entirely if the process dies while backgrounded, so the distribution is biased
    short — the longest sessions are the likeliest to be killed.
15. **`notice_card_visible` has no minimum and no maximum.** Very short rows are expected from
    flings and should be filtered in the query; there is deliberately no floor in the app. There is
    no upper bound beyond the foreground gate, so a phone left foregrounded can accrue hours — cap
    in the query. `position` is captured when the card appears and goes stale if a notice arrives
    above it.

**Further traps from the whole-branch review**

18. **`attachment_fetch_end.outcome` can only be `fetched` or `failed`.** §5 lists
    fetched/failed/gone/deferred, but a deferral never reaches `download`, and a 4xx arrives as
    `failed` with `http_status` carrying the real answer. `WHERE outcome = 'gone'` returns nothing
    whatever the 4xx rate; filter on `http_status`.
19. **Jittered rows are selection-biased.** `enqueueFor` delays up to 60s and is the only producer
    of `jitter_seconds` and `enqueued_at_ms`; the app-open sweep fires with no delay. A reader who
    taps the notification inside the jitter window triggers the undelayed sweep, which fetches
    everything, and the jittered work then finds it cached and emits no start event. Jittered rows
    therefore come disproportionately from phones whose owners did *not* open the app promptly
    (see 10). Question 2 is still answerable: sweep rows are identifiable by the absent jitter.
20. **`network_state_on_open` fires on every `ON_RESUME`, not every open** — including rotation,
    return from the external PDF viewer, and after the notification-permission dialog. It is
    harmless for "did this device ever see wifi" grouped per user; a metered-to-unmetered *ratio*
    is weighted by how often people rotate their phones and read PDFs.
21. **`started_at_ms` is the worker's start, shared by every attachment in one sweep.** A
    ten-notice sweep emits ten start events bearing one identical instant; it is not each fetch's
    own start. Use `duration_ms` on the end event for individual fetches.
16. **`pdf_opened` means "resolved and handed off"**, not "read". There is no close event, because
    the PDF goes to an external viewer, and a handset with no PDF viewer installed still records
    an open. Share and Save are excluded.

**Activation**

17. **`activation_submitted` is an upper bound on attempts.** A submit with no result is possible
    if the ViewModel is cleared mid-flight. The reverse — a result with no submit — cannot happen.

### Where logging must never go

`NoticeRow`'s body performs three `listFiles()` disk scans on **every recomposition**, deliberately
un-memoised. The image viewer's `rememberTransformableState` lambda runs **per pinch frame**.
`ActivationScreen`'s `onValueChange` runs **per keystroke**. `AttachmentWorker`'s `notices.forEach`
re-reads metered/roaming per notice. Events belong at state transitions, not in these.

## 10. The queries

`event_params` is a **repeated record** — one row per parameter. Pulling parameters by joining
(`FROM events_*, UNNEST(event_params)`) cross-joins and multiplies every `COUNT(*)` by the number
of parameters, which would make fetch clustering look dramatically worse than it is. The correct
idiom is a scalar subquery per parameter:

```sql
WITH fetches AS (
  SELECT
    TIMESTAMP_TRUNC(TIMESTAMP_MICROS(event_timestamp), SECOND) AS fetch_second,
    user_pseudo_id,
    (SELECT ep.value.string_value FROM UNNEST(event_params) ep WHERE ep.key = 'kind') AS kind
  FROM `PROJECT.analytics_PROPERTY.events_*`
  WHERE _TABLE_SUFFIX BETWEEN '20261101' AND '20261114'
    AND event_name = 'attachment_fetch_start'
)
SELECT fetch_second, kind, COUNT(DISTINCT user_pseudo_id) AS devices
FROM fetches GROUP BY fetch_second, kind HAVING devices > 1 ORDER BY devices DESC;
```

The `_TABLE_SUFFIX` filter is not optional: it excludes `events_intraday_*` from the wildcard and
stops a full-dataset scan against the 1 TiB monthly allowance. Dry-run every query first.

## 11. Open questions

1. **Per-device exclusion.** Decided: not built. The policy says only that a single switch governs
   every phone. Revisit only if the policy is to promise more, and then build it first (an
   exclusion list keyed by activation code, read alongside the kill switch).
2. **Sunset.** The kill switch turns collection off; nothing removes the code. Whether 0.8 strips
   it is a later decision.
3. **GA4 streaming on sandbox** is documented for a sibling product but not for GA4 specifically.
   Assume daily-only; confirm from what the link wizard actually offers.
4. **Two facts we have not established, and what the policy does about them.** (a) *GA4's location
   precision*: whether the IP-derived geo record is city-level or coarser. (b) *Where Crashlytics
   holds its data*: which country or region. The published policy therefore states the
   conservative superset in both cases ("at least the country, and possibly the town or city";
   Crashlytics data "on Google's own servers", no country). Tighten the wording only once either
   is confirmed: a real exported event's `geo` record settles the first, Google's own
   documentation settles the second. Do not trust a precision nobody checked, and do not quietly
   weaken a disclosure that was chosen deliberately. Separately, the policy says Google may attach
   more than it lists; that stays whatever is confirmed, because a third party's telemetry cannot
   be enumerated from here.

## 12. Before the collecting build ships

Actions only the account owner can take. Order matters where stated: Data Safety is declared
app-wide and the open-testing track is public, so **items 4 and 5 must be live before a collecting
build reaches open testing**, not alongside it. Item 10 is what starts collection, so do it last.

1. **GA4 retention.** Admin, Data settings, Data retention: 2 months, and turn OFF "Reset user data
   on new activity". Skipped: the policy's "two months" is false.
2. **Crashlytics retention.** Verify it is 90 days; change the policy number if not. Skipped: a
   wrong number in a public promise.
3. **BigQuery dataset.** Confirm it is `asia-south1` and that the 60-day sandbox expiry applies.
   Skipped: the policy's "Mumbai" and "sixty days" may be false.
4. **Publish the updated policy** on paramanuseniorshealth.org, with "Last updated" set to the
   actual publication date. Skipped: a collecting build ships under a policy saying it collects
   nothing, or a public document carries a wrong date.
5. **Update Play Data Safety:** App activity, Crash logs, Diagnostics, Device or other IDs = yes;
   Advertising ID = no; required; not linked; encrypted in transit; **"can users request deletion"
   = No**. Skipped, or left at "Yes": an inaccurate or false declaration.
6. **Decide whether Data Safety needs an approximate-location category** (Google derives rough
   location from the internet address) and anything for the install-referrer and attribution
   permissions the measurement SDK contributes. Skipped: possible under-declaration.
7. **Decide on GA4's granular location and device data collection setting.** It reduces what is
   gathered. Skipped: the disclosure stands as written and remains true.
8. **Decide whether to keep the sunset sentence** in the policy's Measurement bullet ("will turn it
   off once those questions are answered"). Skipped: nothing breaks, but the organisation is
   publicly committed to it.
9. **Deploy rules and config with the `firebase` CLI where relevant.** Skipped: the app reads
   `/config/analytics/enabled` against whatever rules are live.
10. **Set `/config/analytics/enabled = true` in RTDB** (hand-edited) when collection should begin.
    Skipped: nothing is collected, crashes included, by design.
