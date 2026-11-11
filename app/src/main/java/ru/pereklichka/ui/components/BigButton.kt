package ru.pereklichka.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val MinButtonHeight = 54.dp
private val BorderWidth = 1.5.dp

/** Вид кнопки: главное действие, второстепенное, опасное сплошное и опасное с рамкой (SOS на главном экране). */
enum class ButtonKind { PRIMARY, SECONDARY, DANGER, DANGER_OUTLINED }

/** Крупная кнопка для действий руководителя и участника. Высота не меньше 54 dp: на ходу попасть должно быть легко. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = when (kind) {
        ButtonKind.PRIMARY -> ButtonDefaults.buttonColors()
        ButtonKind.SECONDARY -> ButtonDefaults.buttonColors(
            containerColor = scheme.surfaceContainerLowest, contentColor = scheme.onSurface,
        )
        ButtonKind.DANGER -> ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)
        ButtonKind.DANGER_OUTLINED -> ButtonDefaults.buttonColors(
            containerColor = scheme.surfaceContainerLowest, contentColor = scheme.error,
        )
    }
    val border = when (kind) {
        ButtonKind.SECONDARY -> BorderStroke(BorderWidth, scheme.outlineVariant)
        ButtonKind.DANGER_OUTLINED -> BorderStroke(BorderWidth, scheme.error)
        else -> null
    }
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = MinButtonHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = colors,
        border = border,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Кнопка на всю ширину. */
@Composable
fun BigButtonFull(text: String, onClick: () -> Unit, kind: ButtonKind = ButtonKind.PRIMARY) {
    BigButton(text, onClick, Modifier.fillMaxWidth(), kind)
}