# Analytics: on-device verification

This is the walk that finishes the analytics rollout. Everything in the code and unit tests is
done and reviewed; what remains is watching the thirteen events leave a real handset. Only the
owner can do it, because it needs an unlocked phone, a production database switch, and a decision
about spending an activation code.

Design background is in `docs/superpowers/specs/2026-10-15-analytics-design.md`. This document is
referenced from its section 12.

## Why this walk matters

The unit tests encode our belief about GA4's rules (reserved names, name length, parameter caps).
DebugView is the only thing that tests that belief. Two failures can only be caught here:

- **An event name the SDK drops silently** (reserved, over-long, or over a parameter cap). GA4 does
  not backfill, so a silently rejected name means that question is unanswerable for the whole
  collection window.
- **A parameter that never arrives.** The event shows up but a field is missing.

For every event, check the name appears and every parameter listed below is present with a sensible
value.

## Result of the 2026-10-16 walk: all thirteen events observed

Run on `f0d9797`, debug build, `SM_S928U1` / Android 16, fresh install, switch `true`, every line
attributed to `app_id: org.paramanuseniorshealth.notices` via the upload records. **All thirteen
event names arrive with their parameter sets intact** — no name was silently dropped, which was the
one failure this walk existed to rule out.

**Every payload size below is a test asset and is not representative of production.** The PDF was
13,374,538 bytes and the image 1,015,470; real circulars are not that shape, and notices go out
only about once a fortnight. Read these sizes as evidence that the parameter arrives and is
numeric, never as evidence about what readers actually download.

Observed values:

| # | Event | Observed |
|---|---|---|
| 1 | `fetch_policy_decision` | `kind=img\|pdf\|pdf_thumb`, `probe=reachable`, `decision=fetch`, `size_known=1`, `size_bytes=5804…13374538` |
| 2 | `network_state_on_open` | `metered=0, roaming=0` (×11) |
| 3 | `attachment_fetch_start` | both shapes: `jitter_seconds=5…32` + `enqueued_at_ms` on jitter-enqueued rows, both absent on sweep and tap rows; `attempt=0` |
| 4 | `attachment_fetch_end` | `outcome=fetched`, `duration_ms=14…1014`, `http_status=200`, `size_bytes` — and twice `size_bytes=0`, see the race below |
| 5 | `notice_received` | all four `has_image`/`has_pdf` combinations, `is_new=1`, `delivery_lag_ms=43…388680` (the large values are a genuine FCM queue delay) |
| 6 | `notification_tapped` | `cold_start=1`, `ms_since_shown=31879`, `ms_since_process_start=34023` |
| 7 | `app_open_source` | all three values: `launcher`, `link`, `notification` — and correctly silent on warm returns |
| 8 | `notice_card_visible` | `notice_id` 1, 8–13; `position` 1–6; `visible_ms=101…265953` |
| 9 | `image_viewer_closed` | `notice_id=8`, `duration_ms=11340`, `max_scale=1.0` |
| 10 | `pdf_opened` | `notice_id=10`, `was_cached=0` then `1`, `size_bytes=13374538` |
| 11 | `activation_shown` | `has_code=0` |
| 12 | `activation_submitted` | no params |
| 13 | `activation_result` | `mistyped`, `notaccepted`, `success` — never carrying the code |

Also confirmed: `first_open`, `session_start` and `ga_campaign` arrive on the first *enabled*
session, settling spec §9's item 4.

**Parameter values still unobserved.** None risks a silently dropped event name, so these are
lower-stakes than the above, but no device has produced them:

- `max_scale` above 1.0 — needs a real two-finger pinch; `adb input` cannot do multi-touch, and the
  viewer has no double-tap zoom.
- `activation_result = offline` with `offline_cause`, and `= samecode`. The offline arm needs the
  phone genuinely off the network; note that a "wifi off" automation may switch mobile data on,
  which is what defeated the attempt on 2026-10-16. Use airplane mode, and keep adb on **USB** so
  the connection survives.
- `decision = defer`, `probe = skipped|sender|unreachable`, `metered=1`, `roaming=1` — all need a
  metered or roaming network, or an unreachable host.
