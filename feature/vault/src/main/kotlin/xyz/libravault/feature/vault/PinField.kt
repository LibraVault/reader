package xyz.libravault.feature.vault

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/**
 * The vault PIN / passphrase entry field, with its show-hide toggle.
 *
 * Lifted out of [CreateVaultScreen] so [UnlockVaultScreen] can use the same
 * one. The two screens previously carried byte-for-byte copies of this field —
 * same label, same `PasswordVisualTransformation`, same trailing toggle — which
 * is how the *entry point to an encrypted vault* ends up behaving differently
 * in two places after someone fixes only the copy they were looking at. That is
 * not hypothetical: the accessibility fix below had to be applied twice before
 * this was unified.
 *
 * `internal` rather than `private` so it can be unit-tested directly, per
 * `AGENTS.md`. It is a pure function of its arguments — no ViewModel, no DI.
 */
@Composable
internal fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    isError: Boolean,
    supportingText: String?,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("PIN or passphrase") },
        singleLine = true,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        // Password (not NumberPassword): a 4-digit PIN is the suggested
        // default, but a longer alphanumeric passphrase must remain typeable.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { onVisibleChange(!visible) }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    // "Show"/"Hide" alone is what TalkBack read before: a bare
                    // verb with no object, on a screen that also has a Back
                    // button and a submit button. Naming the thing being
                    // toggled is the whole difference between a usable
                    // announcement and a guess.
                    contentDescription = if (visible) "Hide PIN" else "Show PIN",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
