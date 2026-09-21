# Deferred attachment fetching

Spec 1 of two. Spec 2 covers how a fetched attachment is presented on the card
(thumbnail, badge, Share and Save) and depends on the columns added here.

## Why

`NoticeMessagingService.onMessageReceived` currently downloads every attachment
inline, inside `runBlocking`, before the notification is posted
(`NoticeMessagingService.kt:52-94`). For a circular that means
`fetchPdfRender` pulls the **entire PDF** to a scratch file, renders page one,
and deletes the scratch (`NoticeImageStore.kt:173-196`).

Testing on 2026-09-20 established that a real circular is about **192 pages and
5.6MB**, and that this is typical rather than exceptional. Three things follow:

1. **Egress.** At ~400 devices, one circular costs roughly 2.2GB off the
   website's hosting, and 5.6MB of each user's mobile data — to produce a
   thumbnail.
2. **It mostly fails.** `fetchPdfRender` runs under `TIMEOUT_MS = 8_000`
   (`NoticeImageStore.kt:70`). 5.6MB in 8 seconds needs a sustained ~5.6 Mbps.
   On an ordinary mobile connection that download does not finish, so a large
   share of users are already getting no thumbnail — silently, with no signal in
   the UI and no log anywhere they can see.
3. **It is paid twice.** `fetchPdf` re-downloads the same bytes when the user
   taps to open (`NoticeImageStore.kt:206`).

There is no partial-fetch escape. Android's `PdfRenderer` takes a
`ParcelFileDescriptor` over a complete seekable file, so page one cannot be
rendered without the whole document. The only real levers are *when* the
download happens, *whether* it happens automatically, and whether the result is
kept.

## Decisions taken

- **Attachment fetching leaves the FCM service entirely** and moves to a
  `WorkManager` job with a small random initial delay.
- **The notification posts immediately**, before any attachment exists, with a
  type placeholder.
- **Messages are still sent at FCM high priority.** Without it the app is not
  woken and the work is never enqueued. Priority buys the wake-up, not the
  download.
- **A downloaded PDF is kept**, under a byte budget. The bytes have already
  crossed the network; deleting them only guarantees a second download.
- **No error text anywhere.** Not-yet-fetched, deferred-because-metered, and
  failed all render as the same tappable download glyph. They are the same
  situation from the user's side and the same tap resolves all three. Words here
  become helpdesk calls.
- **No feed or payload change is required now.** The app ships tolerating the
  new keys; the pipeline adopts them later with no second app release.
- **Notices are never deleted by age.** Already true — `NoticeDao` has only
  `deleteAll` and `deleteByIds`, both user-driven. Stated so it is not
  "tidied up" later.

## The fetch decision

One pure function, and the most heavily tested thing in this spec:

```
fetchDecision(sizeBytes: Long?, metered: Boolean, roaming: Boolean): Fetch | Defer
```

| Condition | Outcome |
|---|---|
| Roaming | **Defer**, at any size, including a 40KB logo |
| Metered, not roaming, size ≤ 2MB | Fetch |
| Metered, not roaming, size > 2MB | Defer |
| Metered, not roaming, size unknown | Defer |
| Unmetered, size ≤ 25MB | Fetch |
| Unmetered, size > 25MB | Defer |
| User tapped the glyph | Fetch, unconditionally |

**2MB** clears every link logo, sender photo and PDF thumbnail while excluding
every circular. **25MB** is a sanity cap so a mispublished 400MB file cannot
swallow the cache. Roaming defers everything because it is the one case where a
user can be charged real money for a picture they did not ask for; they get the
default type icon and the manual download.

A tap is consent, so it bypasses every tier. Unknown size means no
`Content-Length` on a chunked response, and is treated as large.

**Detecting roaming:** `NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING` on API
28+, falling back to `TelephonyManager.isNetworkRoaming` on 26–27. Both are
permission-free. **Detecting metering:**
`ConnectivityManager.isActiveNetworkMetered`.

**Where the size comes from:** today, `Content-Length` read off the
`HttpURLConnection` in `download()` before the body is streamed. Once the
pipeline ships, `pdfBytes` from the payload — which means a metered device skips
the circular without making any request at all.

## The worker

`AttachmentWorker` in `fcm/`, modelled on the existing `ActivationRefreshWorker`.

