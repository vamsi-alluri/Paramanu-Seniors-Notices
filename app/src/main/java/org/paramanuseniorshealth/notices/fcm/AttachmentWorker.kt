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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

        // One bad notice must not abandon the rest of a sweep, so each is isolated. Logged rather
        // than swallowed: a fetch that throws is a bug here, not a policy decision, and the
        // recordAttachment inside fetch() never runs when it does.
        notices.forEach { notice ->
            runCatching { fetch(app, notice) }
                .onFailure { Log.w(TAG, "Attachment fetch threw for ${notice.logId}", it) }
        }
        return Result.success()
    }

    private suspend fun fetch(app: NoticesApplication, notice: NoticeEntity) {
        // Both routes into here guarantee a logId: `withAttachments()` filters `logId IS NOT NULL`,
        // and `byLogId` matched on one. So this is an assertion about an invariant, not a case to
        // handle -- a null here would mean the query changed underneath the worker, and silently
        // returning would hide that. It is a notice with no logId that can never hold a cached
        // attachment anyway: every cache path in NoticeImageStore keys on it.
        val logId = checkNotNull(notice.logId) { "notice ${notice.id} has no logId" }
        val context = applicationContext

        // NetworkStatus is framework-facts-only and deliberately not suspending, so the wrapping is
        // the caller's job -- and doWork runs on Dispatchers.Default, a pool sized for CPU work.
        // remoteSize() in particular is a blocking HttpURLConnection round trip; running one on
        // Default would occupy a core-count-sized pool that the rest of the app shares.
        val metered = withContext(Dispatchers.IO) { NetworkStatus.isMetered(context) }
        val roaming = withContext(Dispatchers.IO) { NetworkStatus.isRoaming(context) }

        var deferred = false
        var failed = false
        var pages: Int? = null
        var bytes: Long? = null

        // Small things first: a link logo and a sender photo clear the 2MB threshold on any
        // non-roaming connection, so the card has something to show within the hour.
        notice.linkImage?.takeIf { it.isNotBlank() }?.let { url ->
            if (NoticeImageStore.cachedLinkImage(context, logId) == null) {
                when (decide(url, null, metered, roaming)) {
                    Verdict.FETCH -> if (
                        NoticeImageStore.fetchLinkImage(context, url, logId) == null
                    ) failed = true
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
                    Verdict.FETCH -> if (
                        NoticeImageStore.fetchPdfThumb(context, thumbUrl, logId) == null
                    ) failed = true
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

        val state = FetchPolicy.outcome(deferred = deferred, failed = failed)
        val attempts = FetchPolicy.nextAttempts(state, notice.attachmentAttempts)
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
     *
     * The probe is moved to [Dispatchers.IO] for the reason given in [fetch]: `remoteSize` blocks.
     */
    private suspend fun decide(
        url: String,
        known: Long?,
        metered: Boolean,
        roaming: Boolean,
    ): Verdict {
        // Roaming answers without any request at all: nothing is going to be fetched either way.
        if (roaming) return Verdict.DEFER
        val size = known ?: withContext(Dispatchers.IO) { NetworkStatus.remoteSize(url) }
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
