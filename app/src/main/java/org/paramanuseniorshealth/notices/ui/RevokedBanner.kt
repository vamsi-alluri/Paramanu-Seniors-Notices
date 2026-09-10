package org.paramanuseniorshealth.notices.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.paramanuseniorshealth.notices.R
import org.paramanuseniorshealth.notices.activation.ActivationCode

/**
 * Shown above every screen while this device's code is revoked.
 *
 * Not dismissible, and pinned outside whatever is scrolling: it is the reason the rest of the app
 * has stopped updating, so it must not be something the user can scroll past and forget.
 *
 * It sends the user to Settings rather than straight to the code screen. Settings is where the code
 * itself is shown, struck through, next to the helpdesk number they will need -- so the one place
 * that explains the situation is also the place that offers the way out of it.
 *
 * The code is printed here too, because it is the first thing the helpdesk will ask for and
 * somebody who has just been cut off should not have to go hunting for it.
 */
@Composable
fun RevokedBanner(
    code: String?,
    checking: Boolean,
    onRecheck: () -> Unit,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
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
            // "Check again" first: it is the one they will press repeatedly, every time they come
            // back from the helpdesk. "What to do" is read once, on the day they are cut off.
            Row(modifier = Modifier.padding(top = 2.dp)) {
                TextButton(onClick = onRecheck, enabled = !checking) {
                    Text(
                        text = stringResource(
                            if (checking) R.string.settings_recheck_checking
                            else R.string.settings_recheck_action
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                // Omitted on the Settings screen itself, where it would be a button that visibly
                // does nothing.
                if (onOpenSettings != null) {
                    TextButton(onClick = onOpenSettings) {
                        Text(
                            text = stringResource(R.string.revoked_banner_action),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}
