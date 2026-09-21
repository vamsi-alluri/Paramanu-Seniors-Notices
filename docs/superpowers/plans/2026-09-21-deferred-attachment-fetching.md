# Deferred Attachment Fetching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move attachment downloading out of the FCM message path into a jittered, policy-gated WorkManager job, so a 5.6MB circular no longer blocks notification delivery, no longer downloads on every one of ~400 phones, and no longer fails silently at an 8-second timeout.

**Architecture:** `NoticeMessagingService` stops touching the network: it saves the row, posts a placeholder notification, and enqueues `AttachmentWorker` with a random 0–60 minute delay. The worker asks a pure `FetchPolicy` whether to fetch, given size, metering and roaming, and records the outcome in a new `attachmentState` column so deferred and failed attachments can retry themselves while pruned ones wait for a tap. The UI is cut off from the network entirely — Compose renders local files only — which closes the hole where Coil bypassed every policy.

**Tech Stack:** Kotlin, Jetpack Compose, Room 2.x (`androidx.sqlite` migration API), WorkManager (`androidx.work`), Coil 3, Firebase Messaging, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-09-21-deferred-attachment-fetching-design.md`

## Global Constraints

- **minSdk 26.** `NET_CAPABILITY_NOT_ROAMING` is API 28+; 26–27 must fall back to `TelephonyManager.isNetworkRoaming`.
- **No error text, ever.** Deferred, failed, pending and pruned all render as one tappable download glyph. New strings are content descriptions only — nothing new is drawn on screen.
- **Migrations must be real.** `NoticesDatabase.kt:18` forbids destructive fallback; stored notices are the only copy a user has of a dismissed notice.
- **Metered threshold 2MB. Unmetered cap 25MB. Roaming fetches nothing. `FAILED` caps at 5 attempts; `DEFERRED` never counts against the cap.**
- **Image cache budget 25MB, PDF cache budget 50MB**, both byte-based.
- **The app must tolerate `pdfThumbUrl` / `pdfBytes` / `pdfPages` being absent**, because the publishing pipeline ships after this release.
- **Follow `ActivationRefreshWorker`** (`activation/ActivationRefreshWorker.kt`) for worker shape, jitter, `enqueueUniqueWork` and `NoticesApplication` access.
- Run tests with `.\gradlew.bat :app:testDebugUnitTest`. Verify compilation with `.\gradlew.bat :app:assembleDebug`.

---

## File Structure

**Create:**
- `fcm/FetchPolicy.kt` — pure decision logic + `AttachmentState`. No Android imports, fully JVM-testable.
- `fcm/NetworkStatus.kt` — metering/roaming/`Content-Length` probes. Deliberately logic-free.
- `fcm/AttachmentWorker.kt` — the deferred fetch job and its enqueue helpers.
- `ui/AttachmentPlaceholder.kt` — the type icon + download glyph.
- `res/drawable/ic_file_pdf.xml`, `ic_file_image.xml`, `ic_download.xml`
- `app/src/test/.../FetchPolicyTest.kt`, `PruneTest.kt`
- `app/src/androidTest/.../MigrationTest.kt`

**Modify:**
- `data/NoticeEntity.kt` — five columns
- `data/NoticesDatabase.kt` — version 4 + `MIGRATION_3_4`
- `data/NoticeDao.kt`, `data/NoticeRepository.kt` — state reads/writes
- `fcm/PdfPageRenderer.kt` — expose page count
- `fcm/NoticeImageStore.kt` — keep the PDF, byte-budget pruning, worker timeout
- `fcm/NoticeMessagingService.kt` — stop fetching
- `fcm/NoticeNotifications.kt` — placeholder icon, in-place update
- `ui/NoticeListScreen.kt`, `ui/NoticeAttachments.kt` — local files only
- `ui/NoticeViewModel.kt`, `MainActivity.kt` — retry triggers
- `docs/apps-script/sender/Poller.gs`, `docs/apps-script/sender/Tests.gs`, `docs/sender-contract.md`

---

### Task 1: The fetch policy

Pure Kotlin, no Android. Everything that can be got wrong lives here so it can be tested without a device.

**Files:**
- Create: `app/src/main/java/org/paramanuseniorshealth/notices/fcm/FetchPolicy.kt`
- Test: `app/src/test/java/org/paramanuseniorshealth/notices/FetchPolicyTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class AttachmentState { PENDING, DEFERRED, FAILED, FETCHED }` with `AttachmentState.parse(raw: String?): AttachmentState`; `FetchPolicy.shouldFetch(sizeBytes: Long?, metered: Boolean, roaming: Boolean): Boolean`; `FetchPolicy.shouldRetry(state: AttachmentState, attempts: Int): Boolean`; `FetchPolicy.isPruned(state: AttachmentState, fileExists: Boolean): Boolean`; constants `METERED_MAX_BYTES`, `UNMETERED_MAX_BYTES`, `MAX_ATTEMPTS`.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.fcm.AttachmentState
import org.paramanuseniorshealth.notices.fcm.FetchPolicy

class FetchPolicyTest {

    private val small = 40L * 1024
    private val circular = 5_872_345L

    @Test
    fun `roaming fetches nothing, at any size`() {
        assertFalse(FetchPolicy.shouldFetch(small, metered = true, roaming = true))
        assertFalse(FetchPolicy.shouldFetch(small, metered = false, roaming = true))
        assertFalse(FetchPolicy.shouldFetch(circular, metered = false, roaming = true))
    }

    @Test
    fun `metered fetches up to two megabytes`() {
        assertTrue(FetchPolicy.shouldFetch(small, metered = true, roaming = false))
        assertTrue(FetchPolicy.shouldFetch(FetchPolicy.METERED_MAX_BYTES, metered = true, roaming = false))
        assertFalse(FetchPolicy.shouldFetch(FetchPolicy.METERED_MAX_BYTES + 1, metered = true, roaming = false))
        assertFalse(FetchPolicy.shouldFetch(circular, metered = true, roaming = false))
    }

    @Test
    fun `unmetered fetches a circular but not an absurd file`() {
        assertTrue(FetchPolicy.shouldFetch(circular, metered = false, roaming = false))
        assertTrue(FetchPolicy.shouldFetch(FetchPolicy.UNMETERED_MAX_BYTES, metered = false, roaming = false))
        assertFalse(FetchPolicy.shouldFetch(FetchPolicy.UNMETERED_MAX_BYTES + 1, metered = false, roaming = false))
    }

    /** No Content-Length. Treated as large, because assuming small is the expensive mistake. */
    @Test
    fun `unknown size is deferred on metered and allowed on unmetered`() {
        assertFalse(FetchPolicy.shouldFetch(null, metered = true, roaming = false))
        assertTrue(FetchPolicy.shouldFetch(null, metered = false, roaming = false))
    }

    @Test
    fun `pending and deferred always retry, regardless of attempts`() {
        assertTrue(FetchPolicy.shouldRetry(AttachmentState.PENDING, 0))
        assertTrue(FetchPolicy.shouldRetry(AttachmentState.DEFERRED, 99))
    }

    @Test
    fun `failed retries up to the cap and then stops`() {
        assertTrue(FetchPolicy.shouldRetry(AttachmentState.FAILED, FetchPolicy.MAX_ATTEMPTS - 1))
        assertFalse(FetchPolicy.shouldRetry(AttachmentState.FAILED, FetchPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun `a fetched attachment never retries`() {
        assertFalse(FetchPolicy.shouldRetry(AttachmentState.FETCHED, 0))
    }

    /** Pruned is derived, not stored: it was delivered once, so it waits for a tap. */
    @Test
    fun `pruned is fetched with the file gone`() {
        assertTrue(FetchPolicy.isPruned(AttachmentState.FETCHED, fileExists = false))
        assertFalse(FetchPolicy.isPruned(AttachmentState.FETCHED, fileExists = true))
        assertFalse(FetchPolicy.isPruned(AttachmentState.DEFERRED, fileExists = false))
    }

    /** Rows predating the migration carry NULL, and must read as never-attempted. */
    @Test
    fun `an unknown or absent stored state reads as pending`() {
        assertEquals(AttachmentState.PENDING, AttachmentState.parse(null))
        assertEquals(AttachmentState.PENDING, AttachmentState.parse(""))
        assertEquals(AttachmentState.PENDING, AttachmentState.parse("NONSENSE"))
        assertEquals(AttachmentState.FETCHED, AttachmentState.parse("FETCHED"))
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*FetchPolicyTest"`
Expected: FAIL — `Unresolved reference: FetchPolicy`.

