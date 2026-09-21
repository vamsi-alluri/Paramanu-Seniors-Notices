package org.paramanuseniorshealth.notices.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One received notice.
 *
 * [logId] is the sender-supplied identifier and carries a UNIQUE index: the same message can
 * legitimately reach us twice (FCM guarantees at-least-once delivery, and a message can arrive via
 * both [org.paramanuseniorshealth.notices.fcm.NoticeMessagingService] and the notification-tap
 * intent). Inserting with OnConflictStrategy.IGNORE turns that into a no-op instead of a duplicate.
 *
 * SQLite allows multiple NULLs in a unique index, so notices sent without a logId are still stored.
 */
@Entity(
    tableName = "notices",
    indices = [Index(value = ["logId"], unique = true)],
)
data class NoticeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val logId: String?,
    /** The plain-text headline. Mandatory: an attachment is never allowed to be the whole message. */
    val title: String,
    /** One-line summary. May be blank; the push contract only requires a title. */
    val body: String,
    /** Epoch millis. Parsed from [logId] when it is a timestamp, otherwise arrival time. */
    val receivedAt: Long,
    /**
     * A picture the sender attached directly: a photograph of a note taped to the shutter, a
     * poster, a screenshot. Shown as-is.
     */
    val imageUrl: String? = null,
    /**
     * A notice PDF on the website. Page one is rendered to an image on arrival.
     *
     * This and [imageUrl] are not alternatives and may both be present: a notice can carry a photo
     * *and* link the official circular. When both exist the photo wins the notification tray, since
     * it was chosen for a small frame, and the expanded row shows both.
     */
    val pdfUrl: String? = null,
    /**
     * A link found in the notice text, shown as a preview card.
     *
     * Resolved entirely by the sender: [linkTitle], [linkImage] and [linkSite] arrive already
     * filled in, and this app never fetches or parses a web page. Four hundred phones each
     * scraping the same URL inside onMessageReceived would be four hundred chances to lose a
     * notice over a slow site, to save one lookup.
     *
     * The three display fields degrade independently, and this is the case that matters: a
     * [linkUrl] with all three empty means the sender could not resolve a card and the row shows a
     * plain tappable link. Losing the decoration is cosmetic; losing the link would leave a notice
     * that no longer says where to go.
     */
    val linkUrl: String? = null,
    /** The target page's title, or the video title for a YouTube link. May be absent. */
    val linkTitle: String? = null,
    /**
     * The site's logo, or a YouTube still. May be absent -- notably when the site has no icon the
     * favicon service knows, which the sender checks for rather than sending a URL that 404s.
     */
    val linkImage: String? = null,
    /** The host, or `YouTube`. The small grey line on the card. */
    val linkSite: String? = null,
    /**
     * A ready-made first-page image for [pdfUrl], published alongside the circular.
     *
     * Absent today: the website does not produce one yet, so the worker downloads the whole PDF and
     * renders page one itself. Once the pipeline ships this arrives filled in, the worker fetches
     * ~40KB instead of ~5.6MB, and the circular is only ever downloaded when somebody taps it.
     *
     * Persisted rather than used and discarded, because a notice deferred on mobile data may be
     * tapped a day later and the tap path has nothing else to read the URL from.
     */
    val pdfThumbUrl: String? = null,
    /**
     * Page count and size of [pdfUrl], for the card's badge.
     *
     * Filled from the payload when the sender supplies them, and otherwise derived locally the
     * first time the worker renders the PDF -- `PdfRenderer` already has both and used to throw
     * them away. Null means neither has happened yet, and the badge simply omits the numbers.
     */
    val pdfPages: Int? = null,
    val pdfBytes: Long? = null,
    /**
     * One of [org.paramanuseniorshealth.notices.fcm.AttachmentState], or NULL on a row written
     * before this column existed -- which reads as PENDING and so retries.
     */
    val attachmentState: String? = null,
    /** Failed fetches only. Deferrals do not count; see FetchPolicy.shouldRetry. */
    val attachmentAttempts: Int = 0,
)
