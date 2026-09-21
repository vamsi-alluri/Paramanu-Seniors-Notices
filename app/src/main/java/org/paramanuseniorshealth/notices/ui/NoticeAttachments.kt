package org.paramanuseniorshealth.notices.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.paramanuseniorshealth.notices.R
import org.paramanuseniorshealth.notices.data.NoticeEntity
import org.paramanuseniorshealth.notices.fcm.NoticeImageStore
import java.io.File

/**
 * What a notice carries, below its text: pictures, the circular, and a link card.
 *
 * Split out of NoticeListScreen because that file was already carrying the list, the header, the
 * selection mode and the permission banner, and attachments are the part most likely to keep
 * growing. Everything here is about one row's attachments and nothing else.
 */

/**
 * Open / Share / Save for one cached file.
 *
 * Shown only when the file is actually on the phone. That is the honest version of the reasoning
 * ShareText was written with -- it declined to share images because a render may have been pruned,
 * and a share that silently carries nothing is worse than no button. The condition, not the
 * feature, was the right response.
 */
@Composable
private fun FileActions(
    file: File,
    shareText: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<File?>(null) }

    // StartActivityForResult rather than the CreateDocument contract, because that contract fixes
    // the MIME type when the launcher is created and this row may offer a JPEG and a PDF at once.
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val destination: Uri? = result.data?.data
        val source = pending
        pending = null
        if (destination != null && source != null) {
            // Confirmed either way. The file lands somewhere the user chose and this app cannot
            // then show them, so silence would leave them with no way to know it worked short of
            // going to look.
            val saved = AttachmentActions.writeTo(context, destination, source)
            Toast.makeText(
                context,
                if (saved) R.string.toast_saved else R.string.toast_save_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = {
            // Falls through to nothing on a phone with no viewer for this type; the row's own
            // image is still tappable and the in-app viewer still works.
            AttachmentActions.open(context, file)
        }) {
            Text(stringResource(R.string.action_open_with))
        }
        TextButton(onClick = {
            AttachmentActions.share(
                context = context,
                file = file,
                text = shareText,
                chooserTitle = context.getString(R.string.action_share_chooser),
            )
        }) {
            Text(stringResource(R.string.action_share))
        }
        TextButton(onClick = {
            pending = file
            saver.launch(
                AttachmentActions.saveIntent(file.name, AttachmentActions.mimeOf(file))
            )
        }) {
            Text(stringResource(R.string.action_save))
        }
    }
}

/**
 * The link preview card.
 *
 * Assembled from whatever the sender managed to resolve. All three display fields are optional and
 * they fail independently, so this handles a card with a logo and no title, a title and no logo,
 * and neither -- the last being a plain tappable link, which is the point: the URL survives even
 * when the decoration does not.
 */
@Composable
fun LinkCard(
    notice: NoticeEntity,
    modifier: Modifier = Modifier,
) {
    val url = notice.linkUrl?.takeIf { it.isNotBlank() } ?: return
    val context = LocalContext.current
    // Local file only, same reasoning as the row thumbnail: the URL is never handed to Coil, so an
    // unfetched logo falls straight through to the placeholder below rather than downloading here.
    val image = NoticeImageStore.cachedLinkImage(context, notice.logId)
    val heading = notice.linkTitle?.takeIf { it.isNotBlank() }
    val site = notice.linkSite?.takeIf { it.isNotBlank() } ?: url

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            // ACTION_VIEW rather than any special-casing: this is what sends a YouTube link to the
            // YouTube app, a maps link to maps, and everything else to the browser, for free.
            .combinedClickable(onClick = { AttachmentActions.openUrl(context, url) })
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
        } else {
            // The sender checks the logo actually exists before sending it, so an absent one is
            // the normal outcome for a site the favicon service does not know -- not a failure.
            // A lettered square keeps the card a card instead of leaving a hole where an icon
            // would be, and it needs no network at all.
            LinkPlaceholder(site = notice.linkSite ?: notice.linkUrl)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = heading ?: stringResource(R.string.link_open),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = site,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Everything an expanded row shows below its body text.
 *
 * [photo] and [pdfRender] are local files, already downloaded when the notice arrived, so this
 * performs no network I/O and works offline. The circular behind [pdfRender] is a different matter
 * and is fetched on demand -- see [downloading].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoticeAttachments(
    notice: NoticeEntity,
    photo: File?,
    pdfRender: File?,
    shareText: String,
    downloading: Boolean,
    onOpenImage: () -> Unit,
    onOpenPdf: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {

        // Both are shown when both exist: the photo is what the sender chose, the render is the
        // official circular, and neither substitutes for the other.
        listOfNotNull(photo, pdfRender).forEach { file ->
            AsyncImage(
                model = file,
                contentDescription = notice.title,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(onClick = onOpenImage, onLongClick = onLongClick),
            )
            FileActions(
                file = file,
                shareText = shareText,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (!notice.pdfUrl.isNullOrBlank()) {
            if (downloading) {
                // A 2MB circular on a poor connection takes long enough that a button which simply
                // stayed still would read as broken, and get pressed again.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp, start = 12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.attachment_downloading),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                TextButton(onClick = onOpenPdf, modifier = Modifier.padding(top = 4.dp)) {
                    Text(stringResource(R.string.action_open_pdf))
                }
            }
        }

        LinkCard(notice = notice, modifier = Modifier.padding(top = 12.dp))
    }
}

/** A placeholder so a card with no usable logo is still a card rather than a gap. */
@Composable
private fun LinkPlaceholder(site: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = site?.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
