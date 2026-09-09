package org.paramanuseniorshealth.notices.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import org.paramanuseniorshealth.notices.fcm.MimeTypes
import java.io.File

/**
 * Hands a cached attachment to whichever app the user already has for it.
 *
 * The alternative -- building a PDF reader and an image viewer into a notices app -- would be a lot
 * of code to arrive somewhere worse than the reader the user already knows. The app keeps its own
 * viewer for a quick look; this is the door out to Drive, the gallery, WhatsApp, and Files.
 *
 * Every path here is a `content://` URI from the app's FileProvider, never a `file://` path.
 * Handing out a file path has been an immediate FileUriExposedException since Android 7, and this
 * app's minSdk is 26.
 *
 * Nothing here can throw at the caller. A phone with no PDF reader, no gallery and no share target
 * is unusual but real, and losing the notice over a missing viewer would be a poor trade.
 */
object AttachmentActions {

    private const val TAG = "AttachmentActions"

    /** Matches the authority declared in the manifest. */
    private fun authority(context: Context) = "${context.packageName}.images"

    fun uriFor(context: Context, file: File): Uri? = runCatching {
        FileProvider.getUriForFile(context, authority(context), file)
    }.onFailure {
        // Thrown when the file sits outside every path in file_paths.xml -- a configuration error,
        // not a runtime condition, so it is worth a log rather than a silent no-op.
        Log.w(TAG, "No FileProvider path covers ${file.absolutePath}", it)
    }.getOrNull()

    /** The MIME type for a stored file, from its extension. */
    fun mimeOf(file: File): String = MimeTypes.forExtension(file.extension)

    /**
     * Opens [file] in another app.
     *
     * Returns false when nothing on the phone handles the type, so the caller can fall back to
     * [openUrl] -- which reaches a browser, and from there Drive's own viewer. That fallback needs
     * a connection, but it is better than a tap that does nothing.
     */
    fun open(context: Context, file: File): Boolean {
        val uri = uriFor(context, file) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeOf(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.i(TAG, "No app handles ${mimeOf(file)}")
            false
        }
    }

    /**
     * Shares [file] through the system share sheet, with [text] as the accompanying message.
     *
     * The text goes along because a picture arriving in a chat with no context is a poor way to
     * pass on a notice: the title and date are what make it useful to whoever receives it. This is
     * the case ShareText was written for, now that a file can go with it.
     */
    fun share(context: Context, file: File, text: String, chooserTitle: String) {
        val uri = uriFor(context, file) ?: return
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeOf(file))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(
                Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            Log.i(TAG, "No share target available")
        }
    }

    /**
     * The intent for "Save a copy".
     *
     * ACTION_CREATE_DOCUMENT rather than writing into Pictures or Downloads directly: it behaves
     * identically from API 26 to 36 and needs no storage permission at all, so the Play listing's
     * Data Safety answers stay as they are. The cost is one extra dialog, which also buys the user
     * a say in where the file lands.
     */
    fun saveIntent(fileName: String, mimeType: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_TITLE, fileName)

    /** Copies [file] into the document the user picked. Returns false if anything went wrong. */
    fun writeTo(context: Context, destination: Uri, file: File): Boolean = runCatching {
        context.contentResolver.openOutputStream(destination)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        } ?: error("Could not open $destination for writing")
        true
    }.onFailure { Log.w(TAG, "Could not save ${file.name}", it) }.getOrDefault(false)

    /** Opens a plain http(s) URL, for the fallbacks. Never throws. */
    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BodyText.normalise(url)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // No browser installed. Nothing useful to offer, and crashing over a tapped link would
            // be a poor trade for a notice the user can still read in full.
            Log.i(TAG, "No browser to open $url")
        }
    }
}
