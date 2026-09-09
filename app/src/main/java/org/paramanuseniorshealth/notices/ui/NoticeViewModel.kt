package org.paramanuseniorshealth.notices.ui

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
import org.paramanuseniorshealth.notices.activation.RedeemResult
import org.paramanuseniorshealth.notices.activation.Subscription
import org.paramanuseniorshealth.notices.data.InfoRepository
import org.paramanuseniorshealth.notices.data.NoticeEntity
import org.paramanuseniorshealth.notices.data.NoticeRepository
import org.paramanuseniorshealth.notices.data.OfficeInfo

/** Where the user is. Deliberately a state machine rather than a navigation graph: four
 *  destinations do not justify a navigation dependency, and the code screen is a gate rather
 *  than a place you can navigate back to. */
sealed interface Screen {
    /** The code ("PIN") gate. Reachable only on a fresh install or after a reset. */
    data object Activation : Screen
    data object Notices : Screen
    data object Settings : Screen
    data class Viewer(val noticeId: Long) : Screen
}

class NoticeViewModel(
    private val notices: NoticeRepository,
    private val activation: ActivationRepository,
    private val info: InfoRepository,
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

    private val _subscriptions = MutableStateFlow(activation.subscriptions())
    val subscriptions: StateFlow<Map<Subscription, Boolean>> = _subscriptions.asStateFlow()

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
     * not show it. The subscription itself is persisted, so a device already opted in keeps
     * receiving test messages and its row stays visible.
     */
    private val _testingUnlocked = MutableStateFlow(activation.isSubscribed(Subscription.TESTING))
    val testingUnlocked: StateFlow<Boolean> = _testingUnlocked.asStateFlow()

    private val _officeInfo = MutableStateFlow(info.cached)
    val officeInfo: StateFlow<OfficeInfo?> = _officeInfo.asStateFlow()

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
        refreshOfficeInfo()
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

    /** Keeps whatever is cached when the fetch fails, rather than emptying the header. */
    fun refreshOfficeInfo() {
        viewModelScope.launch { info.refresh()?.let { _officeInfo.value = it } }
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
     * Asks the backend whether this install's claim still stands.
     *
     * A definitive [ActivationState.Revoked] raises the banner but leaves the user where they are.
     * [ActivationState.Unknown] -- offline, slow signal -- changes nothing, for the same reason the
     * delivery gate permits it: locking someone out of notices they have already received because
     * their phone had no signal at launch would be indefensible.
     */
    fun refreshActivation() {
        viewModelScope.launch {
            when (activation.verify()) {
                // suspendClaim and resumeClaim publish the change themselves, so there is nothing
                // to mirror here.
                ActivationState.Revoked -> activation.suspendClaim()

                ActivationState.Active -> {
                    // The fastest route back for a restored code, for anyone who does open the app.
                    if (activation.isRevoked) {
                        activation.resumeClaim()
                        notices.clearRevokedNotice()
                    }
                }

                // Genuinely no claim on this device: a fresh install, or after a reset.
                ActivationState.NotActivated -> _screen.value = Screen.Activation

                ActivationState.Unknown -> Unit
            }
        }
    }

    /**
     * Opens the code screen without surrendering anything.
     *
     * Distinct from [reset], which wipes the notices too. Somebody who has been revoked and given a
     * fresh slip at the counter should keep everything they have already received -- the new code
     * is the same person continuing, not a new one starting.
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
                    _subscriptions.value = activation.subscriptions()
                    // redeem() clears the revocation itself, so the banner is already down by here.
                    // The old revocation notice goes too: it says no more alerts will arrive, which
                    // has just stopped being true.
                    notices.clearRevokedNotice()
                    // Written before the screen changes so the list is never momentarily empty.
                    notices.saveWelcome(welcomeTitle, welcomeBody)

                    // Fetch the banner again now that there is an account.
                    //
                    // The attempt in init ran before anybody had signed in, and /info requires
                    // auth != null, so on a fresh install it was refused. Without this the header
                    // stays blank until the app is next opened -- which is the first thing a new
                    // user sees, and looks like the app half-working.
                    refreshOfficeInfo()

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

    fun setSubscribed(subscription: Subscription, enabled: Boolean) {
        // Optimistic: the switch moves at once and the network call follows. FCM subscription can
        // take a moment, and a toggle that visibly lags gets pressed twice.
        _subscriptions.value = _subscriptions.value + (subscription to enabled)
        viewModelScope.launch { activation.setSubscribed(subscription, enabled) }
    }

    /**
     * Surrenders the claim and returns to the code screen. The stored notices are cleared with it:
     * leaving one person's notice history on a phone that is being handed to somebody else is the
     * kind of surprise this app should not produce.
     */
    fun reset() {
        viewModelScope.launch {
            activation.resetByUser()
            notices.clearAll()
            _selected.value = emptySet()
            _screen.value = Screen.Activation
        }
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

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                        as NoticesApplication
                NoticeViewModel(
                    notices = app.repository,
                    activation = app.activationRepository,
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
