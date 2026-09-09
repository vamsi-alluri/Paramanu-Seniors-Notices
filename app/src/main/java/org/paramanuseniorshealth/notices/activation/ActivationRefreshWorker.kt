package org.paramanuseniorshealth.notices.activation

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.database.FirebaseDatabase
import org.paramanuseniorshealth.notices.NoticesApplication
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Refreshes the entitlement state away from the message path.
 *
 * Every arriving notice used to call `verify()`, so a broadcast to four hundred phones opened
 * roughly four hundred Realtime Database websockets inside a few seconds, against a Spark cap of
 * 100 simultaneous connections. Over the cap the connection is refused, `verify()` returns Unknown,
 * the notice is shown anyway -- and the gate quietly stopped working during the one event it exists
 * for. See docs/decisions.md.
 *
 * Because this no longer runs inside `onMessageReceived`, it is not bounded by FCM's ~10 second
 * window, so the start is spread across a quarter of an hour rather than seconds.
 */
class ActivationRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as NoticesApplication
        val activation = app.activationRepository

        // A device that has reset has nothing to check, and the periodic request outlives the
        // claim that scheduled it.
        if (!activation.isActivated) return Result.success()

        return try {
            when (activation.verify()) {
                ActivationState.Revoked -> {
                    activation.suspendClaim()
                    app.announceRevocation()
                    Result.success()
                }

                ActivationState.Active -> {
                    // This branch is the only thing that ever notices a *restore*. A revoked device
                    // has unsubscribed from every topic, so no broadcast can reach it, and the
                    // person it belongs to may never open the app again.
                    if (activation.isRevoked) {
                        activation.resumeClaim()
                        app.repository.clearRevokedNotice()
                    } else {
                        activation.recordVerification(ActivationState.Active)
                    }
                    Result.success()
                }

                ActivationState.NotActivated -> Result.success()

                // Offline, or the connection was refused. Retry with WorkManager's default
                // exponential backoff; an inconclusive answer costs nothing, because the gate
                // permits delivery when it does not know.
                ActivationState.Unknown -> Result.retry()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed", e)
            Result.retry()
        } finally {
            // The SDK otherwise holds the socket open for about a minute of idleness, occupying a
            // connection slot long after the answer has arrived.
            runCatching { FirebaseDatabase.getInstance().goOffline() }
                .onFailure { Log.w(TAG, "goOffline failed", it) }
        }
    }

    companion object {
        private const val TAG = "ActivationRefresh"
        private const val ONE_TIME = "activation-refresh"
        private const val PERIODIC = "activation-refresh-daily"
        private const val MAX_JITTER_SECONDS = 15L * 60

        private val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        private fun jitter(): Long = Random.nextLong(0, MAX_JITTER_SECONDS)

        /**
         * Enqueued from the message path when the stored answer has aged out.
         *
         * KEEP, so a burst of notices does not queue several. The jitter is what keeps four hundred
         * phones from reconnecting together after a broadcast, which is the whole problem this
         * design exists to remove.
         */
        fun enqueueIfStale(context: Context) {
            val app = context.applicationContext as NoticesApplication
            val activation = app.activationRepository
            if (!activation.isActivated) return
            if (!ActivationFreshness.isStale(activation.lastVerifiedAt, System.currentTimeMillis())) return

            val request = OneTimeWorkRequestBuilder<ActivationRefreshWorker>()
                .setConstraints(constraints)
                .setInitialDelay(jitter(), TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * The daily fallback, and the only route by which a restore is ever discovered.
         *
         * A revoked device has unsubscribed from every topic, so nothing arrives to trigger the
         * one-time path above; and someone in their eighties who has been told to ring the helpdesk
         * may not open the app again. Without this, a code the NGO has restored stays dark
         * indefinitely.
         *
         * KEEP so an existing schedule is not restarted on every launch, which would push the next
         * run a day further out each time the app was opened.
         */
        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ActivationRefreshWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(jitter(), TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
