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
import org.paramanuseniorshealth.notices.data.NoticeRepository
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

        notices.forEach { notice ->
            // Notices are processed serially and each may take up to NoticeImageStore's five-minute
            // circular ceiling, so a sweep holding several retryable circulars can run past
            // WorkManager's ten-minute execution window. Once stopped, continuing would keep
            // downloading and keep writing to Room on behalf of work the system has already given
            // up on. What a cutoff drops is decided by `withAttachments()`'s `ORDER BY receivedAt
            // DESC`: the oldest notices go first, which is the right end to lose -- and they are
            // picked up by the next sweep, since nothing here has advanced their state.
            if (isStopped) return Result.success()

            // One bad notice must not abandon the rest of a sweep, so each is isolated. Logged
            // rather than swallowed: a fetch that throws is a bug here, not a policy decision.
            runCatching { fetch(app, notice) }.onFailure { failure ->
                Log.w(TAG, "Attachment fetch threw for ${notice.logId}", failure)

                // fetch()'s own recordAttachment never ran, so without this the row keeps both its
                // prior state and its prior attempt count -- and a notice that throws on every pass
                // would be retried by every sweep forever, never reaching MAX_ATTEMPTS. A throw is
                // a failure in the plainest sense, so it is recorded as one.
                //
                // Null-safe rather than asserted, unlike in fetch(): the throw being handled here
                // may be that very assertion, and re-throwing it from the handler would abandon the
                // rest of the sweep -- which is the one thing this block exists to prevent.
                notice.logId?.let { logId ->
                    runCatching {
                        app.repository.recordAttachment(
                            logId = logId,
                            state = AttachmentState.FAILED,
                            attempts = notice.attachmentAttempts + 1,
                        )
                    }.onFailure { Log.w(TAG, "Could not record the failure for $logId", it) }
                }
            }
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

        fetchAttachments(context, app.repository, notice, logId, tapInitiated = false) { url, known ->
            decide(url, known, metered, roaming)
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
        /**
         * Two minutes, not the hour this started as.
         *
         * The hour was sized against a thundering herd hitting an origin server. The attachments
         * are served from GitHub Pages, which is a CDN -- 400 requests for one cached file is a
         * non-event, and the edge collapses concurrent misses into a single origin fetch, so even a
         * cold file does not produce a stampede.
         *
         * What actually binds is the monthly bandwidth quota, and spreading a download out does
         * nothing for a quota: 400 devices fetching 5.6MB costs the same 2.2GB whether it happens
         * in one second or over an hour. The lever for that is the published thumbnail, which takes
         * the same notice to about 70MB -- see [NoticeImageStore.fetchPdfThumb].
         *
         * So the jitter is now only cheap insurance against ever moving off a CDN, and it is kept
         * short because a long one costs something real: [NoticeNotifications.updatePicture]
         * deliberately does nothing once the notification has left the tray, so a picture arriving
         * an hour late lands in the app only and the notification never fills in at all. Two
         * minutes is inside the window where the notice is still on screen.
         */
        private const val MAX_JITTER_SECONDS = 2L * 60

        private fun connected() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        private fun unmetered() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        /**
         * The per-notice fetch, delayed by up to [MAX_JITTER_SECONDS].
         *
         * The delay is deliberately small: see [MAX_JITTER_SECONDS] for why spreading the load
         * turned out not to be the thing worth optimising for.
         *
         * `CONNECTED` rather than `UNMETERED`, because an unmetered *constraint* waits
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

        /**
         * Sweeps everything outstanding. Called when the app comes forward: no delay, no jitter.
         *
         * KEEP, not REPLACE. WorkManager cancels a REPLACE'd `CoroutineWorker`'s backing job on
         * `onStopped()`, and that throws at the next suspension point -- not only between notices --
         * so a REPLACE landing mid-download tears down the transfer itself. A user who resumes the
         * app faster than one large circular takes to fetch would re-cancel that download every
         * time, silently, forever. REPLACE would also buy nothing here even without that risk: this
         * request carries no input data, no initial delay and no backoff, so there is no newer
         * version of it for REPLACE to install. KEEP does not retain *finished* work either, so a
         * later resume still enqueues a fresh sweep once the previous one has ended -- it only
         * refuses to interrupt one still running. That also makes all three enqueue calls in this
         * file consistently KEEP; do not "optimise" this one back to REPLACE.
         */
        fun enqueueCatchUp(context: Context) {
            val request = OneTimeWorkRequestBuilder<AttachmentWorker>()
                .setConstraints(connected())
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(CATCH_UP, ExistingWorkPolicy.KEEP, request)
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

        /**
         * Whichever of [notice]'s attachments are missing, fetched or deferred per URL by
         * [verdict], then recorded through [NoticeRepository.recordAttachment] and reflected in the
         * notification tray.
         *
         * Shared between [fetch] (the policy-gated worker sweep) and [fetchNow] (an explicit tap)
         * so there is one place that knows which attachments a notice can carry and how the outcome
         * is recorded -- only the verdict and [tapInitiated] differ between them. See [fetch] for
         * why the notification update and the wifi re-enqueue are unconditional.
         *
         * [tapInitiated] threads two things that must not be inferred from the verdict alone: it
         * tells [NoticeImageStore.fetchPdfKeeping] to use a scratch file distinct from the worker's
         * own, since a tap bypasses [FetchPolicy] and so can run concurrently with a sweep for the
         * same notice; and it tells [FetchPolicy.nextAttempts] that a failure here must not burn the
         * automatic-retry budget -- the user asking is not the app failing on its own.
         */
        private suspend fun fetchAttachments(
            context: Context,
            repository: NoticeRepository,
            notice: NoticeEntity,
            logId: String,
            tapInitiated: Boolean,
            verdict: suspend (url: String, known: Long?) -> Verdict,
        ) {
            var deferred = false
            var failed = false
            var pages: Int? = null
            var bytes: Long? = null

            // Small things first: a link logo and a sender photo clear the 2MB threshold on any
            // non-roaming connection, so the card has something to show within the hour.
            notice.linkImage?.takeIf { it.isNotBlank() }?.let { url ->
                if (NoticeImageStore.cachedLinkImage(context, logId) == null) {
                    when (verdict(url, null)) {
                        Verdict.FETCH -> if (
                            NoticeImageStore.fetchLinkImage(context, url, logId) == null
                        ) failed = true
                        Verdict.DEFER -> deferred = true
                    }
                }
            }

            notice.imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                if (NoticeImageStore.cachedImage(context, logId) == null) {
                    when (verdict(url, null)) {
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
                    // touched until somebody taps it. This is the whole egress saving and it must
                    // not be "optimised" into pre-fetching on wifi: the website pays for the bytes
                    // whatever the phone is connected to.
                    when (verdict(thumbUrl, null)) {
                        Verdict.FETCH -> if (
                            NoticeImageStore.fetchPdfThumb(context, thumbUrl, logId) == null
                        ) failed = true
                        Verdict.DEFER -> deferred = true
                    }
                } else {
                    // No published thumbnail, so page one can only be had by downloading the
                    // document. Its size decides, and the result is kept rather than thrown away.
                    when (verdict(pdfUrl, notice.pdfBytes)) {
                        Verdict.FETCH -> {
                            val fetched = NoticeImageStore.fetchPdfKeeping(
                                context, pdfUrl, logId, tapInitiated,
                            )
                            if (fetched == null) failed = true else {
                                pages = fetched.pages
                                bytes = fetched.bytes
                            }
                        }
                        Verdict.DEFER -> deferred = true
                    }
                }
            }

            val state = FetchPolicy.outcome(
                deferred = deferred,
                failed = failed,
                previous = AttachmentState.parse(notice.attachmentState),
                tapInitiated = tapInitiated,
            )
            val attempts = FetchPolicy.nextAttempts(state, notice.attachmentAttempts, tapInitiated)
            repository.recordAttachment(logId, state, attempts, pages, bytes)

            // Unconditional, and deliberately not gated on FETCHED. A notice carrying a sender photo
            // and a thumbless circular on a metered connection fetches the photo and defers the
            // document, so the outcome is DEFERRED -- and gating here would leave the tray showing a
            // type placeholder while the photo sat on disk and the in-app row already displayed it.
            // That is exactly the case the deferral machinery exists to handle well. updatePicture
            // early-returns when the notification is gone and when nothing is cached, so it is
            // already a no-op in every case where it should not act.
            NoticeNotifications.updatePicture(context, notice)
            if (state == AttachmentState.DEFERRED) {
                // Nothing is scheduled for "later on mobile data" -- that is the tap. This is only
                // the standing wifi sweep, which costs nothing while no wifi appears.
                enqueueWifiCatchUp(context)
            }
        }

        /**
         * Fetches whichever of [notice]'s attachments are missing, right now, bypassing
         * [FetchPolicy] entirely.
         *
         * Called only from an explicit tap on the download glyph (see
         * `NoticeViewModel.downloadAttachment`). A tap is consent -- the policy exists to protect a
         * user's data plan without their say-so, and asking is exactly what the tap already did, so
         * asking it again here would be asking permission for permission already given.
         *
         * Shares [fetchAttachments] with the worker's policy-gated sweep so a successful tap is
         * recorded exactly the way a successful sweep is: [AttachmentState.FETCHED] with the attempt
         * count reset to zero. No-ops when [notice] has no logId -- every cache and every
         * notification key on it, so there is nothing to fetch into and nowhere to record the
         * outcome. That is a real possibility here, unlike in [fetch]: this is reached from whatever
         * the UI happens to be showing, not from a query that already filtered on logId.
         */
        suspend fun fetchNow(context: Context, repository: NoticeRepository, notice: NoticeEntity) {
            val logId = notice.logId ?: return
            fetchAttachments(context, repository, notice, logId, tapInitiated = true) { _, _ ->
                Verdict.FETCH
            }
        }
    }
}
