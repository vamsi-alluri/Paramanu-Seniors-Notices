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
 * Downloads and caches everything a notice carries.
 *
 * Three kinds, which are not alternatives:
 *  - **image** ([fetchImage]) is a picture the sender attached;
 *  - **pdf** ([fetchPdfRender] and [fetchPdf]) is a notice circular;
 *  - **link** ([fetchLinkImage]) is the logo or still for a preview card.
 *
 * They are cached under different names so one never overwrites another, and the download that
 * feeds the tray notification is the same file the in-app row reads later -- so the UI performs no
 * network I/O and a notice stays readable offline afterwards.
 *
 * THE PDF, AND WHY IT IS NOW KEPT
 *   It used to be downloaded to a scratch file, rendered, and deleted, on the reasoning that it was
 *   the largest artefact and re-downloadable from the website. That was true and still cost the
 *   user the thing they wanted: there was no PDF on the phone to open, so a notice circular could
 *   only ever be viewed as a flattened picture of its first page. The render is still made at push
 *   time, because the tray needs a bitmap within seconds; the PDF itself is fetched by [fetchPdf]
 *   when the user asks for it, and kept from then on.
 */
object NoticeImageStore {

    private const val TAG = "NoticeImageStore"
    private const val IMAGE_DIR = "notice_images"
    private const val FILE_DIR = "notice_files"

    /**
     * Where downloaded PDFs live.
     *
     * `false` puts them in filesDir, where they survive until pruned: a circular opened once is
     * then available offline for good. `true` puts them in cacheDir, which the OS may reclaim under
     * storage pressure -- the phone never fills up, but a notice can silently stop opening offline.
     *
     * One constant rather than a scattering of directory calls, so the choice is one edit and
     * cannot be made inconsistently.
     */
    private const val PDFS_IN_CACHE = false

    /**
     * The artwork budget, in bytes.
     *
     * This used to be a file count (sixty). Counting files let sixty 40KB thumbnails and three
     * multi-megabyte page renders be called the same amount of storage, which they are not -- and a
     * page-sized render of an A4 circular is the larger artefact by an order of magnitude.
     */
    private const val IMAGE_BUDGET_BYTES = 25L * 1024 * 1024

    /**
     * The PDF budget, in bytes, and deliberately expressed differently from the image one.
     *
     * Counting files would let sixty 40KB thumbnails and three multi-megabyte page renders be
     * called the same amount of storage, which they are not. Circulars are the large, rarely-reread
     * artefact; this is about nine of them at the measured 5.6MB.
     */
    private const val PDF_BUDGET_BYTES = 50L * 1024 * 1024

    /**
     * onMessageReceived allows roughly 10-20 seconds before the service may be torn down.
     *
     * The entitlement check no longer spends any of it -- verification moved off the message path
     * and does no network -- but the ceiling stays: a slow attachment must never cost the
     * notification itself, which is the part that matters.
     */
    private const val TIMEOUT_MS = 8_000L

    /**
     * A user waiting on a tap can wait longer than a push handler can.
     *
     * [fetchPdf] runs with a visible progress indicator and no service teardown behind it, so a
     * 2MB circular on a poor connection has room to finish rather than failing at eight seconds
     * and leaving the user to guess whether pressing again would help.
     */
    private const val TAP_TIMEOUT_MS = 60_000L

    /**
     * The worker's ceiling.
     *
     * [TIMEOUT_MS] was eight seconds because `onMessageReceived` may be torn down after ten or
     * twenty. A 5.6MB circular needs a sustained ~5.6 Mbps to land inside that, which an ordinary
     * mobile connection does not provide -- so the download reliably failed and nobody was told.
     * Nothing is held open behind the worker, so it can wait as long as the file honestly needs.
     */
    private const val WORKER_TIMEOUT_MS = 5L * 60 * 1000

    /** Notifications reject oversized bitmaps and the list shows a thumbnail, so decode small. */
    private const val MAX_DIMENSION = 1024

    fun directory(context: Context): File =
        File(context.filesDir, IMAGE_DIR).apply { if (!exists()) mkdirs() }