- [ ] **Step 3: Write the implementation**

```kotlin
package org.paramanuseniorshealth.notices.fcm

/**
 * What has been tried for a notice's attachment, and therefore what should be tried next.
 *
 * Stored as text in `notices.attachmentState`. Four values rather than five: **PRUNED is not
 * stored**, it is derived as [AttachmentState.FETCHED] with the file gone. Writing it would mean
 * giving the pruning code a database handle -- it runs inside NoticeImageStore from the FCM path
 * and has none -- to record something a one-line check at read time already answers.
 *
 * The user is never shown which of these applies. They exist so the app knows what to retry, not
 * so it can explain itself.
 */
enum class AttachmentState {
    /** The row exists and the worker has not run yet. */
    PENDING,

    /** The policy said no: metered and too large, or roaming. Not a failure, and never capped. */
    DEFERRED,

    /** A fetch was attempted and threw or returned nothing. Capped at [FetchPolicy.MAX_ATTEMPTS]. */
    FAILED,

    /** The file reached the phone. */
    FETCHED;

    companion object {
        /**
         * Anything unrecognised reads as [PENDING].
         *
         * Rows written before the migration carry NULL, and that must mean "never attempted" --
         * so every notice already on a tester's phone whose thumbnail never arrived repairs itself
         * on the next app open rather than staying blank forever.
         */
        fun parse(raw: String?): AttachmentState =
            entries.firstOrNull { it.name == raw } ?: PENDING
    }
}

/**
 * Whether to spend a user's data on an attachment, and whether to try again.
 *
 * Deliberately free of Android imports. Metering and roaming arrive as booleans from
 * [NetworkStatus], which is a thin wrapper with no decisions in it, so the whole of the judgement
 * is here and testable on the JVM.
 *
 * The numbers come from a real measurement: a dispensary circular runs to roughly 192 pages and
 * 5.6MB, and that is typical rather than exceptional. At ~400 devices, fetching one on every phone
 * is over 2GB off the website. Nothing here is a round number chosen for looks.
 */
object FetchPolicy {

    /**
     * Clears every link logo, sender photo and PDF thumbnail; clears no circular.
     *
     * The gap between a ~40KB thumbnail and a 5.6MB circular is wide enough that the exact
     * threshold hardly matters -- what matters is that it sits inside the gap.
     */
    const val METERED_MAX_BYTES = 2L * 1024 * 1024

    /**
     * A sanity cap, not a policy. On wifi the user is not paying by the megabyte; this exists so a
     * mispublished 400MB file cannot swallow the cache and push out every other notice.
     */
    const val UNMETERED_MAX_BYTES = 25L * 1024 * 1024

    /** After this many failures only a tap retries, so a permanently 404 URL stops costing data. */
    const val MAX_ATTEMPTS = 5

    /**
     * [sizeBytes] is null when the server sent no `Content-Length`, and is then treated as large:
     * guessing small is the mistake that costs a user money, guessing large costs them one tap.
     *
     * A user's tap does not come through here. Tapping is consent, and bypasses every tier.
     */
    fun shouldFetch(sizeBytes: Long?, metered: Boolean, roaming: Boolean): Boolean = when {
        // The one case where a picture nobody asked for can appear on a bill.
        roaming -> false
        metered -> sizeBytes != null && sizeBytes <= METERED_MAX_BYTES
        else -> sizeBytes == null || sizeBytes <= UNMETERED_MAX_BYTES
    }

    /**
     * [AttachmentState.DEFERRED] is uncapped on purpose. Deferring is the policy working, not a
     * failure, and a phone that sits on mobile data for a month must still fetch the moment it
     * reaches wifi.
     */
    fun shouldRetry(state: AttachmentState, attempts: Int): Boolean = when (state) {
        AttachmentState.PENDING, AttachmentState.DEFERRED -> true
        AttachmentState.FAILED -> attempts < MAX_ATTEMPTS
        AttachmentState.FETCHED -> false
    }

    /** Delivered once and since pruned. Shows the glyph, but never re-downloads by itself. */
    fun isPruned(state: AttachmentState, fileExists: Boolean): Boolean =
        state == AttachmentState.FETCHED && !fileExists
}
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*FetchPolicyTest"`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/fcm/FetchPolicy.kt app/src/test/java/org/paramanuseniorshealth/notices/FetchPolicyTest.kt
git commit -m "Add the attachment fetch policy and its state model"
```

---

### Task 2: Schema — five columns and a real migration

**Files:**
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/data/NoticeEntity.kt`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/data/NoticesDatabase.kt:22` (version), `:60` (add migration to builder)
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/data/NoticeDao.kt`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/data/NoticeRepository.kt:25-49`
- Create: `app/src/androidTest/java/org/paramanuseniorshealth/notices/MigrationTest.kt`
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`

**Interfaces:**
- Consumes: `AttachmentState` from Task 1.
- Produces: `NoticeEntity.pdfThumbUrl: String?`, `.pdfPages: Int?`, `.pdfBytes: Long?`, `.attachmentState: String?`, `.attachmentAttempts: Int`; `NoticeDao.withAttachments(): List<NoticeEntity>`; `NoticeDao.updateAttachment(logId: String, state: String, attempts: Int, pages: Int?, bytes: Long?)`; `NoticeRepository.byLogId(logId: String): NoticeEntity?`; `NoticeRepository.recordAttachment(logId: String, state: AttachmentState, attempts: Int, pages: Int?, bytes: Long?)`; `NoticeRepository.withAttachments(): List<NoticeEntity>`.

- [ ] **Step 1: Add the Room testing dependency**

In `gradle/libs.versions.toml`, under `[libraries]`:

```toml
androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }
```

In `app/build.gradle.kts` dependencies:

```kotlin
androidTestImplementation(libs.androidx.room.testing)
```

In `app/build.gradle.kts`, inside `android { defaultConfig { ... } }`, so the exported schemas are on the test classpath:

```kotlin
sourceSets {
    getByName("androidTest").assets.srcDir("$projectDir/schemas")
}
```

- [ ] **Step 2: Write the failing migration test**

This is an instrumented test — it needs a connected device or emulator. It is worth the ceremony: a broken migration destroys the only copy a user has of notices they have already dismissed.

```kotlin
package org.paramanuseniorshealth.notices

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.paramanuseniorshealth.notices.data.NoticesDatabase

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NoticesDatabase::class.java,
    )

    /** The case that matters: a notice already on a tester's phone must survive intact. */
    @Test
    fun migrate3To4_keepsExistingNotices() {
        helper.createDatabase(name, 3).use { db ->
            db.execSQL(
                "INSERT INTO notices (logId, title, body, receivedAt, imageUrl, pdfUrl) " +
                    "VALUES ('1757000000000', 'Holiday list', 'Closed Monday', 1757000000000, NULL, " +
                    "'https://paramanuseniorshealth.org/files/list.pdf')"
            )
        }

        val db = helper.runMigrationsAndValidate(
            name, 4, true, NoticesDatabase.MIGRATION_3_4,
        )

        db.query("SELECT title, body, pdfUrl, pdfThumbUrl, pdfPages, pdfBytes, " +
            "attachmentState, attachmentAttempts FROM notices").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Holiday list", c.getString(0))
            assertEquals("Closed Monday", c.getString(1))
            assertEquals("https://paramanuseniorshealth.org/files/list.pdf", c.getString(2))
            // The new columns arrive empty, and a NULL state reads as PENDING -- which is what
            // makes an existing notice with no thumbnail repair itself on the next app open.
            assertNull(c.getString(3))
            assertTrue(c.isNull(4))
            assertTrue(c.isNull(5))
            assertNull(c.getString(6))
            assertEquals(0, c.getInt(7))
        }
    }
}
```

