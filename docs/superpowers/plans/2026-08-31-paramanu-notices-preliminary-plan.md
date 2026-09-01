# Paramanu Notices — Preliminary Plan

**Status:** PRELIMINARY / pre-approval. UI is undecided; this document deliberately
stops short of task-level TDD steps. Once UI is settled, this becomes a task-by-task
implementation plan and the project skeleton gets created under
`C:\Users\vamsi\AndroidStudioProjects\`.

**Written:** 2026-08-31

---

## Goal

Deliver dispensary/OPD notices published by Paramanu Seniors Health (CHSS/DAE
pensioners, BARC Hospital) as push notifications to ~400 users aged roughly 80–95,
with a minimal sender console for the NGO.

## Architecture

Notice posted via sender console → trusted sender (Apps Script or Cloud Function)
holds the service account and calls FCM HTTP v1 → **data-only, `android.priority:
high`** message carrying a short text headline/body plus the notice PDF URL →
Android client renders page 1 of the PDF on-device with `PdfRenderer`, letterboxes
it, and posts a `BigPictureStyle` notification → row persists to Room for in-app
history.

No server operated by us. The only trusted code is the send step, which cannot be
client-side (the legacy FCM server-key endpoint was shut down June 2024; HTTP v1
requires a service-account-signed OAuth2 token).

## Tech Stack

Kotlin, Jetpack Compose, Material 3, Room, WorkManager (expedited), Firebase
Cloud Messaging, Firebase Auth (anonymous), Firebase RTDB, framework `PdfRenderer`.

---

## Global Constraints

- **applicationId:** `org.paramanuseniorshealth.notices` — permanent, never changeable
  after first Play upload. Gradle `namespace` may differ; only `applicationId` is locked.
- **minSdk: 26** (see Risk 1 — this is the single highest-impact number in the project).
- **Separate Firebase project** from the personal `Notifier` app. Separate
  `google-services.json`. NGO notices must not share a project with personal server alerts.
- **Data-only FCM messages.** A `notification:` block must never appear in the payload —
  it makes the SDK render the tray notification itself, `onMessageReceived` never runs,
  and both the client-side gate and Room persistence are bypassed.
- **Play distribution: India only.** Also settles US export-control embargo exposure.
- **Text is mandatory, image is supplementary.** Every notice requires a plain-text
  headline and one-line body. The PDF render is decoration.
- **Never fail silent.** Image download/render failure degrades to a text-only
  notification; it never suppresses the notification.
- Dev account is a **US individual** (settled). Export-compliance checkbox is accurate
  for this app: ancillary cryptography (TLS/FCM only), Note 4 to Category 5 Part 2 —
  no CCATS, no self-classification report.

---

## Decisions locked

| # | Decision | Rationale |
|---|---|---|
| 1 | `org.paramanuseniorshealth.notices` | Reverse-DNS of the real domain; keeps `BARC` out of a permanent public id |
| 2 | Data-only, high priority | Only payload type that lets the client gate on activation state |
| 3 | On-device `PdfRenderer` | No conversion backend; notices are single-page PDFs |
| 4 | Firebase Auth anonymous as activation identity | Revocable-ish handle with zero contact information held |
| 5 | Activation code redeemed server-side | A client-side RTDB read to validate a code would expose the whole code list |
| 6 | Sender console (small web app) | Replaces QR distribution; gives NGO operating visibility |
| 7 | No contact information stored anywhere | Notices are public; no PII needed to deliver them |

## Open decisions — need your call

| # | Question | Options | Recommendation |
|---|---|---|---|
| A | **UI** | yours to design | Text-first, very large type, respects system font scale |
| B | **Topic vs registration tokens** | topic `notices` + client-side gate / stored tokens + multicast | Tokens — real revocation, delivery feedback, auto-pruning of uninstalls. You leaned topic; see Risk 2 |
| C | **Trusted sender host** | Apps Script `/exec` (you already own one) / Cloud Function on RTDB write | Apps Script — already built, needs only the shared secret it is missing |
| D | **Code format** | length + alphabet | 8 chars Crockford Base32 (no `0/O`, `1/I/L`, `U`) + check character |
| E | **Code scope** | one shared code / single-use per person | Single-use, issued at the dispensary counter |
| F | **Sender console hosting** | Firebase Hosting / static page | Firebase Hosting — same project, Auth already there |

---

## Reuse map — `AndroidStudioProjects\Notifier` (`alluri.notify.me`)

The existing app already runs this exact pipeline in production against Indian
server alerts. Reuse is the plan, not a nice-to-have.

| Source file | Disposition |
|---|---|
| `fcm/NotifierMessagingService.kt` | **Adapt** — add activation gate + PDF branch |
| `fcm/NotificationChannels.kt` | **Adapt** — BigPicture/BigText fallback already correct; strip level/colour chrome, fix bitmap sizing (Risk 3) |
| `fcm/NotificationImageStore.kt` | **Adapt** — download + cache + prune + downsample all reusable; add PDF render step, retune `MAX_DIMENSION` |
| `data/NotificationEntity.kt`, `NotificationDao.kt`, `NotificationRepository.kt`, `NotifierDatabase.kt` | **Reuse nearly as-is** — unique index on `logId`, `OnConflictStrategy.IGNORE`, schema export already configured |
| `ui/NotificationListScreen.kt`, `NotificationViewModel.kt` | **Rewrite** — UI is the whole point of divergence (audience is 80–95, not a sysadmin) |
| `NotificationAccent.kt`, `fcm/LevelIcon.kt`, `ui/LevelFilter.kt` | **Drop** — severity levels are a server-monitoring concept with no meaning here |
| `ui/ShareText.kt`, `ui/NotificationAccess.kt` | **Evaluate** during UI design |
| `app/build.gradle` | **Template** — same plugin/BOM setup; change `applicationId`, `namespace`, `minSdk` |
| `docs/superpowers/specs/2026-08-06-rich-notifications-design.md` | Read before building; prior art on tray rendering |

**Hard-won operational knowledge in `Notifier/FCM-PIPELINE-CONTEXT.md`** — read it before
touching the sender. Two items carry straight over:

- `/exec` always serves the **deployed version**, never HEAD. Every script edit needs an
  explicit *New version* of the existing deployment. `testWebhook()` from the editor runs
  HEAD and therefore **cannot detect a stale deployment**.
- Adopt the `SCRIPT_VERSION` echo in the response so deployment identity is self-evident
  from one POST, with no phone involved.

---

## Module map (new project)

```
org.paramanuseniorshealth.notices
├─ activation/
│   ├─ ActivationRepository.kt     redeem code → anonymous UID; persist state
│   ├─ ActivationState.kt          Unactivated | Active | Revoked
│   └─ ActivationCode.kt           Crockford Base32 normalise + check char (pure, unit-testable)
├─ fcm/
│   ├─ NoticeMessagingService.kt   onMessageReceived → gate → enqueue work
│   ├─ NoticeWorker.kt             expedited: download PDF, render, post notification
│   ├─ PdfPageRenderer.kt          PDF → letterboxed 2:1 bitmap (the risky bit; isolate it)
│   ├─ NoticeImageStore.kt         cache + prune (forked from NotificationImageStore)
│   └─ NoticeNotifications.kt      channel + BigPicture/BigText builder
├─ data/  (Room: NoticeEntity, NoticeDao, NoticeRepository, NoticeDatabase)
└─ ui/    (UNDECIDED — activation screen, notice list, full-screen zoomable viewer)
```

`ActivationCode.kt` and `PdfPageRenderer.kt` are deliberately pure/isolated so they
carry JVM unit tests without a device — same discipline as the existing
`NotificationImageStorePruneTest`.

---

## Phased outline

**Phase 0 — Decisions.** Settle B–F above. Create the Firebase project. Confirm the
Play Console entry (in progress in parallel).

**Phase 1 — Skeleton + delivery.** New project, `applicationId`, `minSdk 26`,
`google-services.json`. Port Room + messaging service. Prove a data-only high-priority
message reaches Room with the app backgrounded. *No activation, no PDF yet.*

**Phase 2 — Activation.** Code generation and storage, redemption via callable
function, anonymous Auth, gate in `onMessageReceived` using
`getIdToken(forceRefresh = true)`. Explicit `unsubscribeFromTopic` / token delete on
logout.

**Phase 3 — PDF pipeline.** `PdfPageRenderer` with unit tests over sample notice PDFs
pulled from the live site. Letterboxing, sizing, cache, prune, text-only fallback.

**Phase 4 — UI.** Blocked on decision A.

**Phase 5 — Sender console.** Notice composer (mandatory headline + body + PDF URL),
code issuance, subscriber/delivery visibility.

**Phase 6 — Play.** Data Safety form, privacy policy URL, India-only availability,
closed testing.

---

## Risks

**1 — `minSdk` (highest impact in the project).** The existing `Notifier` sets
`minSdk 34` — Android 14, released late 2023. That is a personal-device app for one
user. Carrying that number into this project would make the app **uninstallable for
most of the intended audience**; an 80–95 year old's phone is realistically Android
8–12. Set `minSdk 26` (Android 8.0 — notification channels exist; `PdfRenderer` has
been present since 21). Verify against whatever the NGO knows about actual devices
before first release, because this is the one setting that silently decides reach.

**2 — Revocation is cooperative, not enforced.** With topic delivery plus a
client-side gate, the payload still reaches a revoked device; the app merely declines
to display it. Acceptable here — the notices are public and worthless to outsiders —
but it must not later be mistaken for access control. The operational cost is real: a
leaked code cannot be un-subscribed, and "please stop sending these" phone calls can
only be answered by talking an elderly caller through in-app settings. Decision B is
where this gets resolved.

**3 — Notification bitmap size (latent bug inherited from `Notifier`).** Notifications
cross a Binder transaction capped near 1MB. `NotificationImageStore.MAX_DIMENSION = 1024`
permits a 1024×768 `ARGB_8888` decode ≈ **3MB**, which throws
`TransactionTooLargeException` and loses the whole notification. Fix in the fork: pass
`bigPicture(Icon.createWithContentUri(...))` through a FileProvider so API 31+ loads it
lazily, with a downscaled-bitmap fallback (≈640×320 ARGB ≈ 819KB) below that.

**4 — A4 portrait into a 2:1 slot.** `BigPictureStyle` center-crops, which removes the
letterhead and the footer — exactly the date and signature that make a notice
credible. Letterbox onto a 2:1 canvas in `PdfPageRenderer` rather than letting the
system choose what to discard.

**5 — Legibility.** A full A4 page in a ~400dp notification is roughly 4pt text:
decorative, not informational, for this audience. Mitigated by the mandatory text
headline/body and a pinch-zoom full-screen viewer on tap.

**6 — `POST_NOTIFICATIONS`.** On Android 13+ a denial silently disables the entire
app, and these users will not go find it in system settings. Request immediately after
successful activation, with one plain sentence of explanation, while the user is still
in setup mode.

**7 — No RSS feed.** The earlier design assumed WordPress at `/feed`.
`paramanuseniorshealth.org` is custom-built with no generator tag and no
`rel=alternate` feed; notices are published as PDFs. Hence the sender console — the
NGO gains a posting step rather than the pipeline being invisible to them. Worth one
question to whoever maintains the site: a hidden CMS feed would change Phase 5.

**8 — `logId` collisions.** The existing Apps Script stamps `logId = String(Date.now())`
and Room's unique index silently `IGNORE`s a repeat — two sends inside the same
millisecond drop one. Use a UUID or RTDB push key in the new sender.

**9 — Data-only delivery reliability.** Somewhat less reliable than notification
payloads, and a force-stopped or never-launched app receives nothing at all. Fails
closed, which is the right direction, but the NGO should know delivery is best-effort.

**10 — Authorization for the BARC name.** Get the NGO's sign-off in writing before
`BARC` appears in the Play listing title, description, or icon — Play's impersonation
policy bites on listing identity, and strikes land on your personal developer account.

---

## Deferred / out of scope for now

- WhatsApp Business Platform fallback for non-Android users (open question from the
  earlier session: how many of the 400 are on iPhone?).
- Delivery-confirmation ping from client to RTDB (cheap once data-only is in place;
  decide during Phase 5).
- Shared secret on the existing `/exec` webhook — **do this regardless**, it is a live
  exposure on the personal Notifier pipeline today.

---

## Progress log

**2026-08-31 — Phase 1 (mechanical fork) done, build blocked.**

Forked `AndroidStudioProjects\Notifier` to `AndroidStudioProjects\ParamanuNotices`,
stripped `build/`, `.gradle/`, `.idea/`, `.kotlin/`, `.tokensave/`, the old
`google-services.json`, and the copied `docs/` + `FCM-PIPELINE-CONTEXT.md` (those
stay with the original).

Applied: package `alluri.notify.me` -> `org.paramanuseniorshealth.notices` (source
dirs moved, all refs rewritten); `applicationId` and `namespace` set; `minSdk`
34 -> 26; `rootProject.name` -> `ParamanuNotices`; theme `Theme.Notifier` ->
`Theme.ParamanuNotices` and composable `NotifierTheme` -> `ParamanuNoticesTheme`;
classes `NotifierApplication` -> `NoticesApplication`, `NotifierMessagingService` ->
`NoticeMessagingService`, `NotifierDatabase` -> `NoticesDatabase`; channel ids
`notifier_default`/`notifier_silent` -> `notices_default`/`notices_silent`;
`app_name` -> "Paramanu Notices" plus the strings that named the old app.
Zero residual `Notifier`/`alluri` references.

Deviation from the module map above: the `Notification*` file names
(`NotificationChannels`, `NotificationImageStore`, `NotificationAccent`, Room
`Notification*`) were NOT renamed to `Notice*`. They are descriptive rather than
branded, and they get their final names when they are rewritten in Phases 2-3 —
renaming them twice is churn.

**Verified:** `gradlew :app:tasks --offline` configures cleanly.
**Blocked:** `gradlew :app:testDebugUnitTest --offline` fails at
`:app:processDebugGoogleServices` — "File google-services.json is missing." Nothing
else failed. Kotlin has not been compiled yet, so the rename is not yet
compiler-verified.

**Next action (user):** create the new Firebase project, register an Android app with
package `org.paramanuseniorshealth.notices`, drop `google-services.json` into `app/`.
Then the build is unblocked and Phase 1 finishes with a real delivery test.

**Still open before Phase 2:** decision B (topic vs registration tokens) — it
determines what `NoticeMessagingService` does on arrival and whether `onNewToken`
must be overridden.
