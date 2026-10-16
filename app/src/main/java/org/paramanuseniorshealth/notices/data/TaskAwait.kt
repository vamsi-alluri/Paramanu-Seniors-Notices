package org.paramanuseniorshealth.notices.data

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Bridges a Play Services [Task] into a coroutine without pulling in
 * `kotlinx-coroutines-play-services` for the handful of call sites that need it. It is shared by
 * activation and the analytics kill-switch read, so the bridge has one definition to keep correct.
 */
internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            @Suppress("UNCHECKED_CAST")
            continuation.resume(task.result as T)
        } else {
            continuation.resumeWithException(
                task.exception ?: IllegalStateException("Task failed without an exception")
            )
        }
    }
}
