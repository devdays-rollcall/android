package ru.pereklichka.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Стрелка «Назад» из макета: шеврон 24 dp, линия 2,2. Своя, чтобы не тянуть библиотеку иконок ради одной стрелки. */
private val BackChevron: ImageVector = ImageVector.Builder(
    name = "BackChevron",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).path(
    stroke = SolidColor(Color.Black),
    strokeLineWidth = 2.2f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
) {
    moveTo(15f, 5f)
    lineToRelative(-7f, 7f)
    lineToRelative(7f, 7f)
}.build()

/** Кнопка «Назад» в шапке экрана: зона нажатия 48 dp, описание для TalkBack. */
@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onBackground) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            imageVector = BackChevron,
            contentDescription = "Назад",
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
}
