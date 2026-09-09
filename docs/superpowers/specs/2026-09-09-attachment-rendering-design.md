# Attachment rendering: images, PDFs and link previews

Date: 2026-09-09
Status: implemented 2026-09-09 (sender and app). Builds clean; 84 unit tests pass.

## Why

Three notices reached a test handset. Two carried images and showed nothing; the third carried a
PDF and showed a single page as a thumbnail with no PDF behind it. Investigation found three
separate causes, only one of which was a defect in the app:

1. **Images were never sent.** `pollerSendAlert_` promoted an enclosure to an extra only when its
   type was `application/pdf`; `image/*` enclosures were logged and dropped. The app's `imageUrl`
   path — data key, Room column, migration, download, thumbnail, viewer — was complete and had
   never once been fed. Fixed 2026-09-09.
2. **The PDF is deleted on arrival, by design.** `NoticeImageStore.fetchPdfRender` downloads to a
   cacheDir scratch file, renders page one, writes a JPEG and deletes the source. There is no PDF
   on the device to open. The stored JPEG is also the 800x400 *tray* canvas, not a page-sized
   render, so an A4 page lands at roughly 283x400 px — which is why pinch-to-zoom cannot rescue it.
3. **The white borders are in the source file.** `sbeba-kochi-consultation-2026-09-11.jpeg` is
   976x1600, a white sheet on a light-grey ground with a border baked in by whoever made the
   poster. Neither the app nor `BigPictureStyle` added them.

This design covers what the app should do with an image, a PDF and a bare link, and what the sender
must send for each.

## Decisions taken

| Question | Decision |
|---|---|
| When is the PDF downloaded? | On tap. The page-one render still happens at push time for the tray; the PDF itself is fetched when the user asks for it, with progress. |
| Where is the PDF stored? | Behind one function so `filesDir` and `cacheDir` can be swapped without touching callers. Not yet decided. |
| In-app viewer | Kept, with an "open in another app" action alongside it. Not replaced by hand-off. |
| Save target | `ACTION_CREATE_DOCUMENT` (SAF). Works identically on API 26-36 and needs no storage permission, so the Data Safety listing is untouched. |
| Where are links resolved? | On the sender. The phone never parses HTML. |
| Which URL becomes a card? | The first URL in the item's description, using the same end-of-URL rules as `BodyText.links`. |
| How much of the page is read? | `<title>` only, paired with the site's favicon. No `og:` scraping. YouTube is the exception and uses oEmbed. |
| Margin trimming | Applied to the tray bitmap only. The stored file is always exactly what the sender published. |

## The data contract

Additive. Every key is optional and absent keys mean "no attachment of this kind", so a sender and
an app at different versions stay compatible in both directions.

| key | meaning |
|---|---|
| `logId`, `title`, `body`, `category` | as now |
| `imageUrl` | a picture the sender attached; shown as it arrives |
| `pdfUrl` | a circular; page one rendered on arrival, file fetched on tap |
| `linkUrl` | the URL found in the description |
| `linkTitle` | the target page's `<title>`, or the YouTube video title |
| `linkImageUrl` | the site's favicon, or the YouTube thumbnail; absent when neither is usable |
| `linkSite` | the host, or `YouTube` |

`linkUrl` may arrive **without** the other three: that is the deliberate signal that the preview
fetch failed and the app should show a plain link chip rather than a card. A card is never a
precondition for the notice.

RSS allows at most one enclosure per item, so an alert carries a picture or a circular, never both.
The app tolerates both being present; the feed cannot express it. Link previews are resolved only
when there is no enclosure at all.

## Sender: `docs/apps-script/sender/Poller.gs`

`pollerFirstLink_(text)` is pure and finds the first URL in the description, porting the trailing
punctuation rules from `BodyText.links` so that the sender and the app agree on where a URL ends.
`extras.linkUrl` is set from its result *before* any network call, so a fetch failure downgrades the
card to a chip instead of losing the link.

`pollerLinkCard_(url)` then does the network work:

- **YouTube** (`youtube.com/watch`, `youtu.be`, `/shorts/`, `/embed/`, `/live/`) — the video id is
  extracted from the URL and the title comes from the public oEmbed endpoint, which needs no API
  key and works for unlisted videos. The thumbnail is `maxresdefault.jpg` when a HEAD request finds
  it, else `mqdefault.jpg`. `hqdefault.jpg` is deliberately not used: it is 4:3 and pillarboxes
  16:9 videos with black bars, which is the artefact this work exists to remove.