    /** Where PDFs are kept. See [PDFS_IN_CACHE]. */
    fun fileDirectory(context: Context): File =
        File(if (PDFS_IN_CACHE) context.cacheDir else context.filesDir, FILE_DIR)
            .apply { if (!exists()) mkdirs() }

    // ------------------------------------------------------------------ naming
    //
    // The extension is not decoration. ACTION_VIEW picks the viewing app from the MIME type, and a
    // share target names the saved file from its extension -- so a PNG stored as `.jpg` reaches the
    // gallery as a file it may refuse. Because the extension is therefore only known once the
    // server has answered, lookups scan for the prefix rather than assuming a name.

    private fun stem(logId: String, kind: String) = "$logId-$kind"

    private fun find(dir: File, stem: String): File? =
        dir.listFiles { file -> file.name.startsWith("$stem.") }?.firstOrNull()

    fun cachedImage(context: Context, logId: String?): File? =
        logId?.let { find(directory(context), stem(it, "img")) }

    fun cachedPdfRender(context: Context, logId: String?): File? =
        logId?.let { find(directory(context), stem(it, "pdf")) }

    fun cachedLinkImage(context: Context, logId: String?): File? =
        logId?.let { find(directory(context), stem(it, "link")) }

    /** The downloaded circular itself, if it has been fetched. */
    fun cachedPdf(context: Context, logId: String?): File? =
        logId?.let { find(fileDirectory(context), stem(it, "doc")) }

    // ------------------------------------------------------------------ fetching

    /**
     * Fetches [url] into the cache and returns the decoded bitmap, or null on any failure --
     * callers fall back to a text-only notification, never to silence.
     */
    suspend fun fetchImage(context: Context, url: String, logId: String): Bitmap? =
        fetchPicture(context, url, logId, "img")

    /** The logo or still for a link preview card. Same path, different name on disk. */
    suspend fun fetchLinkImage(context: Context, url: String, logId: String): Bitmap? =
        fetchPicture(context, url, logId, "link")

    private suspend fun fetchPicture(
        context: Context,
        url: String,
        logId: String,
        kind: String,
    ): Bitmap? = withTimeoutOrNull(TIMEOUT_MS) {
        withContext(Dispatchers.IO) {
            runCatching {
                val scratch = File(context.cacheDir, "notice_$logId-$kind.part")
                val contentType = download(url, scratch)

                // Checked before the file is kept, not after it fails to decode. An ICO favicon
                // downloads perfectly and then renders as nothing at all, which on a phone looks
                // like the app losing the picture rather than like a format it cannot read.
                if (!MimeTypes.isDecodableImage(contentType)) {
                    scratch.delete()
                    error("$url is ${MimeTypes.normalise(contentType)}, which cannot be decoded")
                }

                val target = File(
                    directory(context),
                    "${stem(logId, kind)}.${MimeTypes.imageExtension(contentType, url)}",
                )
                find(directory(context), stem(logId, kind))?.takeIf { it != target }?.delete()
                moveInto(scratch, target)

                prune(context)
                decodeDownsampled(target)
            }.onFailure { Log.w(TAG, "Image fetch failed for $url", it) }
                .getOrNull()
        }
    }

