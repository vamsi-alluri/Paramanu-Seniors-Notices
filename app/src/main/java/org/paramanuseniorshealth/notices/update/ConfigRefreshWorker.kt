package org.paramanuseniorshealth.notices.update

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.paramanuseniorshealth.notices.NoticesApplication
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Fetches Remote Config after a `configStale` push, so a kill or a version floor reaches a phone
 * nobody is opening -- and stops its notifications -- in minutes rather than at its next six-hourly
 * fetch, which only an open would trigger anyway.
 *
 * Every install receives the push at once, so the start is spread across a quarter of an hour, as
 * [org.paramanuseniorshealth.notices.activation.ActivationRefreshWorker] spreads its own.
 */
class ConfigRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as NoticesApplication
        return try {
            app.syncRemoteConfig()
            // The fetch failed if the mark is still there; Remote Config said so in the log.
            if (app.remoteConfigStale) Result.retry() else Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Config refresh failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ConfigRefresh"
        private const val NAME = "config-refresh"
        private const val MAX_JITTER_SECONDS = 15L * 60

        private val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** KEEP, so two publishes close together queue one fetch, which takes the later of them. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ConfigRefreshWorker>()
                .setConstraints(constraints)
                .setInitialDelay(Random.nextLong(0, MAX_JITTER_SECONDS), TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
