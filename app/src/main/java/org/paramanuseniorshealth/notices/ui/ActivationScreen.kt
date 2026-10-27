package org.paramanuseniorshealth.notices.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.paramanuseniorshealth.notices.NoticesApplication
import org.paramanuseniorshealth.notices.R
import org.paramanuseniorshealth.notices.activation.ActivationCode
import org.paramanuseniorshealth.notices.analytics.AnalyticsEvent
import org.paramanuseniorshealth.notices.activation.RedeemResult

/**
 * The code gate, shown on a fresh install and when a revoked user chooses to enter a new code.
 *
 * Every choice here is aimed at someone in their eighties typing eight characters off a paper slip:
 * a monospace field at 28sp so no character is ambiguous, capitals forced so the keyboard never
 * offers lower case, and errors that name the likely cause rather than reporting a status.
 */
@Composable
fun ActivationScreen(
    onSubmit: (String) -> Unit,
    busy: Boolean,
    error: RedeemResult?,
    onErrorDismissed: () -> Unit,
    modifier: Modifier = Modifier,
    prefill: String = "",
    hasCode: Boolean = false,
    stoppedCode: String? = null,
) {
    // Held unpunctuated. The dash is painted by CodeDashTransformation, so it cannot be deleted,
    // retyped, or land in the middle of a paste.
    //
    // [prefill] arrives from an activation link and goes through the same clean() as typing and
    // pasting, so a link cannot put anything in the field that a user could not have typed. It is
    // never submitted for them: the button still has to be pressed, which keeps the code visible
    // before it is spent and means a stale link fails where it can be seen.
    var code by remember(prefill) { mutableStateOf(CodeDashTransformation.clean(prefill)) }
    val complete = code.length == ActivationCode.LENGTH

    // Once per appearance. The screen recomposes on every keystroke and keyboard change, and
    // onValueChange runs per character, so neither may log. Keyed on Unit, so a later change of
    // [prefill] is not a new appearance. The flag is saved state so that an activity recreation
    // with this screen up does not count the same appearance again; it is dropped when the screen
    // is left, so coming back is a new appearance. [hasCode] is re-entry from Settings as against a
    // fresh install: the phone holds a working code. A revoked phone does hold a stored code, but it
    // counts as not having one, because it is the phone a replacement slip is for.
    val context = LocalContext.current
    var shownReported by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (shownReported) return@LaunchedEffect
        shownReported = true
        (context.applicationContext as NoticesApplication).analytics.log(
            AnalyticsEvent.ActivationShown(hasCode = hasCode)
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // imePadding keeps the field above the keyboard. Without it the soft keyboard covers
            // the one control on the screen, which on a first launch looks like the app is broken.
            .imePadding()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.logo),
            contentDescription = stringResource(R.string.logo_description),
            modifier = Modifier.size(140.dp),
        )

        Text(
            text = stringResource(R.string.activation_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = stringResource(R.string.activation_explainer),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )

        OutlinedTextField(
            value = code,
            onValueChange = {
                // Everything the user or the clipboard offers is cleaned to storable characters:
                // typed dashes and spaces are dropped, O/I/L are folded onto 0/1, and the result is
                // capped. So pasting "MQCA-MGPC" behaves exactly like typing it.
                val cleaned = CodeDashTransformation.clean(it)
                if (cleaned != code) {
                    code = cleaned
                    if (error != null) onErrorDismissed()
                }
            },
            visualTransformation = CodeDashTransformation,
            singleLine = true,
            enabled = !busy,
            isError = error != null,
            // The colour is set explicitly. A TextStyle built from scratch carries
            // Color.Unspecified, which leaves the resolved colour at the mercy of whatever is
            // ambient -- and that is how the field ended up white-on-white in dark mode.
            textStyle = TextStyle(
                fontSize = 28.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp),
        )

        if (error != null) {
            Text(
                text = stringResource(
                    when (error) {
                        RedeemResult.Mistyped -> R.string.activation_error_mistyped
                        RedeemResult.NotAccepted -> R.string.activation_error_not_accepted
                        RedeemResult.SameCode -> R.string.activation_error_same_code
                        RedeemResult.Offline -> R.string.activation_error_offline
                        RedeemResult.Success -> R.string.activation_error_not_accepted
                    }
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        Button(
            onClick = { onSubmit(code) },
            enabled = complete && !busy,
            // Material's default disabled container is so close to the surface colour that the
            // button reads as absent rather than as not-yet-available. For a user who is not sure
            // what to do next, a greyed-out button they can see is guidance; empty space is a dead
            // end.
            colors = ButtonDefaults.buttonColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(top = 8.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                Text(
                    text = stringResource(R.string.activation_submit),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        Text(
            text = stringResource(R.string.activation_where_to_get_code),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )

        // The phone's own code when it has been stopped, however this screen was reached: from a
        // replacement link or by hand from Settings. It says why the user is here, and sat beside
        // the field it shows which code not to type. Below the button rather than above the field,
        // so the one code on screen that does not work is never the first one read. Struck through
        // as in Settings, so the meaning does not rest on seeing red.
        if (stoppedCode != null) {
            Text(
                text = stringResource(R.string.activation_stopped_code_label),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                text = ActivationCode.format(stoppedCode),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.error,
                textDecoration = TextDecoration.LineThrough,
            )
            Text(
                text = stringResource(R.string.settings_code_revoked),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}
