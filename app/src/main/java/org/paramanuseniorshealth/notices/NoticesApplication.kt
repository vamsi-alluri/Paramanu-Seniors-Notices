package org.paramanuseniorshealth.notices

import android.app.Application
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.paramanuseniorshealth.notices.activation.ActivationRefreshWorker
import org.paramanuseniorshealth.notices.activation.ActivationRepository
import org.paramanuseniorshealth.notices.analytics.Analytics
import org.paramanuseniorshealth.notices.analytics.AnalyticsConfig
import org.paramanuseniorshealth.notices.analytics.FirebaseAnalyticsSink
import org.paramanuseniorshealth.notices.data.DispensaryRepository
import org.paramanuseniorshealth.notices.data.InfoRepository
import org.paramanuseniorshealth.notices.data.NoticeRepository
import org.paramanuseniorshealth.notices.data.NoticesDatabase
import org.paramanuseniorshealth.notices.fcm.NoticeChannel
import org.paramanuseniorshealth.notices.fcm.NoticeNotifications

class NoticesApplication : Application() {

    /**
     * Hand-rolled container. The graph is one database -> one DAO -> one repository, plus the
     * activation, dispensary and banner repositories, shared by the Activity and the FCM service;
     * Hilt would add a KSP round and a Gradle plugin without removing any wiring.
     */
    val repository: NoticeRepository by lazy {
        NoticeRepository(NoticesDatabase.getInstance(this).noticeDao(), this)
    }

    val infoRepository: InfoRepository by lazy { InfoRepository(this) }

    val dispensaryRepository: DispensaryRepository by lazy { DispensaryRepository(this, infoRepository) }

    val activationRepository: ActivationRepository by lazy { ActivationRepository(this, dispensaryRepository) }

    /**
     * When this process started.
     *
     * Nothing else in the app tracks it, and it is what makes "was the app already running when
     * the notice landed" answerable without adding lifecycle-process as a dependency.
     */
    val processStartedAt: Long = System.currentTimeMillis()

    private val openedInThisProcess = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Records that the app has been opened, and says whether this was the first open in this
     * process.
     *
     * Process-wide rather than per activity because an activity can be destroyed and recreated
     * inside a live process, and the question this answers is whether the process was already
     * serving someone. It is true for the first open even if a message had started the process
     * earlier, since no open had preceded it.
     */
    fun markOpened(): Boolean = !openedInThisProcess.getAndSet(true)

    private val analyticsPrefs by lazy {
        getSharedPreferences("analytics", MODE_PRIVATE)
    }

    val analyticsConfig by lazy { AnalyticsConfig(analyticsPrefs) }

    val analytics by lazy { Analytics(FirebaseAnalyticsSink(this)) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Must exist before the first notification is posted, and the FCM service can start without
        // an Activity ever having run, so the channel is created here.
        NoticeNotifications.create(this)
        if (activationRepository.testingOn) {
            NoticeNotifications.ensureChannel(this, NoticeChannel.TESTING)
        }

        // Unlike the app this was forked from, there is no unconditional subscribe on launch. A
        // device subscribes only once a code has been redeemed, and only to what its dispensary offers
        // and the user has left on -- otherwise an un-activated install would sit on a topic receiving
        // payloads it merely declines to draw.
        appScope.launch { activationRepository.syncSubscriptions() }

        // Scheduled for a revoked device too. A restore is pushed over the control topic, but a phone
        // that was switched off when it went out misses it, and this is how that phone still hears.
        if (activationRepository.isActivated) {
            ActivationRefreshWorker.ensurePeriodic(this)
        }

        // The cached value applies immediately so a launch while offline behaves as the last known
        // state -- which on a first install is off. The live read then corrects it.
        applyCollectionSwitch(analyticsConfig.cached())
        refreshAnalyticsSwitch()
    }

    /**
     * The one place the switch is applied, so analytics and crash reporting cannot disagree.
     *
     * Crashlytics is set separately from [analytics] because it is a different SDK with its own
     * flag; guarding one would leave the other reporting while the app called itself off. Its
     * failure is swallowed for the same reason [Analytics.setEnabled] swallows the sink's: nothing
     * here may be able to break app start.
     */
    private fun applyCollectionSwitch(enabled: Boolean) {
        analytics.setEnabled(enabled)
        runCatching { FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = enabled }
    }

    /**
     * Brings the switch into line with the database. Safe to call on every foreground.
     *
     * Called from [onCreate] (process start) and from MainActivity's ON_RESUME effect (every
     * foreground), which is what lets "off" reach a process that stays alive for days. Neither
     * call is throttled.
     *
     * Uses the existing appScope rather than a new one: it is already IO-dispatched and
     * supervisor-scoped, which is exactly what a best-effort read wants.
     */
    fun refreshAnalyticsSwitch() {
        appScope.launch {
            applyCollectionSwitch(analyticsConfig.refresh(FirebaseDatabase.getInstance()))
        }
    }

    /**
     * Says out loud that access has ended: once in the notification tray, once in the notice list.
     *
     * Reuses the high-importance channel deliberately. Android lists every channel an app has ever
     * created in system settings, so a dedicated one would put a permanent extra entry in four
     * hundred people's notification settings for something that happens to almost none of them --
     * the same reasoning that keeps the Testing channel lazily created.
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
        )
    }
}