- One-time work, unique per `logId`, `ExistingWorkPolicy.KEEP`, so an FCM
  redelivery does not double-enqueue.
- Constraint `NetworkType.CONNECTED` only. **Not `UNMETERED`** — a constraint
  waits indefinitely, and a thumbnail that arrives in three days when the user
  next finds wifi is worse than one that never arrives, because it cannot be
  explained without words. The metered and roaming questions are asked at
  execution, where they can produce a tappable glyph instead of a silent wait.
- `setInitialDelay` to a uniform random **0–2 minutes**, injected rather than
  computed inline so the range is testable.

  This began as 0–60 minutes, sized against a thundering herd hitting an origin
  server. The attachments are served from GitHub Pages, which is a CDN: 400
  requests for one cached file is a non-event, and the edge collapses concurrent
  misses into a single origin fetch. What actually binds is the monthly bandwidth
  quota, and spreading a download does nothing for a quota — 400 devices fetching
  5.6MB costs the same whether it takes a second or an hour. The lever for that is
  the published thumbnail, not the delay.

  A long delay also costs something real: `updatePicture` deliberately does nothing
  once the notification has left the tray, so a picture arriving an hour late lands
  in the app only and the notification never fills in. Two minutes keeps it inside
  the window where the notice is still on screen. What remains of the jitter is
  cheap insurance against ever moving off a CDN.
- No `requiresBatteryNotLow`. A notice attachment matters more than a few
  percent of battery.
- No entitlement re-check. The window is minutes, and revocation is handled on
  its own path.
- Timeout rises from 8 seconds to 5 minutes. Nothing is being held up any more.
  `TAP_TIMEOUT_MS` (60s) is unchanged for the tap path.

At execution, per attachment present on the notice:

- **Link logo, sender photo, PDF thumbnail** — subject to `fetchDecision`, which
  on any non-roaming connection clears them.
- **The circular itself** — fetched automatically **only when there is no
  `pdfThumbUrl`**, and then subject to `fetchDecision`. On Fetch: download, render
  page one, keep both the render and the PDF, prune. On Defer: do nothing and
  finish successfully. There is no retry to schedule; the user's tap is the retry.

  When `pdfThumbUrl` *is* present the worker fetches the ~40KB thumbnail and
  stops. It does not pre-fetch the PDF, even on unmetered wifi: the thumbnail is
  all the card needs, `pdfPages` and `pdfBytes` arrive in the payload, and the
  document is fetched on first tap and kept from then on. This is the whole
  egress saving — ~2.2GB per notice today against ~16MB once the pipeline ships —
  and pre-fetching "because wifi is free" would give it straight back, since the
  website pays for egress whatever the phone is connected to.

## Attachment state is stored

Derived state was considered and rejected. The user sees one glyph either way,
but the *app* has to tell these apart: an attachment that was deferred, failed,
or never attempted should retry by itself when conditions improve, while one
that was pruned must not — it was already delivered once, and silently
re-downloading 5.6MB because a cache filled up is exactly the surprise this
spec exists to remove.

One column, `attachmentState TEXT`:

| Value | Set when | Auto-retries |
|---|---|---|
| `PENDING` | row inserted, worker not yet run | yes |
| `DEFERRED` | `fetchDecision` returned Defer | yes |
| `FAILED` | fetch attempted and errored | yes, up to a cap |
| `FETCHED` | file is on disk | n/a |

**`PRUNED` is not stored — it is derived**, as `FETCHED` with the file gone.
That keeps `prune`/`prunePdfs` free of any database dependency; they run inside
`NoticeImageStore` from the FCM path and have no DAO, and giving them one to
write a status back would be a worse coupling than a one-line check at read
time. A pruned attachment shows the glyph and waits for a tap.

**Retry triggers** re-enqueue the worker for every row in `PENDING`, `DEFERRED`
or `FAILED`:

- app open
- connectivity becoming unmetered

Both go through `fetchDecision` as normal, so a retry on a metered network
defers again and costs nothing.

`FAILED` is capped at **5 attempts**, counted in `attachmentAttempts`. Past that
only a tap retries, so a URL that is permanently 404 stops re-attempting on
every launch. `DEFERRED` does **not** count against the cap — deferring is the
policy working, not a failure, and a phone that stays on mobile data for a month
must still fetch the moment it reaches wifi. A successful fetch resets the
count to zero.

