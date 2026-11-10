package ru.pereklichka.domain.time

/**
 * Часы телефона. На телефоне это `AndroidClock`, в тестах [ru.pereklichka.domain.testing.FakeClock].
 *
 * Возраст записей считается только по [elapsedMs]: эти часы монотонны и идут во сне. При перезагрузке
 * они обнуляются, и это видно по смене [bootId]. Настенные часы [wallMs] человек может перевести,
 * поэтому они нужны только как запасной вариант после перезагрузки и для подписей.
 */
interface Clock {
    /** Миллисекунды с загрузки телефона, `SystemClock.elapsedRealtime()`. */
    fun elapsedMs(): Long

    /** Миллисекунды от эпохи по настенным часам, `System.currentTimeMillis()`. */
    fun wallMs(): Long

    /** Номер загрузки телефона, `Settings.Global.BOOT_COUNT`. */
    fun bootId(): String
}
