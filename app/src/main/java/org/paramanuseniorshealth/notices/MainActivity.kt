package org.paramanuseniorshealth.notices

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.paramanuseniorshealth.notices.data.NoticeEntity
import org.paramanuseniorshealth.notices.data.NoticeRepository
import org.paramanuseniorshealth.notices.fcm.NoticeNotifications
import org.paramanuseniorshealth.notices.ui.ActivationScreen
import org.paramanuseniorshealth.notices.ui.AttachmentActions
import org.paramanuseniorshealth.notices.ui.NoticeListScreen
import org.paramanuseniorshealth.notices.ui.RevokedBanner
import org.paramanuseniorshealth.notices.ui.NoticeViewModel
import org.paramanuseniorshealth.notices.ui.NoticeViewerScreen
import org.paramanuseniorshealth.notices.ui.Screen
import org.paramanuseniorshealth.notices.ui.ShareText
import org.paramanuseniorshealth.notices.ui.SettingsScreen
import org.paramanuseniorshealth.notices.ui.rememberNotificationAccessState
import org.paramanuseniorshealth.notices.ui.theme.ParamanuNoticesTheme

class MainActivity : ComponentActivity() {

    /**
     * The logId of the notification the user tapped, if any.
     *
     * A flow rather than a one-off read of `intent`, because onNewIntent fires while the Compose
     * tree is already running: tapping a second notice with the app open has to move the list to
     * that notice, not be ignored because the first value was already consumed.
     */
    private val tappedLogId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        persistFromNotificationIntent(intent)

        setContent {
            ParamanuNoticesTheme {
                // Recomposes when a new intent arrives while the app is already open, which is the
                // common case: the user taps a notice while the app sits in the background.
                val tapped by tappedLogId.collectAsStateWithLifecycle()
                NoticesApp(tappedLogId = tapped)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        persistFromNotificationIntent(intent)
    }

    /**
     * Fallback for payloads that carry a `notification` block, which the FCM SDK renders itself
     * without ever reaching NoticeMessagingService. The unique index on `logId` makes this safe to
     * run alongside the service path -- whichever arrives second is ignored.
     *
     * Note this path bypasses the entitlement gate by design: it only fires when the user has
     * already tapped a notification the system drew for them, so suppressing it would show them an
     * empty app immediately after they tapped a notice.
     */
    private fun persistFromNotificationIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        val logId = extras.getString(NoticeNotifications.EXTRA_LOG_ID) ?: return

        // Locally-posted notices are never re-persisted from an intent. This fallback exists for
        // payloads the FCM SDK drew itself, which always come from the sender with a numeric logId
        // -- a `local-` one cannot arrive that way, so there is nothing here to recover.
        //
        // Without this, tapping the revocation notice after the code had been restored wrote the
        // row straight back into history, undoing the delete that had just removed it.
        if (logId.startsWith(NoticeRepository.LOCAL_LOG_ID_PREFIX)) {
            tappedLogId.value = logId
            return
        }

        val title = extras.getString(NoticeNotifications.EXTRA_TITLE)
            ?: extras.getString("gcm.notification.title")
            ?: return
        val body = extras.getString(NoticeNotifications.EXTRA_BODY)
            ?: extras.getString("gcm.notification.body")
            ?: ""

        val repository = (application as NoticesApplication).repository
        lifecycleScope.launch { repository.save(title = title, body = body, logId = logId) }

        tappedLogId.value = logId
    }
}

