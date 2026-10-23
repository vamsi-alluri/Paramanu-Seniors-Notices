package org.paramanuseniorshealth.notices.fcm

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.paramanuseniorshealth.notices.NoticesApplication
import org.paramanuseniorshealth.notices.analytics.AnalyticsEvent
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

        // Only enqueueFor writes KEY_JITTER and KEY_ENQUEUED_AT. The sweeps carry no input data on
        // purpose (see enqueueCatchUp), so for them both are null -- absent, not 0. getLong's
        // default cannot be used: the jitter draw includes 0, and a default would make "no jitter
        // concept" and "rolled zero" the same value. The key's presence is what decides.
        val origin = FetchOrigin(
            jitterSeconds = inputData.longOrNull(KEY_JITTER),
            enqueuedAtMs = inputData.longOrNull(KEY_ENQUEUED_AT),
            startedAtMs = System.currentTimeMillis(),
            attempt = runAttemptCount,
        )

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
            if (isStopped) {
                // Nothing of ours is buffered: Analytics.log writes straight through. If that ever
                // changes, this is where a truncated sweep would silently lose its events.
                return Result.success()
            }

            // One bad notice must not abandon the rest of a sweep, so each is isolated. Logged
            // rather than swallowed: a fetch that throws is a bug here, not a policy decision.
            runCatching { fetch(app, notice, origin) }.onFailure { failure ->
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

    private suspend fun fetch(app: NoticesApplication, notice: NoticeEntity, origin: FetchOrigin) {
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

        fetchAttachments(
            context, app.repository, notice, logId, tapInitiated = false, origin = origin,
        ) { kind, url, known ->
            decide(kind, url, known, metered, roaming)
        }
    }

    /**
     * [GONE] is not a kind of failure to be retried; it is the server saying the file was never
     * published. It exists so the card can stop offering a download that cannot succeed.
     */
    private enum class Verdict { FETCH, DEFER, GONE }

    /**
     * [known] is the size the payload supplied, and skips the HEAD request entirely. Otherwise the
     * size is probed, which is one small round trip against a possible 5.6MB one.
     *
     * The probe is moved to [Dispatchers.IO] for the reason given in [fetch]: `remoteSize` blocks.
     *
     * This is where the policy decision is logged, and deliberately not in [fetchAttachments]'s
     * arms or in the lambda [fetchNow] passes: this function is reached only from [fetch], so a
     * tap-initiated fetch -- which is consent, not a policy decision -- can never be recorded as
     * one. It is also the only place that holds the facts the event needs (metered, roaming and
     * the size actually measured). Every return that is a decision logs exactly once; [Verdict.GONE]
     * logs nothing, because a 4xx is the server's answer about the file, not the policy's about
     * the connection, and counting it as a decline would inflate the deferral ratio.
     *
     * A size is reported only when one was measured or supplied, and is otherwise sent as absent
     * rather than as zero. The `probe` parameter is what separates the cases a size could
     * otherwise come from, and it matters: an unreachable attachment is decided with the
     * same unknown-size policy as a withheld `Content-Length`, so without `probe` a flaky network
     * would be recorded as policy deferrals and inflate the very ratio these events exist to
     * measure. What a null means depends on it:
     *  - `skipped` (roaming short-circuit): nothing was probed, so a present size is always the
     *    sender's own (only `pdf` carries one, from `pdfBytes`) and a null says nothing about the
     *    file's size;
     *  - `sender`: the payload supplied the size, so no request was made and the size is always
     *    present;
     *  - `reachable`: a real HEAD request was answered, so a null means the server withheld
     *    `Content-Length`;
     *  - `unreachable`: no answer came back, so the size is always null, and the row is a
     *    connectivity fact rather than a policy one -- leave it out of any deferral ratio.
     *
     * These rows are decisions, not attachments: a deferred attachment is decided again on every
     * later sweep, so a ratio must be taken per decision, never per attachment.
     */
    private suspend fun decide(
        kind: String,
        url: String,
        known: Long?,
        metered: Boolean,
        roaming: Boolean,
    ): Verdict {
        val analytics = (applicationContext as NoticesApplication).analytics
        fun decided(verdict: Verdict, sizeBytes: Long?, probed: String): Verdict {
            analytics.log(
                AnalyticsEvent.FetchPolicyDecision(
                    kind = kind,
                    metered = metered,
                    roaming = roaming,
                    sizeBytes = sizeBytes,
                    probe = probed,
                    fetched = verdict == Verdict.FETCH,
                )
            )
            return verdict
        }

        // Roaming answers without any request at all: nothing is going to be fetched either way.
        // It returns before FetchPolicy is consulted, so it needs its own log -- without it every
        // roaming deferral would be invisible, and they are what the roaming rule is judged by.
        if (roaming) return decided(Verdict.DEFER, known, "skipped")
        // A size supplied by the sender stands in for a successful probe: the website would not
        // publish a length for a file it does not have.
        // Decided here because only this expression knows which of the two produced a
        // Probe.Reachable: the event must say whether a request was actually made.
        val fromSender = known != null
        val probe = known?.let { Probe.Reachable(it) }
            ?: withContext(Dispatchers.IO) { NetworkStatus.probe(url) }
        return when (probe) {
            Probe.Gone -> Verdict.GONE
            is Probe.Reachable ->
                decided(
                    if (FetchPolicy.shouldFetch(probe.bytes, metered, roaming)) Verdict.FETCH
                    else Verdict.DEFER,
                    probe.bytes,
                    if (fromSender) "sender" else "reachable",
                )
            // No answer is not the same as a refusal. Treated as an unknown size, which the policy
            // reads as "large" -- so it waits for wifi rather than giving up.
            Probe.Unreachable ->
                decided(
                    if (FetchPolicy.shouldFetch(null, metered, roaming)) Verdict.FETCH
                    else Verdict.DEFER,
                    null,
                    "unreachable",
                )
        }
    }

    companion object {
        private const val TAG = "AttachmentWorker"
        private const val KEY_LOG_ID = "logId"
        private const val KEY_JITTER = "jitter"
        private const val KEY_ENQUEUED_AT = "enqueuedAt"
        private const val CATCH_UP = "attachment-catch-up"
        private const val CATCH_UP_WIFI = "attachment-catch-up-wifi"
        /**
         * One minute, not the hour this started as (and two minutes after that).
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
         * an hour late lands in the app only and the notification never fills in at all. One
         * minute is well inside the window where the notice is still on screen.
         */
        private const val MAX_JITTER_SECONDS = 60L

        private fun Data.longOrNull(key: String): Long? =
            if (hasKeyWithValueOfType(key, Long::class.javaObjectType)) getLong(key, 0L) else null

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
            // Hoisted so the draw can be recorded: spread-versus-clustered jitter is exactly what
            // the fetch-start events exist to show.
            val jitterSeconds = Random.nextLong(0, MAX_JITTER_SECONDS)
            val request = OneTimeWorkRequestBuilder<AttachmentWorker>()
                .setConstraints(connected())
                .setInitialDelay(jitterSeconds, TimeUnit.SECONDS)
                .setInputData(
                    workDataOf(
                        KEY_LOG_ID to logId,
                        KEY_JITTER to jitterSeconds,
                        // Input data is how a worker receives values from whoever enqueued it, and
                        // WorkManager does not put the enqueue time there, so it is added here.
                        // Only here: the sweeps stay free of input data so that KEEP can see their
                        // requests as identical.
                        KEY_ENQUEUED_AT to System.currentTimeMillis(),
                    )
                )
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
            origin: FetchOrigin,
            verdict: suspend (kind: String, url: String, known: Long?) -> Verdict,
        ) {
            var deferred = false
            var failed = false
            // At least one attachment came back 4xx. Tracked apart from [failed] because it is not
            // a thing to try again -- see where the attempt count is set below.
            var gone = false
            var pages: Int? = null
            var bytes: Long? = null

            // Small things first: a link logo and a sender photo clear the 2MB threshold on any
            // non-roaming connection, so the card has something to show within the hour.
            notice.linkImage?.takeIf { it.isNotBlank() }?.let { url ->
                if (NoticeImageStore.cachedLinkImage(context, logId) == null) {
                    when (verdict("link", url, null)) {
                        Verdict.FETCH -> if (
                            NoticeImageStore.fetchLinkImage(context, url, logId, origin) == null
                        ) failed = true
                        Verdict.DEFER -> deferred = true
                        Verdict.GONE -> { failed = true; gone = true }
                    }
                }
            }

            notice.imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
                if (NoticeImageStore.cachedImage(context, logId) == null) {
                    when (verdict("img", url, null)) {
                        Verdict.FETCH ->
                            if (NoticeImageStore.fetchImage(context, url, logId, origin) == null) {
                                failed = true
                            }
                        Verdict.DEFER -> deferred = true
                        Verdict.GONE -> { failed = true; gone = true }
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
                    when (verdict("pdf_thumb", thumbUrl, null)) {
                        Verdict.FETCH -> if (
                            NoticeImageStore.fetchPdfThumb(context, thumbUrl, logId, origin) == null
                        ) failed = true
                        Verdict.DEFER -> deferred = true
                        Verdict.GONE -> { failed = true; gone = true }
                    }
                } else {
                    // No published thumbnail, so page one can only be had by downloading the
                    // document. Its size decides, and the result is kept rather than thrown away.
                    when (verdict("pdf", pdfUrl, notice.pdfBytes)) {
                        Verdict.FETCH -> {
                            val fetched = NoticeImageStore.fetchPdfKeeping(
                                context, pdfUrl, logId, tapInitiated, origin, notice.pdfBytes,
                            )
                            if (fetched == null) failed = true else {
                                pages = fetched.pages
                                bytes = fetched.bytes
                            }
                        }
                        Verdict.DEFER -> deferred = true
                        Verdict.GONE -> { failed = true; gone = true }
                    }
                }
            }

            val state = FetchPolicy.outcome(
                deferred = deferred,
                failed = failed,
                previous = AttachmentState.parse(notice.attachmentState),
                tapInitiated = tapInitiated,
            )
            // A 4xx goes straight to the cap rather than spending five sweeps rediscovering it.
            // That stops the automatic retries -- shouldRetry is false at the cap -- and it is what
            // the card reads to draw a struck-out page instead of a download glyph, so the reader is
            // not invited to tap something that cannot work.
            //
            // Only when the outcome is actually FAILED. If something else on the notice merely
            // deferred, deferral still outranks failure and the count is left alone, because that
            // part is still worth retrying; the row settles to FAILED-at-cap once it resolves.
            val attempts = if (gone && state == AttachmentState.FAILED) {
                FetchPolicy.MAX_ATTEMPTS
            } else {
                FetchPolicy.nextAttempts(state, notice.attachmentAttempts, tapInitiated)
            }
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
            // A tap has no worker, so there is no jitter and no enqueue time -- both absent -- and
            // its attempt is 0. FetchOrigin's defaults say exactly that, stamped with now.
            fetchAttachments(
                context, repository, notice, logId, tapInitiated = true, origin = FetchOrigin(),
            ) { _, _, _ ->
                Verdict.FETCH
            }
        }
    }
}
