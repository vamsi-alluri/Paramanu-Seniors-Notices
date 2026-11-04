# Analytics report — 2026-11-03

**Window:** `events_20261016` to `events_20261102` (the export is a daily batch, so 2026-11-03 has not landed).
**Sources:** the saved views in `paramanu-seniors.notices_analytics` (`real_events`, `daily_cohort`,
`notice_delivery`, `fetch_policy`, `attachment_fetches`, `activation_funnel`, `notice_engagement`), the
four §5 queries in `docs/code-management-runbook.md`, the clustering query in
`docs/superpowers/specs/2026-10-15-analytics-design.md` §10, and `firebase_crashlytics`.
**Filtering:** everything goes through `real_events` unless stated (drops 0.7.2, `SM-S928U1`, and
`IN2025`/Android 11).

**Views rewritten 2026-11-04.** The views now live in `docs/analytics/views/`. `real_events` also drops
dev builds 0.8.2–0.8.4, the NULL-model robo rows and intraday tables. The figures below were taken
**before** that, so they include 0.8.2/0.8.3 traffic. Rerun the views for current numbers; for example,
`attachment_fetches` now shows pdf 109 fetches / 16 failed instead of 160 / 23.

**No real circulars from 2026-10-27 to 2026-11-04.** On 10-27, `ALERTS_FEED_URL` was pointed at a test
feed instead of the live website, and the poller stayed broken until Vamsi fixed it on 11-04. No real
notice was scheduled in that time, so nothing was missed. But every notice in this window from 10-27 on
(the 10-28 batches, the three on 10-29, the one on 11-01) is **not a production circular**. Per-notice
figures (delivery lag, reach, card attention, attachment sizes, fetch decisions) describe test or manual
sends, and those don't represent production (see `notice-cadence-and-test-payloads`). Per-open figures
(app opens, network state, activation, code_management) are unaffected.

**Read this before the numbers:** roughly 20 to 25 real devices are in the data. Test sends and real
notices can't be told apart in GA4. The 10-27 and 10-28 bursts (five notices within seconds, at 02:43,
06:44 and 10:43 IST) look like poller or test batches. The cleanest single broadcast is the **11-01 14:56
IST notice** (log_id `1793525197998`), which reached 19 devices once each. Per the note above, it was
not a production circular. `event_date` is in the
property's timezone, not IST.

## Summary

| Question | Answer | How firm |
|---|---|---|
| Is 0.8.5 healthy? | Yes. No fatal crashes on 0.8.5, no 5xx from code_management, and on 11-02 every active device was on 0.8.5. | Firm |
| Can the roaming/metered rule go? | The roaming arm never fired (zero roaming rows). The metered arm mostly delays: 40 of 44 devices were on wifi at least once. | Firm for this cohort |
| Is `MAX_JITTER_SECONDS = 60` right? | No clustering seen: at most 2 devices fetched in the same second, and that happened 5 times. | Weak (about 20 devices, not 400) |
| Notification or app as entry point? | The app. Launcher opens outnumber notification opens about 4.5 to 1. | Moderate |
| What do people read? | Card text, briefly. In-app PDF and image opens are rare. | Moderate |
| Do slips work at the counter? | 22 of 33 submits succeeded. 4 failed on connectivity, 3 of them the 20 s timeout. | Moderate |

## 1. Rollout and cohort

- The GA4 screenshot's version rows are not all testers:
  - **0.8.2 and 0.8.3 are dev builds.** Almost all of their devices are US-geo, and neither build shipped to a track.
  - **0.8.4 is the Play pre-launch robo farm** (Android 11, no geo, `first_open` + `session_start` only).
- Migration is real. `daily_cohort` on 11-02 shows `app_versions = 0.8.5` only, across 14 devices. 25 devices have made a 0.8.5 code_management call (a few are dev phones).
- **`real_events` lets robo rows through.** The robo farm now reports `model = NULL`, so the `IN2025` filter misses it. Android 11 rows with empty geo appear on 0.8.1, 0.8.2, 0.8.4 and 0.8.5. Add `AND IFNULL(geo.country,'') != ''` (or the Android 11 + no-geo pair) to the view.
- `device.mobile_model_name` is NULL on most rows since about 10-17. Any filter written as `model != 'X'` silently drops those rows; use `IFNULL(model,'') != 'X'`, as `real_events` does.

## 2. Stability

- **Crashlytics: 3 fatals, all on 0.8.1, all the same issue.** It's an NPE in `com.google.android.gms.auth.api.signin.internal.SignInHubActivity.onCreate` (play-services-auth 20.7.0). It hit HRY-LX1T twice (10-25) and OnePlus8Pro once (10-27). There are none on 0.8.5 yet, but 0.8.5's exposure is small; see open issues.
- No `app_exception` events in GA4.

## 3. code_management (runbook §5, run for 0.8.5)

