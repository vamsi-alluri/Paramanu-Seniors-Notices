package org.paramanuseniorshealth.notices.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.paramanuseniorshealth.notices.R

/**
 * What stands in for an attachment that is not on the phone.
 *
 * One appearance for never fetched, deferred because the connection is metered or roaming, failed
 * with attempts left, and pruned after delivery. Those are the same thing from the reader's side:
 * the picture is not here, and tapping fetches it. Telling them apart in words would mean
 * explaining metered connections to people in their eighties, and would arrive at the helpdesk as
 * a phone call rather than at the user as understanding.
 *
 * [broken] is the one that genuinely differs, and it earns its own glyph. Everything above is "not
 * here yet"; this is "not coming" -- the server answered 4xx, so the file was never published and
 * no number of taps will produce it. Offering a download arrow for that is a small lie, and the
 * reader who takes it up learns nothing from the tap except that the app ignored them. A struck-out
 * page says what is true. The tile stays clickable even so: a sender who fixes the URL should not
 * need the user to reinstall, and the tap is the only route back.
 *
 * The download glyph sits over the type glyph rather than beside it, so the tap target is the whole
 * tile and there is nothing small to miss.
 *
 * While [downloading] the download glyph is replaced by a spinner and the tile stops being
 * clickable, at the same 56dp size and with the same dimmed type glyph behind it -- the tile's
 * identity does not change, only what its centre says about to happen next. Without this, a tap on
 * a *collapsed* row (the default state, and where most taps land) produced no feedback at all: the
 * only in-flight indicator lived in NoticeAttachments, which only renders when the row is expanded
 * and only for a PDF, and the wait behind a thumbless circular can run to five minutes
 * ([org.paramanuseniorshealth.notices.fcm.NoticeImageStore] `WORKER_TIMEOUT_MS`) with re-taps
 * silently swallowed by the view model's re-entry guard. A user watching nothing happen for minutes
 * is indistinguishable, from where they sit, from a broken app.
 *
 * A single content description sits on the outer [Box] rather than on either [Icon] -- two
 * descriptions on one tile would have TalkBack announce "Picture, Download" over a tile with no
 * button role at all. [Role.Button] here gives it one.
 */
@Composable
fun AttachmentPlaceholder(
    isPdf: Boolean,
    downloading: Boolean,
    broken: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val typeDescription =
        stringResource(if (isPdf) R.string.attachment_pdf else R.string.attachment_image)
    // Only spoken while the tile is actually an offer to fetch something -- mid-download there is
    // nothing left to invite a tap for, and the spinner already says a fetch is under way.
    val description = when {
        downloading -> typeDescription
        broken -> "$typeDescription ${stringResource(R.string.attachment_unavailable)}"
        else -> "$typeDescription ${stringResource(R.string.attachment_download)}"
    }
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .semantics { contentDescription = description }
            .then(
                // Not clickable mid-fetch: a second tap here would start a second fetch were it not
                // for the view model's re-entry guard, and a tile that looks tappable while doing
                // nothing on tap is worse than one that plainly is not.
                if (downloading) Modifier
                else Modifier.clickable(role = Role.Button, onClick = onDownload)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (broken) {
            // One glyph rather than a badge over a type icon. A struck-out page is already a page,
            // so stacking the type behind it would only make both harder to read at 56dp.
            Icon(
                painter = painterResource(R.drawable.ic_file_broken),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.55f),
                modifier = Modifier.size(36.dp),
            )
            return@Box
        }

        Icon(
            painter = painterResource(
                if (isPdf) R.drawable.ic_file_pdf else R.drawable.ic_file_image
            ),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.45f),
            modifier = Modifier.size(40.dp),
        )
        if (downloading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_download),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
