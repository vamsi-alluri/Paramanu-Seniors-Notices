package org.paramanuseniorshealth.notices

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.paramanuseniorshealth.notices.activation.ActivationRefreshWorker
import org.paramanuseniorshealth.notices.activation.ActivationRepository
import org.paramanuseniorshealth.notices.activation.Subscription
import org.paramanuseniorshealth.notices.data.InfoRepository
import org.paramanuseniorshealth.notices.data.NoticeRepository
import org.paramanuseniorshealth.notices.data.NoticesDatabase
import org.paramanuseniorshealth.notices.fcm.NoticeNotifications

class NoticesApplication : Application() {

    /**
     * Hand-rolled container. The graph is one database -> one DAO -> one repository plus the
     * activation repository, shared by the Activity and the FCM service; Hilt would add a KSP round
     * and a Gradle plugin without removing any wiring.
     */
    val repository: NoticeRepository by lazy {
        NoticeRepository(NoticesDatabase.getInstance(this).noticeDao(), this)
    }

    val activationRepository: ActivationRepository by lazy { ActivationRepository(this) }

    val infoRepository: InfoRepository by lazy { InfoRepository(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Must exist before the first notification is posted, and the FCM service can start without
        // an Activity ever having run, so the channel is created here.
        NoticeNotifications.create(this)
        if (activationRepository.isSubscribed(Subscription.TESTING)) {
            NoticeNotifications.createTestingChannel(this)
        }

        // Unlike the app this was forked from, there is no unconditional subscribe on launch. A
        // device subscribes only once a code has been redeemed, and stays unsubscribed if the user
        // has switched notices off in Settings -- otherwise an un-activated install would sit on
        // the topic receiving payloads it merely declines to draw.
        appScope.launch { activationRepository.syncSubscriptions() }

        // Scheduled for a revoked device too: it has unsubscribed from every topic, so this is the
        // only way it will ever hear that its code has been restored.
        if (activationRepository.isActivated) {
            ActivationRefreshWorker.ensurePeriodic(this)
        }
    }

    /**
     * Says out loud that access has ended: once in the notification tray, once in the notice list.
     *
     * Reuses the NOTICES channel deliberately. Android lists every channel an app has ever created
     * in system settings, so a dedicated one would put a permanent extra entry in four hundred
     * people's notification settings for something that happens to almost none of them -- the same
     * reasoning that keeps the Testing channel lazily created.
     *
     * Idempotent: the Room insert ignores a duplicate `logId`, so a repeated revoke broadcast
     * cannot post twice.
     */
    suspend fun announceRevocation() {
        val title = getString(R.string.revoked_notification_title)
        val body = getString(R.string.revoked_notification_body)

        if (!repository.saveRevoked(title, body)) return

        NoticeNotifications.post(
            context = this,
            title = title,
            body = body,
            logId = NoticeRepository.REVOKED_LOG_ID,
            image = null,
            subscription = Subscription.NOTICES,
        )
    }
}
