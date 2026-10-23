# Launch Analytics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Instrument the app with Firebase Analytics and Crashlytics, gated by a server-side kill switch, so the four launch questions in the spec can be answered from BigQuery rows.

**Architecture:** One `Analytics` class is the only thing that knows Firebase exists. It takes an `AnalyticsSink` interface, so the whole event taxonomy and the kill-switch logic are unit-tested on the JVM with a fake sink and no Android or Firebase runtime. `NoticesApplication` — already a hand-rolled service locator — constructs it once; every consumer already reaches that object, so no call site needs new dependency plumbing.

**Tech Stack:** Kotlin, Jetpack Compose, WorkManager, Room, Firebase (BoM 34.18.0) Analytics + Crashlytics, Firebase Realtime Database, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-15-analytics-design.md`

## Global Constraints

- **Firebase project stays on Spark.** No Blaze, no Cloud Storage, no Data Transfer Service.
- **`AD_ID` stays out of the merged manifest.** The `tools:node="remove"` entry stays, and the Gradle dependency must exclude `play-services-ads-identifier`. Play's advertising-ID answer remains "no".
- **`FetchPolicy.kt` and `NetworkStatus.kt` keep zero Android imports and zero analytics imports.** Their KDoc states this and `FetchPolicyTest` depends on it. Log at their callers.
- **Collection defaults to OFF.** Absent, unreadable or not-yet-read config means no collection, including GA4's own automatic events.
- **No notice title or body is ever sent.** Notices are identified by `noticeId` and `logId` only.
- **GA4 parameter names: max 40 characters, max 25 parameters per event.** Event names: max 40 characters.
- **Minimum SDK is 26.** No API above 26 without a guarded fallback.
- **`notice.receivedAt` is the sender's send instant, identical on every phone.** Never label it arrival time.
- **Firebase BoM governs versions.** Do not pin `firebase-analytics` or `firebase-crashlytics` versions directly.

## Review Focus

1. **Config read fails or `/config/analytics` is missing** — collection must stay off and nothing may be logged, rather than defaulting to on or crashing at startup. Pinned in Task 3.
2. **`fetch_end` arriving after its caller already logged a failure** — `NoticeImageStore.download` has no suspension points and cannot be interrupted by `withTimeoutOrNull`, so duplicate and out-of-order events are normal and must not corrupt state or throw. Pinned in Task 6.
3. **A sweep abandoned mid-way by `isStopped`** — buffered events must flush, or a truncated sweep is indistinguishable from a clean one. Pinned in Task 5.
4. **`size_bytes` absent because the server sent no `Content-Length`** — null must travel as a missing parameter, never as `0` or `-1`, which would read as a real measurement. Pinned in Task 2.
5. **An event or parameter name over GA4's 40-character limit, or colliding with a GA4 reserved name** — these fail silently at runtime and produce a column that is always null. Pinned in Task 2.

---

## File Structure

**Create:**
- `app/src/main/java/.../analytics/AnalyticsEvent.kt` — sealed interface, one variant per event, each producing its own name and parameter map. No Android imports.
- `app/src/main/java/.../analytics/AnalyticsSink.kt` — the two-method interface `Analytics` writes through.
- `app/src/main/java/.../analytics/Analytics.kt` — the gate: holds enabled state, drops events when off.
- `app/src/main/java/.../analytics/FirebaseAnalyticsSink.kt` — the only file importing Firebase Analytics.
- `app/src/main/java/.../analytics/AnalyticsConfig.kt` — reads and caches the kill switch.
- `app/src/test/java/.../AnalyticsEventTest.kt`, `AnalyticsTest.kt`, `AnalyticsConfigTest.kt`

**Modify:** `gradle/libs.versions.toml`, `app/build.gradle`, `AndroidManifest.xml`, `database.rules.json`, `NoticesApplication.kt`, `AttachmentWorker.kt`, `NoticeImageStore.kt`, `NoticeMessagingService.kt`, `NoticeNotifications.kt`, `MainActivity.kt`, `NoticeViewerScreen.kt`, `NoticeViewModel.kt`, `docs/SYSTEM.md`, `docs/privacy-policy.md`

---

### Task 1: Dependencies, with AD_ID kept out

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: nothing
- Produces: `com.google.firebase.analytics.FirebaseAnalytics` and `com.google.firebase.crashlytics.FirebaseCrashlytics` available on the compile classpath

- [ ] **Step 1: Add the Crashlytics plugin and library aliases**

In `gradle/libs.versions.toml`, under `[versions]` add:

```toml
crashlyticsPlugin = "3.0.2"
```

`firebase-analytics` already exists under `[libraries]`. Add beside it:

```toml
firebase-crashlytics = { module = "com.google.firebase:firebase-crashlytics" }
```

Under `[plugins]` add:

```toml
firebase-crashlytics = { id = "com.google.firebase.crashlytics", version.ref = "crashlyticsPlugin" }
```

- [ ] **Step 2: Wire them into the app module**

In `app/build.gradle`, add to the `plugins` block:

```groovy
    alias(libs.plugins.firebase.crashlytics)
```

In `dependencies`, after `implementation libs.firebase.database`, add:

```groovy
    // Analytics is deliberately excluded from pulling the advertising-ID artifact: the Data
    // Safety advertising-ID answer stays "no", and the manifest's tools:node="remove" is the
    // second lock on the same guarantee. See docs/SYSTEM.md.
    implementation(libs.firebase.analytics) {
        exclude group: 'com.google.android.gms', module: 'play-services-ads-identifier'
    }
    implementation libs.firebase.crashlytics
```

- [ ] **Step 3: Turn GA4's own ad-id collection off in the manifest**

In `AndroidManifest.xml`, inside `<application>`, next to the existing FCM meta-data:

```xml
        <!--
          Belt and braces with the AD_ID permission removal above: this tells the Analytics SDK
          itself never to collect the advertising identifier, so the guarantee does not rest on
          dependency resolution alone.
        -->
        <meta-data
            android:name="google_analytics_adid_collection_enabled"
            android:value="false" />
```

Leave the existing `AD_ID` `tools:node="remove"` block exactly as it is, and leave its comment intact.

- [ ] **Step 4: Verify AD_ID is absent from the merged manifest**

Run:

```bash
./gradlew.bat processDebugMainManifest --console=plain
grep -c "AD_ID" app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
```

Expected: `0`. If it prints anything else, the exclude in Step 2 did not take — run `./gradlew.bat :app:dependencies --configuration debugRuntimeClasspath | grep ads-identifier` to find which dependency reintroduced it, and add the same `exclude` there. Do not proceed until this prints `0`.

- [ ] **Step 5: Verify the build still works**

Run: `./gradlew.bat assembleDebug testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`, 159 tests still passing.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle app/src/main/AndroidManifest.xml
git commit -m "Analytics and Crashlytics added, with the advertising ID still kept out."
```

