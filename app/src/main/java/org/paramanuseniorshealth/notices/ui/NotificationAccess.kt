package org.paramanuseniorshealth.notices.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Whether the app can actually deliver a push to the user.
 *
 * [Blocked] is deliberately distinct from [NeedsPermission]: once POST_NOTIFICATIONS is permanently
 * denied, `launcher.launch()` returns without ever drawing a dialog, so the only remaining route is
 * the system settings screen. Treating both states the same is why a "request permission" call can
 * appear to do nothing at all.
 */
enum class NotificationAccess {
    /** Permission granted and notifications are switched on. */
    Enabled,

    /** Not granted, but the system dialog can still be shown. */
    NeedsPermission,

    /** The system dialog will not appear again — the user must change this in Settings. */
    Blocked,
}

class NotificationAccessState internal constructor(
    val access: NotificationAccess,
    private val onRequest: () -> Unit,
) {
    val isEnabled: Boolean get() = access == NotificationAccess.Enabled

    fun request() = onRequest()
}

/**
 * Re-evaluates notification access on every resume, not just on first composition, so returning
 * from Settings or from a background stint reflects the current state. This app is useless without
 * notifications, so the check runs every time the app is opened.
 */
@Composable
fun rememberNotificationAccessState(): NotificationAccessState {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val prefs = remember(context) {
        context.getSharedPreferences("notification_access", Context.MODE_PRIVATE)
    }

    var access by remember { mutableStateOf(readAccess(context, activity, prefs)) }
    // Guards against re-prompting in a loop if the user dismisses the dialog by tapping outside,
    // which leaves the state as NeedsPermission and immediately triggers another resume.
    var promptedThisSession by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        // Record that the dialog has now been shown. Before the first request and after a permanent
        // denial, shouldShowRequestPermissionRationale() is false in both cases -- this flag is what
        // tells those two situations apart on the next launch.
        prefs.edit().putBoolean(KEY_REQUESTED, true).apply()
        access = readAccess(context, activity, prefs)
    }

    fun request() {
        when (readAccess(context, activity, prefs)) {
            NotificationAccess.NeedsPermission -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            NotificationAccess.Blocked -> context.openNotificationSettings()
            NotificationAccess.Enabled -> Unit
        }
    }

    LifecycleResumeEffect(Unit) {
        access = readAccess(context, activity, prefs)
        if (access == NotificationAccess.NeedsPermission && !promptedThisSession) {
            promptedThisSession = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onPauseOrDispose { promptedThisSession = false }
    }

    return NotificationAccessState(access = access, onRequest = ::request)
}

private const val KEY_REQUESTED = "post_notifications_requested"

private fun readAccess(
    context: Context,
    activity: Activity?,
    prefs: android.content.SharedPreferences,
): NotificationAccess {
    val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED

    if (granted) {
        // Granted is not sufficient: the user can still switch notifications off for the whole app
        // from Settings, which leaves the permission intact but silently drops every message.
        return if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationAccess.Enabled
        } else {
            NotificationAccess.Blocked
        }
    }

    val canAskAgain = activity == null ||
        !prefs.getBoolean(KEY_REQUESTED, false) ||
        ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.POST_NOTIFICATIONS,
        )

    return if (canAskAgain) NotificationAccess.NeedsPermission else NotificationAccess.Blocked
}

private fun Context.openNotificationSettings() {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
