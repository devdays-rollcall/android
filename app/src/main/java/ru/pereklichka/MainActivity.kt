package ru.pereklichka

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import ru.pereklichka.ui.navigation.AppNavHost
import ru.pereklichka.ui.theme.PereklichkaTheme

/** Единственная Activity: всё остальное экраны Compose в [AppNavHost]. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PereklichkaTheme {
                AppNavHost()
            }
        }
    }
}