- [ ] **Step 3: Run it and confirm it fails**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --tests "*MigrationTest"`
Expected: FAIL — no `MIGRATION_3_4`, and schema 4 has not been exported.

- [ ] **Step 4: Add the columns to the entity**

Append to `NoticeEntity`'s constructor, after `linkSite`:

```kotlin
    /**
     * A ready-made first-page image for [pdfUrl], published alongside the circular.
     *
     * Absent today: the website does not produce one yet, so the worker downloads the whole PDF and
     * renders page one itself. Once the pipeline ships this arrives filled in, the worker fetches
     * ~40KB instead of ~5.6MB, and the circular is only ever downloaded when somebody taps it.
     *
     * Persisted rather than used and discarded, because a notice deferred on mobile data may be
     * tapped a day later and the tap path has nothing else to read the URL from.
     */
    val pdfThumbUrl: String? = null,
    /**
     * Page count and size of [pdfUrl], for the card's badge.
     *
     * Filled from the payload when the sender supplies them, and otherwise derived locally the
     * first time the worker renders the PDF -- `PdfRenderer` already has both and used to throw
     * them away. Null means neither has happened yet, and the badge simply omits the numbers.
     */
    val pdfPages: Int? = null,
    val pdfBytes: Long? = null,
    /**
     * One of [org.paramanuseniorshealth.notices.fcm.AttachmentState], or NULL on a row written
     * before this column existed -- which reads as PENDING and so retries.
     */
    val attachmentState: String? = null,
    /** Failed fetches only. Deferrals do not count; see FetchPolicy.shouldRetry. */
    val attachmentAttempts: Int = 0,
