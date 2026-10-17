# Handoff — Remote Config switch, delivery logging, and the policy — 2026-10-17

Picks up from `docs/2026-09-21-handoff-and-open-questions.md`. Everything below is state, not
narrative: what is done, what is true, what is still open, and the traps that cost time.

## Where the code is

Branch `analytics`, **local only, never pushed**, 8 commits on top of `3249146`:

| Commit | What |
|---|---|
| `ce1e7db` | `AnalyticsSwitch` seam + `RecordingSwitch` test double |
| `e0b6237` | Kill switch moved RTDB → Remote Config; `config/analytics` rule removed from `database.rules.json` |
| `5e9c498` | Spec §7/§12, runbook, SYSTEM.md rewritten for the new mechanism |
| `0995a0c` | First read off the main thread; `AnalyticsConfig.DEFAULT` pinned by a test |
| `f4cc4dd` | FCM delivery logging wired behind the same switch |
| `a97e252` | **Crashlytics taken out of the switch**, always on |
| `3b88365` | Collection setters log instead of swallowing; stale-APK trap documented |
| `1437192` | First-launch gate re-measured; spec §7's "no window" claim corrected |

Suite: **22 suites / 192 tests / 0 failures**. `assembleDebug` and `processReleaseMainManifest` both
green. Branch is **not merged and not pushed**; `superpowers:finishing-a-development-branch` has
never been run for it.

## What the switch governs now

`analytics_enabled` (Remote Config, **Boolean**, currently `true`) drives exactly two things through
`NoticesApplication.applyCollectionSwitch`:

- GA4, via `Analytics.setEnabled`
- FCM delivery logging, via `FirebaseMessaging.setDeliveryMetricsExportToBigQuery`

**Crashlytics is deliberately not one of them.** It ships `true` in the manifest and is set `true`
unconditionally in `onCreate`, because the setter persists and every install that ran an older build
carries `false` in `com.google.firebase.crashlytics.xml`. Diagnostics are not behaviour, and the
switch spends most of its life off.

Release fetch interval is 6 hours; debug is 0.

## Verified on the handset (SM_S928U1, 2026-10-17)

- **Fail-safe on a genuinely cold install**: `measurement enabled: false` at 02:24:15.370, activation
  screen composes at .379, `true` only at .995 when the first fetch returns. A phone that has never
  fetched collects nothing. This is the guarantee the privacy policy rests on, and it is now observed
  rather than inferred from a test double.
- **Delivery logging tracks the switch both ways**: `wanted=false reported=false` before the fetch,
  `wanted=true reported=true` after, with `export_to_big_query` landing in the messaging prefs.
- **Crashlytics reads `true` while measurement is off** — the separation works.
- **`activation_shown` is still lost on a first install** (§9 item 22 re-measured: ~600 ms now, was
  752 ms under RTDB). Cause changed, outcome identical. The funnel's first step is missing on exactly
  the install the funnel is about.

**Not verified:** that the unconditional Crashlytics write actually overwrites a *persisted* `false`.
The handset was uninstalled before that could be tested, which wiped the state that made it a real
test. The 14 closed testers are still in that state, so this is worth catching on the next install
over an older build.

## Console and configuration state

- **Remote Config**: `analytics_enabled` exists, Boolean, `true`. Collection is **on** for the ~10
  closed testers right now.
- **BigQuery**: linked 2026-10-17, `asia-south1`, daily batch, sandbox 60-day expiry.
  `firebase-measurement@system.gserviceaccount.com` is in project IAM, which is the marker that the
  link is real. Datasets `firebase_crashlytics`, `firebase_messaging`, `firebase_performance`,
  `firebase_sessions` were created; **`analytics_<property id>` had not appeared yet** — expected
  within 24–48h of linking. The hand-made `analytics_paramanu_seniors_notices_app` is empty and
  always will be; delete it once the real one appears.
- **GA4 granular location and device data: OFF** (confirmed 2026-10-17). City, device brand/model/name
  and screen resolution are no longer collected; **country remains**. Existing city values persist
  until retention rolls them off.
- **`firebase_messaging` is not empty by default**: `MESSAGE_ACCEPTED` rows arrive server-side from
  the moment the link exists, with no app change and no flag. Only `MESSAGE_DELIVERED` depends on
  the app.
- **Apps Script / stage 1**: being attached to a GCP project using the **utils domain as a bridge**,
  because `paramanuseniorshealth.org`'s Search Console sits under another account and Cloudflare DNS
  access is unavailable for ~2 days. Nothing is to be built in utils; it is a domain bridge only.

## Policy and Terms

