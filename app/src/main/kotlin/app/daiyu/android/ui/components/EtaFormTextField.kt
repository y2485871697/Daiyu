package app.daiyu.android.ui.components


import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val EtaInputCornerRadius = 14.dp

@Composable
internal fun etaInputContainerColor(): Color {
    val colors = MiuixTheme.colorScheme
    return colors.surfaceContainerHigh
}

/** Rounded, softly filled form field with an in-field placeholder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EtaFormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val colors = MiuixTheme.colorScheme
    val container = etaInputContainerColor()
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
        WithoutPressRipple {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = singleLine,
                minLines = if (singleLine) 1 else minLines,
                maxLines = if (singleLine) 1 else maxLines,
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                modifier = modifier.managedInput().fillMaxWidth().heightIn(min = 48.dp)
                    .semantics { contentDescription = hint },
                shape = RoundedCornerShape(EtaInputCornerRadius),
                textStyle = MiuixTheme.textStyles.body1,
                placeholder = { Text(hint, style = MiuixTheme.textStyles.body1) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = container,
                    unfocusedContainerColor = container,
                    disabledContainerColor = container,
                    focusedTextColor = colors.onSurface,
                    unfocusedTextColor = colors.onSurface,
                    disabledTextColor = colors.onSurface.copy(alpha = 0.38f),
                    cursorColor = materialImeCursorColor(colors.primary),
                    errorCursorColor = materialImeCursorColor(androidx.compose.material3.MaterialTheme.colorScheme.error),
                    focusedBorderColor = colors.primary.copy(alpha = 0.65f),
                    unfocusedBorderColor = colors.outline.copy(alpha = 0.30f),
                    disabledBorderColor = colors.outline.copy(alpha = 0.15f),
                    focusedPlaceholderColor = colors.onSurfaceVariantSummary,
                    unfocusedPlaceholderColor = colors.onSurfaceVariantSummary,
                    disabledPlaceholderColor = colors.onSurfaceVariantSummary.copy(alpha = 0.38f),
                ))
        }
    }
}