- **Everything else** — one fetch, a capped prefix searched for `<title>`, and
  `https://www.google.com/s2/favicons?sz=128&domain_url=...` for the logo. Choosing the favicon
  service over the page's own `<link rel=icon>` avoids parsing the document.

  The favicon URL is **verified with a HEAD before it is sent**. Testing against real sites found
  the service answers `404` for a domain it does not know — including this project's own Vercel
  host — while still returning a generic globe in the body. Passing that URL on would fail twice:
  `NoticeImageStore.download` rejects anything outside `200..299`, so the card would arrive with a
  blank space where the logo goes and nothing in any log to explain it. On a 404 the poller sends
  no `linkImageUrl` and the app draws its own placeholder.

  The site's own `/favicon.ico` is deliberately **not** a fallback: it is usually ICO, which
  Android's `BitmapFactory` cannot decode. It would look like a working URL and render nothing.

Each field degrades on its own. Verified against live sites: YouTube yields title and still;
`github.com` yields title and logo; the Vercel host yields title and host but no logo; a
JS-rendered government site yields logo and host but no title. A card is assembled from whichever
of the three arrived.

**A preview failure must never fail the send.** `pollerSendAlert_` throwing leaves the item unseen
and it is retried on every subsequent run, so a site that blocks Apps Script's fetcher would jam
the queue indefinitely. The card lookup gets its own try/catch and logs.

## App

**Storage.** `NoticeImageStore` gains `notice_files/` for downloaded PDFs, behind a single
directory function so the cache-vs-internal choice is one line. `download()` returns the response
`Content-Type` so files get honest extensions; `ACTION_VIEW` cannot pick the right app without one.
Today every image is written as `-img.jpg` regardless of what arrived.

**Room.** Version 2 to 3, adding `linkUrl`, `linkTitle`, `linkImageUrl` and `linkSite` as TEXT, in
the style of the existing `MIGRATION_1_2`.

**Actions.** One `AttachmentActions` object providing Open (FileProvider `content://` + MIME +
`ACTION_VIEW`, falling back to the http URL when nothing handles it), Share (`ACTION_SEND` with
`FLAG_GRANT_READ_URI_PERMISSION`) and Save (`ACTION_CREATE_DOCUMENT`). `file_paths.xml` needs a
second `<files-path>` entry — nothing is reachable through the provider until it is declared, and
the existing comment is right that widening the path would expose the Room database.

`ShareText`'s "images are deliberately not shared" reasoning was sound and is preserved rather than
discarded: Share and Save appear only when the local file exists, and the PDF path downloads on
demand first.

**Tray artwork.** `decodeDownsampled` currently hands a 488x800 portrait to both `bigPicture` and
`largeIcon` — about 780 KB in RGB_565 against a Binder cap near 1 MB. Portrait posters are the case
that budget was never sized for. A `TrayArtwork` step owns everything the notification sees: trim
near-uniform edge margins, fit a 2:1 frame, stay under ~400 KB. It never touches the stored file.

**Link card.** A Compose row — Coil thumbnail, title, host — that `ACTION_VIEW`s on tap, which
sends YouTube links to the YouTube app without special-casing. Falls back to a plain chip when only
`linkUrl` arrived.

**Render quality.** `renderFirstPage` splits into a page-sized render written to disk and the
letterbox composite used for the tray, so the in-app viewer has pixels to zoom into.

## Testing

Pure functions carry the tests, because the interesting failures are all in string handling and
geometry:

- `testPollerLinkExtraction()` — no network, no credentials: URL extraction against trailing
  punctuation and brackets, YouTube id extraction across all five URL shapes, host parsing.
- `testPollerParseFixture()` — extended to assert that a PDF item has no `imageUrl` and vice versa.
  It was also silently failing before this work: the fixture listed items oldest-first while
  `pollerParseFeed_` reverses, so its "oldest first" assertion could never have passed.
- `pollerDryRun()` — shows resolved cards against the live feed without sending.
- App side: `MarginCrop` and the tray budget as JVM unit tests beside the existing `fitLetterbox`
  tests; MIME mapping; prune budgets.

## Order of work

All four done on 2026-09-09:

1. Link previews on the sender.
2. PDF hand-off in the app.
3. Share and Save across image, PDF and link.
4. Tray artwork and margin trimming.

Two defects were found by testing rather than by reading, and both are recorded above: the favicon
service 404s for unknown domains, and a page-sized PDF render is ~14MB as ARGB_8888 and must not be
handed to the notification path. A third was found by its own unit test: MarginCrop trimmed a
single-colour image to the guard on all four sides instead of leaving it alone.

## Known open item

`testPollerEndToEnd` sets `TOPIC_OVERRIDE = 'notices-v1'`, the live topic. That is intentional
while the app is in closed testing, where the subscriber set is the test cohort. **It must be
pointed at `poller-test` before the app leaves closed testing**, or a routine test run notifies
every real user.
