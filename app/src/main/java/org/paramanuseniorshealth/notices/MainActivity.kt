package org.paramanuseniorshealth.notices

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import org.paramanuseniorshealth.notices.fcm.NotificationChannels
import org.paramanuseniorshealth.notices.ui.NotificationListScreen
import org.paramanuseniorshealth.notices.ui.theme.ParamanuNoticesTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        persistFromNotificationIntent(intent)

        setContent {
            ParamanuNoticesTheme {
                // Notification access is re-checked on every resume from inside the screen.
                NotificationListScreen()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        persistFromNotificationIntent(intent)
    }

    /**
     * Fallback for payloads that carry a `notification` block. Those are rendered by the FCM SDK
     * without ever reaching NoticeMessagingService while the app is backgrounded, so the only
     * chance to capture them is the extras attached to the launch intent when the user taps.
     *
     * The unique index on `logId` makes this safe to run alongside the service path -- whichever
     * arrives second is ignored. Send data-only messages from the backend to stop relying on this.
     */
    private fun persistFromNotificationIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        val logId = extras.getString(NotificationChannels.EXTRA_LOG_ID) ?: return
        val title = extras.getString(NotificationChannels.EXTRA_TITLE)
            ?: extras.getString("gcm.notification.title")
            ?: return
        val body = extras.getString(NotificationChannels.EXTRA_BODY)
            ?: extras.getString("gcm.notification.body")
            ?: ""

        val repository = (application as NoticesApplication).repository
        lifecycleScope.launch { repository.save(title = title, body = body, logId = logId) }
    }
}