@Composable
private fun NoticesApp(
    tappedLogId: String?,
    viewModel: NoticeViewModel = viewModel(factory = NoticeViewModel.Factory),
) {
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val notices by viewModel.allNotices.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val dispensary by viewModel.dispensary.collectAsStateWithLifecycle()
    val topicChoices by viewModel.topicChoices.collectAsStateWithLifecycle()
    val testingOn by viewModel.testingOn.collectAsStateWithLifecycle()
    val redeeming by viewModel.redeeming.collectAsStateWithLifecycle()
    val redeemError by viewModel.redeemError.collectAsStateWithLifecycle()
    val expandedId by viewModel.expandedId.collectAsStateWithLifecycle()
    val highlightId by viewModel.highlightId.collectAsStateWithLifecycle()
    val officeInfo by viewModel.officeInfo.collectAsStateWithLifecycle()
    val testingUnlocked by viewModel.testingUnlocked.collectAsStateWithLifecycle()
    val revoked by viewModel.revoked.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    val downloadingPdf by viewModel.downloadingPdf.collectAsStateWithLifecycle()

    val access = rememberNotificationAccessState()
    val context = LocalContext.current

    // Resolved in composition rather than inside the click lambda: stringResource is the
    // configuration-aware path, and reading resources off LocalContext misses a locale or font
    // scale change until the next recomposition.
    val testTitle = stringResource(R.string.test_notification_title)
    val testBody = stringResource(R.string.test_notification_body)

    // Opens the notice the user tapped: expand it, highlight it, scroll to it. Keyed on the id so
    // tapping a second notification while the app is open moves to that one.
    LaunchedEffect(tappedLogId) {
        tappedLogId?.let { viewModel.openFromNotification(it) }
    }

    // A toast rather than a snackbar: the screen is about to be replaced by the code gate, and a
    // toast is the only surface that survives that change and stays readable across it.
    LaunchedEffect(Unit) {
        viewModel.messages.collect { messageRes ->
            Toast.makeText(context, messageRes, Toast.LENGTH_LONG).show()
        }
    }

    // Re-check whenever the app comes forward. Previously the only check was in the view model's
    // init, which survives a trip through recents -- so a user whose code had been restored had to
    // know to swipe the app away and reopen it, which nobody knows.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResumed(context.applicationContext) }

    // The permission prompt is deliberately not raised on the code screen: asking before the user
    // has any reason to expect notices is how a denial happens, and on Android 13+ a denial
    // silently disables the whole app for someone who will not go looking in system settings.
    LaunchedEffect(screen) {
        if (screen is Screen.Notices && !access.isEnabled) access.request()
    }

    // The banner sits above every screen rather than inside the notice list. Being cut off is a
    // fact about the whole app, and a user who happened to be in Settings or reading an attachment
    // when the revoke landed would otherwise see nothing at all. It is left off the code screen
    // itself, where it would be telling somebody mid-typing that the code they are entering is
    // already dead.
    val showBanner = revoked && screen !is Screen.Activation

    Column(Modifier.fillMaxSize()) {
        if (showBanner) {
            // The window is edge to edge, and this sits above the Scaffold that would otherwise
            // have handled the status bar, so it takes that inset itself -- and the screen below
            // consumes it, or every Scaffold would pad for a status bar that is already covered.
            RevokedBanner(
                code = viewModel.activationCode,
                checking = checking,
                onRecheck = viewModel::recheckActivation,
                // Already there: the button would go nowhere, and Settings carries its own
                // Check again alongside the code and the helpdesk number.
                onOpenSettings = if (screen is Screen.Settings) null
                                 else ({ viewModel.show(Screen.Settings) }),
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
            )
        }

        // weight(1f) rather than letting the screens fill: each is a Scaffold asking for the whole
        // height, which in a plain Column would run off the bottom by exactly the banner's height.
        Box(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (showBanner) Modifier.consumeWindowInsets(WindowInsets.statusBars)
                    else Modifier
                )
        ) {
    when (val current = screen) {
        Screen.Activation -> {
            // Back is a way out of this screen only when there is somewhere to go back to. A
            // device that still holds a code arrived here from Settings, having chosen "Enter a
            // new code", and must be able to change its mind -- previously back fell through to
            // the system and closed the app.
            //
            // On a fresh install there is nothing behind this screen, so
            // no handler is installed and back does what it always did: leaves the app.
            if (viewModel.activationCode != null) {
                BackHandler { viewModel.show(Screen.Settings) }
            }
            ActivationScreen(
                onSubmit = viewModel::redeem,
                busy = redeeming,
                error = redeemError,
                onErrorDismissed = viewModel::dismissRedeemError,
            )
        }

        Screen.Notices -> {
            BackHandler(enabled = selected.isNotEmpty()) { viewModel.clearSelection() }
            NoticeListScreen(
                notices = notices,
                selected = selected,
                expandedId = expandedId,
                highlightId = highlightId,
                officeInfo = officeInfo,
                notificationsBlocked = !access.isEnabled,
                onOpenNotificationSettings = access::request,
                onToggleExpanded = viewModel::toggleExpanded,
                onOpenImage = { viewModel.show(Screen.Viewer(it.id)) },
                downloadingPdf = downloadingPdf,
                onOpenPdf = { notice ->
                    // Opening needs a Context, so the attempt is passed in as a lambda and the view
                    // model keeps none -- the same arrangement as onSendTest above.
                    viewModel.openPdf(
                        notice = notice,
                        open = { file -> AttachmentActions.open(context, file) },
                        onUnavailable = {
                            notice.pdfUrl?.let { AttachmentActions.openUrl(context, it) }
                        },
                    )
                },
                onFetchPdfFile = { notice, onReady ->
                    // Share/Save's own entry point, threaded the same way as onOpenPdf above: it
                    // needs no Context, since NoticeRepository already holds one, but is wired here
                    // rather than left for the view model to call unprompted.
                    viewModel.withAttachmentFile(notice, onReady)
                },
                onDownloadAttachment = { notice ->
                    // A tap is consent and bypasses the fetch policy -- see
                    // NoticeViewModel.downloadAttachment. Needs a Context for the same reason
                    // onOpenPdf above does, and the view model keeps none.
                    viewModel.downloadAttachment(notice, context.applicationContext)
                },
                onToggleSelection = viewModel::toggleSelection,
                onClearSelection = viewModel::clearSelection,
                onDeleteSelected = viewModel::deleteSelected,
                onOpenSettings = { viewModel.show(Screen.Settings) },
            )
        }

        Screen.Settings -> {
            BackHandler { viewModel.backToNotices() }
            SettingsScreen(
                dispensary = dispensary,
                topicChoices = topicChoices,
                testingOn = testingOn,
                activationCode = viewModel.activationCode,
                revoked = revoked,
                checking = checking,
                testingUnlocked = testingUnlocked,
                versionName = BuildConfig.VERSION_NAME,
                onTopicChange = viewModel::setTopic,
                onTestingChange = viewModel::setTesting,
                onSendTest = {
                    // Posted from here rather than the view model: it needs a Context, and the view
                    // model deliberately holds none.
                    viewModel.sendTestNotification {
                        if (!NoticeNotifications.canPost(context)) {
                            false
                        } else {
                            NoticeNotifications.post(
                                context = context,
                                title = testTitle,
                                body = testBody,
                                logId = NoticeRepository.LOCAL_LOG_ID_PREFIX + "test-" +
                                        System.currentTimeMillis(),
                            )
                            true
                        }
                    }
                },
                onUnlockTesting = viewModel::unlockTesting,
                onRecheck = viewModel::recheckActivation,
                onEnterNewCode = viewModel::enterNewCode,
                onBack = viewModel::backToNotices,
            )
        }

        is Screen.Viewer -> {
            BackHandler { viewModel.backToNotices() }
            var notice by remember(current.noticeId) { mutableStateOf<NoticeEntity?>(null) }
            LaunchedEffect(current.noticeId) { notice = viewModel.notice(current.noticeId) }
            NoticeViewerScreen(
                notice = notice,
                onBack = viewModel::backToNotices,
                shareText = notice?.let { ShareText.build(listOf(it)) }.orEmpty(),
            )
        }
    }
        }
    }
}
