package org.paramanuseniorshealth.notices.fcm

/**
 * What a downloaded attachment is, and what to call it on disk.
 *
 * This exists because two things downstream need an honest answer and neither can work it out for
 * itself. `ACTION_VIEW` picks the viewing app from the MIME type on the Intent, and a share target
 * usually names the file from its extension -- so a PNG saved as `.jpg` reaches the gallery as a
 * file the gallery may refuse and the user cannot rename. Until now every image was written as
 * `-img.jpg` whatever arrived, which decoded fine and was invisible precisely because nothing had
 * yet tried to hand the file to another app.
 *
 * Kept free of android.* so it carries JVM unit tests: every case here is a string-handling case,
 * and string handling is where this quietly goes wrong.
 */
object MimeTypes {

    const val PDF = "application/pdf"
    const val JPEG = "image/jpeg"

    /** The fallback when a server says nothing useful. Almost every notice attachment is a JPEG. */
    private const val DEFAULT_IMAGE_EXTENSION = "jpg"

    /**
     * Types [android.graphics.BitmapFactory] can actually decode.
     *
     * ICO is the reason this is a list rather than a `startsWith("image/")` check. Favicons are
     * usually ICO, BitmapFactory cannot decode it, and the failure looks like a working URL that
     * renders nothing -- a blank space on the card with no error anywhere. The sender already
     * declines to send one; this is the same rule enforced at the other end, because the sender
     * can be an older version than the app.
     */
    private val DECODABLE = mapOf(
        JPEG to "jpg",
        "image/jpg" to "jpg",
        "image/pjpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/heic" to "heic",
        "image/heif" to "heif",
        "image/bmp" to "bmp",
    )

    /** `image/jpeg; charset=binary` and `IMAGE/JPEG` both occur in the wild. */
    fun normalise(contentType: String?): String =
        contentType.orEmpty().substringBefore(';').trim().lowercase()

    fun isPdf(contentType: String?): Boolean = normalise(contentType) == PDF

    fun isDecodableImage(contentType: String?): Boolean = normalise(contentType) in DECODABLE

    /**
     * The extension to store an image under.
     *
     * The Content-Type is trusted first because it is what the server actually sent. The URL's own
     * suffix is the fallback for servers that answer `application/octet-stream` for everything,
     * which is common on static file hosts.
     */
    fun imageExtension(contentType: String?, url: String? = null): String {
        DECODABLE[normalise(contentType)]?.let { return it }

        val fromUrl = url.orEmpty()
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .lowercase()

        return if (fromUrl in DECODABLE.values) fromUrl else DEFAULT_IMAGE_EXTENSION
    }

    /** The MIME type to put on an Intent for a file stored under [extension]. */
    fun forExtension(extension: String): String = when (extension.lowercase().removePrefix(".")) {
        "pdf" -> PDF
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "bmp" -> "image/bmp"
        else -> JPEG
    }

    /**
     * A filename for the share sheet and the save dialog, derived from the source URL.
     *
     * The sender's own name is used when there is one, because a saved circular called
     * `list-of-holidays-2026.pdf` is findable six months later and `1725870000-pdf.pdf` is not.
     */
    fun fileName(url: String?, fallbackStem: String, extension: String): String {
        val candidate = url.orEmpty()
            .substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/', "")
            .trim()

        val stem = candidate.substringBeforeLast('.', candidate)
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == ' ' }
            .trim()
            .take(80)

        return if (stem.isEmpty()) "$fallbackStem.$extension" else "$stem.$extension"
    }
}