---

### Task 2: The event taxonomy, unit-tested on the JVM

**Files:**
- Create: `app/src/main/java/org/paramanuseniorshealth/notices/analytics/AnalyticsEvent.kt`
- Create: `app/src/test/java/org/paramanuseniorshealth/notices/AnalyticsEventTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `sealed interface AnalyticsEvent { val name: String; val params: Map<String, Any> }` and its thirteen variants, named exactly as below. Later tasks construct these by name.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/paramanuseniorshealth/notices/AnalyticsEventTest.kt`:

```kotlin
package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.analytics.AnalyticsEvent

class AnalyticsEventTest {

    @Test
    fun `fetch policy decision carries the network facts`() {
        val event = AnalyticsEvent.FetchPolicyDecision(
            kind = "pdf", metered = true, roaming = false, sizeBytes = 5_600_000, fetched = false,
        )
        assertEquals("fetch_policy_decision", event.name)
        assertEquals("pdf", event.params["kind"])
        assertEquals(1L, event.params["metered"])
        assertEquals(0L, event.params["roaming"])
        assertEquals(5_600_000L, event.params["size_bytes"])
        assertEquals("defer", event.params["decision"])
        assertEquals(1L, event.params["size_known"])
    }

    @Test
    fun `an unknown size is absent, never zero`() {
        // A server that sends no Content-Length must not be recorded as a zero-byte file: zero is
        // a measurement, and this is the absence of one.
        val event = AnalyticsEvent.FetchPolicyDecision(
            kind = "img", metered = true, roaming = false, sizeBytes = null, fetched = false,
        )
        assertFalse(event.params.containsKey("size_bytes"))
        assertEquals(0L, event.params["size_known"])
    }

    @Test
    fun `every event and parameter name is within GA4 limits`() {
        // Over-long names are dropped silently at runtime and surface as a column that is always
        // null -- a failure that only shows up days later in BigQuery.
        val all = listOf(
            AnalyticsEvent.FetchPolicyDecision("pdf", true, false, 1L, true),
            AnalyticsEvent.NetworkStateOnOpen(metered = true, roaming = false),
            AnalyticsEvent.AttachmentFetchStart("pdf", 1L, 30L, 1L, 2L, 0),
            AnalyticsEvent.AttachmentFetchEnd("pdf", 1L, 250L, "fetched", 200),
            AnalyticsEvent.NoticeReceived(hasImage = true, hasPdf = false, isNew = true, deliveryLagMs = 10L, msSinceProcessStart = 20L),
            AnalyticsEvent.NotificationTapped(msSinceShown = 5L, coldStart = true),
            AnalyticsEvent.AppOpen(source = "launcher"),
            AnalyticsEvent.NoticeCardVisible(noticeId = 1L, visibleMs = 900L, hasImage = true, hasPdf = false, position = 3),
            AnalyticsEvent.ImageViewerClosed(noticeId = 1L, durationMs = 400L, maxScale = 2.5f),
            AnalyticsEvent.PdfOpened(noticeId = 1L, sizeBytes = 1L, wasCached = true),
            AnalyticsEvent.ActivationShown(hasCode = false),
            AnalyticsEvent.ActivationSubmitted,
            AnalyticsEvent.ActivationResult(result = "offline", offlineCause = "timeout"),
        )
        for (event in all) {
            assertTrue("event name too long: ${event.name}", event.name.length <= 40)
            assertTrue("event name not snake_case: ${event.name}", event.name.matches(Regex("[a-z][a-z0-9_]*")))
            assertTrue("too many params on ${event.name}", event.params.size <= 25)
            for (key in event.params.keys) {
                assertTrue("param too long on ${event.name}: $key", key.length <= 40)
                assertTrue("param not snake_case on ${event.name}: $key", key.matches(Regex("[a-z][a-z0-9_]*")))
            }
        }
    }

    @Test
    fun `no event carries notice text`() {
        val event = AnalyticsEvent.NoticeCardVisible(
            noticeId = 7L, visibleMs = 100L, hasImage = false, hasPdf = false, position = 0,
        )
        assertEquals(7L, event.params["notice_id"])
        assertFalse(event.params.containsKey("title"))
        assertFalse(event.params.containsKey("body"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat testDebugUnitTest --tests "*AnalyticsEventTest*" --console=plain`
Expected: FAIL — `Unresolved reference 'AnalyticsEvent'`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/org/paramanuseniorshealth/notices/analytics/AnalyticsEvent.kt`:

```kotlin
package org.paramanuseniorshealth.notices.analytics

/**
 * Every measurement the app can report, as a closed set.
 *
 * Deliberately free of Android and Firebase imports, like FetchPolicy: the whole of the event
 * taxonomy is testable on the JVM, and a parameter-name typo is a compile error here rather than
 * a BigQuery column that is silently always null.
 *
 * Booleans travel as 1/0 longs because GA4 has no boolean parameter type, and a null is recorded
 * by *omitting* the key -- an unknown size is not a zero-byte file.
 */
sealed interface AnalyticsEvent {

    val name: String
    val params: Map<String, Any>

    data class FetchPolicyDecision(
        val kind: String,
        val metered: Boolean,
        val roaming: Boolean,
        val sizeBytes: Long?,
        val fetched: Boolean,
    ) : AnalyticsEvent {
        override val name = "fetch_policy_decision"
        override val params = buildMap {
            put("kind", kind)
            put("metered", metered.asParam())
            put("roaming", roaming.asParam())
            put("decision", if (fetched) "fetch" else "defer")
            // size_known discriminates a real HEAD result from a sender-supplied pdfBytes.
            put("size_known", (sizeBytes != null).asParam())
            sizeBytes?.let { put("size_bytes", it) }
        }
    }

    data class NetworkStateOnOpen(val metered: Boolean, val roaming: Boolean) : AnalyticsEvent {
        override val name = "network_state_on_open"
        override val params = mapOf<String, Any>(
            "metered" to metered.asParam(),
            "roaming" to roaming.asParam(),
        )
    }

