package org.paramanuseniorshealth.notices.update

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Asks Play, on every foreground, whether an update is available, and raises Play's own prompt as
 * hard as the release's priority says ([UpdatePrompt]).
 *
 * Play's sheets carry the words, so the app adds none. A flexible update downloads while the app is
 * in use and is installed when the app next goes to the background, which Play does silently --
 * so nobody is shown a restart.
 *
 * Only an install from Play gets an answer. A debug or sideloaded build fails the request, which is
 * logged and otherwise ignored.
 *
 * Construct in `onCreate`: it registers an activity result, which must happen before the activity
 * starts.
 */
class PlayUpdatePrompt(private val activity: ComponentActivity) : DefaultLifecycleObserver {

    private val manager = AppUpdateManagerFactory.create(activity)
    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The version the open prompt is for, so a cancel can be recorded against it. */
    private var offeredVersion = 0L
    private var downloaded = false

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        // Recorded for every priority, but only 0-2 consults it: 3 and up ask again regardless.
        if (result.resultCode == Activity.RESULT_CANCELED && offeredVersion > 0) {
            prefs.edit { putLong(KEY_DISMISSED, offeredVersion) }
        }
    }

    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) downloaded = true
    }

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        manager.registerListener(installListener)
    }

    override fun onResume(owner: LifecycleOwner) {
        manager.appUpdateInfo
            .addOnSuccessListener(::act)
            .addOnFailureListener { Log.i(TAG, "No update info: ${it.message}") }
    }

    /**
     * Installs a finished flexible download. In the background Play does it without a word; in the
     * foreground it would restart the app under the reader, which is why it waits for this.
     */
    override fun onStop(owner: LifecycleOwner) {
        if (downloaded && !activity.isChangingConfigurations) manager.completeUpdate()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        manager.unregisterListener(installListener)
    }

    private fun act(info: AppUpdateInfo) {
        if (info.installStatus() == InstallStatus.DOWNLOADED) {
            downloaded = true
            return
        }
        // An immediate update the user left part-way is resumed, as Play asks of every app that
        // starts one.
        if (info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
            start(info, AppUpdateType.IMMEDIATE)
            return
        }
        if (info.updateAvailability() != UpdateAvailability.UPDATE_AVAILABLE) return

        val now = System.currentTimeMillis()
        val kind = UpdatePrompt.decide(
            priority = info.updatePriority(),
            availableVersion = info.availableVersionCode().toLong(),
            flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
            immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
            dismissedVersion = prefs.getLong(KEY_DISMISSED, 0L),
            lastPromptAt = prefs.getLong(KEY_LAST_PROMPT_AT, 0L),
            now = now,
        )
        val type = when (kind) {
            UpdatePrompt.Kind.NONE -> return
            UpdatePrompt.Kind.FLEXIBLE -> AppUpdateType.FLEXIBLE
            UpdatePrompt.Kind.IMMEDIATE -> AppUpdateType.IMMEDIATE
        }
        prefs.edit { putLong(KEY_LAST_PROMPT_AT, now) }
        offeredVersion = info.availableVersionCode().toLong()
        start(info, type)
    }

    private fun start(info: AppUpdateInfo, type: Int) {
        try {
            manager.startUpdateFlowForResult(info, launcher, AppUpdateOptions.defaultOptions(type))
        } catch (e: Exception) {
            // The info went stale, or Play refused the type. Asked again on the next foreground.
            Log.i(TAG, "Could not start the update flow: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "PlayUpdatePrompt"
        const val PREFS = "update_prompt"
        const val KEY_DISMISSED = "dismissed_version"
        const val KEY_LAST_PROMPT_AT = "last_prompt_at"
    }
}
