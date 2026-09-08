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
 * Downloads and caches the artwork for a notice.
 *
 * Two kinds, which are not alternatives:
 *  - **image** ([fetchImage]) is a picture the sender attached, stored downsampled, as in Notifier;
 *  - **pdf** ([fetchPdfRender]) is page one of a notice PDF, rendered here on the device.
 *
 * A notice can carry both. They are cached under different names so one never overwrites the other,
 * and the download that feeds the tray notification is the same file the in-app row reads later --
 * so the UI performs no network I/O and a notice stays readable offline afterwards.
 *
 * The PDF itself is deliberately not kept: it is the largest artefact, it is re-downloadable from
 * the website, and on the phones this app targets storage is usually the scarce resource.
 */
object NoticeImageStore {

    private const val TAG = "NoticeImageStore"
    private const val IMAGE_DIR = "notice_images"

    /** Twenty notices' worth of artwork. Each notice may contribute two files, so this is ~40. */
    private const val KEEP_NEWEST = 40

    /**
     * onMessageReceived allows roughly 10-20 seconds before the service may be torn down, and the
     * entitlement check has already spent part of that. Overrunning costs the whole notification,
     * not just the picture, so each fetch is capped well inside what remains.
     */
    private const val TIMEOUT_MS = 8_000L

    /**
     * Notifications reject oversized bitmaps and the list only ever shows a thumbnail or a
     * screen-width image, so full-resolution photographs are downsampled on decode.
     */
    private const val MAX_DIMENSION = 1024

    fun directory(context: Context): File =
        File(context.filesDir, IMAGE_DIR).apply { if (!exists()) mkdirs() }

    /** The sender's own picture. */
    fun imageFile(context: Context, logId: String): File =
        File(directory(context), "$logId-img.jpg")

    /** Page one of the notice PDF, rendered. */
    fun pdfFile(context: Context, logId: String): File =
        File(directory(context), "$logId-pdf.jpg")

    fun cachedImage(context: Context, logId: String?): File? =
        logId?.let { imageFile(context, it) }?.takeIf { it.exists() }

    fun cachedPdfRender(context: Context, logId: String?): File? =
        logId?.let { pdfFile(context, it) }?.takeIf { it.exists() }

    /**
     * Fetches [url] into the cache and returns the decoded bitmap for the tray notification, or
     * null on any failure -- callers fall back to a text-only notification, never to silence.
     */
    suspend fun fetchImage(context: Context, url: String, logId: String): Bitmap? =
        withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val target = imageFile(context, logId)
                    download(url, target)
                    prune(context)
                    decodeDownsampled(target)
                }.onFailure { Log.w(TAG, "Image fetch failed for $url", it) }
                    .getOrNull()
            }
        }

    /**
     * Downloads [pdfUrl], renders its first page, caches the render, and returns a bitmap sized for
     * the notification tray.
     *
     * Returns null for anything unreachable, not actually a PDF, or password protected, which
     * PdfRenderer refuses outright.
     */
    suspend fun fetchPdfRender(context: Context, pdfUrl: String, logId: String): Bitmap? =
        withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                // Scratch file in cacheDir, not filesDir: if the process dies mid-render the OS is
                // free to reclaim it, and nothing here is worth keeping.
                val scratch = File(context.cacheDir, "notice_$logId.pdf")
                runCatching {
                    download(pdfUrl, scratch)
                    val rendered = PdfPageRenderer.renderFirstPage(scratch)
                        ?: error("Could not render $pdfUrl")
                    writeJpeg(rendered, pdfFile(context, logId))
                    prune(context)
                    PdfPageRenderer.toNotificationBitmap(rendered)
                }.onFailure { Log.w(TAG, "Notice PDF failed for $pdfUrl", it) }
                    .also { runCatching { scratch.delete() } }
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

    private fun writeJpeg(bitmap: Bitmap, target: File) {
        target.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
    }

    fun decodeDownsampled(file: File): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        var sample = 1
        while (bounds.outWidth / sample > MAX_DIMENSION || bounds.outHeight / sample > MAX_DIMENSION) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        // A notification crosses a Binder transaction capped near 1MB, and ARGB_8888 costs four
        // bytes a pixel -- so 1024x768 alone is 3MB and throws TransactionTooLargeException, losing
        // the whole notification rather than just the picture. This is the bug the forked app still
        // carries; sizing here is what stops it.
        return PdfPageRenderer.toNotificationBitmap(decoded)
    }

    /** Keeps the [KEEP_NEWEST] most recently written files and deletes the rest. */
    fun prune(context: Context, keep: Int = KEEP_NEWEST) = prune(directory(context), keep)

    /** Directory-level overload, kept free of Context so it is unit-testable on the JVM. */
    fun prune(dir: File, keep: Int = KEEP_NEWEST) {
        val files = dir.listFiles()?.takeIf { it.size > keep } ?: return
        files.sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { it.delete() }
    }

    fun delete(context: Context, logIds: Collection<String>) {
        logIds.forEach { logId ->
            runCatching { imageFile(context, logId).delete() }
            runCatching { pdfFile(context, logId).delete() }
        }
    }

    fun clear(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
    }
}
