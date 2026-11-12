package ru.pereklichka.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/** Строки «Проверки телефона», карточка итога и подписи раздела «Связь с группой». */
class PhoneReadinessTest {

    private val ready = PhoneReadings(
        exchangeGranted = true, fineLocation = true, coarseLocation = true, locationEnabled = true, backgroundLocation = true,
        bluetoothEnabled = true, playServices = true, notifications = true, totalSilence = false, batteryExempt = true,
    )

    @Test
    fun `всё выдано и включено, телефон готов, автозапуск проверяют вручную`() {
        val checks = phoneChecks(ready)

        assertEquals(CheckKind.entries.toList(), checks.map { it.kind })
        assertEquals(CheckLevel.MANUAL, checks.single { it.kind == CheckKind.AUTOSTART }.level)
        assertEquals("Точная, всегда", checks.single { it.kind == CheckKind.LOCATION }.subtitle)
        assertEquals(CheckLevel.OK, phoneSummary(checks).level)
    }

    @Test
    fun `полная тишина даёт одну проблему и ведёт в настройки звука`() {
        val checks = phoneChecks(ready.copy(totalSilence = true))
        val dnd = checks.single { it.kind == CheckKind.DO_NOT_DISTURB }

        assertEquals(Fix.SOUND_SETTINGS, dnd.fix)
        assertEquals(
            PhoneSummary(CheckLevel.WARNING, "Телефон почти готов", "1 проблема может помешать услышать тревогу"),
            phoneSummary(checks),
        )
    }

    @Test
    fun `несколько проблем считаются вместе`() {
        val checks = phoneChecks(ready.copy(bluetoothEnabled = false, notifications = false, batteryExempt = false))

        assertEquals("3 проблемы могут помешать услышать тревогу", phoneSummary(checks).subtitle)
    }

    @Test
    fun `без обязательного разрешения карточка красная, а исправляют на экране разрешений`() {
        val coarseOnly = phoneChecks(ready.copy(fineLocation = false))
        val noExchange = phoneChecks(ready.copy(exchangeGranted = false, bluetoothEnabled = false))

        assertEquals(CheckLevel.REQUIRED, phoneSummary(coarseOnly).level)
        assertEquals(Fix.PERMISSIONS, coarseOnly.single { it.kind == CheckKind.LOCATION }.fix)
        assertEquals(Fix.PERMISSIONS, noExchange.single { it.kind == CheckKind.BLUETOOTH }.fix)
    }

    @Test
    fun `геолокация выключена в системе или без «всегда»`() {
        assertEquals(Fix.LOCATION_SETTINGS, phoneChecks(ready.copy(locationEnabled = false)).single { it.kind == CheckKind.LOCATION }.fix)
        assertEquals(CheckLevel.WARNING, phoneChecks(ready.copy(backgroundLocation = false)).single { it.kind == CheckKind.LOCATION }.level)
        assertEquals("Точная", phoneChecks(ready.copy(backgroundLocation = null)).single { it.kind == CheckKind.LOCATION }.subtitle)
    }

    @Test
    fun `подписи соседей и карты`() {
        assertEquals("Рядом 2 телефона", nearbyTitle(2))
        assertEquals("Рядом никого", nearbyTitle(0))
        assertEquals("Даниил, Ольга · последнее сообщение 3 с назад", nearbySubtitle(listOf("Даниил", "Ольга"), 3_400))
        assertEquals("Последнее сообщение 2 мин назад", nearbySubtitle(emptyList(), 150_000))
        assertEquals("Сообщений от группы не было", nearbySubtitle(emptyList(), null))
        assertEquals("Отправлено 1 240 точек · ждут 12", mapSubtitle(1_240, 12, serverSet = true, error = null).replace(Char(0xA0), ' '))
        assertEquals("Отправлена 1 точка · ждут 0", mapSubtitle(1, 0, serverSet = true, error = null))
        assertEquals("Адрес сервера не задан · ждут 3", mapSubtitle(0, 3, serverSet = false, error = null))
    }

    @Test
    fun `сосед слышен напрямую, через посредника или не слышен`() {
        assertEquals("слышим напрямую · 3 с назад", neighbourSubtitle(3_000, groupHeard = true))
        assertEquals("слышали напрямую 2 мин назад", neighbourSubtitle(120_000, groupHeard = true))
        assertEquals("только через посредника", neighbourSubtitle(null, groupHeard = true))
        assertEquals("не слышно", neighbourSubtitle(null, groupHeard = false))
    }
}
