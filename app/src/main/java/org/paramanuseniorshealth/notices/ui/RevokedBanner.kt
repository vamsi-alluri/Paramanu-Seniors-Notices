package org.paramanuseniorshealth.notices.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.paramanuseniorshealth.notices.R
import org.paramanuseniorshealth.notices.activation.ActivationCode

/**
 * Shown above everything on the notice list when this device's code has been revoked.
 *
 * It is not dismissible, and it deliberately offers no way to type another code. Coming back is
 * the NGO's decision, made by restoring this code in the console -- at which point the device
 * resumes on its own within a day, or immediately if the app is opened. A "have a new code" button
 * here would just be the loophole that made the old code screen pointless: any valid slip lifted
 * that gate, and it never had to be the user's own.
 *
 * The code is printed because it is what the helpdesk will ask for, and somebody who has just been
 * cut off should not have to go hunting through Settings for it.
 */
@Composable
fun RevokedBanner(code: String?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = stringResource(R.string.revoked_banner_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.revoked_banner_body),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (code != null) {
                Text(
                    text = stringResource(R.string.revoked_banner_helpdesk, ActivationCode.format(code)),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
