package org.paramanuseniorshealth.notices.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.paramanuseniorshealth.notices.R

/**
 * What stands in for an attachment that is not on the phone.
 *
 * One appearance for four situations -- never fetched, deferred because the connection is metered
 * or roaming, failed, and pruned after delivery. They are the same thing from the reader's side:
 * the picture is not here, and tapping fetches it. Telling them apart in words would mean
 * explaining metered connections to people in their eighties, and would arrive at the helpdesk as
 * a phone call rather than at the user as understanding.
 *
 * The download glyph sits over the type glyph rather than beside it, so the tap target is the whole
 * tile and there is nothing small to miss.
 */
@Composable
fun AttachmentPlaceholder(
    isPdf: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onDownload),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(
                if (isPdf) R.drawable.ic_file_pdf else R.drawable.ic_file_image
            ),
            contentDescription = stringResource(
                if (isPdf) R.string.attachment_pdf else R.string.attachment_image
            ),
            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.45f),
            modifier = Modifier.size(40.dp),
        )
        Icon(
            painter = painterResource(R.drawable.ic_download),
            contentDescription = stringResource(R.string.attachment_download),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(24.dp),
        )
    }
}
