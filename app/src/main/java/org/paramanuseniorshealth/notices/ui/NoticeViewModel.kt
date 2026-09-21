package org.paramanuseniorshealth.notices.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.paramanuseniorshealth.notices.NoticesApplication
import org.paramanuseniorshealth.notices.R
import org.paramanuseniorshealth.notices.activation.ActivationRepository
import org.paramanuseniorshealth.notices.activation.ActivationState
import org.paramanuseniorshealth.notices.activation.Dispensary
import org.paramanuseniorshealth.notices.activation.RedeemResult
import org.paramanuseniorshealth.notices.data.DispensaryRepository
import org.paramanuseniorshealth.notices.data.InfoRepository
import org.paramanuseniorshealth.notices.data.NoticeEntity
import org.paramanuseniorshealth.notices.data.NoticeRepository
import org.paramanuseniorshealth.notices.data.OfficeInfo
import org.paramanuseniorshealth.notices.fcm.AttachmentWorker

/** Where the user is. Deliberately a state machine rather than a navigation graph: four
 *  destinations do not justify a navigation dependency, and the code screen is a gate rather
 *  than a place you can navigate back to. */
sealed interface Screen {
    /** The code ("PIN") gate. Reached on a fresh install, or by a revoked user entering a new code. */
    data object Activation : Screen
    data object Notices : Screen
    data object Settings : Screen
    data class Viewer(val noticeId: Long) : Screen
}

