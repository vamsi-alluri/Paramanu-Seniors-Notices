package org.paramanuseniorshealth.notices.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.paramanuseniorshealth.notices.R
import org.paramanuseniorshealth.notices.activation.ActivationCode
import org.paramanuseniorshealth.notices.activation.Subscription

private const val TESTING_UNLOCK_TAPS = 7

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    subscriptions: Map<Subscription, Boolean>,
    activationCode: String?,
    testingUnlocked: Boolean,
    versionName: String,
    onSubscriptionChange: (Subscription, Boolean) -> Unit,
    onSendTest: () -> Unit,
    onUnlockTesting: () -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingReset by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Deliberately awkward, because it is not for users. Nobody taps a version number seven times
    // by accident, and a visible switch would put a "Testing" entry in four hundred sets of
    // notification settings for a feature that will never concern them.
    var versionTaps by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_subscriptions),
                style = MaterialTheme.typography.titleLarge,
            )

            // One row per subscription rather than a single on/off, because these differ in kind:
            // notices are occasional and important, the daily status is frequent and routine, and
            // wanting one without the other is an entirely reasonable position.
            Subscription.entries.filter { it.isPublic || testingUnlocked }.forEach { subscription ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                when (subscription) {
                                    Subscription.NOTICES -> R.string.settings_receive_notices
                                    Subscription.STATUS -> R.string.settings_receive_status
                                    Subscription.TESTING -> R.string.settings_receive_testing
                                }
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(
                                when (subscription) {
                                    Subscription.NOTICES -> R.string.settings_receive_notices_explainer
                                    Subscription.STATUS -> R.string.settings_receive_status_explainer
                                    Subscription.TESTING -> R.string.settings_receive_testing_explainer
                                }
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(
                        checked = subscriptions[subscription] ?: subscription.defaultEnabled,
                        onCheckedChange = { onSubscriptionChange(subscription, it) },
                    )
                }
            }

            HorizontalDivider()

            // Answers the question a user actually has -- "will this thing tell me?" -- without
            // making them wait for the NGO to send something. It proves the permission and the
            // channel, which is the failure people hit; it does not exercise delivery.
            Column {
                Text(
                    text = stringResource(R.string.settings_check),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.settings_check_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onSendTest) {
                    Text(
                        text = stringResource(R.string.settings_check_action),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            HorizontalDivider()

            if (activationCode != null) {
                Column {
                    Text(
                        text = stringResource(R.string.settings_your_code),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    // Shown so the user can read it back over the phone if they ever call the
                    // dispensary for help. It is not a secret worth hiding: it is already spent.
                    Text(
                        text = ActivationCode.format(activationCode),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                HorizontalDivider()
            }

            Column {
                Text(
                    text = stringResource(R.string.settings_reset),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.settings_reset_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { confirmingReset = true }) {
                    Text(
                        text = stringResource(R.string.settings_reset_action),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            HorizontalDivider()

            ContactSection(context)

            HorizontalDivider()

            Text(
                text = stringResource(R.string.settings_about),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable {
                    versionTaps++
                    if (versionTaps >= TESTING_UNLOCK_TAPS) onUnlockTesting()
                },
            )
        }
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text(stringResource(R.string.settings_reset_confirm_title)) },
            text = { Text(stringResource(R.string.settings_reset_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReset = false
                    onReset()
                }) {
                    Text(
                        text = stringResource(R.string.settings_reset_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/**
 * Who to contact, and the privacy policy.
 *
 * The phone number and email are tappable: a reader in their eighties should be able to place the
 * call from here rather than copy a number across to the dialler by hand.
 */
@Composable
private fun ContactSection(context: Context) {
    val phone = stringResource(R.string.settings_contact_phone)
    val email = stringResource(R.string.settings_contact_email)
    val websiteUrl = stringResource(R.string.settings_website_url)
    val privacyUrl = stringResource(R.string.settings_privacy_url)

    Column {
        Text(
            text = stringResource(R.string.settings_contact),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.settings_contact_org),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            text = stringResource(R.string.settings_contact_helpdesk),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = stringResource(R.string.settings_contact_address),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )

        ContactLink(phone) {
            val digits = phone.filter { it.isDigit() || it == '+' }
            context.open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits")))
        }
        ContactLink(email) {
            context.open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
        }
        ContactLink(stringResource(R.string.settings_contact_website)) {
            context.open(Intent(Intent.ACTION_VIEW, Uri.parse(websiteUrl)))
        }
        ContactLink(stringResource(R.string.settings_privacy)) {
            context.open(Intent(Intent.ACTION_VIEW, Uri.parse(privacyUrl)))
        }
    }
}

@Composable
private fun ContactLink(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .padding(top = 8.dp)
            .clickable(onClick = onClick),
    )
}

/** Swallows the case where no app can handle the intent, rather than crashing on a tap. */
private fun Context.open(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
