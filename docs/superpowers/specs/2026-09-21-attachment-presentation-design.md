# Attachment presentation and actions

Spec 2 of two. Spec 1 (`2026-09-21-deferred-attachment-fetching-design.md`) moved
fetching into a deferred worker and added the columns this one reads. This spec
covers what the reader sees and can do with an attachment once it is on the phone.

## Why

Three complaints, all the same shape: the app makes the reader work out what to do.

1. **A circular shares as a picture of its first page.** The in-app viewer loads
   `cachedImage ?: cachedPdfRender` — a JPEG — and its Share acts on that JPEG. A
   192-page circular arrives in a WhatsApp chat as page one, flattened. The recipient
   cannot read the other 191 pages and has no sign they exist.
2. **Too many doors.** A card offers Open, Share, Save, *and* a separate "Open
   circular" button, and tapping the picture opens an in-app viewer that has its own
   Open/Share/Save overflow. Four ways to open one attachment is three too many for
   someone who wants to read a notice.
3. **Nothing says what the attachment is.** No type, no page count, no size. A reader
   deciding whether to tap a 5.6MB download on mobile data has nothing to decide with.

## Decisions taken

- **A notice carries at most one attachment — a PDF or an image, never both.** This is
  already enforced upstream: RSS permits one `<enclosure>` per item, and `Poller.gs:105`
  records exactly that. The UI stops trying to render two.
- **Tapping the attachment *is* opening it.** There is no separate Open action. A reader
  who taps a picture wants the picture; making them tap it and then find a button is a
  step that teaches nothing.
- **An image opens in the app; a PDF goes straight out.** The in-app viewer is instant,
  offline and free of a chooser dialog, which matters for this audience — but only for a
  picture it can actually show. A PDF goes to whichever reader the user already has,
  with no in-app stop on the way.
- **Two actions per attachment: Share and Save. Nothing else.**
- **Share carries the real file.** For a circular that is the PDF binary, all pages, not
  the render. The accompanying text stays what `ShareText` already produces.
- **The rendered page one is display-only.** It is a thumbnail and a preview. It is never
  opened, never shared, never saved.
- **No error text, as everywhere else in this app.** A missing attachment is a tappable
  glyph; a fetch in progress is a spinner.

## What the card shows

One attachment area, resolved in this order: the cached photo, else the cached PDF
render (which may be a published thumbnail or a locally rendered page one — `Spec 1`
made those indistinguishable on purpose), else the placeholder glyph from Spec 1.

**Collapsed:** a 56dp thumbnail beside the title, as today.

**Expanded:** the attachment full-width, with a badge overlaid along its bottom edge —
a dark scrim with light text, the way WhatsApp labels a document preview. The badge is
part of the picture, not a line of text under it, so it costs no vertical space and
reads as a property of the thing rather than a caption.

Badge content, in order, separated by a middle dot:

| Case | Badge |
|---|---|
| PDF, pages and size known | `PDF · 192 pages · 5.6 MB` |
| PDF, one page | `PDF · 1 page · 300 kB` |
| PDF, numbers not yet known | `PDF` |
| Image | `Image · 1.2 MB` |

Numbers are absent before the document has been fetched or the pipeline has published
them — `pdfPages` and `pdfBytes` are nullable for exactly that reason. The badge degrades
a field at a time rather than disappearing.

**Sizes are formatted for a reader, not a developer.** `5.6 MB`, `300 kB`, `12 kB` —
one decimal place above a megabyte, none below, SI units because that is what the
file manager on the phone will also say. This is a pure function and is unit-tested.

## What a tap does

| Tap | Result |
|---|---|
| Attachment thumbnail, image notice | In-app viewer, pinch to zoom, title in the bar |
| Attachment thumbnail, PDF notice | Fetch if needed, then hand to an external PDF reader |
| Placeholder glyph | Fetch the attachment. A tap is consent and bypasses the fetch policy |
| Share | Share sheet: the real file plus `ShareText.build(listOf(notice))` |
| Save | `ACTION_CREATE_DOCUMENT`, then copy |

A PDF tap that cannot reach a reader falls back to opening `pdfUrl` in a browser, as
today — the tap always leads somewhere.

## What is removed

- The "Open with" button under an attachment, and `R.string.action_open_with`.
- The separate "Open circular" button, and `R.string.action_open_pdf`.
- `NoticeViewerScreen`'s `cachedPdfRender` fallback. The viewer becomes image-only,
  because a PDF never reaches it any more.
- `NoticeViewerScreen`'s "Open with" overflow item. Its overflow becomes Share and Save,
  matching the card exactly — the same two words in both places.
- The double attachment rendering in `NoticeAttachments` (`listOfNotNull(photo, pdfRender)`),
  which the one-attachment rule makes unreachable.

## Sharing a circular that is not on the phone yet

Share and Save need the real PDF. On a metered connection the worker will have deferred
it, so the file may not exist when the user taps Share.

The tap path already solves this: `NoticeViewModel.openPdf` fetches on demand behind the
existing `downloadingPdf` spinner, and `fetchPdf` returns the cached copy instantly when
there is one. Share and Save route through the same path — fetch if absent, then act. A
tap is consent, so no policy check applies.

If the fetch fails there is no message. The spinner stops and the glyph returns, which is
the same state the user could already act on.

## Testing

- `AttachmentBadge.format(...)` — a pure function over `(isPdf, pages, bytes)`, covering
  every row of the badge table, the singular "1 page", and every combination of nulls.
- Size formatting — boundaries at 1 kB and 1 MB, rounding, and zero.
- The rest is Compose and hand-off, verified by running the app against a device.

## Order of work

1. Badge formatting as a pure function, with tests.
2. The badge overlay, and the single-attachment resolution in `NoticeAttachments`.
3. Share and Save as the only two actions; delete Open in both places.
4. Tap routing: image to the viewer, PDF straight out.
5. `NoticeViewerScreen` reduced to images, overflow reduced to Share and Save.
6. String removals.

## Known open items

- Whether a badge should appear on the 56dp collapsed thumbnail as well. It would be
  illegible at that size, so it does not — but that means the type is only visible once
  the card is open. Accepted.
- `ShareText` is unchanged. A shared PDF carries title, body and timestamp as before; the
  page count is not in the text, on the grounds that the file itself says so.
