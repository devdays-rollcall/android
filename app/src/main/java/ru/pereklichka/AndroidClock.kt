package ru.pereklichka

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import ru.pereklichka.domain.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** [Clock] на системных часах Android. В тестах вместо него `FakeClock`. */
@Singleton
class AndroidClock @Inject constructor(@ApplicationContext private val context: Context) : Clock {

    // Номер загрузки не меняется, пока процесс жив, поэтому читается один раз.
    private val bootId: String by lazy {
        val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        if (bootCount >= 0) {
            bootCount.toString()
        } else {
            // Без BOOT_COUNT загрузку отличаем по её моменту на настенных часах, округлённому вниз до минуты.
            // На границе минуты или после перевода часов id может смениться, и возрасты станут примерными.
            "t" + (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60_000
        }
    }

    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()

    override fun wallMs(): Long = System.currentTimeMillis()

    override fun bootId(): String = bootId
}