| Day | Calls | Inconclusive | 5xx | No response | Inconclusive share |
|---|---|---|---|---|---|
| 11-02 | 30 | 2 | 0 | 2 | 0.067 |
| 11-01 | 134 | 8 | 0 | 8 | 0.060 |
| 10-31 | 9 | 1 | 0 | 1 | 0.111 |
| 10-30 | 27 | 4 | 0 | 4 | 0.148 |
| 10-29 | 21 | 3 | 0 | 3 | 0.143 |
| 10-28 | 24 | 1 | 0 | 1 | 0.042 |
| 10-27 | 1 | 0 | 0 | 0 | 0.000 |

- **No outage signature.** There are zero 5xx responses.
- **No unpublished-key signature.** `inconclusive_instant` (≤ 5 ms) is 0 on every day. The inconclusive calls take 13–33 ms, or several seconds. A 13–33 ms failure is the phone having no connectivity, not the URL guard.
- One call took **1,922,976 ms (32 min)** (SM-S921B, US, 10-30). That is probably a process frozen mid-call, not the network.
- **The 11-01 spike is one device.** See open issues: CPH2691 made 94 of that day's 134 calls.
- Repeated `revoked` answers come from a few India devices (6 on 11-02 from one, 4 on 10-29 from another). That is consistent with revoked phones skipping the 15-minute throttle on every resume.
- 0.8.1 makes no code_management calls, as expected. **Fix in the runbook:** its `DECLARE ... DEFAULT '0.8.1'` should now be `0.8.5`.

## 4. Fetch policy — question 1 (`fetch_policy`)

| Decision | Metered | Probe | Decisions | Devices | p50 size | Max size |
|---|---|---|---|---|---|---|
| fetch | no | reachable | 141 | 15 | 152 KB | 22.9 MB |
| defer | no | reachable | 121 | 6 | 34.5 MB | 34.5 MB |
| defer | yes | sender | 113 | 14 | 5.6 MB | 22.9 MB |
| fetch | no | sender | 92 | 15 | 5.6 MB | 22.9 MB |
| defer | yes | reachable | 72 | 3 | 22.9 MB | 34.5 MB |
| defer | yes | unreachable | 59 | 11 | — | — |
| fetch | yes | reachable | 40 | 8 | 134 KB | 1.0 MB |
| fetch | no | unreachable | 13 | 4 | — | — |
| fetch | yes | sender | 10 | 9 | 388 KB | 388 KB |

- **Roaming never appears.** The roaming short-circuit has no evidence for or against it.
- **The typical circular is deferred on mobile data, as designed.** The sender-sized PDF median is **5,648,604 bytes**, which matches the standing 5.6 MB figure (`typical-circular-is-192-pages-5mb`). On metered connections it is always over the 2 MB cap.
- **The metered rule mostly delays.** Per device across `network_state_on_open` + `fetch_policy_decision`, 40 devices were unmetered at least once and **4 never were**. Those 4 get a circular only by tapping. The metered share on open runs 0.14–0.33 on most days.
- Every unmetered deferral is **27.8 or 34.5 MB**, over the 25 MB `UNMETERED_MAX_BYTES`. Those files are almost certainly test assets (6 devices).
- `unreachable` rows (72) are connectivity facts and stay out of any ratio (design §9 trap 7).
- **The live feed sends no PDF previews.** As of 2026-11-04, it emits no `<media:thumbnail>` or `<pn:pages>` (confirmed by the website session on both the paramanuseniorshealth.org and vercel.app feeds). Poller PDF notices therefore never carry `pdfThumbUrl` or `pdfPages`. A PDF card's preview exists only if the phone downloads and renders the whole circular, which on mobile data is always deferred (above). That fits `pdf_thumb` having a single fetch in the whole window.

## 5. Fetch timing and clustering — question 2

- Design §10 query, whole window: **5 seconds with 2 devices fetching, none with more.**
- `attachment_fetches`:

| Kind | Fetches | Devices | Fail rate | No HTTP status | p50 | p90 | Max |
|---|---|---|---|---|---|---|---|
| pdf | 160 | 19 | 0.144 | 13 | 791 ms | 5.2 s | 8.0 min |
| img | 132 | 19 | 0.091 | 12 | 125 ms | 321 ms | 2.4 s |

