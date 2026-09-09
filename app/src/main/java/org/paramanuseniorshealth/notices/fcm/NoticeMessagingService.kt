package org.paramanuseniorshealth.notices.fcm

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking
import org.paramanuseniorshealth.notices.NoticesApplication
import org.paramanuseniorshealth.notices.activation.ActivationRefreshWorker
import org.paramanuseniorshealth.notices.activation.ActivationState
import org.paramanuseniorshealth.notices.activation.Subscription

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

        // Checked before anything else, because a revoke carries no title and the notice path
        // below would drop it as malformed. It is a topic broadcast, so this arrives on every
        // subscribed phone and only the holder of that code acts on it.
        RevokeMessage.codeIn(data)?.let { revokedCode ->
            runBlocking {
                val app = applicationContext as NoticesApplication
                if (app.activationRepository.storedCode == revokedCode) {
                    Log.i(TAG, "Revoke received for this device")
                    app.activationRepository.suspendClaim()
                    app.announceRevocation()
                }
            }
            return
        }

        val title = data["title"] ?: message.notification?.title ?: return
        val body = data["body"] ?: message.notification?.body.orEmpty()
        val logId = data["logId"]
        val imageUrl = data["imageUrl"]?.takeIf { it.isNotBlank() }
        val pdfUrl = data["pdfUrl"]?.takeIf { it.isNotBlank() }
        val subscription = Subscription.fromCategory(data["category"])

        // onMessageReceived already runs off the main thread and the service is held alive for the
        // duration of this call, so blocking here guarantees the work lands before teardown.
        runBlocking {
            val app = applicationContext as NoticesApplication

            if (!isEntitled(app)) return@runBlocking

            // A second check behind the topic subscription. Unsubscribing is not instant -- FCM can
            // keep delivering for a short while afterwards -- so without this a user who has just
            // switched the daily status off still gets the next one, and reasonably concludes the
            // switch does not work.
            if (!app.activationRepository.isSubscribed(subscription)) {
                Log.i(TAG, "Dropped a ${'$'}{subscription.name} message: not subscribed")
                return@runBlocking
            }

            // Saved before the PDF is fetched: a slow or dead URL must never cost a history entry.
            val isNew = app.repository.save(
                title = title,
                body = body,
                logId = logId,
                imageUrl = imageUrl,
                pdfUrl = pdfUrl,
            )

            // Both attachments are fetched when both are present -- they are not alternatives, and
            // the expanded row shows each. Fetching serves double duty: one of these bitmaps goes
            // into the tray notification and the same files back the in-app row, so the UI never
            // performs network I/O and the notice stays readable offline.
            val picture = if (logId != null) {
                val photo = imageUrl?.let { NoticeImageStore.fetchImage(applicationContext, it, logId) }
                val rendered = pdfUrl?.let { NoticeImageStore.fetchPdfRender(applicationContext, it, logId) }
                // The sender's own picture wins the tray: it was chosen for a small frame, whereas
                // an A4 page shrunk to notification size is barely legible.
                photo ?: rendered
            } else {
                null
            }

            // Only draw the tray notification for data-only payloads: if the sender also included a
            // `notification` block while we were foregrounded, posting again would duplicate it.
            // `isNew` additionally suppresses FCM's at-least-once re-deliveries.
            if (isNew && message.notification == null) {
                NoticeNotifications.post(
                    context = this@NoticeMessagingService,
                    title = title,
                    body = body,
                    logId = logId,
                    image = picture,
                    subscription = subscription,
                )
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
