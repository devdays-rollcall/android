package ru.pereklichka.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Общая разметка экранов-заглушек: заголовок, подпись «заглушка» и кнопки переходов. Нужна, чтобы по экранам
 * можно было пройти до того, как Валентин их нарисует. Когда заглушек не останется, файл удаляется.
 */
@Composable
fun StubScreen(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text("Экран-заглушка", style = MaterialTheme.typography.labelMedium)
            content()
        }
    }
}

/** Кнопка во всю ширину для заглушек. */
@Composable
fun StubButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(text) }
}
