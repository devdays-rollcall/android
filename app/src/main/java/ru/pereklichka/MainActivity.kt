package ru.pereklichka

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import dagger.hilt.android.AndroidEntryPoint
import ru.pereklichka.notify.NotificationTarget
import ru.pereklichka.ui.navigation.AppNavHost
import ru.pereklichka.ui.theme.PereklichkaTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val openTarget = mutableStateOf<NotificationTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openTarget.value = NotificationTarget.from(intent)
        setContent {
            PereklichkaTheme {
                AppNavHost(
                    openTarget = openTarget.value,
                    onOpenHandled = { openTarget.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationTarget.from(intent)?.let { openTarget.value = it }
    }
}
