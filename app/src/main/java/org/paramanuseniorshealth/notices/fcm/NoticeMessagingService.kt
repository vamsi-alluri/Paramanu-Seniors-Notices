package org.paramanuseniorshealth.notices.fcm

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import org.paramanuseniorshealth.notices.NoticesApplication
import org.paramanuseniorshealth.notices.activation.ActivationRefreshWorker
import org.paramanuseniorshealth.notices.activation.ActivationRepository
import org.paramanuseniorshealth.notices.activation.ActivationState
import org.paramanuseniorshealth.notices.activation.Importance

class NoticeMessagingService : FirebaseMessagingService() {

    /**
     * Called for every message while the app is in the foreground, and for **data-only** messages
     * in the background too. A payload containing a `notification` block is handled by the SDK and
     * drawn straight into the tray while backgrounded -- this method never runs, the entitlement
     * check below is bypassed, and nothing is written to history. The sender must therefore send
     * data-only messages; see docs/sender-contract.md.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data

        // Checked before anything else, because a control message carries no title and the notice
        // path below would drop it as malformed.
        ControlMessage.parse(data)?.let { control ->
            runBlocking { applyControl(applicationContext as NoticesApplication, control) }
            return
        }

        val title = data["title"] ?: message.notification?.title ?: return
        // Which topic a notice came on decides whether it is wanted and how loudly it arrives. Every
        // notice the sender builds carries it.
        val topic = data["topic"]?.takeIf { it.isNotBlank() } ?: run {
            Log.i(TAG, "Dropped a notice with no topic")
            return
        }
        val body = data["body"] ?: message.notification?.body.orEmpty()
        val logId = data["logId"]
        val imageUrl = data["imageUrl"]?.takeIf { it.isNotBlank() }
        val pdfUrl = data["pdfUrl"]?.takeIf { it.isNotBlank() }
        // A published first-page image, and the circular's measurements. All three are absent until
        // the website's pipeline ships; when they arrive the worker fetches ~40KB rather than
        // ~5.6MB, and `pdfBytes` lets a metered phone decide without even making a HEAD request.
        val pdfThumbUrl = data["pdfThumbUrl"]?.takeIf { it.isNotBlank() }
        val pdfPages = data["pdfPages"]?.toIntOrNull()
        val pdfBytes = data["pdfBytes"]?.toLongOrNull()
        // Resolved by the sender, never here: see NoticeEntity.linkUrl. All four may be absent, and
        // linkUrl may arrive alone when the sender could not resolve a card.
        val linkUrl = data["linkUrl"]?.takeIf { it.isNotBlank() }
        val linkTitle = data["linkTitle"]?.takeIf { it.isNotBlank() }
        val linkImage = data["linkImageUrl"]?.takeIf { it.isNotBlank() }
        val linkSite = data["linkSite"]?.takeIf { it.isNotBlank() }

        // onMessageReceived already runs off the main thread and the service is held alive for the
        // duration of this call, so blocking here guarantees the work lands before teardown.
        runBlocking {
            val app = applicationContext as NoticesApplication

            if (!isEntitled(app)) return@runBlocking

            if (!app.activationRepository.wantsTopic(topic)) {
                Log.i(TAG, "Dropped a notice on '$topic': not offered, or switched off")
                return@runBlocking
            }

            val channel = if (topic == ActivationRepository.TESTING_TOPIC) {
                NoticeChannel.TESTING
            } else {
                NoticeChannel.forImportance(
                    app.dispensaryRepository.current?.topic(topic)?.importance ?: Importance.HIGH
                )
            }

            // Saved before anything is fetched: a slow or dead URL must never cost a history entry.
            // It is also what the worker reads minutes later, so the URLs must be persisted here
            // even though nothing on this path uses them.
            val isNew = app.repository.save(
                title = title,
                body = body,
                logId = logId,
                imageUrl = imageUrl,
                pdfUrl = pdfUrl,
                linkUrl = linkUrl,
                linkTitle = linkTitle,
                linkImage = linkImage,
                linkSite = linkSite,
                pdfThumbUrl = pdfThumbUrl,
                pdfPages = pdfPages,
                pdfBytes = pdfBytes,
            )

            // Nothing is downloaded here any more.
            //
            // This ran under runBlocking inside a service that may be torn down after ten or twenty
            // seconds, and for a circular it pulled the whole 5.6MB document to render page one.
            // The notification now goes out immediately with a type placeholder, and the worker
            // fills the picture in within the hour -- or leaves a download glyph, if the policy says
            // this is not the app's data to spend.
            //
            // Only draw the tray notification for data-only payloads: if the sender also included a
            // `notification` block while we were foregrounded, posting again would duplicate it.
            // `isNew` additionally suppresses FCM's at-least-once re-deliveries.
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
            // Guarded by isNew for the same reason: a re-delivery must not queue a second fetch.
            // Guarded by logId because every cache path keys on it, so a notice without one has
            // nowhere to put an attachment.
            if (isNew && logId != null) AttachmentWorker.enqueueFor(applicationContext, logId)
        }
    }

    /**
     * Acts on a revoke, a resume or a banner change.
     *
     * Revoke and resume are topic broadcasts, so every phone on the control topic receives every one
     * and only the holder of that code acts on it. Each is stamped with when staff acted, and a stamp
     * no newer than the last one applied is ignored: FCM does not promise order, and a Disable
     * delivered after the Enable that followed it would otherwise leave the phone off.
     *
     * A resume needs no server check. The drain in the sender re-reads the code before sending one
     * and drops it unless the code stands, and the daily verification still corrects a phone that
     * somehow got it wrong.
     */
    private suspend fun applyControl(app: NoticesApplication, control: ControlMessage) {
        val activation = app.activationRepository
        when (control) {
            is ControlMessage.Revoke -> {
                if (activation.storedCode != control.code) return
                if (!activation.acceptControl(control.at)) return
                Log.i(TAG, "Revoke received for this device")
                activation.suspendClaim()
                app.announceRevocation()
            }

            is ControlMessage.Resume -> {
                if (activation.storedCode != control.code) return
                if (!activation.acceptControl(control.at)) return
                if (!activation.isRevoked) return
                Log.i(TAG, "Resume received for this device")
                activation.resumeClaim()
                app.repository.clearRevokedNotice()
            }

            // Every dispensary's banner goes out on the one control topic, so a phone keeps only its
            // own. A revoked phone receives nothing, the banner included; it catches up when it
            // resumes and next comes to the foreground.
            is ControlMessage.Banner -> {
                if (control.dispensary != activation.dispensaryId) return
                if (!activation.isActivated || activation.isRevoked) return
                app.infoRepository.applyPushed(control.html, control.at)
            }
        }
    }

