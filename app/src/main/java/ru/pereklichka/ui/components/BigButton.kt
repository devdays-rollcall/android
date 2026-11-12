package ru.pereklichka.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private val BorderWidth = 1.dp

/**
 * Вид кнопки: главное действие, тональное (второе по важности, на светло-зелёном фоне), второстепенное с рамкой,
 * опасное сплошное и опасное тональное.
 */
enum class ButtonKind { PRIMARY, TONAL, SECONDARY, DANGER, DANGER_TONAL }

/**
 * Кнопка-овал из макета. Высота 56 dp, с [large] 64 dp: на ходу и в перчатках попасть должно быть легко.
 * [icon] стоит перед текстом.
 */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.PRIMARY,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
    large: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = when (kind) {
        ButtonKind.PRIMARY -> ButtonDefaults.buttonColors()
        ButtonKind.TONAL -> ButtonDefaults.buttonColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer)
        ButtonKind.SECONDARY -> ButtonDefaults.buttonColors(containerColor = scheme.background, contentColor = scheme.primary)
        ButtonKind.DANGER -> ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)
        ButtonKind.DANGER_TONAL -> ButtonDefaults.buttonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer)
    }
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = if (large) 64.dp else 56.dp),
        enabled = enabled,
        shape = CircleShape,
        colors = colors,
        border = if (kind == ButtonKind.SECONDARY) BorderStroke(BorderWidth, scheme.outline) else null,
    ) {
        if (icon != null) {
            SymIcon(icon, size = 22.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/** Кнопка на всю ширину. */
@Composable
fun BigButtonFull(
    text: String,
    onClick: () -> Unit,
    kind: ButtonKind = ButtonKind.PRIMARY,
    @DrawableRes icon: Int? = null,
    large: Boolean = false,
    enabled: Boolean = true,
) {
    BigButton(text, onClick, Modifier.fillMaxWidth(), kind, enabled, icon, large)
}
