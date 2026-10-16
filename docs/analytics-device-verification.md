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

## What has already been verified, and its limits

Only the **OFF path** has been observed, on the current debug build (versionCode 13, versionName
0.7.2, fresh install). Decisive lines, all from the app's own process:

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

Limits, stated plainly:

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
2. **`/config/analytics/enabled` set to the boolean `true`** in the production Realtime Database. It
   must be a real boolean, not the string `"true"`; the parser requires a literal boolean.
3. **A fresh install with no activation.** The `activation_*` events fire only in the unactivated
   state, so do those first. A fresh install of the debug build currently has none (the notice
   list is empty and the activation screen is what a user sees).
4. Wireless or USB debugging connected, and the Firebase console open at Analytics > DebugView.

## Commands that are easy to get wrong

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

## Warning: other apps share the logcat

The phone carries other apps whose measurement traffic appears in the same logcat, and a capture on
this branch was once briefly misread because of it. **Attribute every line to
`org.paramanuseniorshealth.notices`, by pid or by package, before it counts as a conclusion.** In
DebugView, make sure the selected device and app are ours.

## Step 0: turn collection on and confirm it took

After setting the database node to `true`, force-stop and relaunch the app so the live read stores
the new value. **Before judging any event**, confirm:

```
adb shell run-as org.paramanuseniorshealth.notices cat shared_prefs/analytics.xml
```

It must show `<boolean name="analytics_enabled" value="true" />`. If the file already existed,
check that its modification time is later than the launch. Also expect
`Setting app measurement enabled (FE): true` after the live read, and no
`AnalyticsConfig: Could not read the analytics switch` line. If collection is not enabled yet,
events are dropped, not delayed, so a missing event proves nothing.

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
| 11 | `activation_shown` | Reach the activation screen. Only in the unactivated state, so do this **before** redeeming. Fires once per appearance. | `has_code` is 0, or 1 when you arrive through a link carrying `?code=`. |
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
