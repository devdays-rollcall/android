package ru.pereklichka.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldLabelPosition
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * Поле ввода из макетов создания группы и вступления: подпись всегда на рамке, иконка слева и серая подсказка
 * [placeholder] в пустом поле. Высота 56 dp, фон белый, рамка в фокусе цвета primary. Текст хранится в [state],
 * длиннее [maxLength] его не ввести. По кнопке клавиатуры с [ImeAction.Done] вызывается [onDone], с остальными
 * действиями клавиатура ведёт себя как обычно.
 */
@Composable
fun IconTextField(
    label: String,
    state: TextFieldState,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    maxLength: Int? = null,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        state = state,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        enabled = enabled,
        lineLimits = TextFieldLineLimits.SingleLine,
        inputTransformation = maxLength?.let { InputTransformation.maxLength(it) },
        labelPosition = TextFieldLabelPosition.Attached(alwaysMinimize = true),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        leadingIcon = { SymIcon(icon) },
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
        onKeyboardAction = KeyboardActionHandler { performDefault -> if (imeAction == ImeAction.Done) onDone() else performDefault() },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = scheme.surfaceContainerLowest,
            unfocusedContainerColor = scheme.surfaceContainerLowest,
            disabledContainerColor = scheme.surfaceContainerLowest.copy(alpha = 0.55f),
            unfocusedBorderColor = scheme.outline,
            focusedBorderColor = scheme.primary,
            focusedLabelColor = scheme.primary,
            unfocusedLabelColor = scheme.onSurfaceVariant,
            focusedLeadingIconColor = scheme.onSurfaceVariant,
            unfocusedLeadingIconColor = scheme.onSurfaceVariant,
            focusedPlaceholderColor = scheme.onSurfaceVariant,
            unfocusedPlaceholderColor = scheme.onSurfaceVariant,
        ),
    )
}