    /**
     * Downloads [pdfUrl], renders its first page, caches the render, and returns the bitmap.
     *
     * The PDF is fetched to a scratch file and deleted afterwards: at push time only the render is
     * needed, and holding a 2MB download inside the service's budget for a file the user may never
     * open would be paying the cost early for everyone to benefit nobody. [fetchPdf] keeps it when
     * they do open it.
     *
     * Returns null for anything unreachable, not actually a PDF, or password protected.
     */
    suspend fun fetchPdfRender(context: Context, pdfUrl: String, logId: String): Bitmap? =
        withTimeoutOrNull(TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val scratch = File(context.cacheDir, "notice_$logId.pdf")
                runCatching {
                    download(pdfUrl, scratch)
                    val rendered = PdfPageRenderer.renderFirstPage(scratch)
                        ?: error("Could not render $pdfUrl")

                    val target = File(directory(context), "${stem(logId, "pdf")}.jpg")
                    writeJpeg(rendered, target)

                    // Released before returning, and read back at a smaller size. The render is now
                    // page-sized so the viewer has something to zoom into -- an A4 page at 1600px is
                    // about 14MB as ARGB_8888 -- and handing that to the notification path would
                    // hold it live inside a service that is already the most memory-constrained
                    // place this app runs. The file on disk is what the viewer opens later.
                    rendered.recycle()
                    prune(context)
                    decodeDownsampled(target)
                }.onFailure { Log.w(TAG, "Notice PDF failed for $pdfUrl", it) }
                    .also { runCatching { scratch.delete() } }
                    .getOrNull()
            }
        }

    /**
     * Downloads the circular itself and keeps it, for handing to a PDF viewer.
     *
     * Returns the cached copy immediately when there is one, so a second tap opens instantly. On
     * failure returns null and the caller falls back to opening the URL in a browser -- which needs
     * a connection, but so did this.
     */
    suspend fun fetchPdf(context: Context, pdfUrl: String, logId: String): File? {
        cachedPdf(context, logId)?.takeIf { it.length() > 0 }?.let { return it }

        return withTimeoutOrNull(TAP_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(fileDirectory(context), "${stem(logId, "doc")}.pdf")
                    val scratch = File(context.cacheDir, "notice_$logId-doc.part")

                    // Downloaded to a scratch name and moved into place, so an interrupted download
                    // cannot leave a truncated file that looks cached and opens to an error.
                    download(pdfUrl, scratch)
                    if (!PdfPageRenderer.isReadablePdf(scratch)) {
                        scratch.delete()
                        error("$pdfUrl is not a readable PDF")
                    }
                    target.delete()
                    moveInto(scratch, target)

                    prunePdfs(context)
                    target
                }.onFailure { Log.w(TAG, "PDF download failed for $pdfUrl", it) }
                    .getOrNull()
            }
        }
    }

    /** What one circular fetch produced: the document, its first page, and its measurements. */
    data class PdfFetch(val pdf: File, val render: File, val pages: Int, val bytes: Long)

    /**
     * Downloads a circular, renders page one, and **keeps both**.
     *
     * This used to download to a scratch file and delete it, on the reasoning that holding a large
     * download for a file the user may never open paid the cost early for everyone to benefit
     * nobody. The reasoning was about the wrong cost. The download happens either way -- page one
     * cannot be rendered without the whole document, because `PdfRenderer` takes a descriptor on a
     * complete file -- so deleting it saved no bandwidth at all and guaranteed a second download
     * later. It saved disk, which [prunePdfs] already manages.
     *
     * Runs under [WORKER_TIMEOUT_MS], not the old eight seconds: nothing is holding a service open
     * behind this any more.
     *
     * **What the timeout does and does not bound.** [download] is a synchronous
     * `HttpURLConnection` read with no suspension points, so [withTimeoutOrNull] cannot interrupt
     * it mid-read -- it only stops *waiting* for it. A stalled trickle can keep this coroutine's
     * underlying thread running well past [WORKER_TIMEOUT_MS], because each individual `read()`
     * only has to beat the connection's own 15s `readTimeout` to keep going, and it can do that
     * indefinitely. So a timed-out caller may still see the permanent PDF and render appear on
     * disk afterwards, written by a download it had already given up on. This is inherited, not
     * introduced here: [fetchPdfRender] and [fetchPdf] have had the identical shape all along. It
     * is also harmless -- the worker records FAILED for the timeout, the late write lands a valid
     * pdf+render pair, and the next sweep finds [cachedPdfRender] non-null, skips the fetch, and
     * records FETCHED. This function's scratch file is named distinctly from [fetchPdf]'s so that
     * late write cannot collide with a concurrent tap-path download for the same notice.
     */
    suspend fun fetchPdfKeeping(context: Context, pdfUrl: String, logId: String): PdfFetch? =
        withTimeoutOrNull(WORKER_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                // Named differently from fetchPdf's "notice_$logId-doc.part": the worker's much
                // wider timeout (5 minutes vs. fetchPdf's 60 seconds) makes it plausible for both
                // to be mid-download for the same logId at once, and two HttpURLConnections
                // writing to and renaming the same scratch file would corrupt each other. Keep
                // this name distinct rather than folding it back into a shared constant.
                val scratch = File(context.cacheDir, "notice_$logId-doc-worker.part")
                runCatching {
                    download(pdfUrl, scratch)
                    // pageCount and isReadablePdf open the file identically (same guards, same
                    // PdfRenderer, same >= 1 check) -- pageCount's non-null result already proves
                    // the file is readable, so a separate isReadablePdf call would be a third full
                    // PdfRenderer open over what can be a 192-page document for information the
                    // next line already has.
                    val pages = PdfPageRenderer.pageCount(scratch)
                        ?: error("$pdfUrl is not a readable PDF")
                    val bytes = scratch.length()

                    val rendered = PdfPageRenderer.renderFirstPage(scratch)
                        ?: error("Could not render $pdfUrl")
                    val render = File(directory(context), "${stem(logId, "pdf")}.jpg")
                    writeJpeg(rendered, render)
                    rendered.recycle()

                    // Moved into place only after the render succeeded, so a file that cannot be
                    // shown is never left sitting in the cache looking like a usable circular.
                    val pdf = File(fileDirectory(context), "${stem(logId, "doc")}.pdf")
                    pdf.delete()
                    moveInto(scratch, pdf)

                    prune(context)
                    prunePdfs(context)
                    PdfFetch(pdf, render, pages, bytes)
                }.onFailure {
                    Log.w(TAG, "Notice PDF failed for $pdfUrl", it)
                    runCatching { scratch.delete() }
                }.getOrNull()
            }
        }

    /** Returns the response's Content-Type, which is the only honest source for the extension. */
    private fun download(url: String, target: File): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("HTTP ${connection.responseCode} for $url")
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return connection.contentType
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Moves [scratch] onto [target], copying if a rename is refused.
     *
     * cacheDir and filesDir sit on the same partition on every Android device this runs on, so the
     * rename is expected to succeed and the copy is insurance rather than the normal path. It is
     * here because the failure it guards against is silent: a false from renameTo would otherwise
     * discard a download that had already completely succeeded.
     */
    private fun moveInto(scratch: File, target: File) {
        if (scratch.renameTo(target)) return
        scratch.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        scratch.delete()
    }

    private fun writeJpeg(bitmap: Bitmap, target: File) {
        target.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
    }

    /**
     * Decodes [file] at a size fit for the screen.
     *
     * It no longer sizes for the notification tray: that is [TrayArtwork]'s job now, and doing it
     * here meant the in-app viewer was handed a bitmap shrunk to a notification's budget -- which
     * is why an A4 page could not be zoomed into. This returns something the viewer can use, and
     * the tray narrows it further.
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

    // ------------------------------------------------------------------ housekeeping

    fun prune(context: Context) = pruneTo(directory(context), IMAGE_BUDGET_BYTES)

    fun prunePdfs(context: Context) = pruneTo(fileDirectory(context), PDF_BUDGET_BYTES)

    /**
     * Trims [dir] to [budgetBytes], oldest first.
     *
     * The newest file is always kept, even if it alone is over budget: it is the notice that has
     * just arrived, and deleting it would mean the user never sees the attachment they were
     * notified about. An oversized file is skipped rather than ending the scan, so a smaller older
     * file that still fits is kept. One shape for both directories, because "how much storage may
     * this use" is the same question whether the files are thumbnails or circulars.
     */
    fun pruneTo(dir: File, budgetBytes: Long) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var used = 0L
        files.forEachIndexed { index, file ->
            val projected = used + file.length()
            if (index > 0 && projected > budgetBytes) {
                file.delete()
            } else {
                used = projected
            }
        }
    }

    fun delete(context: Context, logIds: Collection<String>) {
        logIds.forEach { logId ->
            listOf("img", "pdf", "link").forEach { kind ->
                runCatching { find(directory(context), stem(logId, kind))?.delete() }
            }
            runCatching { cachedPdf(context, logId)?.delete() }
        }
    }

    fun clear(context: Context) {
        directory(context).listFiles()?.forEach { it.delete() }
        fileDirectory(context).listFiles()?.forEach { it.delete() }
    }
}