What the card renders is still one glyph for all four of deferred, failed,
pending and pruned — the stored state drives retry behaviour, never wording.

## Migration

A real `MIGRATION_3_4` — `NoticesDatabase.kt:18` requires it, and stored notices
are the only copy a user has of something already dismissed. No destructive
fallback.

```sql
ALTER TABLE notices ADD COLUMN pdfThumbUrl TEXT;
ALTER TABLE notices ADD COLUMN pdfPages INTEGER;
ALTER TABLE notices ADD COLUMN pdfBytes INTEGER;
ALTER TABLE notices ADD COLUMN attachmentState TEXT;
ALTER TABLE notices ADD COLUMN attachmentAttempts INTEGER NOT NULL DEFAULT 0;
```

Existing rows get `attachmentState` NULL, read as `PENDING` when the row has an
attachment URL and ignored when it has none. So every notice already on a
tester's phone whose thumbnail never arrived retries on the next app open —
which is the desired repair, not a migration artefact.

All three are written here; `pdfPages` and `pdfBytes` are only *read* in Spec 2,
for the badge. `pdfThumbUrl` must persist because a deferred notice may be
tapped a day later and the tap path needs the URL from the row.

`pdfPages`/`pdfBytes` are also filled locally when the worker takes the
download-and-render path: `renderFirstPage` already opens a `PdfRenderer` and
reads `pageCount` (`PdfPageRenderer.kt:117`) before discarding it, and size is
`file.length()`. So the badge works before the pipeline ships, for any notice
whose circular was actually fetched.

## Two existing bugs this must fix

Both found while tracing why a thumbnail appeared on card expansion after the
notification had none. Neither is caused by this work, and both would defeat it.

**Coil bypasses the fetch policy entirely.** The collapsed thumbnail falls back
from local files to `notice.imageUrl` and then `notice.linkImage` as remote URLs
(`NoticeListScreen.kt:325-327`), and the link-preview card loads
`notice.linkImage` remotely whenever a row is expanded
(`NoticeAttachments.kt:132,148`). Coil downloads all of these at compose time
with no timeout, no size check, no metering check and no roaming check — so the
card would download on roaming while the worker was busy refusing to. This is
also the honest answer to "why did it appear when I expanded": the 8-second
timeout never applied to these loads.

Every remote model in the UI must go through `fetchDecision`. Where it returns
Defer, the card renders the placeholder and glyph instead of handing Coil a URL.
The local-file models (`NoticeAttachments.kt:207`) are unaffected.

**Expanding hides the only picture a failed notice has.** Line 351 draws the
thumbnail only when `!expanded`, while the expanded view renders local files
only. So an image notice whose download failed shows its picture collapsed and
nothing at all when expanded. Spec 2 restructures this area, but the fix belongs
here, with the state model that makes it expressible.

## Cache budgets

- `PDF_BUDGET_BYTES` 24MB → **50MB** (about nine circulars at 5.6MB).
- Artwork moves from count-based to byte-based: `KEEP_NEWEST = 60` files →
  a **25MB** budget, so `prune` takes the same shape as `prunePdfs`. No existing
  test touches `prune`, so the change is free.

## The contract

Additive and optional. Absent keys mean exactly today's behaviour, so the
website, the poller and the app can each adopt at their own pace, and the app
needs no release on pipeline day.

### Feed (Hugo)

Two of three are existing standards:

```xml
<enclosure url="https://…/circular.pdf" length="5872345" type="application/pdf"/>
<media:thumbnail url="https://…/circular-thumb.jpg" width="1200" height="1697"/>
<pn:pages>192</pn:pages>
```

`<enclosure>` is core RSS 2.0 and `length` *is* bytes, so size needs no custom
field. `media:thumbnail` is Media RSS
(`xmlns:media="http://search.yahoo.com/mrss/"`) and is the only way to carry a
PDF thumbnail, since RSS permits one enclosure per item and the enclosure is the
PDF. Page count alone has no standard home and takes a project namespace.

Hugo cannot rasterize a PDF — its image processing accepts only decodable image
resources (JPEG, PNG, GIF, TIFF, BMP, WebP) and it will not shell out at build
time. A CI step before Hugo produces all three values:

