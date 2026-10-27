package org.paramanuseniorshealth.notices

import android.app.Application
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.paramanuseniorshealth.notices.activation.ActivationRefreshWorker
import org.paramanuseniorshealth.notices.activation.ActivationRepository
import org.paramanuseniorshealth.notices.activation.CodeManagementApi
import org.paramanuseniorshealth.notices.activation.HttpCodeManagementApi
import org.paramanuseniorshealth.notices.activation.ResolvingCodeManagementApi
import org.paramanuseniorshealth.notices.analytics.Analytics
import org.paramanuseniorshealth.notices.analytics.AnalyticsConfig
import org.paramanuseniorshealth.notices.analytics.FirebaseAnalyticsSink
import org.paramanuseniorshealth.notices.analytics.FirebaseRemoteConfigSwitch
import org.paramanuseniorshealth.notices.data.DispensaryRepository
import org.paramanuseniorshealth.notices.data.InfoRepository
import org.paramanuseniorshealth.notices.data.NoticeRepository
import org.paramanuseniorshealth.notices.data.NoticesDatabase
import org.paramanuseniorshealth.notices.fcm.ControlTopics
import org.paramanuseniorshealth.notices.fcm.NoticeChannel
import org.paramanuseniorshealth.notices.fcm.NoticeNotifications
import org.paramanuseniorshealth.notices.update.AppGate
import org.paramanuseniorshealth.notices.update.ConfigRefreshWorker

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

    /**
     * The one Remote Config reader: it answers the analytics switch, the function's base URL and the
     * app gate.
     */
    private val remoteConfigSwitch by lazy { FirebaseRemoteConfigSwitch(this) }

    /**
     * Whether this build may run: open, below the version floor, or switched off. See [AppGate].
     *
     * Starts open and is corrected by the first [evaluateGate], which [onCreate] launches off the
     * main thread. A closed app therefore shows its notice list for the moment before that, which
     * is the price of never reading Remote Config on the main thread (see [refreshRemoteConfig]).
     */
    private val _appGate = MutableStateFlow<AppGate>(AppGate.Open)
    val appGate: StateFlow<AppGate> = _appGate.asStateFlow()

    /**
     * Decides the gate from Remote Config's activated values, publishes it, and returns it. No
     * network, but a disk read the first time in a process, so never on the main thread. The
     * messaging service calls it directly rather than reading [appGate], because a message can start
     * the process before anything else has evaluated it.
     */
    fun evaluateGate(): AppGate = AppGate.decide(
        installedVersion = BuildConfig.VERSION_CODE.toLong(),
        minVersion = remoteConfigSwitch.minVersionSupported(),
        killed = remoteConfigSwitch.killed(),
    ).also { _appGate.value = it }

    /**
     * Asks that the next Remote Config fetch ignore its six-hour interval. Set by a `configStale`
     * push, which is sent on every publish.
     */
    fun markRemoteConfigStale() = remoteConfigSwitch.markStale()

    /** Whether a fetch owed after [markRemoteConfigStale] has still not succeeded. */
    val remoteConfigStale: Boolean get() = remoteConfigSwitch.isStale

    /**
     * Resolves the base URL on every call, because Remote Config fetches after the process starts.
     * Until the key is published the URL is blank and every call is inconclusive without touching
     * the network.
     */
    private val codeManagementApi: CodeManagementApi by lazy {
        ResolvingCodeManagementApi(remoteConfigSwitch) { baseUrl -> HttpCodeManagementApi(baseUrl, analytics) }
    }

    val activationRepository: ActivationRepository by lazy {
        ActivationRepository(this, dispensaryRepository, codeManagementApi) { evaluateGate() == AppGate.Open }
    }

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

    val analyticsConfig by lazy { AnalyticsConfig(remoteConfigSwitch) }

    val analytics by lazy { Analytics(FirebaseAnalyticsSink(this)) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Crash reporting runs always, and is deliberately not part of applyCollectionSwitch: it
        // says the app broke, never what anyone read, and the switch spends most of its life off.
        //
        // Set explicitly rather than left to the manifest, because the setter persists. Every phone
        // that ran a build where the kill switch drove Crashlytics has `false` written into
        // com.google.firebase.crashlytics.xml, and a persisted value wins over the manifest for
        // ever. Writing true once is what reaches those installs; after that it is a no-op.
        guarded("enable crash reporting") {
            FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = true
        }

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

        // The value already in force applies first, so a launch while offline behaves as the last
        // known state -- which on a first install is off. The fetch then corrects it.
        refreshRemoteConfig()
    }

    /**
     * The one place the switch is applied, so the two things it governs cannot disagree.
     *
     * Those two are GA4 and FCM delivery logging -- what people did, and whether notices arrived.
     * **Crashlytics is deliberately not here**: it reports that the app broke, not what anyone
     * read, and it runs always (see [onCreate]). Grouping it with the behavioural measurement
     * would have meant no crash reports at all for the long stretches when the switch is off.
     *
     * Delivery logging is set separately from [analytics] because it is a different SDK with its
     * own flag; guarding one would leave the other reporting while the app called itself off. Each
     * failure is swallowed for the same reason [Analytics.setEnabled] swallows the sink's: nothing
     * here may be able to break app start.
     *
     * Delivery logging is applied on **every** call, in both directions, and not only when turning
     * it on. The messaging SDK persists the runtime value in its own preferences and prefers it
     * over the manifest default from then on, so a value set once would otherwise stick -- leaving
     * delivery logging running on a phone whose switch had since been turned off.
     *
     * It is also the one measurement here that does not need anybody to open the app: it runs in
     * the message receive path, so it reports on a phone that merely received a notice. That makes
     * it the most important of the three to turn off promptly, not the least.
     */
    private fun applyCollectionSwitch(enabled: Boolean) {
        analytics.setEnabled(enabled)
        guarded("set delivery logging $enabled") {
            FirebaseMessaging.getInstance().setDeliveryMetricsExportToBigQuery(enabled)
        }
    }

    /**
     * Runs [block], and says so when it fails rather than swallowing it.
     *
     * These calls must never break app start, which is why they are caught at all. But a silent
     * catch here is worse than it looks: the privacy policy says one switch governs what is
     * collected, so a setter that quietly does nothing makes a published promise untrue with no
     * trace anywhere. [FirebaseAnalyticsSink] draws the same line for the event path.
     */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Could not $what: $e")
        }
    }

    /**
     * Brings the analytics switch and the app gate into line with Remote Config. Safe to call on
     * every foreground.
     *
     * Called from [onCreate] (process start) and from MainActivity's ON_RESUME effect (every
     * foreground), which is what lets "off" reach a process that stays alive for days. Neither
     * call is throttled here, and neither needs to be: inside Remote Config's fetch window the
     * fetch is answered from local storage without touching the network.
     *
     * Uses the existing appScope rather than a new one: it is already IO-dispatched and
     * supervisor-scoped, which is exactly what a best-effort read wants.
     *
     * The standing value is applied first, inside the same coroutine, and deliberately **not** on
     * the main thread. Remote Config's first getter call in a process resolves its activated
     * container through a blocking disk read with a five-second timeout, and this used to run in
     * `onCreate` on the main thread. Measured at ~40ms on the reference handset, but the ceiling is
     * an ANR, and nothing here may be able to break app start. Moving it cannot let collection
     * start early: both SDKs ship disabled by manifest meta-data, so the pre-gate state of a phone
     * that has never been enabled is already off.
     */
    fun refreshRemoteConfig() {
        appScope.launch { syncRemoteConfig() }
    }

    /**
     * [refreshRemoteConfig]'s work, callable from [ConfigRefreshWorker], which must wait for it.
     *
     * A closed gate forces the fetch past the six-hour interval. Without that, a gate opened in
     * the console stays shut on a phone for up to six hours after the owner fixed the mistake --
     * and the person holding it sees only a screen that says the app does not work. A closed gate
     * is rare, so the extra fetches are too.
     *
     * When the gate moves either way, the subscriptions move with it: closed keeps only
     * [ControlTopics.CONFIG], so nothing more arrives; open restores the rest.
     */
    suspend fun syncRemoteConfig() {
        applyCollectionSwitch(analyticsConfig.cached())
        val before = evaluateGate()
        if (before != AppGate.Open) remoteConfigSwitch.markStale()
        applyCollectionSwitch(analyticsConfig.refresh())
        if (evaluateGate() != before) activationRepository.syncSubscriptions()
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
    private companion object {
        const val TAG = "NoticesApplication"
    }

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