- **10 PDF failures carry HTTP 200.** (23 failed, 13 had no status, and the view's `non_200` is 0.) These are probably the uninterruptible-download timeout or the `.part` race (design traps 1 and 24). All 12 image failures are transport failures.

## 6. Entry point — question 3

- `app_open_source` across the window: **launcher 129 (32 devices), link 39 (10), notification 29 (7).** Launcher is a lower bound (trap 11).
- **11-01 broadcast:** 19 devices received it and **4 tapped the notification (21%)**. The median tap delay was 6.5 min.
- Across all days, `notification_tapped` median `ms_since_shown` is about 6 min and the max is 6.5 h. The 10-28 daily median was 6.5 h. 19 of 29 taps were cold starts.
- **Delivery (`notice_delivery`):** on clean days, p50 lag is 190–550 ms and p90 is 1.1–1.7 s. On 11-01, p90 was 15 s. On 10-27 and 10-28 the p90 ran to hours, from phones that were offline. **Negative lag (clock skew) is common:** 34 of 165 receipts on 10-27 and 29 of 91 on 10-28. Filter `>= 0` before any percentile. `notice_delivery` currently doesn't.

## 7. Reading — question 4

- **`notice_engagement` is broken for every build after 0.8.0.** 0.8.1 replaced the per-device `notice_id` with the sender's `log_id` (a string, the same on every phone; see `AnalyticsEvent.kt:20`). The view still reads `notice_id` as an int, so all 1,106 impressions since 10-23 collapse into one NULL row. **The view needs rewriting on `log_id`.** The numbers below come from the same logic keyed on `log_id`.
- Cards: overall median visible time is 1.4 s, and **28% of rows are flings under 500 ms**. Per notice, the median (flings removed) is **1.5–7.6 s**.
- **11-01 notice:** 8 of 19 receiving devices have seen the card so far (12 impressions), with a 16 s median.
- The `local-welcome` card has 19 devices and a 7.3 s median, the most-read card. `local-revoked` has 7 devices.
- **Attachments are rarely opened in-app:** `pdf_opened` 20 times on 6 devices, `image_viewer_closed` 7 times on 4. On the batch notices, at most 2 devices opened a given PDF.

## 8. Activation — the slips

- Totals (`real_events`): 33 submits (23 devices) produced **22 `success`** (20 devices), 4 `samecode`, 3 `notaccepted`, and 4 `offline` (3 `timeout`, 1 `transport`).
- 14 `activation_shown`, which is a lower bound (trap 22).
- **`activation_funnel` undercounts failures.** It has columns for `success`, `mistyped` and `notaccepted`. `mistyped` never occurs (it's answered locally before a call), and `samecode` and `offline` have no column. Add them.
- **3 timeouts out of 33 is the number to watch at go-live.** Each one is a person at the counter with a slip that "didn't work".

## To check later

- **First real poller send since the feed-URL fix.** The 2026-11-04 00:13 CST run failed (it was still on the test feed). The fix is picked up on the next run, about 04:13 CST on the poller's 4-hour cadence. This will be the **first production circular in the export**. Once its daily table lands (about a day later), read it in `notice_delivery` (receipts, devices, p50/p90 lag) and `notice_engagement` (`received_devices`, `devices`, `seen_rate_by_device`). Also read its `fetch_policy` sizes, the first real attachment sizes. The 11-01 send (19 received, 4 tapped, 8 seen) is a reference shape only, not a production baseline.
- **Poller silence went unnoticed for 8 days.** Nothing flagged that the poller had stopped sending real notices. With [[hands-off-after-1-0-0]] in mind, a no-real-send-in-N-days check is worth considering. It would have to watch the sender side, since GA4 can't tell test sends from real ones.

## Open issues, for separate sessions

1. **Status-call loop on one device (CPH2691, `user_pseudo_id 44c162cb73826cf95d56b2d12cc6950b`).** It made 94 `status` calls from 07:22 to 07:27 IST on 11-02, one every 3–5 s, each answering `active` in about 600 ms.
   - There are no `session_start`, `network_state_on_open` or `ga_session_id` values in that window, so the app was not foregrounded.
   - The loop started right after an attachment sweep (9 fetch decisions at 07:22:04–09).
   - Callers of `ActivationRepository.verify()`: `ActivationRefreshWorker` (KEEP; retries only on Unknown), `NoticeViewModel` init (`:161`), `onResumed` (throttled to 15 min unless revoked), and `recheckActivation`. None of them obviously yields a 3 s cadence with Active answers.
   - The device has been on the data since 10-20 (0.8.0) and has never logged a revoked answer.
   - The other devices peak at 16 calls a day.
2. **SignInHubActivity NPE (play-services-auth 20.7.0) on 0.8.1.** Find out why a sign-in hub activity starts at all in an anonymous-auth app, and whether 0.8.5 still carries that dependency.
3. ~~Saved-view fixes~~ **Done 2026-11-04** (`docs/analytics/views/`). One caveat: `notice_engagement.received_devices` counts only 0.8.1+ receipts, because 0.8.0 `notice_received` carried no `log_id`. On notices from before most phones upgraded, `seen_rate_by_device` can therefore exceed 1.
4. ~~Runbook §5 default version~~ **Done 2026-11-04** (`0.8.5`).
5. **Real versus test sends.** Analytics can't separate them. Joining `log_id` to `/sent/<logId>` would let every per-notice figure state its real-notice count.
