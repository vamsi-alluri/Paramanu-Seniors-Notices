package org.paramanuseniorshealth.notices

import android.app.Application
import android.util.Log
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import org.paramanuseniorshealth.notices.data.NotificationRepository
import org.paramanuseniorshealth.notices.data.NoticesDatabase
import org.paramanuseniorshealth.notices.fcm.NotificationChannels
import com.google.firebase.messaging.FirebaseMessaging

class NoticesApplication : Application(), SingletonImageLoader.Factory {

    /**
     * Hand-rolled container. The graph is one database -> one DAO -> one repository, shared by the
     * Activity and the FCM service; Hilt would add a KSP round and an extra Gradle plugin without
     * removing any wiring. See README notes for the Hilt migration if the graph grows.
     */
    val repository: NotificationRepository by lazy {
        NotificationRepository(NoticesDatabase.getInstance(this).notificationDao(), this)
    }

    /**
     * Caps Coil's disk cache, which the list relies on once it falls back to fetching images by URL
     * for alerts whose local copy has been pruned.
     *
     * Coil's default is a share of free space up to roughly 250MB. That would quietly replace a
     * deliberately bounded ten-image store with a very large one, on a device where this app is a
     * background utility. Coil keys its cache by URL, so alerts sharing an image share one entry.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(IMAGE_CACHE_BYTES)
                    .build()
            }
            .build()

    override fun onCreate() {
        super.onCreate()

        // Must exist before the first notification is posted, and the FCM service can start
        // without an Activity ever having run, so create it here.
        NotificationChannels.create(this)

        subscribeToTopic()
    }

    private fun subscribeToTopic() {
        // Idempotent and locally persisted: the SDK retries on its own if there is no network at
        // launch, so calling it on every start is cheap and self-healing.
        FirebaseMessaging.getInstance().subscribeToTopic(TOPIC)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Subscribed to topic '$TOPIC'")
                } else {
                    Log.w(TAG, "Failed to subscribe to topic '$TOPIC'", task.exception)
                }
            }
    }

    companion object {
        private const val TAG = "NoticesApplication"
        private const val IMAGE_CACHE_BYTES = 50L * 1024 * 1024
        const val TOPIC = "all"
    }
}
