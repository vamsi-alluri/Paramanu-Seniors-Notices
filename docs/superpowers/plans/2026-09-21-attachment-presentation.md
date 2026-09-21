# Attachment Presentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** One attachment per notice, labelled with what it is, opened by tapping it, and shared as the real file — replacing four ways to open an attachment with one, and a rasterized first page with the actual PDF.

**Architecture:** The card resolves a single attachment (cached photo, else cached PDF render, else the Spec 1 placeholder) and overlays a WhatsApp-style badge on the expanded preview. Tapping routes by type: an image to the in-app zoom viewer, a PDF straight out to an external reader. Share and Save become the only two actions and both operate on the real file, fetching it on demand through the existing tap path when a metered connection deferred it.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Coil 3, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-21-attachment-presentation-design.md`

**Depends on:** the `deferred-attachment-fetching` plan, complete. In particular `NoticeEntity.pdfPages` / `.pdfBytes`, `AttachmentPlaceholder`, `NoticeViewModel.downloadAttachment`, and the rule that Compose renders **local files only**.

## Global Constraints

- **A notice carries at most one attachment — a PDF or an image, never both.** Enforced upstream by RSS's one-enclosure-per-item rule (`Poller.gs:105`).
- **No new user-visible strings beyond the badge itself.** This app never explains a state in words; every sentence becomes a helpdesk call. Strings being *removed* is expected.
- **No remote URL may reach Coil.** Local files only, everywhere.
- **The rendered page one is display-only** — never opened, never shared, never saved.
- **A tap is consent**: the fetch policy applies to the worker, never to a user-initiated fetch.
- Sizes render as `5.6 MB` / `300 kB` — one decimal above a megabyte, none below, SI units to match the phone's own file manager.
- KDoc comments carrying reasoning are part of the deliverable; this codebase's comments are its primary specification.
- Verify with `.\gradlew.bat :app:testDebugUnitTest` and `.\gradlew.bat :app:assembleDebug`. A device (`R5CX90A4KSN`) is connected.

---

### Task 1: The badge, as a pure function and an overlay

**Files:**
- Create: `app/src/main/java/org/paramanuseniorshealth/notices/ui/AttachmentBadge.kt`
- Create: `app/src/test/java/org/paramanuseniorshealth/notices/AttachmentBadgeTest.kt`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/ui/NoticeAttachments.kt`

**Interfaces produced:** `AttachmentBadge.label(isPdf: Boolean, pages: Int?, bytes: Long?): String`; `AttachmentBadge.formatSize(bytes: Long?): String?`; `@Composable AttachmentBadge(text: String, modifier: Modifier)`.

- [ ] **Step 1: Write the failing tests.** Cover every row of the spec's badge table plus the null combinations and the size boundaries:

| Input | Expected |
|---|---|
| `label(isPdf=true, 192, 5_872_345)` | `PDF · 192 pages · 5.6 MB` |
| `label(isPdf=true, 1, 307_200)` | `PDF · 1 page · 300 kB` |
| `label(isPdf=true, null, null)` | `PDF` |
| `label(isPdf=true, 192, null)` | `PDF · 192 pages` |
| `label(isPdf=true, null, 5_872_345)` | `PDF · 5.6 MB` |
| `label(isPdf=false, null, 1_258_291)` | `Image · 1.2 MB` |
| `label(isPdf=false, null, null)` | `Image` |
| `formatSize(0)` | `0 kB` |
| `formatSize(1_023)` | `1 kB` (never `0 kB` for a non-empty file) |
| `formatSize(1_048_576)` | `1.0 MB` |
| `formatSize(null)` | `null` |

Singular/plural on `page` is the detail most likely to be got wrong; assert it explicitly. A `pages` value of `0` or negative is bad data — treat it as absent rather than rendering `0 pages`.

- [ ] **Step 2: Run them and confirm they fail** for an unresolved reference, not a syntax error.
  `.\gradlew.bat :app:testDebugUnitTest --tests "*AttachmentBadgeTest"`

- [ ] **Step 3: Implement `AttachmentBadge.label` and `formatSize`.** No Android imports in the formatting functions — they must be JVM-testable. Join present parts with `" · "` (U+00B7, spaces either side).

- [ ] **Step 4: Implement the overlay composable.** A `Box` aligned to `BottomStart` over the attachment image: a scrim (`Color.Black.copy(alpha = 0.55f)`) spanning the full width, with `Color.White` text at `bodyMedium`, padded 8dp horizontal and 4dp vertical. It is part of the picture rather than a caption beneath it, so it costs no vertical space and reads as a property of the thing. Do not draw it on the 56dp collapsed thumbnail — it would be illegible.

