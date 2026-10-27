package org.paramanuseniorshealth.notices.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.paramanuseniorshealth.notices.R
import org.paramanuseniorshealth.notices.update.AppGate

/**
 * The whole app while the gate is closed: one line and one button, with no way past either.
 *
 * One line, not an explanation. Whoever holds the phone cannot act on why, only on the button, and
 * the reason is the helpdesk's to give. Back leaves the app, since nothing is behind this screen.
 */
@Composable
fun GateScreen(gate: AppGate) {
    val context = LocalContext.current
    val (message, action, onAction) = when (gate) {
        AppGate.Unsupported -> Triple(R.string.gate_update_title, R.string.gate_update_action) {
            openPlayListing(context)
        }
        else -> Triple(R.string.gate_stopped_title, R.string.gate_stopped_action) {
            AttachmentActions.openUrl(context, context.getString(R.string.settings_website_url))
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(message),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = onAction,
                modifier = Modifier
                    .padding(top = 32.dp)
                    .fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(action),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

/** The Play Store app if it is there, the Play website if not. */
private fun openPlayListing(context: Context) {
    val market = Uri.parse("market://details?id=${context.packageName}")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, market).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        AttachmentActions.openUrl(context, "https://play.google.com/store/apps/details?id=${context.packageName}")
    }
}
