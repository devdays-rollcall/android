package ru.pereklichka.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Размечает экран со своей верхней частью вместо шапки [AppScreen]. Так устроены старт с шестерёнкой, разрешения
 * с крупным заголовком и сбор с названием группы и кнопкой «Поделиться». [top] стоит на месте, содержимое
 * прокручивается, [bottom] прижат к низу. Отступы от системных панелей и клавиатуры учтены здесь.
 */
@Composable
fun CustomTopScreen(
    top: @Composable () -> Unit,
    bottom: @Composable ColumnScope.() -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
    contentSpacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding()) {
            top()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(contentSpacing),
                content = content,
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = bottom,
            )
        }
    }
}
