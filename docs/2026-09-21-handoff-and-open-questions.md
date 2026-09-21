# Banked questions and decisions — 2026-09-21

Working alone from here. Everything I could not ask about is listed with the
decision I took and what it costs if wrong. Nothing here blocked progress.

## Decisions I made on your behalf (Spec 1)

1. **Branch, not worktree.** `local.properties` is gitignored and `ANDROID_HOME` is
   unset, so a worktree would have no SDK path and every Gradle build would fail.
   Work is on `deferred-attachment-fetching` off main.

2. **Link-card images in the tray: restored.** Moving fetching to the worker silently
   dropped the ability for a link-only notice (a YouTube still) to fill the
   notification. Restored behind the existing 240px `isPictureWorthy` gate.
   Deciding argument: `TrayArtwork.kt:41-43`'s KDoc still claimed that behaviour, and
   in this codebase a confidently-worded false comment is worse than a missing feature.

3. **Three ordering fixes to my own plan.** The plan repeatedly solved task ordering by
   committing a broken tree (a commented-out call with `// TODO next task`). I changed
   all three to "add alongside now, delete in the commit that makes it unreachable".

4. **`FAILED` retry cap = 5**, as you approved. `DEFERRED` is uncapped — deferring is
   the policy working, not a failure.

## Open questions for you

1. **`imageBytes` / `linkImageBytes` in the feed.** The app now issues a HEAD request
   to size a picture before fetching it on a metered connection. The website already
   publishes `pdfBytes`; publishing the same for images would remove HEAD requests from
   the app entirely. Small win, your call whether it is worth the pipeline change.

2. **Go-live checklist additions** (flagging, not acting):
   - The 8-second timeout meant most users were probably getting no thumbnail at all.
     Worth confirming on a tester's phone that this release visibly fixes it.
   - The shipped `prunePdfs` has been over-pruning the PDF cache (see below). Users may
     have lost cached circulars they should have kept. Fixed here.

3. **`mutate()` before `setTint`** in `NoticeNotifications.glyphBitmap`. Currently safe —
   Compose's `painterResource` does not share Android Drawable constant state — but it
   would become a real bug if anyone tints those vectors through the Drawable API a
   second time. One call to make it moot.

## Bugs found that were already live in 0.5.0

1. **`prunePdfs` over-prunes.** Its running total keeps counting bytes of files it has
   already deleted, so once one file tips the total over budget, every older file is
   deleted regardless of its own size. With a 25MB budget and files of 1MB / 26MB / 1MB
   newest-first, the trailing 1MB file is deleted although the survivors total 2MB.
   Fixed on this branch.

2. **The attachment download never had a chance on mobile data.** `fetchPdfRender` pulled
   the whole 5.6MB circular under an 8-second ceiling, which needs a sustained ~5.6 Mbps.
   You confirmed this on your device.

## Things only you can do

1. **Run the poller tests in the Apps Script editor.** They are written but cannot be
   executed from here. Run `testPollerParseFixture` in the sender project. Expect:
   `testPollerParseFixture: 4 items parsed, all fields correct.` and a `true` return.
   Until that passes, treat the poller change as unverified.

2. **The website pipeline is not built yet.** The three new payload keys are optional and
   absent today, so nothing changes until Hugo publishes them. The CI step that produces
   them is documented in `docs/sender-contract.md`:
   `pdftoppm` for the thumbnail, `pdfinfo` for the page count, `stat` for the bytes.
   Hugo cannot rasterize a PDF itself, so the thumbnail must be made before Hugo runs.

3. **Decide whether a zero-page or zero-byte PDF should suppress its badge field.**
   The poller currently drops `pdfPages`/`pdfBytes` when they are `0`, treating zero as
   bad data. I believe that is right — a zero-page PDF is a broken file — but it is a
   judgement call baked into a falsy check rather than an explicit one.

## Blocked on you — device testing

Your phone `RZ8R60HNP5T` has release-signed **0.5.0 (versionCode 9)** installed. A debug
build cannot replace it: `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, signatures do not match.

The only way through is `adb uninstall`, which **deletes your stored notices and your
redeemed activation code**. Your notices are the only copy of anything already dismissed,
and one code means one device. I did not do it and will not without you saying so.

Pick one:
- **(a)** A spare or test phone — cleanest, and exercises a fresh install rather than the
  migration.
- **(b)** Give me the keystore path and alias and I build a release-signed APK, which
  installs over 0.5.0 and exercises `MIGRATION_3_4` against your real notices. This is the
  most valuable test available, because it is the one every real user will perform.
- **(c)** Ship verified by build and tests only. Unit tests pass, `assembleDebug` passes,
  and the instrumented migration test passed on the other phone (`R5CX90A4KSN`) — but
  against seeded rows, not your real history.

I would pick (b) if the keystore is to hand, otherwise (a).

## On-device checklist (from the final whole-branch review)

Nothing below is reachable by a unit test. Use the TestSends harness.

1. **Migration on a real upgraded install** — a device already holding notices upgrades to
   v4 with history intact, and a notice whose thumbnail never arrived repairs itself on the
   next app open. The one irreversible step; the data is the user's only copy.
2. **The tap on mobile data** — wifi off, tap a deferred notice's glyph, confirm it downloads.
   The worst-outcome path in the design.
3. **The race, now fixed** — open the app and tap the newest notice's glyph within two
   seconds, so the catch-up sweep and the tap run concurrently. Expect a correct picture,
   not a blank tile.
4. **`pdfThumbUrl` present means no PDF download** — confirm ~40KB moves, not ~5.6MB, on
   wifi as well as mobile data.
5. **Notification updates in place** — posts with the type glyph, gains the picture later
   without buzzing again, and a dismissed one does not come back.
6. **Channel preserved on update** — a TESTING or LOW notice must not be promoted to HIGH.
7. **Share a real circular** — WhatsApp must receive the PDF binary, all 192 pages, not the
   page-one JPEG. This is the complaint the branch exists for.
8. **PDF tap hand-off and browser fallback** — leaves the app to a reader; with no reader
   installed, lands on the URL in a browser rather than nowhere.
9. **Spinner on a collapsed row** — a thumbless circular can take five minutes.
10. **Badge legibility** at max system font size, both themes.
11. **Glyph contrast** in both themes, including the tray disc.
12. **TalkBack on the placeholder** — one announcement, button role, no "Download" while
    the spinner shows.
13. **No prune cascade** — fetch ten-plus circulars, confirm the newest survives and older
    ones go one at a time.
