package ru.pereklichka

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Отсюда Hilt строит граф зависимостей, и отсюда же WorkManager берёт фабрику воркеров Hilt, чтобы `UploadWorker`
 * получил свои зависимости. Поэтому стандартный инициализатор WorkManager в манифесте отключён.
 */
@HiltAndroidApp
class App : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