    data class AttachmentFetchStart(
        val kind: String,
        val sizeBytes: Long?,
        val jitterSeconds: Long,
        val enqueuedAtMs: Long,
        val startedAtMs: Long,
        val attempt: Int,
    ) : AnalyticsEvent {
        override val name = "attachment_fetch_start"
        override val params = buildMap {
            put("kind", kind)
            put("jitter_seconds", jitterSeconds)
            put("enqueued_at_ms", enqueuedAtMs)
            put("started_at_ms", startedAtMs)
            put("attempt", attempt.toLong())
            sizeBytes?.let { put("size_bytes", it) }
        }
    }

    data class AttachmentFetchEnd(
        val kind: String,
        val sizeBytes: Long?,
        val durationMs: Long,
        val outcome: String,
        val httpStatus: Int?,
    ) : AnalyticsEvent {
        override val name = "attachment_fetch_end"
        override val params = buildMap {
            put("kind", kind)
            put("duration_ms", durationMs)
            put("outcome", outcome)
            sizeBytes?.let { put("size_bytes", it) }
            httpStatus?.let { put("http_status", it.toLong()) }
        }
    }

    data class NoticeReceived(
        val hasImage: Boolean,
        val hasPdf: Boolean,
        val isNew: Boolean,
        val deliveryLagMs: Long?,
        val msSinceProcessStart: Long,
    ) : AnalyticsEvent {
        override val name = "notice_received"
        override val params = buildMap {
            put("has_image", hasImage.asParam())
            put("has_pdf", hasPdf.asParam())
            put("is_new", isNew.asParam())
            put("ms_since_process_start", msSinceProcessStart)
            deliveryLagMs?.let { put("delivery_lag_ms", it) }
        }
    }

    data class NotificationTapped(val msSinceShown: Long?, val coldStart: Boolean) : AnalyticsEvent {
        override val name = "notification_tapped"
        override val params = buildMap {
            put("cold_start", coldStart.asParam())
            msSinceShown?.let { put("ms_since_shown", it) }
        }
    }

    data class AppOpen(val source: String) : AnalyticsEvent {
        override val name = "app_open_source"
        override val params = mapOf<String, Any>("source" to source)
    }

    data class NoticeCardVisible(
        val noticeId: Long,
        val visibleMs: Long,
        val hasImage: Boolean,
        val hasPdf: Boolean,
        val position: Int,
    ) : AnalyticsEvent {
        override val name = "notice_card_visible"
        override val params = mapOf<String, Any>(
            "notice_id" to noticeId,
            "visible_ms" to visibleMs,
            "has_image" to hasImage.asParam(),
            "has_pdf" to hasPdf.asParam(),
            "position" to position.toLong(),
        )
    }

    data class ImageViewerClosed(
        val noticeId: Long,
        val durationMs: Long,
        val maxScale: Float,
    ) : AnalyticsEvent {
        override val name = "image_viewer_closed"
        override val params = mapOf<String, Any>(
            "notice_id" to noticeId,
            "duration_ms" to durationMs,
            "max_scale" to maxScale.toDouble(),
        )
    }

    data class PdfOpened(val noticeId: Long, val sizeBytes: Long?, val wasCached: Boolean) : AnalyticsEvent {
        override val name = "pdf_opened"
        override val params = buildMap {
            put("notice_id", noticeId)
            put("was_cached", wasCached.asParam())
            sizeBytes?.let { put("size_bytes", it) }
        }
    }

    data class ActivationShown(val hasCode: Boolean) : AnalyticsEvent {
        override val name = "activation_shown"
        override val params = mapOf<String, Any>("has_code" to hasCode.asParam())
    }

    data object ActivationSubmitted : AnalyticsEvent {
        override val name = "activation_submitted"
        override val params = emptyMap<String, Any>()
    }

    /**
     * [offlineCause] exists because three unrelated failures all return RedeemResult.Offline --
     * no uid from anonymous sign-in, a transport exception, and the 20-second timeout elapsing --
     * and telling them apart is most of the value of the funnel.
     */
    data class ActivationResult(val result: String, val offlineCause: String?) : AnalyticsEvent {
        override val name = "activation_result"
        override val params = buildMap {
            put("result", result)
            offlineCause?.let { put("offline_cause", it) }
        }
    }
}

/** GA4 has no boolean parameter type, and a string "true" cannot be aggregated arithmetically. */
private fun Boolean.asParam(): Long = if (this) 1L else 0L
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew.bat testDebugUnitTest --tests "*AnalyticsEventTest*" --console=plain`
Expected: PASS, 4 tests.

Note `AppOpen.name` is `app_open_source`, not `app_open`: `app_open` is a GA4 reserved automatic event name and logging it manually is rejected. The snake_case assertion will not catch that, so it is called out here.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/analytics/AnalyticsEvent.kt app/src/test/java/org/paramanuseniorshealth/notices/AnalyticsEventTest.kt
git commit -m "The analytics event taxonomy, as a closed set with no Android imports."
```

---

### Task 3: The gate and the kill switch

**Files:**
- Create: `app/src/main/java/.../analytics/AnalyticsSink.kt`
- Create: `app/src/main/java/.../analytics/Analytics.kt`
- Create: `app/src/test/java/.../AnalyticsTest.kt`

**Interfaces:**
- Consumes: `AnalyticsEvent` from Task 2
- Produces: `interface AnalyticsSink { fun log(name: String, params: Map<String, Any>); fun setCollectionEnabled(enabled: Boolean) }` and `class Analytics(sink: AnalyticsSink)` with `fun log(event: AnalyticsEvent)` and `fun setEnabled(enabled: Boolean)`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/paramanuseniorshealth/notices/AnalyticsTest.kt`:

```kotlin
package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.analytics.Analytics
import org.paramanuseniorshealth.notices.analytics.AnalyticsEvent
import org.paramanuseniorshealth.notices.analytics.AnalyticsSink

private class FakeSink : AnalyticsSink {
    val logged = mutableListOf<Pair<String, Map<String, Any>>>()
    val collectionCalls = mutableListOf<Boolean>()
    override fun log(name: String, params: Map<String, Any>) { logged += name to params }
    override fun setCollectionEnabled(enabled: Boolean) { collectionCalls += enabled }
}

class AnalyticsTest {

    @Test
    fun `collects nothing until it has been switched on`() {
        // The default must be off: a failed or not-yet-finished config read must not collect.
        val sink = FakeSink()
        Analytics(sink).log(AnalyticsEvent.ActivationSubmitted)
        assertTrue(sink.logged.isEmpty())
    }

