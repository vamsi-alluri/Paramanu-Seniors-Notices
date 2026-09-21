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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.paramanuseniorshealth.notices.R
import org.paramanuseniorshealth.notices.activation.ActivationCode
import org.paramanuseniorshealth.notices.activation.Dispensary

private const val TESTING_UNLOCK_TAPS = 7

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    dispensary: Dispensary?,
    topicChoices: Map<String, Boolean>,
    testingOn: Boolean,
    activationCode: String?,
    revoked: Boolean,
    checking: Boolean,
    testingUnlocked: Boolean,
    versionName: String,
    onTopicChange: (String, Boolean) -> Unit,
    onTestingChange: (Boolean) -> Unit,
    onSendTest: () -> Unit,
    onUnlockTesting: () -> Unit,
    onRecheck: () -> Unit,
    onEnterNewCode: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

            dispensary?.name?.takeIf { it.isNotBlank() }?.let { name ->
                Text(
                    text = stringResource(R.string.settings_subscriptions_from, name),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // One row per topic the code's dispensary offers, drawn from its record in the database
            // rather than compiled in, so a dispensary can offer something new without an app
            // release. Nothing to show means the list has never been read -- the phone has not been
            // online since the code was entered.
            val topics = dispensary?.topics.orEmpty()
            if (topics.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_topics_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // A sole topic is standing information, not a choice.
            //
            // A switch invites being switched, and the only thing it can do here is turn the app
            // off entirely -- which does not look like "off" to the person who did it, it looks
            // like an app that stopped working, and arrives as a phone call. There is nothing to
            // decide when there is one option, so the row states what arrives and offers no
            // control to get wrong.
            if (topics.size == 1) {
                val only = topics.first()
                SoleTopicRow(label = only.label, explainer = only.explainer)
            } else {
                topics.forEach { topic ->
                    TopicRow(
                        label = topic.label,
                        explainer = topic.explainer,
                        checked = topicChoices[topic.topic] ?: topic.defaultOn,
                        onCheckedChange = { onTopicChange(topic.topic, it) },
                    )
                }
            }
            if (testingUnlocked) {
                TopicRow(
                    label = stringResource(R.string.settings_receive_testing),
                    explainer = stringResource(R.string.settings_receive_testing_explainer),
                    checked = testingOn,
                    onCheckedChange = onTestingChange,
                )
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
                    //
                    // Struck through and in the error colour once it has been stopped. Colour alone
                    // would not do it: this audience includes people who cannot distinguish it, and
                    // the strikethrough carries the same meaning without relying on sight of red.
                    Text(
                        text = ActivationCode.format(activationCode),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (revoked) MaterialTheme.colorScheme.error else Color.Unspecified,
                        textDecoration = if (revoked) TextDecoration.LineThrough else null,
                    )
                    if (revoked) {
                        Text(
                            text = stringResource(R.string.settings_code_revoked),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                HorizontalDivider()
            }

            // Both of these are offered only while revoked. At any other time the code screen is
            // not somewhere a user has any reason to go, and a permanent door to it in Settings
            // invites somebody to wander in and strand themselves.
            if (revoked) {
                Column {
                    Text(
                        text = stringResource(R.string.settings_recheck),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.settings_recheck_explainer),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(
                        onClick = onRecheck,
                        enabled = !checking,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(
                            if (checking) R.string.settings_recheck_checking
                            else R.string.settings_recheck_action
                        ))
                    }
                }
                HorizontalDivider()

                Column {
                    Text(
                        text = stringResource(R.string.settings_new_code),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.settings_new_code_explainer),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Button(
                        onClick = onEnterNewCode,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.settings_new_code_action))
                    }
                }
                HorizontalDivider()
            }

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

/**
 * The same information a [TopicRow] carries, with no control beside it.
 *
 * Deliberately not a disabled [Switch]: a greyed switch is still a switch, and the reader who
 * cannot move it is left wondering what they have done wrong rather than reading what it says.
 */
@Composable
private fun SoleTopicRow(label: String, explainer: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
        if (explainer.isNotBlank()) {
            Text(text = explainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun TopicRow(label: String, explainer: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
            if (explainer.isNotBlank()) {
                Text(text = explainer, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Swallows the case where no app can handle the intent, rather than crashing on a tap. */
private fun Context.open(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