- [ ] **Step 5: Single-attachment resolution in `NoticeAttachments`.** Replace `listOfNotNull(photo, pdfRender).forEach { … }` with one resolved attachment: the photo, else the PDF render. `isPdf` is `!notice.pdfUrl.isNullOrBlank()`. Pass `notice.pdfPages` and `notice.pdfBytes`; for an image with no stored size, fall back to the file's own `length()`.

- [ ] **Step 6: Run the tests and the build.** Both must pass.

- [ ] **Step 7: Commit.**

---

### Task 2: Two actions, and tapping to open

**Files:**
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/ui/NoticeAttachments.kt`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/ui/NoticeListScreen.kt`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/ui/NoticeViewModel.kt`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/MainActivity.kt`
- Modify: `app/src/main/res/drawable/` — add `ic_save.xml` if no suitable icon exists (`ic_share.xml` already does)

**Interfaces produced:** `NoticeViewModel.shareAttachment(notice, onReady: (File) -> Unit)` and `saveAttachment(notice, onReady: (File) -> Unit)`, or one `withAttachmentFile(notice, then: (File) -> Unit)` if that reads better — decide and say which.

- [ ] **Step 1: Reduce `FileActions` to Share and Save.** Delete the Open `TextButton` entirely. Give Share the existing `ic_share.xml` icon alongside its label, since the user asked for it to be recognisable at a glance.

- [ ] **Step 2: Delete the separate "Open circular" button** and its `downloading` branch from `NoticeAttachments` — tapping the thumbnail replaces it. The spinner moves to the thumbnail itself (see Step 4).

- [ ] **Step 3: Route Share and Save through the real file.** For a PDF the file is the *document*, never the render. The document may not be on the phone — a metered connection defers it — so both actions must fetch on demand through the same path `openPdf` already uses, showing the existing `downloadingPdf` spinner, then act. `fetchPdf` returns a cached copy instantly when there is one. A tap is consent: **do not apply `FetchPolicy` here.**

- [ ] **Step 4: Tap routing on the attachment image.**
  - `pdfUrl` present → fetch if needed, then hand to an external reader, falling back to opening `pdfUrl` in a browser when nothing can display a PDF. This is what `NoticeViewModel.openPdf` already does; reuse it.
  - Otherwise → the in-app viewer, as now.
  - While a tap-fetch is in flight the thumbnail shows a spinner overlay, so every tap has visible feedback. This matters more than usual: the app is forbidden from explaining itself in words, so a tap that appears to do nothing gets tapped again.

- [ ] **Step 5: Build and run the unit suite.** Both must pass.

- [ ] **Step 6: Commit.**

---

### Task 3: The viewer becomes image-only, and the strings go

**Files:**
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/ui/NoticeViewerScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Drop the `cachedPdfRender` fallback** from the viewer's `LaunchedEffect`. A PDF never reaches this screen now, so resolving one here would only produce a picture of page one that the user cannot act on — exactly the confusion this spec removes. It loads `cachedImage` only.

- [ ] **Step 2: Reduce `ViewerActions` to Share and Save**, deleting the "Open with" item, so the overflow matches the card exactly — the same two words in both places.

- [ ] **Step 3: Remove the now-unused strings** `action_open_with` and `action_open_pdf`. Grep first and confirm zero references remain; a stale `R.string` reference is a compile error, and a stale *string* is silent rot.

- [ ] **Step 4: Check `attachment_downloading`.** If the spinner moved to the thumbnail and no longer shows text, remove it too. If it is still drawn, leave it.

- [ ] **Step 5: Build and run the unit suite.**

- [ ] **Step 6: Commit.**

---

## Verification before calling this done

- [ ] `.\gradlew.bat :app:testDebugUnitTest` — all suites pass
- [ ] `.\gradlew.bat :app:assembleDebug` — `BUILD SUCCESSFUL`
- [ ] Install on the connected device and confirm, on a real notice: the badge reads correctly on an expanded card; tapping a PDF thumbnail opens a PDF reader rather than the in-app viewer; tapping an image thumbnail opens the in-app viewer; Share on a circular produces a PDF in the share target, not a JPEG; and only two buttons appear under an attachment.
- [ ] `grep -rn "action_open_with\|action_open_pdf" app/src` returns nothing.
