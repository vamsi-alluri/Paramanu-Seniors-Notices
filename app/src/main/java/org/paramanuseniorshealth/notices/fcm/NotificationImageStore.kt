package org.paramanuseniorshealth.notices.fcm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and caches notification images in app-private storage.
 *
 * The service has to fetch the image anyway to render BigPictureStyle, so that same download is the
 * cache -- the list reads these files and never touches the network.
 *
 * Paths are derived from `logId` rather than stored in Room, which makes file existence the single
 * source of truth for "is this cached" and removes the post-download database write entirely.
 */
object NotificationImageStore {

    private const val TAG = "NotifImageStore"
    private const val DIR = "notif_images"
    private const val KEEP_NEWEST = 10

    /**
     * FCM allows roughly 10-20s inside onMessageReceived before the service may be torn down.
     * Overrunning costs the whole notification, not just the picture, so the fetch is capped well
     * inside that budget.
     */
    private const val TIMEOUT_MS = 8_000L
    private const val MAX_DIMENSION = 1024

    fun directory(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    fun fileFor(context: Context, logId: String): File =
        File(directory(context), "$logId.jpg")

    /** True when a cached image is already on disk for this message. */
    fun isCached(context: Context, logId: String?): Boolean =
        logId != null && fileFor(context, logId).exists()

    /**
     * Fetches [url] into the cache. Returns the decoded bitmap for immediate use in the tray
     * notification, or null on any failure -- callers fall back to a text-only notification.
     */
    suspend fun fetch(context: Context, url: String, logId: String): Bitmap? =
        withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val target = fileFor(context, logId)
                    download(url, target)
                    prune(context)
                    decodeDownsampled(target)
                }.onFailure { Log.w(TAG, "Image fetch failed for $url", it) }
                    .getOrNull()
            }
        }

    private fun download(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("HTTP ${connection.responseCode} for $url")
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Notifications reject oversized bitmaps and the list only ever shows a thumbnail or a
     * screen-width image, so full-resolution screenshots are downsampled on decode.
     */
    fun decodeDownsampled(file: File): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    /** Keeps the [KEEP_NEWEST] most recently written images and deletes the rest. */
    fun prune(context: Context, keep: Int = KEEP_NEWEST) = prune(directory(context), keep)

    /** Directory-level overload, kept free of Context so it is unit-testable on the JVM. */
    fun prune(dir: File, keep: Int = KEEP_NEWEST) {
        val files = dir.listFiles()?.takeIf { it.size > keep } ?: return
        files.sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { it.delete() }
    }

    /**
     * Removes the cached images for specific messages. Failures are swallowed: the row is going
     * away regardless, and a leftover file is collected by [prune] soon enough.
     */
    fun delete(context: Context, logIds: Collection<String>) {
        logIds.forEach { logId -> runCatching { fileFor(context, logId).delete() } }
    }

    fun clear(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }
}
