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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.paramanuseniorshealth.notices.R
import org.paramanuseniorshealth.notices.activation.ActivationCode
import org.paramanuseniorshealth.notices.activation.RedeemResult

/**
 * The code gate, shown only on a fresh install and after a reset.
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
) {
    // Held unpunctuated. The dash is painted by CodeDashTransformation, so it cannot be deleted,
    // retyped, or land in the middle of a paste.
    var code by remember { mutableStateOf("") }
    val complete = code.length == ActivationCode.LENGTH

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
    }
}