Both live in the **website repo**, `content/privacy-policy-app.md` and `content/terms-of-service.md`,
committed and pushed to `main` there (`9c66ebc`, `307b5ff`).

**They are not published.** That repo is a fork of `revanthp/parmanuseniorhealth.github.io`; Pages is
not enabled on the fork and the live domain is served from the parent. Publishing means a PR
upstream, deliberately not done yet.

`docs/privacy-policy.md` in *this* repo is a stale duplicate and is not the live document. Do not
edit it expecting it to matter.

Outstanding on the documents:

- Attorney review of both, including the Terms' **governing-law clause** (drafted as India, courts at
  Mumbai), the limitation of liability, and whether "Paramanu Seniors Health" is the registered
  entity name.
- The Terms carry an effective date of 17 October 2026, which must match the actual publication day.
- The policy says measurement is not collected all the time and is turned off once the questions are
  answered. That is a real commitment and it is currently being broken — see below.

## Open issues

| # | What |
|---|---|
| [#7](https://github.com/vamsi-alluri/Paramanu-Seniors-Notices/issues/7) | Nine operational tunables → Remote Config, with the `const val` inlining trap |
| [#8](https://github.com/vamsi-alluri/Paramanu-Seniors-Notices/issues/8) | PDF scratch filename race (pre-existing, `1013305`) |
| [#9](https://github.com/vamsi-alluri/Paramanu-Seniors-Notices/issues/9) | Resolved as single-switch; left open only for the Crashlytics persisted-`false` check |
| [#10](https://github.com/vamsi-alluri/Paramanu-Seniors-Notices/issues/10) | What else leaves RTDB — dispensary yes, codes never, worker is a bug |
| [#11](https://github.com/vamsi-alluri/Paramanu-Seniors-Notices/issues/11) | `goOffline()` never paired with `goOnline()` |
| [#12](https://github.com/vamsi-alluri/Paramanu-Seniors-Notices/issues/12) | Apps Script → GCP project → Functions → backend → own domain. **Gated on Blaze billing.** |

## Pick up here

1. **Check BigQuery.** `analytics_<property id>` should exist with an `events_YYYYMMDD` table. Verify
   `geo.city` is `(not set)` and device model null on new rows while `geo.country` is populated —
   that confirms the location toggle rather than trusting the console.
2. **Consent screen publishing status.** External + Testing carries a ~7-day token expiry, against a
   per-minute control trigger and an 8-hourly poller. No sensitive scopes appear to be used, so
   Production should not trigger a verification review. This is the thing most likely to break
   unattended.
3. **Attorney pass**, then the upstream PR to publish both documents.
4. **Decide when measurement goes off.** The §1 questions need 2–3 notice cycles of production data
   (~early December). The published policy promises it is turned off afterwards; nothing will remind
   you.

## Traps that cost time today, so they do not cost it twice

- **Stale APK.** `app-debug.apk` exists under both `build/outputs/apk/debug/` and
  `build/intermediates/apk/debug/`. The intermediates copy is **not refreshed by every build** and was
  11 hours stale; `adb install` says `Success` either way. Three "verifications" ran against a binary
  that did not contain the code under test. Install from `outputs/`, and confirm `versionCode` and
  `lastUpdateTime` from `dumpsys` before believing a result. Now in the runbook.
- **The website repo is a fork.** A push there publishes nothing. I claimed otherwise and raised a
  false alarm about an unreviewed legal clause being public.
- **RTDB logs nothing at the default log level**, and activation opens the same socket on the same
  `ON_RESUME`, so neither logcat nor socket inspection can prove the switch stopped opening
  connections. A `setLogLevel(DEBUG)` probe was built and could not be validated either. The claim
  rests on the code and on the positive Remote Config evidence, not on a device-level negative.
- **`UrlFetchApp` quota is account-wide, not per project.** Attaching Apps Script to a different GCP
  project buys visibility into it, never relief from it.

## One known defect in this branch's history

`3b88365` swept in **`docs/privacy-policy.md`**, which is Vamsi's edit, not Claude's — the commit
used `git add -A app docs` instead of naming files. That commit carries a `Co-Authored-By: Claude`
trailer and its message does not mention the file at all, so the history claims authorship of a
change Claude did not write and hides that it is in there. That is the exact thing
`dont-commit-or-coauthor-vamsis-code` exists to prevent.

The branch is local and unpushed, so it is cheap to fix: rebase `3b88365` into two commits — the
`NoticesApplication` guard and the runbook note (Claude's, trailer kept) and the `privacy-policy.md`
edit (Vamsi's, no trailer). Not done unasked, because rewriting history is his call. Recorded here
so it is not quietly forgotten.