class NoticeViewModel(
    private val notices: NoticeRepository,
    private val activation: ActivationRepository,
    private val dispensaries: DispensaryRepository,
    info: InfoRepository,
    private val welcomeTitle: String,
    private val welcomeBody: String,
) : ViewModel() {

    val allNotices: StateFlow<List<NoticeEntity>> = notices.notices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The redeemed code, for display in Settings so the user can read it back over the phone. */
    val activationCode: String? get() = activation.storedCode

    suspend fun notice(id: Long): NoticeEntity? = notices.byId(id)

    private val _screen = MutableStateFlow<Screen>(
        // The local flag decides the first frame, so a returning user never sees the code screen
        // flash before the network answers. Firebase is consulted immediately afterwards, in
        // [refreshActivation], and can still send them back here.
        if (activation.isActivated) Screen.Notices else Screen.Activation
    )
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    /** The dispensary and the topics it offers, straight from the repository. */
    val dispensary: StateFlow<Dispensary?> = dispensaries.dispensary

    /** Each offered topic's switch, keyed by topic name. */
    private val _topicChoices = MutableStateFlow(activation.topicChoices())
    val topicChoices: StateFlow<Map<String, Boolean>> = _topicChoices.asStateFlow()

    private val _testingOn = MutableStateFlow(activation.testingOn)
    val testingOn: StateFlow<Boolean> = _testingOn.asStateFlow()

    private val _redeeming = MutableStateFlow(false)
    val redeeming: StateFlow<Boolean> = _redeeming.asStateFlow()

    /** Null when there is nothing to say; set after a failed redemption. */
    private val _redeemError = MutableStateFlow<RedeemResult?>(null)
    val redeemError: StateFlow<RedeemResult?> = _redeemError.asStateFlow()

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected.asStateFlow()

    /** The one open row, or null with everything collapsed. */
    private val _expandedId = MutableStateFlow<Long?>(null)
    val expandedId: StateFlow<Long?> = _expandedId.asStateFlow()

    /**
     * Briefly tinted after arriving from a notification tap, then cleared.
     *
     * Scrolling to a row is not enough on a long list: the user tapped a specific notice and needs
     * to see which of several similar-looking cards is the one they tapped. The tint fades on its
     * own so the list does not stay in a special state they have to work out how to leave.
     */
    private val _highlightId = MutableStateFlow<Long?>(null)
    val highlightId: StateFlow<Long?> = _highlightId.asStateFlow()

    /**
     * Whether the hidden testing switch is visible.
     *
     * Not persisted: it is unlocked per visit to Settings, so a phone handed to somebody else does
     * not show it. The choice itself is persisted, so a device already opted in keeps receiving test
     * messages and its row stays visible.
     */
    private val _testingUnlocked = MutableStateFlow(activation.testingOn)
    val testingUnlocked: StateFlow<Boolean> = _testingUnlocked.asStateFlow()

    /**
     * The banner, straight from the repository rather than mirrored here, for the same reason as
     * [revoked]: a pushed change is applied by the messaging service with no UI attached.
     */
    val officeInfo: StateFlow<OfficeInfo?> = info.info

    /**
     * One-shot messages for the UI to surface as a toast.
     *
     * `extraBufferCapacity = 1` with no replay: a message emitted before the collector attaches is
     * not lost, and one already shown is not repeated on rotation.
     */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    /**
     * Whether this device's access has been withdrawn.
     *
     * Drives a banner across the app rather than a return to the code screen: hiding the history
     * protected nothing, since any valid slip lifted that gate and it did not have to be theirs,
     * while it cost the user everything they had already received.
     *
     * Taken straight from the repository rather than mirrored here. The change usually originates
     * in the messaging service with no UI attached, and a local copy only caught up when something
     * else happened to redraw the screen.
     */
    val revoked: StateFlow<Boolean> = activation.revoked

    /** True while "Check again" is in flight, so the button can say so. */
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /** When the server was last asked, for the resume throttle. Not persisted: per foreground run. */
    private var lastCheckedAt = 0L

    /**
     * Notices whose circular is downloading right now.
     *
     * A set rather than a single id because the list is scrollable and nothing stops a user from
     * tapping two rows. Held here rather than in the composable so it survives the row scrolling
     * out of view and back -- a download that silently restarted every time the row recomposed
     * would look like a button that does nothing.
     */
    private val _downloadingPdf = MutableStateFlow<Set<Long>>(emptySet())
    val downloadingPdf: StateFlow<Set<Long>> = _downloadingPdf.asStateFlow()

    init {
        refreshActivation()
    }

    /**
     * Fetches the circular if needed, then hands it to [open].
     *
     * [open] returns false when no app on the phone can display a PDF, and [onUnavailable] is the
     * caller's escape to the website -- so the tap always leads somewhere, whether the file is
     * cached, downloadable, or neither.
     *
     * Re-entry is guarded on the notice id: a second tap while the first download is in flight is
     * ignored rather than starting a duplicate.
     */
    fun openPdf(
        notice: NoticeEntity,
        open: (java.io.File) -> Boolean,
        onUnavailable: () -> Unit,
    ) {
        if (notice.id in _downloadingPdf.value) return
        _downloadingPdf.value = _downloadingPdf.value + notice.id
        viewModelScope.launch {
            try {
                val file = notices.pdfFile(notice)
                if (file == null || !open(file)) {
                    // Either the download failed or nothing can open a PDF. Both end at the
                    // website, which needs a connection -- but so did getting this far.
                    _messages.tryEmit(R.string.toast_pdf_opening_online)
                    onUnavailable()
                }
            } finally {
                _downloadingPdf.value = _downloadingPdf.value - notice.id
            }
        }
    }

    fun toggleExpanded(id: Long) {
        _expandedId.value = if (_expandedId.value == id) null else id
    }

    /**
     * Entry point for a notification tap: open the notice the user actually tapped.
     *
     * The row is expanded and highlighted here; the list scrolls to it. Resolution is by `logId`
     * because that is what the notification carries, and the row may not have existed when the
     * intent was created -- the tap and the database write race, so this retries briefly rather
     * than giving up and dropping the user at the top of the list.
     */
    fun openFromNotification(logId: String) {
        viewModelScope.launch {
            repeat(RESOLVE_ATTEMPTS) { attempt ->
                val match = notices.byLogId(logId)
                if (match != null) {
                    _screen.value = Screen.Notices
                    _expandedId.value = match.id
                    _highlightId.value = match.id
                    delay(HIGHLIGHT_MS)
                    if (_highlightId.value == match.id) _highlightId.value = null
                    return@launch
                }
                if (attempt < RESOLVE_ATTEMPTS - 1) delay(RESOLVE_RETRY_MS)
            }
        }
    }

    /**
     * Asks the backend whether this install's claim still stands, and then what its dispensary
     * offers.
     *
     * A definitive [ActivationState.Revoked] raises the banner but leaves the user where they are.
     * [ActivationState.Unknown] -- offline, slow signal -- changes nothing, for the same reason the
     * delivery gate permits it: locking someone out of notices they have already received because
     * their phone had no signal at launch would be indefensible.
     */
    fun refreshActivation() {
        viewModelScope.launch { applyVerification() }
    }

    /**
     * Asks the server and applies the answer. Returns it so a caller can report the outcome.
     *
     * suspendClaim and resumeClaim publish the change themselves, so there is nothing to mirror
     * into a local flag here.
     */
    private suspend fun applyVerification(): ActivationState {
        val state = activation.verify()
        when (state) {
            ActivationState.Revoked -> activation.suspendClaim()

            ActivationState.Active -> {
                if (activation.isRevoked) {
                    activation.resumeClaim()
                    notices.clearRevokedNotice()
                }
                // Only for a claim that stands: a revoked phone receives nothing, the banner and the
                // topic list included.
                loadDispensary()
            }

            // Genuinely no claim on this device: a fresh install.
            ActivationState.NotActivated -> _screen.value = Screen.Activation

            ActivationState.Unknown -> Unit
        }
        lastCheckedAt = System.currentTimeMillis()
        return state
    }

    /**
     * Reads the dispensary -- its topics and its banner in one go -- and brings the subscriptions
     * into line with it. Keeps whatever is cached when the read fails.
     */
    private suspend fun loadDispensary() {
        val id = activation.dispensaryId ?: return
        dispensaries.refresh(id)
        activation.syncSubscriptions()
        enforceSoleTopic()
        _topicChoices.value = activation.topicChoices()
    }

    /**
     * Re-subscribes a dispensary's only topic if it is off. See [Dispensary.soleTopicToForceOn].
     *
     * Runs after [ActivationRepository.syncSubscriptions] rather than before it, so it is deciding
     * against the topic list that was just read rather than the previous one.
     */
    private suspend fun enforceSoleTopic() {
        val topic = dispensaries.dispensary.value?.soleTopicToForceOn(activation.topicChoices())
            ?: return
        activation.setTopic(topic, true)
    }

    /**
     * Called whenever the app comes to the foreground.
     *
     * Without this the only check was in `init`, which runs when the view model is created -- so
     * bringing the app forward from recents re-checked nothing, and a user whose code had been
     * restored had to know to swipe the app away and reopen it. Nobody knows that. The same was true
     * of the banner, which a phone left in recents kept for days.
     *
     * A revoked phone checks every time: its user is the one actively waiting for an answer, and
     * they may be standing at the counter. An active phone is throttled, because switching between
     * two apps should not open a database connection each way.
     *
     * The two attachment sweeps below run unconditionally, outside that throttle. They are cheap
     * and idempotent -- the worker itself filters rows through [FetchPolicy.shouldRetry], so
     * enqueuing when nothing is outstanding costs nothing -- unlike [refreshActivation], which
     * makes a real network call and is rightly rate-limited. One sweep runs now, on whatever
     * connection is available, and picks up anything small that was missed. The other stands
     * waiting for wifi and costs nothing until it appears, which is how a circular deferred on
     * mobile data eventually arrives without the user being told anything.
     *
     * [context] is the application context, threaded in from the call site rather than held by
     * this view model -- a ViewModel holding a Context outlives the Activity and leaks it.
     */
    fun onResumed(context: Context) {
        AttachmentWorker.enqueueCatchUp(context)
        AttachmentWorker.enqueueWifiCatchUp(context)

        val stale = System.currentTimeMillis() - lastCheckedAt >= RESUME_RECHECK_MS
        if (activation.isRevoked || stale) refreshActivation()
    }

    /**
     * The user pressing "Check again" after being told their code was restored.
     *
     * Always asks, however recently it last checked -- a throttle here would answer somebody who
     * has just come off the phone to the helpdesk with silence. Says what happened either way,
     * because a button that appears to do nothing is worse than no button.
     */
    fun recheckActivation() {
        if (_checking.value) return
        _checking.value = true
        viewModelScope.launch {
            val message = when (applyVerification()) {
                ActivationState.Active -> R.string.toast_access_restored
                ActivationState.Revoked -> R.string.toast_still_stopped
                ActivationState.Unknown -> R.string.toast_check_failed
                ActivationState.NotActivated -> R.string.toast_still_stopped
            }
            _messages.tryEmit(message)
            _checking.value = false
        }
    }

    /**
     * Opens the code screen without surrendering anything.
     *
     * Nothing is wiped. Somebody who has been revoked and given a fresh slip at the counter should
     * keep everything they have already received -- the new code is the same person continuing, not
     * a new one starting.
     */
    fun enterNewCode() {
        _screen.value = Screen.Activation
    }

    fun redeem(rawCode: String) {
        if (_redeeming.value) return
        _redeeming.value = true
        _redeemError.value = null
        viewModelScope.launch {
            when (val result = activation.redeem(rawCode)) {
                RedeemResult.Success -> {
                    // redeem() has already read the dispensary -- its topics and its banner -- and
                    // subscribed to the defaults, so Settings and the header are ready. A sole
                    // topic defaulting to off would still leave a silent app, so it is forced here
                    // too rather than only on the refresh path.
                    enforceSoleTopic()
                    _topicChoices.value = activation.topicChoices()
                    // redeem() clears the revocation itself, so the banner is already down by here.
                    // The old revocation notice goes too: it says no more alerts will arrive, which
                    // has just stopped being true.
                    notices.clearRevokedNotice()
                    // Written before the screen changes so the list is never momentarily empty.
                    notices.saveWelcome(welcomeTitle, welcomeBody)
                    lastCheckedAt = System.currentTimeMillis()

                    _screen.value = Screen.Notices
                }
                else -> _redeemError.value = result
            }
            _redeeming.value = false
        }
    }

    fun dismissRedeemError() {
        _redeemError.value = null
    }

    fun show(screen: Screen) {
        _screen.value = screen
    }

    /** Back out of Settings or the viewer. The code screen is not part of this: it is a gate. */
    fun backToNotices() {
        _screen.value = Screen.Notices
    }

    /**
     * Posts a notification to this phone immediately.
     *
     * It proves the permission and the channel work, which is the failure people actually hit --
     * the Play reviewer's own screenshots show notifications denied. It does not exercise FCM at
     * all, so it cannot tell you delivery is working end to end; that is what the testing topic is
     * for.
     */
    fun sendTestNotification(post: () -> Boolean) {
        _messages.tryEmit(if (post()) R.string.toast_test_sent else R.string.toast_test_blocked)
    }

    /** Reveals the testing switch. Called after the deliberate gesture in Settings. */
    fun unlockTesting() {
        if (_testingUnlocked.value) return
        _testingUnlocked.value = true
        _messages.tryEmit(R.string.toast_testing_unlocked)
    }

    fun setTopic(topic: String, enabled: Boolean) {
        // Optimistic: the switch moves at once and the network call follows. FCM subscription can
        // take a moment, and a toggle that visibly lags gets pressed twice.
        _topicChoices.value = _topicChoices.value + (topic to enabled)
        viewModelScope.launch { activation.setTopic(topic, enabled) }
    }

    fun setTesting(enabled: Boolean) {
        _testingOn.value = enabled
        viewModelScope.launch { activation.setTesting(enabled) }
    }

    fun toggleSelection(id: Long) {
        _selected.value = _selected.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun deleteSelected() {
        val ids = _selected.value
        val rows = allNotices.value.filter { it.id in ids }
        _selected.value = emptySet()
        viewModelScope.launch { notices.clear(rows) }
    }

    companion object {
        /** The tap and the database write race, so resolution retries for about a second. */
        private const val RESOLVE_ATTEMPTS = 5
        private const val RESOLVE_RETRY_MS = 200L
        private const val HIGHLIGHT_MS = 2_500L

        /**
         * How stale an answer may be before coming to the foreground re-asks.
         *
         * Short, because an app open is a natural moment to check and they are spread out across
         * users -- unlike a broadcast, which wakes four hundred phones at once and is why the
         * per-notice check had to move off the message path entirely.
         */
        private const val RESUME_RECHECK_MS = 60_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as NoticesApplication
                NoticeViewModel(
                    notices = app.repository,
                    activation = app.activationRepository,
                    dispensaries = app.dispensaryRepository,
                    info = app.infoRepository,
                    // Resolved here rather than in the view model so the strings stay localisable
                    // and the view model keeps no Context.
                    welcomeTitle = app.getString(R.string.welcome_title),
                    welcomeBody = app.getString(R.string.welcome_body),
                )
            }
        }
    }
}
