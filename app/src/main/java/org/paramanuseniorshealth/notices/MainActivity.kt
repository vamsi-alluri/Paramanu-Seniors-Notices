package org.paramanuseniorshealth.notices

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
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
import org.paramanuseniorshealth.notices.fcm.NoticeNotifications
import org.paramanuseniorshealth.notices.ui.ActivationScreen
import org.paramanuseniorshealth.notices.ui.NoticeListScreen
import org.paramanuseniorshealth.notices.ui.NoticeViewModel
import org.paramanuseniorshealth.notices.ui.NoticeViewerScreen
import org.paramanuseniorshealth.notices.ui.Screen
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
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val redeeming by viewModel.redeeming.collectAsStateWithLifecycle()
    val redeemError by viewModel.redeemError.collectAsStateWithLifecycle()
    val expandedId by viewModel.expandedId.collectAsStateWithLifecycle()
    val highlightId by viewModel.highlightId.collectAsStateWithLifecycle()
    val officeInfo by viewModel.officeInfo.collectAsStateWithLifecycle()
    val testingUnlocked by viewModel.testingUnlocked.collectAsStateWithLifecycle()

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

    // The permission prompt is deliberately not raised on the code screen: asking before the user
    // has any reason to expect notices is how a denial happens, and on Android 13+ a denial
    // silently disables the whole app for someone who will not go looking in system settings.
    LaunchedEffect(screen) {
        if (screen is Screen.Notices && !access.isEnabled) access.request()
    }

    when (val current = screen) {
        Screen.Activation -> ActivationScreen(
            onSubmit = viewModel::redeem,
            busy = redeeming,
            error = redeemError,
            onErrorDismissed = viewModel::dismissRedeemError,
        )

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
                onToggleSelection = viewModel::toggleSelection,
                onClearSelection = viewModel::clearSelection,
                onDeleteSelected = viewModel::deleteSelected,
                onOpenSettings = { viewModel.show(Screen.Settings) },
            )
        }

        Screen.Settings -> {
            BackHandler { viewModel.backToNotices() }
            SettingsScreen(
                subscriptions = subscriptions,
                activationCode = viewModel.activationCode,
                testingUnlocked = testingUnlocked,
                versionName = BuildConfig.VERSION_NAME,
                onSubscriptionChange = viewModel::setSubscribed,
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
                                logId = "local-test-" + System.currentTimeMillis(),
                            )
                            true
                        }
                    }
                },
                onUnlockTesting = viewModel::unlockTesting,
                onReset = viewModel::reset,
                onBack = viewModel::backToNotices,
            )
        }

        is Screen.Viewer -> {
            BackHandler { viewModel.backToNotices() }
            var notice by remember(current.noticeId) { mutableStateOf<NoticeEntity?>(null) }
            LaunchedEffect(current.noticeId) { notice = viewModel.notice(current.noticeId) }
            NoticeViewerScreen(notice = notice, onBack = viewModel::backToNotices)
        }
    }
}