- `outcome = failed`, `attempt > 0` — need a download interrupted mid-flight.
- `is_new = 0` — needs the same `logId` delivered twice.

### The defect this walk found

`attachment_fetch_end` twice reported `size_bytes=0` with `outcome=fetched` and `http_status=200`.
Cause, pre-existing and not from the analytics work (`1013305`, 2026-09-09):
`NoticeImageStore.kt:302` names its scratch file `notice_$logId-doc.part` with no uniqueness, while
the image path at `:237` correctly uses `File.createTempFile`. Two concurrent fetches of one
notice's PDF — a tap-time fetch racing the catch-up sweep, which is exactly what tapping a
notification produces — share that path, and the winner's `moveInto` leaves the loser's
`target.length()` at 0 while `completed` is still true.

The analytics consequence is a 0 that reads as a real measurement. The product consequence is
worse: the file is lost, so nothing is cached. After the sweep had already downloaded a 13,374,538-byte
PDF, tapping it logged `was_cached=0` and downloaded the same 13 MB again — verified by the
`attachment_fetch_start`/`end` pair at 10:23:59. Only 3 of the 6 PDFs delivered that sitting were
on disk afterwards. Paying twice for any circular over mobile data is a real cost to the reader
(the sizes above are test assets, so take the doubling rather than the megabytes as the point), and it
also defeats the stated purpose of the scratch name at `:304` ("an interrupted download cannot
leave a truncated file that looks cached").

## Appendix: the OFF path, verified earlier

Observed before the switch was turned on, on the same build. Decisive lines, all from the app's own
process:

```
I FA : App measurement disabled via the manifest
I FA : App measurement initialized, version: 190010
V FA : Persisting first open: 1792143140354
V FA : App measurement setting deferred collection: true
D FA : Setting app measurement enabled (FE): false
D FA : Event not sent since app measurement is disabled
```

The merged and installed manifests carry `google_analytics_adid_collection_enabled`,
`firebase_analytics_collection_enabled` and `firebase_crashlytics_collection_enabled`, all `false`.
The Crashlytics preference file holds `firebase_crashlytics_collection_enabled = false`. The capture
contained no `Uploading data`, `Network upload` or `Logging event` lines.

**The fail-safe was then verified for real.** Before the `config/analytics` read rule was deployed,
a fresh install logged `AnalyticsConfig: Could not read the analytics switch: Permission denied`
twice, wrote no `analytics.xml`, and left `Setting app measurement enabled (FE): false` — no
`Logging event`, no `Uploading data`, no `Network upload` from our process. An unreachable switch
leaves collection off rather than falling open, and a phone that has never reached the node reads
off. The two denials 0 ms apart are the `onCreate` read plus the first `ON_RESUME` re-read, which
incidentally confirms the foreground re-read landed.

Both captures above describe the **pre-2026-10-16 switch**, read from RTDB and cached in
`shared_prefs/analytics.xml`. The finding they establish still holds — an unreachable switch leaves
collection off — but the log lines, the permission denial and the prefs file belong to a mechanism
the app no longer has. What replaced it is in §7 of the spec.

Limits of the original capture, stated plainly:

- The capture covered a **48 ms foreground behind a locked screen, with the phone in Doze**, then
  about 90 seconds in the background. It is not a test of the gate under use.
- **The config read did not complete in that run.** No `analytics.xml` was written, and that file
  appears only after a successful read of `/config/analytics/enabled`. So the
  `/config/analytics/enabled = false` path is untested; what was seen is the cached default on a
  fresh install.
- **The app was never exercised** (no scrolling, Settings, or background/foreground cycles).
- No Crashlytics network traffic was observed; "nothing sent" is inferred from the disabled flag.
- Debug builds are signed differently and GMS refuses its remote flag store for them
  (`GoogleCertificatesRslt: not allowed`, harmless noise). A debug build is not a perfect proxy for
  release measurement flags.

## Prerequisites

1. **An unlocked handset, kept awake** (stay-awake in developer options, or keep touching it). Doze
   blocks the app's network, which is exactly what stopped the earlier run.
2. **The Remote Config parameter `analytics_enabled` published as Boolean `true`.** The app calls
   `getBoolean`, so the parameter must be Boolean-typed in the console, not a String. (Until
   2026-10-16 this was the RTDB node `/config/analytics/enabled`; that node and its read rule are
   dead and the app no longer looks at them.)
3. **A fresh install with no activation.** The `activation_*` events fire only in the unactivated
   state, so do those first. A fresh install of the debug build currently has none (the notice
   list is empty and the activation screen is what a user sees).
4. Wireless or USB debugging connected, and the Firebase console open at Analytics > DebugView.

## Commands that are easy to get wrong

**Install the APK from `app/build/outputs/apk/debug/app-debug.apk`, never from
`app/build/intermediates/apk/debug/`.** Both paths exist and hold a file of the same name. The
intermediates copy is not refreshed by every build and can be hours or days old, and `adb install`
reports `Success` either way — so the walk silently tests code that is not the code you just wrote,
and the conclusions look real. This cost an hour on 2026-10-17: three "verifications" of a switch
whose wiring was not in the installed binary at all. Check it before believing a result:

```
adb shell dumpsys package org.paramanuseniorshealth.notices | grep -E "versionCode|lastUpdateTime"
```

`versionCode` must match `app/build.gradle`, and `lastUpdateTime` must be the install you just did.
`app/build/outputs/apk/debug/output-metadata.json` states the versionCode the build actually produced.

`adb` is **not on PATH** on this machine. Use the full path:

```
C:\Users\vamsi\AppData\Local\Android\Sdk\platform-tools\adb.exe
```

In the steps below, `adb` stands for that path. (In Git Bash it is
`$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`.)

Verbose SDK logging (resets on reboot):

```
adb shell setprop log.tag.FA VERBOSE
adb shell setprop log.tag.FA-SVC VERBOSE
```

DebugView needs the package named explicitly:

```
adb shell setprop debug.firebase.analytics.app org.paramanuseniorshealth.notices
```

Undo it when finished:

```
adb shell setprop debug.firebase.analytics.app .none.
```

Start a clean capture **before** launching the app:

```
adb shell am force-stop org.paramanuseniorshealth.notices
adb logcat -c
adb logcat -v threadtime > partB.log
```

The app's pid, for filtering: `adb shell pidof org.paramanuseniorshealth.notices`.

## Warning: never `am force-stop` during a push test

`am force-stop` puts the package in the **stopped state** (`dumpsys package … | grep stopped=`),
and **FCM does not deliver to a stopped app**. In the 2026-10-16 sitting this silently swallowed
about 26 of 31 test sends: the only ones that arrived were those that happened to land while a
process was alive. There is no error anywhere — the sends simply never appear.

Only a launch clears the flag; `am set-stopped-state` and `cmd package set-stopped-state` do not
exist on this device's build. So, for anything involving a real push:

```
adb shell am start -n org.paramanuseniorshealth.notices/.MainActivity   # clears stopped=true
adb shell am kill org.paramanuseniorshealth.notices                     # drops the process, flag stays clear
```

`am kill` is the right tool for a cold-start test: the process dies, FCM still delivers, and the
next push starts a fresh process, so `cold_start=1` is still reachable. Confirm with
`dumpsys package org.paramanuseniorshealth.notices | grep -oE "stopped=[a-z]+"` before asking the
sender for anything.

## Warning: test notices are silent and easy to miss

With the Testing switch on, test notices post to channel `notices_testing` at **importance 2**
(low): no sound, no heads-up, filed in the collapsed silent section of the shade. They are easy to
read as "nothing arrived" — this happened during the 2026-10-16 sitting. Confirm with
`adb shell dumpsys notification --noredact | grep -c "pkg=org.paramanuseniorshealth.notices"`
rather than by looking at the phone. Real notices use `notices_default` at importance 4 and are
unaffected.

## Warning: other apps share the logcat

The phone carries other apps whose measurement traffic appears in the same logcat, and a capture on
this branch was once briefly misread because of it. **Attribute every line to
`org.paramanuseniorshealth.notices`, by pid or by package, before it counts as a conclusion.** In
DebugView, make sure the selected device and app are ours.

## Step 0: turn collection on and confirm it took

After publishing `analytics_enabled = true` in Remote Config, force-stop and relaunch the app so
the fetch brings the new value down and activates it. Debug builds use a zero-second minimum fetch
interval, so there is nothing to wait for. **Before judging any event**, expect
`Setting app measurement enabled (FE): true` and no
`RemoteConfigSwitch: Could not fetch the analytics switch` line. If collection is not enabled yet,
events are dropped, not delayed, so a missing event proves nothing.

**A throttled fetch is an expected outcome, not a failure of the switch.** Zero seconds removes only
the *client* throttle; the backend keeps its own per-installation limit, and crossing it raises
`FirebaseRemoteConfigFetchThrottledException`. You will then see
`RemoteConfigSwitch: Could not fetch the analytics switch: ...` and **the previous value staying in
force** — which is correct behaviour (a failed fetch leaves the standing value alone) and will look
exactly like "the new value did not propagate". Verified on 2026-10-16: six fetches across five
launches in four minutes were **not** throttled (`num_failed_fetches=0`, `backoff_end_time=-1` in
`shared_prefs/frc_*_firebase_settings.xml`), so a handful of toggles in one sitting is fine. If it
does throttle, check those two fields, then either wait out the backoff or
`adb shell pm clear org.paramanuseniorshealth.notices` and re-activate. Do not read a throttle as a
broken switch.

There is **no `shared_prefs/analytics.xml` any more** — the switch moved off RTDB and
SharedPreferences on 2026-10-16, and Remote Config keeps the activated value in its own store under
`files/`. A new `analytics.xml` appearing is a sign something still writes the old cache, which
nothing should.

In logcat an event appears as `FA-SVC: Logging event: origin=app,name=<event>(...)` and, in debug
mode, `Logging event (FE): ...`.

## The thirteen events

| # | Event | Trigger | Confirming evidence |
|---|---|---|---|
| 1 | `fetch_policy_decision` | Runs in the attachment worker for any notice whose attachments are not yet fetched. The catch-up sweep is enqueued on every foreground, so a new notice is not needed if one has an unfetched attachment. Roaming gives `probe=skipped`. | Params `kind`, `probe` (skipped/sender/reachable/unreachable), `metered`, `roaming`, `decision` (fetch/defer), `size_known`, `size_bytes`. Partly on demand: needs a notice with a missing or deferred attachment. |
| 2 | `network_state_on_open` | Every foreground: background the app and reopen it. | `metered` and `roaming` as 0/1. Switch between wifi and mobile data to see `metered=1`. |
| 3 | `attachment_fetch_start` | A worker or tap-time download of an image or PDF begins. | `kind`, `started_at_ms`, `attempt`. `jitter_seconds` and `enqueued_at_ms` appear only for jitter-enqueued fetches (a new notice), not for catch-up sweeps or taps. Tapping an uncached PDF gives it without jitter fields. |
| 4 | `attachment_fetch_end` | End of that download. | `kind`, `duration_ms`, `outcome` (fetched/failed), `size_bytes` (only if fetched), `http_status` (if a response arrived). Airplane mode mid-tap gives `outcome=failed`. |
| 5 | `notice_received` | A real push message reaching the app, after the notice is saved. | `has_image`, `has_pdf`, `is_new`, `ms_since_process_start`, `delivery_lag_ms` (absent if the push carried no send time). **Not on demand**, see below. |
| 6 | `notification_tapped` | Tap the system notification of a real notice. Test and revocation notifications are excluded on purpose. | `cold_start`, `ms_since_process_start`, `ms_since_shown` (absent if the posting time is missing or in the future). **Not on demand**, see below. Tap once from a killed process and once from a live one to see both `cold_start` values. |
| 7 | `app_open_source` | Cold-launch from the launcher (`source=launcher`); tap a real notice notification (`source=notification`); open an `https://paramanuseniorshealth.org/activation?code=...` link (`source=link`). | `source` string. Launcher and link are on demand. The Settings "send test notification" also yields `source=notification`. |
| 8 | `notice_card_visible` | On the notice list, scroll so a card is on screen, hold, then scroll away or background the app (the stretch is reported when it ends or the activity stops). | `notice_id`, `visible_ms`, `has_image`, `has_pdf`, `position` (1-based, excludes the info header). Needs at least one notice. |
| 9 | `image_viewer_closed` | Open a notice's image, pinch to zoom, press Back. | `notice_id`, `duration_ms`, `max_scale` as a double above 1.0 after pinching. Needs a notice with a cached image. |
| 10 | `pdf_opened` | Tap a notice's PDF. Tap once uncached (preceded by `attachment_fetch_start/end` with `kind=pdf`), then again. | `notice_id`, `was_cached` (0 then 1), `size_bytes`. Logged before the external viewer launches. Needs a notice with a PDF. |
| 11 | `activation_shown` | Reach the activation screen. Fires once per appearance. | `has_code` is **0 on a fresh install and 1 on re-entry from Settings** — it means the phone already holds a working code, *not* that a link carried one. A link with `?code=` still gives `has_code=0` on a fresh install (verified 2026-10-16). A revoked phone counts as 0. So `has_code=1` needs an activated phone, and cannot be reached before redeeming. |
| 12 | `activation_submitted` | Press the activate button. | No params. |
| 13 | `activation_result` | The outcome of that submit. | `result` string (never the code); `offline_cause` only when the result is Offline. |

### Activation events and the cost of `success`

`activation_shown` and `activation_submitted` can be exercised freely. For `activation_result`:

- A deliberately **wrong, well-formed code** (never one of the printed codes) should give a
  non-success result without spending anything. Check first that the server does not rate-limit
  or log invalid attempts in a way you mind.
- Airplane mode gives `Offline`, with `offline_cause` present.
- **`activation_result = success` needs a real redemption.** That binds the activation code to this
  handset and **cannot be undone from the app**. The testing code is the one to use (the earlier
  run was prepared with `AXGXBBBE`), and whether to redeem it is the owner's decision. Skipping it
  leaves `success` unobserved; the other results and the unit tests cover the shape.

### What cannot be triggered on demand

`notice_received` and `notification_tapped` need the sender to push a real notice while the phone
is activated. `adb` cannot inject a push message into the app. Next best evidence, in order:

1. Ask the sender to publish one real notice with an image and a PDF during the sitting. That also
   yields `attachment_fetch_*` with jitter fields and `fetch_policy_decision` in the same pass.
   Tap its notification from a killed process first.
2. Failing that: `app_open_source` with `source=notification` from the Settings test notification
   proves the intent-extras path, but **not** `notification_tapped`.
3. The unit tests cover the parameter shapes of both events and the branch logic of the open
   logging. They are belief, not observation.

`fetch_policy_decision` and `attachment_fetch_*` are reachable without a new notice only if some
notice has an unfetched attachment; otherwise a tap-time PDF fetch gives start/end but not the
policy decision.

## Suggested order for one sitting

1. Set the switch to `true`, relaunch, confirm `analytics.xml` (Step 0).
2. On the activation screen: `activation_shown`; submit a bad code or go offline for
   `activation_submitted` and a non-success `activation_result`; then, only if the owner decides to,
   redeem the testing code for `success`.
3. Foreground and background twice: `network_state_on_open`, `app_open_source=launcher`.
4. Settings test notification, tap it: `app_open_source=notification`.
5. Sender pushes one real notice with image and PDF: `notice_received`, the fetch events, then tap
   its notification from a killed process for `notification_tapped`.
6. Scroll to the card, hold, scroll away: `notice_card_visible`.
7. Open the image, pinch, Back: `image_viewer_closed`.
8. Tap the PDF, then again: `pdf_opened` with both `was_cached` values.
9. Set the node back to `false` if collection is not meant to start yet. Force-stop, reopen, and
   confirm DebugView and logcat fall silent. Undo the DebugView property.

## The open question this walk settles

Spec section 9.4: **whether `first_open` arrives on the first enabled session.** The OFF-path
capture showed the SDK persisting a first-open instant and entering deferred collection, so the spec
deliberately asserts neither outcome. Observing the first enabled session resolves it: check
DebugView and logcat for a `first_open` event on a session that starts with collection on (a fresh
install taken after the switch is `true` is the clean case). **When it is resolved, update spec
section 9.4 to say which happened.**
