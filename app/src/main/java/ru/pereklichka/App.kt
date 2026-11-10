package ru.pereklichka

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Приложение. Hilt строит граф зависимостей отсюда, WorkManager берёт фабрику воркеров у Hilt,
 * поэтому стандартный инициализатор WorkManager в манифесте отключён.
 */
@HiltAndroidApp
class App : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