    /**
     * The client-side entitlement gate.
     *
     * Note what this is and is not. The payload has already reached the device by the time this
     * runs -- the app is declining to *show* it, not being prevented from receiving it. That is
     * adequate here because every notice is also published publicly on the website, and it must not
     * later be mistaken for access control.
     *
     * It performs **no network I/O**. The answer comes from the last persisted verification, and a
     * refresh is scheduled instead -- see [ActivationRefreshWorker]. Calling verify() here meant a
     * broadcast to four hundred phones opened ~400 database sockets at once against a cap of 100,
     * so the check failed during precisely the event it exists for.
     *
     * The consequence, accepted deliberately: a refresh lands in time for the *next* notice rather
     * than this one, so a revoked device can see one more. That costs nothing. This is not access
     * control -- every notice is published publicly on the website -- and a pushed revoke closes
     * the gap in the normal case anyway.
     *
     * [ActivationState.Unknown] still permits delivery: suppressing would mean a user in their
     * eighties misses a closure notice because of a weak signal, which is far worse than an
     * already-revoked device seeing one more public announcement.
     */
    private fun isEntitled(app: NoticesApplication): Boolean {
        ActivationRefreshWorker.enqueueIfStale(applicationContext)

        return when (app.activationRepository.gate()) {
            ActivationState.Active -> true
            ActivationState.Unknown -> {
                Log.i(TAG, "Entitlement not yet known; showing the notice anyway")
                true
            }
            ActivationState.NotActivated -> {
                // Subscribed but never activated: possible after a data wipe.
                Log.i(TAG, "Notice dropped: not activated")
                false
            }
            ActivationState.Revoked -> {
                Log.i(TAG, "Notice dropped: activation revoked")
                false
            }
        }
    }

    // onNewToken is intentionally not overridden: this app is addressed only by topic, and the SDK
    // re-establishes topic subscriptions itself after a token rotation.

    private companion object {
        private const val TAG = "NoticeFCM"
    }
}