```
pdftoppm -jpeg -r 100 -f 1 -l 1 circular.pdf thumb   # the thumbnail
pdfinfo circular.pdf | grep Pages                     # the page count
stat -c %s circular.pdf                               # the bytes
```

The thumbnail lands in `assets/` and Hugo then treats it as an ordinary image
resource. This also sidesteps computing page count in Apps Script, which has no
PDF library and would otherwise need a Drive round-trip or regex over raw bytes
that breaks on compressed object streams.

### Payload (poller → FCM)

Data payloads are `string → string`, 4KB cap; three new keys, nowhere near it.

| Key | From | Example |
|---|---|---|
| `pdfThumbUrl` | `media:thumbnail/@url` | `https://…/circular-thumb.jpg` |
| `pdfBytes` | `enclosure/@length` | `5872345` |
| `pdfPages` | `pn:pages` | `192` |

Key and column are named identically. The existing contract has `linkImageUrl`
mapping to a field called `linkImage` (`NoticeMessagingService.kt:47`); that
drift is not repeated.

`imageBytes` was considered and dropped: sender photos are small enough that
`Content-Length` on a request already being made is sufficient, and every
optional key is one more thing the poller can get wrong.

### Poller (`docs/apps-script/sender/Poller.gs`)

Small and local. It already parses `<enclosure>` and already reads `url` and
`type` (`Poller.gs:122-130`); it discards `length`.

- `pollerParseFeed_` — read `enclosure/@length`; add a `media` and a `pn`
  namespace alongside the existing `contentNs`, built inside the function for the
  load-order reason given at `Poller.gs:109`; read `media:thumbnail/@url` and
  `pn:pages`. Populate only when the enclosure is `application/pdf`.
- `pollerSendAlert_` — add the three `extras` alongside the existing
  `extras.pdfUrl` (`Poller.gs:289`), each only when present.
- `pollerDryRun` and the `Tests.gs` fixtures gain the new fields.

`docs/sender-contract.md` is updated in the same change.

## Testing

Unit-testable on the JVM, in the style the repo already uses:

- `fetchDecision` across every row of the table above, including both unknown-size
  cases and the roaming-beats-everything rule.
- The jitter range: within `[0, 3600_000)`, injected clock/random.
- Byte-budget `prune`, mirroring what a `prunePdfs` test would assert: oldest
  deleted first, newest always kept, empty directory a no-op.
- The state machine: every transition into `PENDING`/`DEFERRED`/`FAILED`/
  `FETCHED`, the derivation of pruned as `FETCHED` + missing file, which states
  a retry trigger picks up, and that the attempt cap stops `FAILED` retrying.
- What the card renders from `(state, cachedFile, inFlight)` — one glyph for
  deferred, failed, pending and pruned alike.
- Poller: extend `Tests.gs` fixtures with a PDF enclosure carrying `length`,
  `media:thumbnail` and `pn:pages`, and assert an item with none of them still
  parses exactly as today.

Worker wiring and the notification update are verified by running the app —
the same line the repo already draws between unit tests and Compose/Android
behaviour.

## Order of work

1. `MIGRATION_3_4` and the five columns.
2. `fetchDecision` and its tests, standalone.
3. The attachment state machine and its tests, standalone.
4. Byte-budget `prune`, new budgets.
5. `AttachmentWorker`; move fetching out of `NoticeMessagingService`.
6. Retry triggers: app open, and connectivity becoming unmetered.
7. Route every Coil model through `fetchDecision`; fix the expanded-thumbnail
   case.
8. Immediate notification with placeholder; in-place update when the attachment
   lands.
9. New drawables: `ic_file_pdf`, `ic_file_image`, `ic_download`. Content
   descriptions only, no new visible strings.
10. Poller and `docs/sender-contract.md`.

Steps 1–9 ship as an app release. Step 10 and the Hugo CI step land afterwards,
independently.

## Known open items

- Confirmed on a tester's device 2026-09-21: a notice on mobile data rendered no
  thumbnail, and the picture only appeared once the card was interacted with —
  consistent with the 8s timeout failing and Coil later fetching the remote URL
  outside it.
- The notification update path uses
  `NotificationManager.getActiveNotifications()` to skip dismissed notices. This
  is per-app and reliable from API 23, well under minSdk 26, but it does not
  distinguish a notification the user dismissed from one the system dropped.
  Both are treated as dismissed.