```

- [ ] **Step 5: Write the migration**

In `NoticesDatabase.kt`, change `version = 3` to `version = 4`, and add inside `companion object`:

```kotlin
        /**
         * Adds the attachment columns.
         *
         * `attachmentState` is nullable rather than defaulted, so that an existing row is
         * distinguishable as "never attempted" and repairs itself on the next app open. A default
         * of 'PENDING' would read identically today but would lose that distinction the moment
         * anything else wanted to know whether a row predates this release.
         */
        @JvmField
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE notices ADD COLUMN pdfThumbUrl TEXT")
                connection.execSQL("ALTER TABLE notices ADD COLUMN pdfPages INTEGER")
                connection.execSQL("ALTER TABLE notices ADD COLUMN pdfBytes INTEGER")
                connection.execSQL("ALTER TABLE notices ADD COLUMN attachmentState TEXT")
                connection.execSQL(
                    "ALTER TABLE notices ADD COLUMN attachmentAttempts INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
```

Change the two existing migrations from `private val` to `@JvmField val` as well, so the test can name them, and extend the builder:

```kotlin
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
```

- [ ] **Step 6: Add the DAO queries**

```kotlin
    /**
     * Rows carrying an attachment, newest first, for the catch-up sweep.
     *
     * Filtering by state happens in Kotlin rather than SQL: the rule lives in
     * [org.paramanuseniorshealth.notices.fcm.FetchPolicy.shouldRetry], where it is unit-tested, and
     * duplicating it as a WHERE clause would give it two homes that could drift apart.
     */
    @Query(
        "SELECT * FROM notices WHERE imageUrl IS NOT NULL OR pdfUrl IS NOT NULL " +
            "OR pdfThumbUrl IS NOT NULL OR linkImage IS NOT NULL ORDER BY receivedAt DESC"
    )
    suspend fun withAttachments(): List<NoticeEntity>

    @Query(
        "UPDATE notices SET attachmentState = :state, attachmentAttempts = :attempts, " +
            "pdfPages = COALESCE(:pages, pdfPages), pdfBytes = COALESCE(:bytes, pdfBytes) " +
            "WHERE logId = :logId"
    )
    suspend fun updateAttachment(
        logId: String,
        state: String,
        attempts: Int,
        pages: Int?,
        bytes: Long?,
    )
```

`COALESCE` so a later deferral cannot blank out numbers an earlier fetch established.

- [ ] **Step 7: Add the repository passthroughs**

In `NoticeRepository`, extend `save` with the three payload-carried fields and add two functions:

```kotlin
    suspend fun byLogId(logId: String): NoticeEntity? = dao.byLogId(logId)

    suspend fun recordAttachment(
        logId: String,
        state: AttachmentState,
        attempts: Int,
        pages: Int? = null,
        bytes: Long? = null,
    ) = dao.updateAttachment(logId, state.name, attempts, pages, bytes)

    suspend fun withAttachments(): List<NoticeEntity> = dao.withAttachments()
```

Add `pdfThumbUrl: String? = null, pdfPages: Int? = null, pdfBytes: Long? = null` to `save`'s parameters and to the `NoticeEntity(...)` it builds, each with the same `?.takeIf { it.isNotBlank() }` treatment the other URLs get (the two numbers pass through unchanged).

- [ ] **Step 8: Run the migration test and confirm it passes**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --tests "*MigrationTest"`
Expected: PASS. A new `app/schemas/org.paramanuseniorshealth.notices.data.NoticesDatabase/4.json` is generated — commit it.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/data/ app/src/androidTest/ app/schemas/ gradle/libs.versions.toml app/build.gradle.kts
git commit -m "Add attachment columns and MIGRATION_3_4"
```

---

### Task 3: Byte-budget pruning

**Files:**
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/fcm/NoticeImageStore.kt:53` (`KEEP_NEWEST`), `:61` (`PDF_BUDGET_BYTES`), `:298-320` (both prune functions)
- Test: `app/src/test/java/org/paramanuseniorshealth/notices/PruneTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `NoticeImageStore.pruneTo(dir: File, budgetBytes: Long)`; `prune(context)` and `prunePdfs(context)` keep their names and lose their `keep` parameter.

- [ ] **Step 1: Write the failing test**

```kotlin
package org.paramanuseniorshealth.notices

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.paramanuseniorshealth.notices.fcm.NoticeImageStore
import java.io.File

class PruneTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Distinct mtimes, oldest first, so "newest kept" is unambiguous. */
    private fun write(name: String, bytes: Int, ageMillis: Long): File =
        folder.newFile(name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(System.currentTimeMillis() - ageMillis)
        }

    @Test
    fun `keeps the newest files within budget and deletes the rest`() {
        val newest = write("c.jpg", 400, 1_000)
        val middle = write("b.jpg", 400, 2_000)
        val oldest = write("a.jpg", 400, 3_000)

        NoticeImageStore.pruneTo(folder.root, budgetBytes = 900)

        assertTrue(newest.exists())
        assertTrue(middle.exists())
        assertFalse(oldest.exists())
    }

    @Test
    fun `a directory already within budget is untouched`() {
        val only = write("a.jpg", 100, 1_000)
        NoticeImageStore.pruneTo(folder.root, budgetBytes = 1_000)
        assertTrue(only.exists())
    }

    /**
     * The newest file is kept even alone over budget. Deleting it would mean a notice that had
     * just arrived was pruned before it could be read.
     */
    @Test
    fun `the newest file survives even when it alone exceeds the budget`() {
        val huge = write("a.jpg", 5_000, 1_000)
        NoticeImageStore.pruneTo(folder.root, budgetBytes = 100)
        assertTrue(huge.exists())
    }

    @Test
    fun `an empty or missing directory is a no-op`() {
        NoticeImageStore.pruneTo(folder.root, budgetBytes = 100)
        NoticeImageStore.pruneTo(File(folder.root, "absent"), budgetBytes = 100)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*PruneTest"`
Expected: FAIL — `Unresolved reference: pruneTo`.

- [ ] **Step 3: Replace both prune implementations**

Replace the `KEEP_NEWEST` constant and its comment with:

```kotlin
    /**
     * The artwork budget, in bytes.
     *
     * This used to be a file count (sixty). Counting files let sixty 40KB thumbnails and three
     * multi-megabyte page renders be called the same amount of storage, which they are not -- and a
     * page-sized render of an A4 circular is the larger artefact by an order of magnitude.
     */
    private const val IMAGE_BUDGET_BYTES = 25L * 1024 * 1024
```

Change `PDF_BUDGET_BYTES` to `50L * 1024 * 1024` and update its comment: about nine circulars at the measured 5.6MB, not twelve at 2MB.

Replace both prune functions with:

```kotlin
    fun prune(context: Context) = pruneTo(directory(context), IMAGE_BUDGET_BYTES)

    fun prunePdfs(context: Context) = pruneTo(fileDirectory(context), PDF_BUDGET_BYTES)

    /**
     * Trims [dir] to [budgetBytes], oldest first.
     *
     * The newest file is always kept, even if it alone is over budget: it is the notice that has
     * just arrived, and deleting it would mean the user never sees the attachment they were
     * notified about. An oversized file is skipped rather than ending the scan, so a smaller older
     * file that still fits is kept. One shape for both directories, because "how much storage may
     * this use" is the same question whether the files are thumbnails or circulars.
     */
    fun pruneTo(dir: File, budgetBytes: Long) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var used = 0L
        files.forEachIndexed { index, file ->
            val projected = used + file.length()
            if (index > 0 && projected > budgetBytes) {
                file.delete()
            } else {
                used = projected
            }
        }
    }
```

- [ ] **Step 4: Run the test and confirm it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*PruneTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/fcm/NoticeImageStore.kt app/src/test/java/org/paramanuseniorshealth/notices/PruneTest.kt
git commit -m "Budget the artwork cache in bytes, and raise both budgets"
```

---

### Task 4: Network status probes

No tests: this file is deliberately nothing but framework calls, and every decision it feeds was tested in Task 1. Keeping it logic-free is what makes that true — do not add a branch here.

**Files:**
- Create: `app/src/main/java/org/paramanuseniorshealth/notices/fcm/NetworkStatus.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `NetworkStatus.isMetered(context: Context): Boolean`; `NetworkStatus.isRoaming(context: Context): Boolean`; `NetworkStatus.remoteSize(url: String): Long?`.

- [ ] **Step 1: Write the file**

```kotlin
package org.paramanuseniorshealth.notices.fcm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * What the connection is, for [FetchPolicy] to judge.
 *
 * Nothing here decides anything. Every branch that matters lives in [FetchPolicy], which has no
 * Android imports and is unit-tested; this file exists only because the framework will not answer
 * these three questions on the JVM. If a policy question appears here, it is in the wrong file.
 */
object NetworkStatus {

    private const val TAG = "NetworkStatus"

    /** Absent or unknown network reads as metered: the cautious answer costs one tap. */
    fun isMetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return manager.isActiveNetworkMetered
    }

    /**
     * `NET_CAPABILITY_NOT_ROAMING` arrived in API 28 and this app's minSdk is 26, so 26 and 27 use
     * the telephony answer instead. Neither needs a permission.
     *
     * Unknown reads as not roaming. Roaming stops every automatic fetch, so guessing "yes" would
     * leave a user with a permanently blank card for a reason they cannot see and the app cannot
     * explain.
     */
    fun isRoaming(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val manager = context.getSystemService(ConnectivityManager::class.java)
                ?: return@runCatching false
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
                ?: return@runCatching false
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(TelephonyManager::class.java)?.isNetworkRoaming ?: false
        }
    }.onFailure { Log.w(TAG, "Could not read roaming state", it) }.getOrDefault(false)

    /**
     * The size of [url] without downloading it, via HEAD.
     *
     * Returns null when the server does not answer HEAD, sends no `Content-Length`, or is
     * unreachable. [FetchPolicy] treats null as large, so an uncooperative server costs the user a
     * tap rather than an unannounced 5.6MB.
     *
     * Once the publishing pipeline ships, `pdfBytes` arrives in the payload and this is not called
     * for circulars at all -- which is the point of that field: a metered phone then skips the
     * download without making any request whatsoever.
     */
    fun remoteSize(url: String): Long? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.contentLengthLong.takeIf { it >= 0 }
        } finally {
            connection.disconnect()
        }
    }.onFailure { Log.i(TAG, "No size for $url: ${it.message}") }.getOrNull()
}
```

- [ ] **Step 2: Confirm it compiles**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/fcm/NetworkStatus.kt
git commit -m "Add metering, roaming and remote-size probes"
```

---

### Task 5: Keep the PDF, and report its page count

**Files:**
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/fcm/PdfPageRenderer.kt`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/fcm/NoticeImageStore.kt:173-196`
- Test: `app/src/test/java/org/paramanuseniorshealth/notices/PdfPageRendererTest.kt` (existing, extend)

**Interfaces:**
- Consumes: nothing.
- Produces: `PdfPageRenderer.pageCount(file: File): Int?`; `NoticeImageStore.fetchPdfKeeping(context, pdfUrl, logId): PdfFetch?` where `data class PdfFetch(val pdf: File, val render: File, val pages: Int, val bytes: Long)`.

- [ ] **Step 1: Extend the existing renderer test**

`PdfPageRendererTest` already exercises `fitLetterbox` on the JVM. `pageCount` needs `android.graphics.pdf`, which is stubbed on the JVM, so test only the guard clauses — the real rendering is covered by running the app.

```kotlin
    @Test
    fun `page count declines a missing or empty file`() {
        assertNull(PdfPageRenderer.pageCount(File("does-not-exist.pdf")))
        val empty = File.createTempFile("empty", ".pdf").apply { deleteOnExit() }
        assertNull(PdfPageRenderer.pageCount(empty))
    }
```

Add `import org.junit.Assert.assertNull` and `import java.io.File` if absent.

- [ ] **Step 2: Run it and confirm it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*PdfPageRendererTest"`
Expected: FAIL — `Unresolved reference: pageCount`.

- [ ] **Step 3: Add `pageCount`**

In `PdfPageRenderer`, after `isReadablePdf`:

```kotlin
    /**
     * How many pages [file] has, or null if it cannot be opened.
     *
     * The renderer already had this and discarded it: [renderFirstPage] opens a `PdfRenderer` and
     * reads `pageCount` purely to check it is at least one. A dispensary circular runs to about
     * 192 pages, which is worth telling the reader before they tap into it.
     */
    fun pageCount(file: File): Int? {
        if (!file.exists() || file.length() == 0L) return null
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        return try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor)
            renderer.pageCount.takeIf { it >= 1 }
        } catch (e: Exception) {
            Log.w(TAG, "Could not count pages in ${file.name}", e)
            null
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
    }
```

- [ ] **Step 4: Replace `fetchPdfRender` with a keeping version**

```kotlin
    /** What one circular fetch produced: the document, its first page, and its measurements. */
    data class PdfFetch(val pdf: File, val render: File, val pages: Int, val bytes: Long)

    /**
     * Downloads a circular, renders page one, and **keeps both**.
     *
     * This used to download to a scratch file and delete it, on the reasoning that holding a large
     * download for a file the user may never open paid the cost early for everyone to benefit
     * nobody. The reasoning was about the wrong cost. The download happens either way -- page one
     * cannot be rendered without the whole document, because `PdfRenderer` takes a descriptor on a
     * complete file -- so deleting it saved no bandwidth at all and guaranteed a second download
     * later. It saved disk, which [prunePdfs] already manages.
     *
     * Runs under [WORKER_TIMEOUT_MS], not the old eight seconds: nothing is holding a service open
     * behind this any more.
     */
    suspend fun fetchPdfKeeping(context: Context, pdfUrl: String, logId: String): PdfFetch? =
        withTimeoutOrNull(WORKER_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val scratch = File(context.cacheDir, "notice_$logId-doc.part")
                runCatching {
                    download(pdfUrl, scratch)
                    if (!PdfPageRenderer.isReadablePdf(scratch)) {
                        error("$pdfUrl is not a readable PDF")
                    }
                    val pages = PdfPageRenderer.pageCount(scratch) ?: error("No pages in $pdfUrl")
                    val bytes = scratch.length()

                    val rendered = PdfPageRenderer.renderFirstPage(scratch)
                        ?: error("Could not render $pdfUrl")
                    val render = File(directory(context), "${stem(logId, "pdf")}.jpg")
                    writeJpeg(rendered, render)
                    rendered.recycle()

                    // Moved into place only after the render succeeded, so a file that cannot be
                    // shown is never left sitting in the cache looking like a usable circular.
                    val pdf = File(fileDirectory(context), "${stem(logId, "doc")}.pdf")
                    pdf.delete()
                    moveInto(scratch, pdf)

                    prune(context)
                    prunePdfs(context)
                    PdfFetch(pdf, render, pages, bytes)
                }.onFailure {
                    Log.w(TAG, "Notice PDF failed for $pdfUrl", it)
                    runCatching { scratch.delete() }
                }.getOrNull()
            }
        }
```

Add the constant beside the existing timeouts:

```kotlin
    /**
     * The worker's ceiling.
     *
     * [TIMEOUT_MS] was eight seconds because `onMessageReceived` may be torn down after ten or
     * twenty. A 5.6MB circular needs a sustained ~5.6 Mbps to land inside that, which an ordinary
     * mobile connection does not provide -- so the download reliably failed and nobody was told.
     * Nothing is held open behind the worker, so it can wait as long as the file honestly needs.
     */
    private const val WORKER_TIMEOUT_MS = 5L * 60 * 1000
```

Delete the old `fetchPdfRender`. `fetchPdf` stays exactly as it is — it is the tap path, and its 60-second ceiling still suits a user watching a spinner.

- [ ] **Step 5: Run the tests and confirm they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: PASS. `NoticeMessagingService` will not compile yet — that is Task 6; if the build breaks on its `fetchPdfRender` call, comment that call out with a `// TODO Task 6` and restore it there.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/fcm/PdfPageRenderer.kt app/src/main/java/org/paramanuseniorshealth/notices/fcm/NoticeImageStore.kt app/src/test/java/org/paramanuseniorshealth/notices/PdfPageRendererTest.kt
git commit -m "Keep the downloaded circular and report its page count"
```

---

### Task 6: The worker, and a message path that touches no network

**Files:**
- Create: `app/src/main/java/org/paramanuseniorshealth/notices/fcm/AttachmentWorker.kt`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/fcm/NoticeMessagingService.kt:40-120`

**Interfaces:**
- Consumes: `FetchPolicy`, `AttachmentState` (Task 1); `NoticeRepository.byLogId`/`.recordAttachment`/`.withAttachments` (Task 2); `NoticeImageStore.fetchPdfKeeping`, `NetworkStatus` (Tasks 4–5).
- Produces: `AttachmentWorker.enqueueFor(context: Context, logId: String)`; `AttachmentWorker.enqueueCatchUp(context: Context)`; `AttachmentWorker.enqueueWifiCatchUp(context: Context)`.

- [ ] **Step 1: Write the worker**

```kotlin
package org.paramanuseniorshealth.notices.fcm

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.paramanuseniorshealth.notices.NoticesApplication
import org.paramanuseniorshealth.notices.data.NoticeEntity
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Fetches a notice's attachments, away from the message path and on the user's terms.
 *
 * Every attachment used to be downloaded inside `onMessageReceived`, under `runBlocking`, before
 * the notification was posted. For a circular that meant the whole 5.6MB document on all ~400
 * phones at once -- over 2GB off the website per notice -- inside an eight-second ceiling it could
 * not meet, so it usually failed and said nothing.
 *
 * Here instead: the notification goes out immediately, and the download happens somewhere in the
 * next hour, if [FetchPolicy] agrees it should happen at all. Where it does not, the card shows a
 * download glyph and the user's tap is the retry. No part of that is explained in words.
 */
class AttachmentWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as NoticesApplication
        val single = inputData.getString(KEY_LOG_ID)

        val notices = if (single != null) {
            listOfNotNull(app.repository.byLogId(single))
        } else {
            // The catch-up sweep. Filtering happens here rather than in SQL so the rule has one
            // home, in FetchPolicy, where it is tested.
            app.repository.withAttachments().filter {
                FetchPolicy.shouldRetry(
                    AttachmentState.parse(it.attachmentState),
                    it.attachmentAttempts,
                )
            }
        }

        notices.forEach { notice -> runCatching { fetch(app, notice) } }
        return Result.success()
    }

    private suspend fun fetch(app: NoticesApplication, notice: NoticeEntity) {
        val logId = notice.logId ?: return
        val context = applicationContext
        val metered = NetworkStatus.isMetered(context)
        val roaming = NetworkStatus.isRoaming(context)

        var deferred = false
        var failed = false
        var pages: Int? = null
        var bytes: Long? = null

        // Small things first: a link logo and a sender photo clear the 2MB threshold on any
        // non-roaming connection, so the card has something to show within the hour.
        notice.linkImage?.takeIf { it.isNotBlank() }?.let { url ->
            if (NoticeImageStore.cachedLinkImage(context, logId) == null) {
                when (decide(url, null, metered, roaming)) {
                    Verdict.FETCH ->
                        if (NoticeImageStore.fetchLinkImage(context, url, logId) == null) failed = true
                    Verdict.DEFER -> deferred = true
                }
            }
        }

        notice.imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            if (NoticeImageStore.cachedImage(context, logId) == null) {
                when (decide(url, null, metered, roaming)) {
                    Verdict.FETCH ->
                        if (NoticeImageStore.fetchImage(context, url, logId) == null) failed = true
                    Verdict.DEFER -> deferred = true
                }
            }
        }

        // The circular. Two quite different routes.
        val pdfUrl = notice.pdfUrl?.takeIf { it.isNotBlank() }
        val thumbUrl = notice.pdfThumbUrl?.takeIf { it.isNotBlank() }
        if (pdfUrl != null && NoticeImageStore.cachedPdfRender(context, logId) == null) {
            if (thumbUrl != null) {
                // The pipeline has published a first-page image, so the document itself is not
                // touched until somebody taps it. This is the whole egress saving and it must not
                // be "optimised" into pre-fetching on wifi: the website pays for the bytes whatever
                // the phone is connected to.
                when (decide(thumbUrl, null, metered, roaming)) {
                    Verdict.FETCH ->
                        if (NoticeImageStore.fetchPdfThumb(context, thumbUrl, logId) == null) failed = true
                    Verdict.DEFER -> deferred = true
                }
            } else {
                // No published thumbnail, so page one can only be had by downloading the document.
                // Its size decides, and the result is kept rather than thrown away.
                when (decide(pdfUrl, notice.pdfBytes, metered, roaming)) {
                    Verdict.FETCH -> {
                        val fetched = NoticeImageStore.fetchPdfKeeping(context, pdfUrl, logId)
                        if (fetched == null) failed = true else {
                            pages = fetched.pages
                            bytes = fetched.bytes
                        }
                    }
                    Verdict.DEFER -> deferred = true
                }
            }
        }

        val state = when {
            // Deferral outranks failure: it is the policy working, and it must not burn an attempt.
            deferred -> AttachmentState.DEFERRED
            failed -> AttachmentState.FAILED
            else -> AttachmentState.FETCHED
        }
        val attempts = when (state) {
            AttachmentState.FAILED -> notice.attachmentAttempts + 1
            AttachmentState.FETCHED -> 0
            else -> notice.attachmentAttempts
        }
        app.repository.recordAttachment(logId, state, attempts, pages, bytes)

        if (state == AttachmentState.FETCHED) {
            NoticeNotifications.updatePicture(context, notice)
        }
        if (state == AttachmentState.DEFERRED) {
            // Nothing is scheduled for "later on mobile data" -- that is the tap. This is only the
            // standing wifi sweep, which costs nothing while no wifi appears.
            enqueueWifiCatchUp(context)
        }
    }

    private enum class Verdict { FETCH, DEFER }

    /**
     * [known] is the size the payload supplied, and skips the HEAD request entirely. Otherwise the
     * size is probed, which is one small round trip against a possible 5.6MB one.
     */
    private fun decide(url: String, known: Long?, metered: Boolean, roaming: Boolean): Verdict {
        // Roaming answers without any request at all: nothing is going to be fetched either way.
        if (roaming) return Verdict.DEFER
        val size = known ?: NetworkStatus.remoteSize(url)
        return if (FetchPolicy.shouldFetch(size, metered, roaming)) Verdict.FETCH else Verdict.DEFER
    }

    companion object {
        private const val TAG = "AttachmentWorker"
        private const val KEY_LOG_ID = "logId"
        private const val CATCH_UP = "attachment-catch-up"
        private const val CATCH_UP_WIFI = "attachment-catch-up-wifi"
        private const val MAX_JITTER_SECONDS = 60L * 60

        private fun connected() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        private fun unmetered() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        /**
         * The per-notice fetch, delayed by up to an hour.
         *
         * The jitter is the point: without it ~400 phones would start the same download in the same
         * second. `CONNECTED` rather than `UNMETERED`, because an unmetered *constraint* waits
         * indefinitely, and a thumbnail arriving in three days when the user next finds wifi is
         * worse than one that never arrives -- it cannot be explained, and this app does not
         * explain things. The metering question is asked at execution instead, where its answer can
         * become a tappable glyph.
         */
        fun enqueueFor(context: Context, logId: String) {
            val request = OneTimeWorkRequestBuilder<AttachmentWorker>()
                .setConstraints(connected())
                .setInitialDelay(Random.nextLong(0, MAX_JITTER_SECONDS), TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY_LOG_ID to logId))
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("attachment-$logId", ExistingWorkPolicy.KEEP, request)
        }

        /** Sweeps everything outstanding. Called when the app comes forward: no delay, no jitter. */
        fun enqueueCatchUp(context: Context) {
            val request = OneTimeWorkRequestBuilder<AttachmentWorker>()
                .setConstraints(connected())
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(CATCH_UP, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * The standing sweep that fires when wifi appears.
         *
         * This is where an `UNMETERED` constraint is right: nothing is waiting on it, so an
         * indefinite wait costs nothing, and the moment the phone reaches wifi every deferred
         * circular is fetched without the user doing anything. KEEP, so repeated calls do not
         * restart it.
         */
        fun enqueueWifiCatchUp(context: Context) {
            val request = OneTimeWorkRequestBuilder<AttachmentWorker>()
                .setConstraints(unmetered())
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(CATCH_UP_WIFI, ExistingWorkPolicy.KEEP, request)
        }
    }
}
```

- [ ] **Step 2: Add `fetchPdfThumb` to `NoticeImageStore`**

Beside `fetchImage` and `fetchLinkImage`, which already delegate to `fetchPicture`:

```kotlin
    /**
     * A published first-page image for a circular, stored under the same name the local render
     * uses -- so every reader downstream asks [cachedPdfRender] and neither knows nor cares which
     * of the two produced it.
     */
    suspend fun fetchPdfThumb(context: Context, url: String, logId: String): Bitmap? =
        fetchPicture(context, url, logId, "pdf")
```

- [ ] **Step 3: Strip fetching out of the message path**

In `NoticeMessagingService.kt`, read the three new keys alongside the existing ones:

```kotlin
        val pdfThumbUrl = data["pdfThumbUrl"]?.takeIf { it.isNotBlank() }
        val pdfPages = data["pdfPages"]?.toIntOrNull()
        val pdfBytes = data["pdfBytes"]?.toLongOrNull()
```

Pass all three to `repository.save(...)`. Then delete the entire `val picture = if (logId != null) { ... }` block and replace the post-and-fetch section with:

```kotlin
            // Nothing is downloaded here any more.
            //
            // This ran under runBlocking inside a service that may be torn down after ten or twenty
            // seconds, and for a circular it pulled the whole 5.6MB document to render page one.
            // The notification now goes out immediately with a type placeholder, and the worker
            // fills the picture in within the hour -- or leaves a download glyph, if the policy says
            // this is not the app's data to spend.
            if (isNew && message.notification == null) {
                NoticeNotifications.post(
                    context = this@NoticeMessagingService,
                    title = title,
                    body = body,
                    logId = logId,
                    hasPdf = pdfUrl != null,
                    hasImage = imageUrl != null,
                    channel = channel,
                )
            }
            if (isNew && logId != null) AttachmentWorker.enqueueFor(applicationContext, logId)
```

- [ ] **Step 4: Confirm it compiles**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: FAIL on `NoticeNotifications.post` — its signature changes in Task 7. Proceed to Task 7 and build there.

- [ ] **Step 5: Commit (after Task 7 builds)**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/fcm/
git commit -m "Fetch attachments in a jittered worker instead of on the message path"
```

---

### Task 7: Placeholder notification, updated in place

**Files:**
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/fcm/NoticeNotifications.kt:98-170`
- Create: `app/src/main/res/drawable/ic_file_pdf.xml`, `ic_file_image.xml`, `ic_download.xml`

**Interfaces:**
- Consumes: `NoticeImageStore.cachedImage`/`cachedPdfRender`, `NoticeEntity`.
- Produces: `NoticeNotifications.post(context, title, body, logId, hasPdf: Boolean = false, hasImage: Boolean = false, channel)`; `NoticeNotifications.updatePicture(context, notice: NoticeEntity)`.

- [ ] **Step 1: Add the drawables**

`ic_file_pdf.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FF000000"
        android:pathData="M14,2H6A2,2 0,0 0,4 4v16a2,2 0,0 0,2 2h12a2,2 0,0 0,2 -2V8l-6,-6zM13,9V3.5L18.5,9L13,9z" />
</vector>
```

`ic_file_image.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FF000000"
        android:pathData="M21,19V5a2,2 0,0 0,-2 -2H5a2,2 0,0 0,-2 2v14a2,2 0,0 0,2 2h14a2,2 0,0 0,2 -2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z" />
</vector>
```

`ic_download.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FF000000"
        android:pathData="M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z" />
</vector>
```

- [ ] **Step 2: Rework `post` and add `updatePicture`**

Replace `post`'s `image: Bitmap? = null` parameter with `hasPdf: Boolean = false, hasImage: Boolean = false`, and insert before `val picture = ...`:

```kotlin
        // The attachment has not been fetched yet -- that happens some time in the next hour, and
        // may not happen at all on a metered connection. A type glyph says "there is a circular
        // here" without claiming it has arrived, and without a sentence explaining why it has not.
        val placeholder = when {
            hasImage -> R.drawable.ic_file_image
            hasPdf -> R.drawable.ic_file_pdf
            else -> null
        }
        val image: Bitmap? = null
```

Add `.setOnlyAlertOnce(true)` to the builder, and set the large icon from the placeholder when there is no bitmap:

```kotlin
            .setLargeIcon(icon ?: placeholder?.let { context.glyphBitmap(it) })
            // The worker re-posts this same id when the picture lands, up to an hour later. Without
            // this the phone would buzz a second time for a notice the user already read.
            .setOnlyAlertOnce(true)
```

Add the helper and the update entry point at the end of the object:

```kotlin
    /** A vector turned into the bitmap `setLargeIcon` requires. */
    private fun Context.glyphBitmap(resId: Int): Bitmap? =
        AppCompatResources.getDrawable(this, resId)?.toBitmap(128, 128)

    /**
     * Puts a fetched picture onto a notification already in the tray.
     *
     * Does nothing if the notification is gone. Dismissed means dismissed -- re-posting an hour
     * later would resurrect something the user has already dealt with, and `setOnlyAlertOnce`
     * would not save them from seeing it reappear. A notice whose notification has gone still gets
     * its picture in the app, which is where they would go looking.
     */
    fun updatePicture(context: Context, notice: NoticeEntity) {
        val logId = notice.logId ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val id = logId.hashCode()
        if (manager.activeNotifications.none { it.id == id }) return

        val file = NoticeImageStore.cachedImage(context, logId)
            ?: NoticeImageStore.cachedPdfRender(context, logId)
            ?: return
        val bitmap = NoticeImageStore.decodeDownsampled(file) ?: return

        post(
            context = context,
            title = notice.title,
            body = notice.body,
            logId = logId,
            image = bitmap,
            channel = NoticeChannel.HIGH,
        )
    }
```

To let `updatePicture` pass a real bitmap, keep an internal overload: change the public `post` to delegate to a private `post(..., image: Bitmap?, ...)` holding the existing body. Imports needed: `android.app.NotificationManager`, `androidx.appcompat.content.res.AppCompatResources`, `androidx.core.graphics.drawable.toBitmap`, `org.paramanuseniorshealth.notices.data.NoticeEntity`.

- [ ] **Step 3: Build and run the full suite**

Run: `.\gradlew.bat :app:assembleDebug ; if ($?) { .\gradlew.bat :app:testDebugUnitTest }`
Expected: `BUILD SUCCESSFUL`, all unit tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/fcm/ app/src/main/res/drawable/
git commit -m "Post notices immediately with a type placeholder, fill the picture in later"
```

---

### Task 8: Retry triggers

**Files:**
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/ui/NoticeViewModel.kt` (the foreground entry point around `:275-290`)
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/MainActivity.kt`

**Interfaces:**
- Consumes: `AttachmentWorker.enqueueCatchUp`, `.enqueueWifiCatchUp` (Task 6).
- Produces: nothing new.

- [ ] **Step 1: Trigger both sweeps when the app comes forward**

In the ViewModel's existing foreground function (the one documented as "Called whenever the app comes to the foreground"), add at the top:

```kotlin
        // Two sweeps, doing the same work under different conditions. The first runs now, on
        // whatever connection there is, and picks up anything small that was missed. The second
        // stands waiting for wifi and costs nothing until it appears -- which is how a circular
        // deferred on mobile data eventually arrives without the user being told anything.
        AttachmentWorker.enqueueCatchUp(context)
        AttachmentWorker.enqueueWifiCatchUp(context)
```

The ViewModel has no `Context`; pass the application context in from `MainActivity` at the call site instead, matching how `onOpenPdf` already hands `context` down from the composable.

- [ ] **Step 2: Build and verify**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/ui/NoticeViewModel.kt app/src/main/java/org/paramanuseniorshealth/notices/MainActivity.kt
git commit -m "Sweep outstanding attachments on app open and when wifi appears"
```

---

### Task 9: Cut the UI off from the network

The bug this closes: Coil is handed remote URLs at compose time (`NoticeListScreen.kt:325-327`, `NoticeAttachments.kt:132,148`) and downloads them with no timeout, no size check and no metering or roaming check — so the card would download on roaming while the worker was busy refusing to. The fix is not to teach the UI the policy. It is to stop the UI having URLs at all.

**Files:**
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/ui/NoticeListScreen.kt:319-340`, `:351`
- Modify: `app/src/main/java/org/paramanuseniorshealth/notices/ui/NoticeAttachments.kt:125-175`, `:200-220`
- Create: `app/src/main/java/org/paramanuseniorshealth/notices/ui/AttachmentPlaceholder.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `R.drawable.ic_file_pdf`, `ic_file_image`, `ic_download` (Task 7).
- Produces: `@Composable AttachmentPlaceholder(isPdf: Boolean, onDownload: () -> Unit, modifier: Modifier)`.

- [ ] **Step 1: Add the content-description strings**

```xml
    <!-- Content descriptions only. Nothing here is drawn on screen; a failure the user can tap
         past does not need a sentence explaining it. -->
    <string name="attachment_download">Download</string>
    <string name="attachment_pdf">Circular</string>
    <string name="attachment_image">Picture</string>
```

- [ ] **Step 2: Write the placeholder**

```kotlin
package org.paramanuseniorshealth.notices.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.paramanuseniorshealth.notices.R

/**
 * What stands in for an attachment that is not on the phone.
 *
 * One appearance for four situations -- never fetched, deferred because the connection is metered
 * or roaming, failed, and pruned after delivery. They are the same thing from the reader's side:
 * the picture is not here, and tapping fetches it. Telling them apart in words would mean
 * explaining metered connections to people in their eighties, and would arrive at the helpdesk as
 * a phone call rather than at the user as understanding.
 *
 * The download glyph sits over the type glyph rather than beside it, so the tap target is the whole
 * tile and there is nothing small to miss.
 */
@Composable
fun AttachmentPlaceholder(
    isPdf: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onDownload),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(
                if (isPdf) R.drawable.ic_file_pdf else R.drawable.ic_file_image
            ),
            contentDescription = stringResource(
                if (isPdf) R.string.attachment_pdf else R.string.attachment_image
            ),
            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.45f),
            modifier = Modifier.size(40.dp),
        )
        Icon(
            painter = painterResource(R.drawable.ic_download),
            contentDescription = stringResource(R.string.attachment_download),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(24.dp),
        )
    }
}
```

- [ ] **Step 3: Remove every remote model from the list screen**

In `NoticeRow`, replace the `thumbModel` chain with local files only:

```kotlin
    // Local files only. Handing Coil a URL here would download it at compose time with no timeout,
    // no size check and no metering or roaming check -- the card fetching on roaming while the
    // worker refused to. The UI does not get to spend the user's data; only the worker and an
    // explicit tap do.
    val thumbFile: File? = photo ?: pdfRender ?: linkImage

    // An attachment the notice says it has, which is not on the phone. One glyph, four causes.
    val awaiting = thumbFile == null && (
        !notice.imageUrl.isNullOrBlank() ||
            !notice.pdfUrl.isNullOrBlank() ||
            !notice.linkImage.isNullOrBlank()
        )
```

Update `lean` so a row awaiting an attachment is not treated as having nothing to show:

```kotlin
    val lean = thumbFile == null && !awaiting &&
        notice.pdfUrl.isNullOrBlank() &&
        notice.linkUrl.isNullOrBlank()
```

Replace the thumbnail block in the title row, and note the dropped `!open`: an attachment that has not arrived must be visible *especially* when the card is open, which is the second bug — line 351 previously hid the only picture a failed notice had the moment you expanded it.

```kotlin
                if (thumbFile != null && !open) {
                    AsyncImage(
                        model = thumbFile,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                } else if (awaiting) {
                    AttachmentPlaceholder(
                        isPdf = !notice.pdfUrl.isNullOrBlank(),
                        onDownload = onDownloadAttachment,
                    )
                    Spacer(Modifier.width(12.dp))
                }
```

Add `onDownloadAttachment: () -> Unit` to `NoticeRow`'s parameters and thread it from `NoticeListScreen`'s `items(...)` block as `onDownloadAttachment = { onDownloadAttachment(notice) }`, alongside a matching `onDownloadAttachment: (NoticeEntity) -> Unit` parameter on `NoticeListScreen` itself. Wire it in `MainActivity` to the same `viewModel.openPdf`-style call that already fetches on demand — a tap is consent and bypasses the policy.

- [ ] **Step 4: Make the link card read a local file**

In `NoticeAttachments.LinkCard`, replace the remote `notice.linkImage` model:

```kotlin
    val image = NoticeImageStore.cachedLinkImage(context, notice.logId)
```

The existing `else` branch already draws `LinkPlaceholder` when there is no image, so a logo that has not been fetched degrades to the lettered square with no further change.

- [ ] **Step 5: Build and run everything**

Run: `.\gradlew.bat :app:assembleDebug ; if ($?) { .\gradlew.bat :app:testDebugUnitTest }`
Expected: `BUILD SUCCESSFUL`, all unit tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/ui/ app/src/main/res/values/strings.xml
git commit -m "Render attachments from local files only, with a tappable placeholder"
```

---

### Task 10: The publishing contract

Ships after the app release, and independently. The app already tolerates the absence of all three keys, so nothing here is urgent and nothing here can break an installed phone.

**Files:**
- Modify: `docs/apps-script/sender/Poller.gs:108-148` (`pollerParseFeed_`), `:279-300` (`pollerSendAlert_`), `:621-640` (`pollerDryRun`)
- Modify: `docs/apps-script/sender/Tests.gs`
- Modify: `docs/sender-contract.md`

**Interfaces:**
- Consumes: the payload keys read in Task 6 — `pdfThumbUrl`, `pdfPages`, `pdfBytes`.
- Produces: feed item fields `thumbUrl`, `pages`, `bytes`.

- [ ] **Step 1: Extend the fixtures in `Tests.gs` first**

Add a third item to the seed feed beside the existing JPEG and PDF ones, carrying all three new values, plus assertions that a PDF item with none of them still parses exactly as today:

```javascript
    '<item>' +
    '<title>Holiday list 2026</title>' +
    '<description>Dispensary closed on listed days.</description>' +
    '<guid>https://paramanuseniorshealth.org/alerts/holidays-2026</guid>' +
    '<enclosure url="https://paramanuseniorshealth.org/files/holidays-2026.pdf" ' +
    'length="5872345" type="application/pdf"/>' +
    '<media:thumbnail url="https://paramanuseniorshealth.org/files/holidays-2026-thumb.jpg" ' +
    'width="1200" height="1697"/>' +
    '<pn:pages>192</pn:pages>' +
    '</item>' +
```

The enclosing `<rss>` element in the fixture needs the two namespace declarations:

```javascript
    'xmlns:media="http://search.yahoo.com/mrss/" ' +
    'xmlns:pn="https://paramanuseniorshealth.org/rss" ' +
```

Assertions:

```javascript
  check('pdfBytes', items[2].bytes, 5872345);
  check('pdfPages', items[2].pages, 192);
  check('pdfThumbUrl', items[2].thumbUrl,
        'https://paramanuseniorshealth.org/files/holidays-2026-thumb.jpg');
  // The pre-pipeline shape must keep parsing unchanged: absent means absent, not zero.
  check('no thumb', items[1].thumbUrl, '');
  check('no pages', items[1].pages, null);
  check('no bytes', items[1].bytes, null);
```

- [ ] **Step 2: Run the tests and confirm they fail**

In the Apps Script editor, run the test entry point in `Tests.gs`.
Expected: FAIL — `items[2].bytes` is `undefined`.

- [ ] **Step 3: Parse the three values**

In `pollerParseFeed_`, beside the existing `contentNs` — built inside the function for the load-order reason given at `Poller.gs:109`:

```javascript
  var mediaNs = XmlService.getNamespace('media', 'http://search.yahoo.com/mrss/');
  var pnNs = XmlService.getNamespace('pn', 'https://paramanuseniorshealth.org/rss');
```

Inside the item mapper, after the existing enclosure block:

```javascript
    // RSS 2.0 gives the size for nothing: enclosure/@length is bytes, and is already required by
    // the spec. Only the page count needs a namespace of our own -- there is no standard for it.
    var lengthAttr = enclosure ? enclosure.getAttribute('length') : null;
    var bytes = lengthAttr ? parseInt(lengthAttr.getValue(), 10) : null;
    if (isNaN(bytes)) bytes = null;

    // A PDF cannot carry its own preview: RSS allows one enclosure per item and that is the
    // document. media:thumbnail is where the first-page image goes.
    var thumb = item.getChild('thumbnail', mediaNs);
    var thumbAttr = thumb ? thumb.getAttribute('url') : null;

    var pagesText = pollerText_(item, 'pages', pnNs);
    var pages = pagesText ? parseInt(pagesText, 10) : null;
    if (isNaN(pages)) pages = null;
```

Add to the returned object:

```javascript
      bytes: bytes,
      pages: pages,
      thumbUrl: (mime === 'application/pdf' && thumbAttr) ? thumbAttr.getValue() : '',
```

`pollerText_` takes only `(element, name)` today — give it an optional third `namespace` argument, defaulting to no namespace so every existing call is unaffected:

```javascript
function pollerText_(element, name, namespace) {
  var child = namespace ? element.getChild(name, namespace) : element.getChild(name);
  // ... existing body unchanged
}
```

- [ ] **Step 4: Put them in the payload**

In `pollerSendAlert_`, after the existing `extras.pdfUrl` line:

```javascript
  // Only alongside a PDF, and only when present. Every optional key is one more thing that can be
  // sent wrong, and the app treats all three as absent-by-default.
  if (item.pdfUrl) {
    if (item.thumbUrl) extras.pdfThumbUrl = item.thumbUrl;
    if (item.pages) extras.pdfPages = String(item.pages);
    if (item.bytes) extras.pdfBytes = String(item.bytes);
  }
```

Extend the `pollerDryRun` logging with `thumbUrl`, `pages` and `bytes`, matching its existing format.

- [ ] **Step 5: Run the tests and confirm they pass**

Run the `Tests.gs` entry point.
Expected: PASS, including the unchanged-behaviour assertions on `items[1]`.

- [ ] **Step 6: Document the contract**

In `docs/sender-contract.md`, under "The message", add the three keys to the table with their sources (`media:thumbnail/@url`, `pn:pages`, `enclosure/@length`), note that all three are optional and PDF-only, and record the CI step that produces them:

```
pdftoppm -jpeg -r 100 -f 1 -l 1 circular.pdf thumb
pdfinfo circular.pdf | grep Pages
stat -c %s circular.pdf
```

State that Hugo cannot rasterize a PDF — its image processing accepts only decodable image formats — so the thumbnail must be produced before Hugo runs and dropped into `assets/`.

- [ ] **Step 7: Commit**

```bash
git add docs/apps-script/sender/ docs/sender-contract.md
git commit -m "Publish PDF thumbnail, page count and size through the poller"
```

---

## Verification before calling this done

- [ ] `.\gradlew.bat :app:testDebugUnitTest` — all suites pass
- [ ] `.\gradlew.bat :app:connectedDebugAndroidTest --tests "*MigrationTest"` — passes on a device
- [ ] `.\gradlew.bat :app:assembleDebug` — `BUILD SUCCESSFUL`
- [ ] Install **over the previous build**, not a fresh install — the migration is the point, and a wipe would not exercise it. Confirm existing notices survive with their text intact.
- [ ] On mobile data: a PDF notice arrives as text with a PDF glyph, and no 5.6MB download starts. The card shows the placeholder; tapping it downloads and the picture appears.
- [ ] On wifi: the same notice fills in by itself within the hour, and the tray notification updates in place **without a second buzz**.
- [ ] Dismiss a notification before its attachment lands, then let the worker finish: the notification must not come back, and the picture must appear in the app.