    @Test
    fun `logs once switched on`() {
        val sink = FakeSink()
        val analytics = Analytics(sink)
        analytics.setEnabled(true)
        analytics.log(AnalyticsEvent.ActivationShown(hasCode = false))
        assertEquals(1, sink.logged.size)
        assertEquals("activation_shown", sink.logged.single().first)
        assertEquals(0L, sink.logged.single().second["has_code"])
    }

    @Test
    fun `switching off stops the SDK too, not only our own calls`() {
        // Guarding only our wrapper would leave GA4's automatic events flowing, which would make
        // "off" a lie.
        val sink = FakeSink()
        val analytics = Analytics(sink)
        analytics.setEnabled(true)
        analytics.setEnabled(false)
        analytics.log(AnalyticsEvent.ActivationSubmitted)
        assertTrue(sink.logged.isEmpty())
        assertEquals(listOf(true, false), sink.collectionCalls)
    }

    @Test
    fun `a sink that throws never reaches the caller`() {
        // Analytics must not be able to break the thing it is measuring.
        val throwing = object : AnalyticsSink {
            override fun log(name: String, params: Map<String, Any>) = error("sink is broken")
            override fun setCollectionEnabled(enabled: Boolean) = Unit
        }
        val analytics = Analytics(throwing)
        analytics.setEnabled(true)
        analytics.log(AnalyticsEvent.ActivationSubmitted)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat testDebugUnitTest --tests "*AnalyticsTest*" --console=plain`
Expected: FAIL — `Unresolved reference 'Analytics'`.

- [ ] **Step 3: Write the implementation**

Create `AnalyticsSink.kt`:

```kotlin
package org.paramanuseniorshealth.notices.analytics

/**
 * Where events go. One implementation ships; the tests supply their own.
 *
 * This interface is the seam that keeps [Analytics] and the whole event taxonomy on the JVM: the
 * Firebase SDK appears in exactly one file behind it.
 */
interface AnalyticsSink {
    fun log(name: String, params: Map<String, Any>)
    fun setCollectionEnabled(enabled: Boolean)
}
```

Create `Analytics.kt`:

```kotlin
package org.paramanuseniorshealth.notices.analytics

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The gate every measurement passes through.
 *
 * **Off is the default and the safe failure.** A missing config node, an unreadable one, or simply
 * the moment before the first read completes all mean no collection -- the opposite default would
 * make a network failure into a privacy failure.
 *
 * [setEnabled] drives the Firebase SDK's own master switch as well as this gate. Guarding only our
 * own calls would leave GA4's automatic events (sessions, screen views) flowing while the app
 * reported itself as not collecting.
 */
class Analytics(private val sink: AnalyticsSink) {

    private val enabled = AtomicBoolean(false)

    fun setEnabled(value: Boolean) {
        enabled.set(value)
        runCatching { sink.setCollectionEnabled(value) }
            .onFailure { Log.w(TAG, "Could not set collection state", it) }
    }

    /**
     * Never throws. Analytics must not be able to break the thing it is measuring, and every call
     * site here sits on a path -- message receipt, a fetch, a tap -- where an exception would be a
     * real failure for the user.
     */
    fun log(event: AnalyticsEvent) {
        if (!enabled.get()) return
        runCatching { sink.log(event.name, event.params) }
            .onFailure { Log.w(TAG, "Could not log ${event.name}", it) }
    }

    private companion object {
        const val TAG = "Analytics"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew.bat testDebugUnitTest --tests "*AnalyticsTest*" --console=plain`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/analytics/ app/src/test/java/org/paramanuseniorshealth/notices/AnalyticsTest.kt
git commit -m "Analytics gate: off by default, and a sink that cannot break the caller."
```

---

### Task 4: The Firebase sink, the config read, and the RTDB rule

**Files:**
- Create: `app/src/main/java/.../analytics/FirebaseAnalyticsSink.kt`
- Create: `app/src/main/java/.../analytics/AnalyticsConfig.kt`
- Create: `app/src/test/java/.../AnalyticsConfigTest.kt`
- Modify: `database.rules.json`

**Interfaces:**
- Consumes: `AnalyticsSink` and `Analytics` from Task 3
- Produces: `FirebaseAnalyticsSink(context: Context) : AnalyticsSink`; `AnalyticsConfig(prefs: SharedPreferences)` with `fun cached(): Boolean`, `fun store(enabled: Boolean)`, and `suspend fun refresh(database: FirebaseDatabase): Boolean`; and the constant `AnalyticsConfig.PATH = "config/analytics/enabled"`

- [ ] **Step 1: Write the failing test for the cache**

Create `app/src/test/java/org/paramanuseniorshealth/notices/AnalyticsConfigTest.kt`. The RTDB read itself needs a device, so what is tested here is the part that decides — the cache and its default:

```kotlin
package org.paramanuseniorshealth.notices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.paramanuseniorshealth.notices.analytics.AnalyticsConfig

class AnalyticsConfigTest {

    @Test
    fun `the path is the agreed one`() {
        // Must match the rule added to database.rules.json in this task.
        assertEquals("config/analytics/enabled", AnalyticsConfig.PATH)
    }

    @Test
    fun `a value that is not a boolean reads as off`() {
        // The node is hand-edited. A typo, a string, or a deleted node must all mean "do not
        // collect" -- never "collect".
        assertFalse(AnalyticsConfig.parse(null))
        assertFalse(AnalyticsConfig.parse("true"))
        assertFalse(AnalyticsConfig.parse(1L))
        assertFalse(AnalyticsConfig.parse(false))
        assertTrue(AnalyticsConfig.parse(true))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat testDebugUnitTest --tests "*AnalyticsConfigTest*" --console=plain`
Expected: FAIL — `Unresolved reference 'AnalyticsConfig'`.

- [ ] **Step 3: Write `AnalyticsConfig`**

```kotlin
package org.paramanuseniorshealth.notices.analytics

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

/**
 * The kill switch, read from RTDB and cached so it survives being offline.
 *
 * Its own node rather than a field on the dispensary record: it is not dispensary-scoped, and
 * putting it there would force the future multi-dispensary seam to carry it.
 *
 * Only a literal `true` enables collection. The node is hand-edited in the RTDB console, where a
 * string "true", a 1, or a deleted node are all realistic, and every one of them must mean off.
 */
class AnalyticsConfig(private val prefs: SharedPreferences) {

    fun cached(): Boolean = prefs.getBoolean(KEY, false)

    fun store(enabled: Boolean) = prefs.edit { putBoolean(KEY, enabled) }

    /**
     * Reads the live value, stores it, and returns it. On any failure the cached value stands --
     * which on a first launch is false, so a phone that has never reached the node collects
     * nothing.
     */
    suspend fun refresh(database: FirebaseDatabase): Boolean = runCatching {
        val snapshot = database.getReference(PATH).get().await()
        parse(snapshot.value).also { store(it) }
    }.onFailure { Log.i(TAG, "Could not read the analytics switch: ${it.message}") }
        .getOrDefault(cached())

    companion object {
        const val PATH = "config/analytics/enabled"
        private const val KEY = "analytics_enabled"
        private const val TAG = "AnalyticsConfig"

        /** Anything that is not a literal boolean `true` is off. */
        fun parse(raw: Any?): Boolean = raw as? Boolean == true
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew.bat testDebugUnitTest --tests "*AnalyticsConfigTest*" --console=plain`
Expected: PASS, 2 tests.

- [ ] **Step 5: Write the Firebase sink**

```kotlin
package org.paramanuseniorshealth.notices.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * The only file in the app that imports Firebase Analytics.
 *
 * Everything that decides *what* to measure lives behind [AnalyticsSink] on the JVM side; this
 * does nothing but translate a map into a Bundle.
 */
class FirebaseAnalyticsSink(context: Context) : AnalyticsSink {

    private val firebase = FirebaseAnalytics.getInstance(context.applicationContext)

    override fun log(name: String, params: Map<String, Any>) {
        val bundle = Bundle(params.size)
        for ((key, value) in params) {
            when (value) {
                is Long -> bundle.putLong(key, value)
                is Double -> bundle.putDouble(key, value)
                is String -> bundle.putString(key, value)
                // The taxonomy only produces these three. Anything else is a programming error,
                // and a string is the reading that loses least.
                else -> bundle.putString(key, value.toString())
            }
        }
        firebase.logEvent(name, bundle)
    }

    override fun setCollectionEnabled(enabled: Boolean) {
        firebase.setAnalyticsCollectionEnabled(enabled)
    }
}
```

- [ ] **Step 6: Add the RTDB rule**

In `database.rules.json`, add inside the top-level `rules` object, alongside the existing entries:

```json
    "config": {
      ".read": true,
      ".write": false
    },
```

World-readable and never client-writable: it holds one boolean, and every phone must be able to read it before deciding to collect anything.

- [ ] **Step 7: Verify the build**

Run: `./gradlew.bat assembleDebug testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests passing.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/analytics/ app/src/test/java/org/paramanuseniorshealth/notices/AnalyticsConfigTest.kt database.rules.json
git commit -m "The kill switch: one boolean in RTDB, cached, and off unless it says true."
```

**After this task, before any later one ships:** create `/config/analytics/enabled` in the RTDB console and set it to boolean `false`, and deploy the rules with `firebase deploy --only database`. A missing node is safe but means the switch cannot be turned on later without a console visit.

---

### Task 5: Wire it into the application, and record process start

**Files:**
- Modify: `app/src/main/java/.../NoticesApplication.kt`

**Interfaces:**
- Consumes: `Analytics`, `AnalyticsConfig`, `FirebaseAnalyticsSink` from Tasks 3–4
- Produces: `NoticesApplication.analytics: Analytics`, `NoticesApplication.processStartedAt: Long`, and `NoticesApplication.refreshAnalyticsSwitch()`

- [ ] **Step 1: Add the fields**

In `NoticesApplication.kt`, beside the existing `by lazy` repositories and the `appScope`:

```kotlin
    /**
     * When this process started.
     *
     * Nothing else in the app tracks it, and it is what makes "was the app already running when
     * the notice landed" answerable without adding lifecycle-process as a dependency.
     */
    val processStartedAt: Long = System.currentTimeMillis()

    private val analyticsPrefs by lazy {
        getSharedPreferences("analytics", MODE_PRIVATE)
    }

    val analyticsConfig by lazy { AnalyticsConfig(analyticsPrefs) }

    val analytics by lazy { Analytics(FirebaseAnalyticsSink(this)) }
```

- [ ] **Step 2: Apply the cached value at startup and refresh it**

In `onCreate`, after the existing setup calls:

```kotlin
        // The cached value applies immediately so a launch while offline behaves as the last known
        // state -- which on a first install is off. The live read then corrects it.
        analytics.setEnabled(analyticsConfig.cached())
        refreshAnalyticsSwitch()
```

And add the method:

```kotlin
    /**
     * Brings the switch into line with the database. Safe to call on every foreground.
     *
     * Uses the existing appScope rather than a new one: it is already IO-dispatched and
     * supervisor-scoped, which is exactly what a best-effort read wants.
     */
    fun refreshAnalyticsSwitch() {
        appScope.launch {
            analytics.setEnabled(analyticsConfig.refresh(FirebaseDatabase.getInstance()))
        }
    }
```

Add the imports: `com.google.firebase.database.FirebaseDatabase`, `kotlinx.coroutines.launch`, and the three analytics classes.

- [ ] **Step 3: Verify the build**

Run: `./gradlew.bat assembleDebug testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests passing.

- [ ] **Step 4: Verify on a device that nothing is collected while the switch is off**

Install the debug build and enable verbose analytics logging:

```bash
adb shell setprop log.tag.FA VERBOSE
adb shell setprop log.tag.FA-SVC VERBOSE
adb logcat -v time -s FA FA-SVC
```

Expected: with `/config/analytics/enabled` absent or `false`, the log shows collection disabled and no event uploads. This is the Review Focus item 1 check, and it is done here rather than in a unit test because the SDK's own behaviour is the thing being verified.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/NoticesApplication.kt
git commit -m "Analytics reaches the app through the service locator that was already there."
```

---

### Task 6: Fetch policy and network events

**Files:**
- Modify: `app/src/main/java/.../fcm/AttachmentWorker.kt`
- Modify: `app/src/main/java/.../ui/NoticeViewModel.kt`

**Interfaces:**
- Consumes: `AnalyticsEvent.FetchPolicyDecision`, `AnalyticsEvent.NetworkStateOnOpen`, `NoticesApplication.analytics`
- Produces: nothing later tasks depend on

- [ ] **Step 1: Log the decision at each verdict arm**

In `fetchAttachments`, at each `when (verdict(...))` arm where a fetch is either made or declined, log:

```kotlin
analytics.log(
    AnalyticsEvent.FetchPolicyDecision(
        kind = kind,
        metered = metered,
        roaming = roaming,
        sizeBytes = probeBytes,
        fetched = verdict == Verdict.FETCH,
    )
)
```

`kind` is one of `"img"`, `"link"`, `"pdf_thumb"`, `"pdf"` — the same vocabulary `NoticeImageStore` already uses.

**Do not** put this inside `decide`. That lambda is shared with `fetchNow`, which always returns FETCH and has no network facts in scope, so logging there would record tap-initiated fetches as policy decisions.

- [ ] **Step 2: Log the roaming short-circuit separately**

The roaming branch returns before `FetchPolicy.shouldFetch` is ever called, so it needs its own call with `fetched = false` and `roaming = true`. Without this, roaming deferrals are invisible — and whether to keep the roaming rule is the whole of question 1.

- [ ] **Step 3: Flush on `isStopped`**

At the `isStopped` early return inside the sweep loop, log nothing new but ensure no buffered state is lost. Since `Analytics.log` writes straight through to the SDK, there is no buffer of our own to flush — add a comment recording that, so a later change that introduces buffering does not silently lose a truncated sweep:

```kotlin
            if (isStopped) {
                // Nothing of ours is buffered: Analytics.log writes straight through. If that ever
                // changes, this is where a truncated sweep would silently lose its events.
                return Result.success()
            }
```

- [ ] **Step 4: Log network state on foreground**

In `NoticeViewModel.onResumed`, before `enqueueCatchUp`:

```kotlin
        analytics.log(
            AnalyticsEvent.NetworkStateOnOpen(
                metered = NetworkStatus.isMetered(context),
                roaming = NetworkStatus.isRoaming(context),
            )
        )
```

Once per foreground, not once per notice: `AttachmentWorker` re-reads these inside `notices.forEach`, and logging there would produce one event per notice in a sweep.

- [ ] **Step 5: Verify `FetchPolicy` and `NetworkStatus` are untouched**

Run:

```bash
git diff --name-only HEAD | grep -E "FetchPolicy.kt|NetworkStatus.kt"
```

Expected: no output. Both files must keep zero analytics imports; their KDoc promises it and `FetchPolicyTest` depends on it.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew.bat testDebugUnitTest --rerun-tasks --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests passing, `FetchPolicyTest` included and unmodified.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/fcm/AttachmentWorker.kt app/src/main/java/org/paramanuseniorshealth/notices/ui/NoticeViewModel.kt
git commit -m "Fetch policy decisions and network state, logged at the callers."
```

---

### Task 7: Fetch timing, and the jitter that was never recorded

**Files:**
- Modify: `app/src/main/java/.../fcm/AttachmentWorker.kt`
- Modify: `app/src/main/java/.../fcm/NoticeImageStore.kt`

**Interfaces:**
- Consumes: `AnalyticsEvent.AttachmentFetchStart`, `AnalyticsEvent.AttachmentFetchEnd`
- Produces: a second work-input key `KEY_ENQUEUED_AT = "enqueuedAt"`

- [ ] **Step 1: Capture the jitter and the enqueue time**

In `enqueueFor`, hoist the random value out of the builder chain — it is currently inline and therefore unrecorded:

```kotlin
            val jitterSeconds = Random.nextLong(0, MAX_JITTER_SECONDS)
            val request = OneTimeWorkRequestBuilder<AttachmentWorker>()
                .setConstraints(connected())
                .setInitialDelay(jitterSeconds, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_LOG_ID to logId,
                        KEY_JITTER to jitterSeconds,
                        // WorkManager does not expose enqueue time to the worker, and WorkInfo
                        // does not carry it either -- so it travels as input data or not at all.
                        KEY_ENQUEUED_AT to System.currentTimeMillis(),
                    )
                )
                .build()
```

Add `private const val KEY_JITTER = "jitter"` and `private const val KEY_ENQUEUED_AT = "enqueuedAt"` beside `KEY_LOG_ID`.

**Do not add input data to `enqueueCatchUp` or `enqueueWifiCatchUp`.** Their KDoc argues `ExistingWorkPolicy.KEEP` is correct precisely because those requests carry no input data; adding a timestamp would make every enqueue a distinct request and undermine that reasoning. Sweeps therefore report a start time but no jitter and no enqueue time, which is correct — they have neither.

- [ ] **Step 2: Read them in `doWork`**

```kotlin
        val startedAt = System.currentTimeMillis()
        val jitterSeconds = inputData.getLong(KEY_JITTER, 0L)
        val enqueuedAt = inputData.getLong(KEY_ENQUEUED_AT, 0L)
```

- [ ] **Step 3: Time the download itself**

In `NoticeImageStore.download`, wrap the body:

```kotlin
    private fun download(url: String, target: File, kind: String, analytics: Analytics): String? {
        val startedAt = System.currentTimeMillis()
        var status: Int? = null
        return try {
            // ... existing body, assigning status from connection.responseCode ...
        } finally {
            analytics.log(
                AnalyticsEvent.AttachmentFetchEnd(
                    kind = kind,
                    sizeBytes = target.length().takeIf { it > 0 },
                    durationMs = System.currentTimeMillis() - startedAt,
                    outcome = if (target.length() > 0) "fetched" else "failed",
                    httpStatus = status,
                )
            )
        }
    }
```

- [ ] **Step 4: Write the test for the late-completion case**

This is Review Focus item 2. `download` cannot be interrupted by `withTimeoutOrNull` — it has no suspension points — so a caller can log a failure and the download can then complete and log success. The contract is that this is tolerated, not prevented. Add to `AnalyticsTest.kt`:

```kotlin
    @Test
    fun `a late duplicate event is logged, not suppressed`() {
        // NoticeImageStore.download cannot be interrupted by withTimeoutOrNull, so a fetch that
        // the caller already gave up on can still complete and report. Both events are real and
        // both are kept; de-duplication belongs in the query, not here, because the app cannot
        // know which one the analysis wants.
        val sink = FakeSink()
        val analytics = Analytics(sink)
        analytics.setEnabled(true)
        val event = AnalyticsEvent.AttachmentFetchEnd("pdf", 100L, 50L, "failed", null)
        analytics.log(event)
        analytics.log(event)
        assertEquals(2, sink.logged.size)
    }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew.bat testDebugUnitTest --rerun-tasks --console=plain`
Expected: `BUILD SUCCESSFUL`, all passing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/fcm/ app/src/test/java/org/paramanuseniorshealth/notices/AnalyticsTest.kt
git commit -m "Attachment fetch timing, including the jitter that was never recorded."
```

---

### Task 8: Receipt, notification taps, and how the app was opened

**Files:**
- Modify: `app/src/main/java/.../fcm/NoticeMessagingService.kt`
- Modify: `app/src/main/java/.../fcm/NoticeNotifications.kt`
- Modify: `app/src/main/java/.../MainActivity.kt`

**Interfaces:**
- Consumes: `AnalyticsEvent.NoticeReceived`, `NotificationTapped`, `AppOpen`, `NoticesApplication.processStartedAt`
- Produces: `NoticeNotifications.EXTRA_POSTED_AT = "postedAt"`

- [ ] **Step 1: Log receipt**

In `NoticeMessagingService`, just after `repository.save(...)` where `isNew` is in scope:

```kotlin
            app.analytics.log(
                AnalyticsEvent.NoticeReceived(
                    hasImage = !imageUrl.isNullOrBlank(),
                    hasPdf = !pdfUrl.isNullOrBlank(),
                    isNew = isNew,
                    // message.sentTime is the sender's clock and the honest source for delivery
                    // lag. notice.receivedAt is NOT: it is parsed from the logId and is identical
                    // on every phone.
                    deliveryLagMs = message.sentTime.takeIf { it > 0 }
                        ?.let { System.currentTimeMillis() - it },
                    msSinceProcessStart = System.currentTimeMillis() - app.processStartedAt,
                )
            )
```

- [ ] **Step 2: Carry the post time into the PendingIntent**

In `NoticeNotifications`, where the content intent is built with `EXTRA_LOG_ID`, add:

```kotlin
            .putExtra(EXTRA_POSTED_AT, System.currentTimeMillis())
```

and the constant `const val EXTRA_POSTED_AT = "postedAt"`. Without this there is no shown→tapped interval at all: nothing stores when a notification was posted.

- [ ] **Step 3: Log the tap and the open source**

In `MainActivity`, at the two `persistFromNotificationIntent` call sites — `onCreate` means the tap created the process, `onNewIntent` means the app was already alive:

```kotlin
    private fun logOpen(intent: Intent?, coldStart: Boolean) {
        val app = application as NoticesApplication
        val logId = intent?.extras?.getString(NoticeNotifications.EXTRA_LOG_ID)
        val source = when {
            logId != null -> "notification"
            intent?.action == Intent.ACTION_VIEW -> "link"
            else -> "launcher"
        }
        app.analytics.log(AnalyticsEvent.AppOpen(source))
        if (logId != null) {
            val postedAt = intent?.extras?.getLong(NoticeNotifications.EXTRA_POSTED_AT, 0L) ?: 0L
            app.analytics.log(
                AnalyticsEvent.NotificationTapped(
                    msSinceShown = postedAt.takeIf { it > 0 }?.let { System.currentTimeMillis() - it },
                    coldStart = coldStart,
                )
            )
        }
    }
```

Call `logOpen(intent, coldStart = true)` from `onCreate` and `logOpen(intent, coldStart = false)` from `onNewIntent`.

- [ ] **Step 4: Verify the build**

Run: `./gradlew.bat assembleDebug testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/fcm/ app/src/main/java/org/paramanuseniorshealth/notices/MainActivity.kt
git commit -m "Notice receipt, notification taps, and which door the app was opened by."
```

---

### Task 9: Reading — cards, the image viewer, and PDFs

**Files:**
- Modify: `app/src/main/java/.../MainActivity.kt`
- Modify: `app/src/main/java/.../ui/NoticeViewerScreen.kt`
- Modify: `app/src/main/java/.../fcm/NoticeImageStore.kt`

**Interfaces:**
- Consumes: `AnalyticsEvent.NoticeCardVisible`, `ImageViewerClosed`, `PdfOpened`
- Produces: nothing later tasks depend on

- [ ] **Step 1: Track card visibility from the hoisted list state**

In `MainActivity`, beside the existing `noticeListState`:

```kotlin
    LaunchedEffect(Unit) {
        val shownAt = mutableMapOf<Long, Long>()
        snapshotFlow { noticeListState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? Long } }
            .distinctUntilChanged()
            .collect { visible ->
                val now = System.currentTimeMillis()
                (shownAt.keys - visible.toSet()).forEach { gone ->
                    val since = shownAt.remove(gone) ?: return@forEach
                    notices.firstOrNull { it.id == gone }?.let { notice ->
                        analytics.log(
                            AnalyticsEvent.NoticeCardVisible(
                                noticeId = gone,
                                visibleMs = now - since,
                                hasImage = !notice.imageUrl.isNullOrBlank(),
                                hasPdf = !notice.pdfUrl.isNullOrBlank(),
                                position = notices.indexOf(notice),
                            )
                        )
                    }
                }
                visible.forEach { shownAt.putIfAbsent(it, now) }
            }
    }
```

The `as? Long` filter matters: index 0 is the office-info header whose key is the String `"office-info"`, and notice rows start at index 1.

**This must not go inside `NoticeRow`.** That composable performs three `listFiles()` disk scans on every recomposition, deliberately un-memoised, and `animateContentSize` plus the 2.5-second highlight animation make that per-frame.

- [ ] **Step 2: Close the image viewer reliably**

In `NoticeViewerScreen`, accumulate the maximum scale into a local beside the existing `scale` state, and add:

```kotlin
    var maxScale by remember { mutableFloatStateOf(1f) }
    val openedAt = remember { System.currentTimeMillis() }
    DisposableEffect(Unit) {
        onDispose {
            notice?.let {
                analytics.log(
                    AnalyticsEvent.ImageViewerClosed(
                        noticeId = it.id,
                        durationMs = System.currentTimeMillis() - openedAt,
                        maxScale = maxScale,
                    )
                )
            }
        }
    }
```

Update `maxScale` inside the existing `rememberTransformableState` lambda with `maxScale = maxOf(maxScale, scale)` — assigning a local, **not** logging, since that lambda runs on every pinch frame.

`onDispose` rather than `backToNotices()`: that function is shared with the Settings exit and misses backgrounding and process death entirely.

- [ ] **Step 3: Log the PDF open where the cache answer is already paid for**

In `NoticeImageStore.fetchPdf`, at the existing cache check:

```kotlin
        cachedPdf(context, logId)?.takeIf { it.length() > 0 }?.let {
            analytics.log(AnalyticsEvent.PdfOpened(noticeId, it.length(), wasCached = true))
            return it
        }
```

and log `wasCached = false` on the path that downloads. The hit/miss boolean is discarded here today and is not recoverable higher up without a second disk hit.

- [ ] **Step 4: Verify the build**

Run: `./gradlew.bat assembleDebug testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/
git commit -m "What gets read: card visibility, viewer dwell, and PDF opens."
```

---

### Task 10: The activation funnel

**Files:**
- Modify: `app/src/main/java/.../ui/NoticeViewModel.kt`
- Modify: `app/src/main/java/.../ui/ActivationScreen.kt`
- Modify: `app/src/main/java/.../activation/ActivationRepository.kt`

**Interfaces:**
- Consumes: `AnalyticsEvent.ActivationShown`, `ActivationSubmitted`, `ActivationResult`
- Produces: `ActivationRepository.lastOfflineCause: String?`

- [ ] **Step 1: Record which Offline it was**

`redeem` returns `RedeemResult.Offline` from three unrelated places, and the sealed type cannot carry the reason. Add a field set immediately before each return — `"no_uid"`, `"transport"`, `"timeout"` — and clear it at the top of `redeem`:

```kotlin
    /**
     * Why the last Offline was Offline. Three unrelated failures produce the same result -- no uid
     * from anonymous sign-in, a transport exception, and the 20-second timeout elapsing -- and
     * telling them apart is most of the value of the activation funnel.
     */
    var lastOfflineCause: String? = null
        private set
```

- [ ] **Step 2: Log the result in one place**

In `NoticeViewModel.redeem`, at the `when (val result = activation.redeem(rawCode))`:

```kotlin
        analytics.log(
            AnalyticsEvent.ActivationResult(
                result = result::class.simpleName.orEmpty().lowercase(),
                offlineCause = activation.lastOfflineCause.takeIf { result is RedeemResult.Offline },
            )
        )
```

One call site covering every outcome, instead of instrumenting six returns in the repository.

- [ ] **Step 3: Log submission and screen entry**

`AnalyticsEvent.ActivationSubmitted` goes in `NoticeViewModel.redeem` immediately after the `_redeeming` guard, so a double-tap does not double-count.

`ActivationShown` goes in a new `LaunchedEffect(Unit)` in `ActivationScreen`, with `hasCode` from whether a code is already stored. **Not** in the composable body: the screen is `verticalScroll` + `imePadding`, so it recomposes on every keystroke and every keyboard show.

- [ ] **Step 4: Verify the build and full suite**

Run: `./gradlew.bat assembleDebug testDebugUnitTest --rerun-tasks --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/
git commit -m "The activation funnel, including which of the three Offlines it was."
```

---

### Task 11: Crashlytics, and the documentation that is now wrong

**Files:**
- Modify: `app/src/main/java/.../NoticesApplication.kt`
- Modify: `docs/SYSTEM.md`
- Modify: `docs/privacy-policy.md`

- [ ] **Step 1: Gate Crashlytics on the same switch**

In `NoticesApplication`, alongside the analytics switch:

```kotlin
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = analyticsConfig.cached()
```

and the same in `refreshAnalyticsSwitch` once the live value is read, so one switch governs both.

- [ ] **Step 2: Rewrite the SYSTEM.md paragraph**

The bullet beginning "**No advertising ID, enforced in the manifest.**" currently says `firebase-analytics` "was removed". Rewrite it to say what is now true: analytics is present, the `AD_ID` permission is still stripped with `tools:node="remove"`, the Gradle dependency excludes `play-services-ads-identifier`, and `google_analytics_adid_collection_enabled` is false — three independent locks on the same guarantee. Also correct "67 Android unit tests" in §8, which is long out of date.

- [ ] **Step 3: Apply the privacy policy amendment**

Apply the eight blocks in `docs/privacy-policy-analytics-draft.md` to `docs/privacy-policy.md`. Before doing so, resolve the two open items the draft flags: set GA4 retention to 2 months, and either build the per-device exclusion or weaken that sentence. The region line is settled — the dataset is in Mumbai (`asia-south1`).

**The published copy on paramanuseniorshealth.org must be updated at the same time, and must be live before the build that collects reaches open testing.** Data Safety is declared app-wide, not per track.

- [ ] **Step 4: Verify the build**

Run: `./gradlew.bat assembleDebug testDebugUnitTest --rerun-tasks --console=plain`
Expected: `BUILD SUCCESSFUL`, all tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/paramanuseniorshealth/notices/NoticesApplication.kt docs/
git commit -m "Crashlytics on the same switch, and the docs that said we collect nothing."
```

---

### Task 12: End-to-end verification on a device

**Files:** none modified

- [ ] **Step 1: Turn the switch on**

Set `/config/analytics/enabled` to boolean `true` in the RTDB console.

- [ ] **Step 2: Watch events arrive in DebugView**

```bash
adb shell setprop debug.firebase.analytics.app org.paramanuseniorshealth.notices
```

Open Firebase console → Analytics → DebugView. Walk one notice end to end: receive it, tap the notification, let the card sit on screen, open the image, pinch it, go back, open the PDF.

Expected, in order: `notice_received`, `attachment_fetch_start`, `attachment_fetch_end`, `app_open_source` (source=notification), `notification_tapped`, `notice_card_visible`, `image_viewer_closed` with a `max_scale` above 1, `pdf_opened`.

Confirm every parameter is populated and none reads `null` where a value was expected.

- [ ] **Step 3: Turn it off and confirm silence**

Set the node to `false`, force-stop and reopen the app, and confirm DebugView shows nothing further — including no automatic session events.

- [ ] **Step 4: Turn it off again before shipping**

Leave `/config/analytics/enabled` at `false`. It is turned on deliberately, after the policy is live, not by default.

- [ ] **Step 5: Record the result**

Write `docs/release-notes/0.8.0.md` following the shape of `0.7.2.md`, including a "Before you upload" section naming the policy and Data Safety prerequisites.

---

## Notes for whoever implements this

- **Branch:** `analytics`, off `e563207`.
- **versionCode:** the next analytics build is **14**. Main takes odd codes, analytics even, so the two branches cannot collide on Play.
- **The spec is the argument, this is the sequence.** Where they disagree, the spec is right and this plan has a bug.
- Tasks 6–10 are independent of each other and all depend on 1–5. They can be done in any order once the foundation is in.
