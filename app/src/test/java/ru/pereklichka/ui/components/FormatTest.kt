package ru.pereklichka.ui.components

import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.domain.time.Age
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Подписи времени, расстояния и направления на экране и для TalkBack. */
class FormatTest {

    @Test
    fun `возраст до минуты только что, а примерный около минуты`() {
        assertEquals("только что", formatAge(Age(59_999, approximate = false)))
        assertEquals("около минуты назад", formatAge(Age(10_000, approximate = true)))
        assertEquals("слышали только что", spokenAge(Age(10_000, approximate = false)))
        assertEquals("слышали около минуты назад", spokenAge(Age(10_000, approximate = true)))
    }

    @Test
    fun `возраст в минутах и часах, с примерно при approximate`() {
        assertEquals("2 мин назад", formatAge(Age(2 * 60_000 + 30_000, approximate = false)))
        assertEquals("примерно 2 мин назад", formatAge(Age(2 * 60_000, approximate = true)))
        assertEquals("1 ч назад", formatAge(Age(60 * 60_000, approximate = false)))
        assertEquals("не слышали", formatAge(null))
        assertEquals("слышали 21 минуту назад", spokenAge(Age(21 * 60_000, approximate = false)))
        assertEquals("слышали примерно 2 часа назад", spokenAge(Age(2 * 3_600_000, approximate = true)))
        assertEquals("не слышали", spokenAge(null))
    }

    @Test
    fun `расстояние округляется до 5 м, ближе 5 м рядом`() {
        assertEquals("рядом", formatDistance(4.9))
        assertEquals("5 м", formatDistance(5.0))
        assertEquals("120 м", formatDistance(122.4))
        assertEquals("125 м", formatDistance(122.5))
        assertEquals("1,2 км", formatDistance(1_234.0))
        assertNull(formatDistance(null))
    }

    @Test
    fun `восемь секторов по 45 градусов, север от минус 22,5 до плюс 22,5`() {
        assertEquals("С", bearingText(0.0))
        assertEquals("С", bearingText(22.4))
        assertEquals("СВ", bearingText(22.5))
        assertEquals("В", bearingText(90.0))
        assertEquals("ЮЗ", bearingText(225.0))
        assertEquals("С", bearingText(337.5))
        assertEquals("СЗ", bearingText(-30.0))
        assertEquals("С", bearingText(360.0))
        assertNull(bearingText(null))
    }

    @Test
    fun `место на экране и для TalkBack берёт одни числа`() {
        val far = member(distanceM = 338.0, bearingDeg = 225.0)
        assertEquals("340 м, ЮЗ", formatPlace(far))
        assertEquals("340 метров, юго-запад", spokenPlace(far))

        val near = member(distanceM = 3.0, bearingDeg = 90.0)
        assertEquals("рядом", formatPlace(near))
        assertEquals("рядом", spokenPlace(near))

        assertEquals("1,5 км, В", formatPlace(member(distanceM = 1_500.0, bearingDeg = 90.0)))
        assertEquals("1,5 километра, восток", spokenPlace(member(distanceM = 1_500.0, bearingDeg = 90.0)))
    }

    @Test
    fun `для себя место не показывается`() {
        val me = member(distanceM = 120.0, bearingDeg = 0.0, isMe = true)
        assertNull(formatPlace(me))
        assertNull(spokenPlace(me))
        assertNull(formatPlace(member(distanceM = null, bearingDeg = 0.0)))
    }

    @Test
    fun `форма слова по числу`() {
        assertEquals("минуту", plural(1, "минуту", "минуты", "минут"))
        assertEquals("минуты", plural(3, "минуту", "минуты", "минут"))
        assertEquals("минут", plural(5, "минуту", "минуты", "минут"))
        assertEquals("минут", plural(11, "минуту", "минуты", "минут"))
        assertEquals("минут", plural(12, "минуту", "минуты", "минут"))
        assertEquals("минуту", plural(21, "минуту", "минуты", "минут"))
        assertEquals("минуты", plural(104, "минуту", "минуты", "минут"))
        assertEquals("минут", plural(0, "минуту", "минуты", "минут"))
    }

    @Test
    fun `оставшееся время округляется вверх`() {
        assertEquals("ещё 1 мин", formatRemaining(1))
        assertEquals("ещё 8 мин", formatRemaining(7 * 60_000 + 1))
        assertEquals("время вышло", formatRemaining(0))
    }
}

internal fun member(
    distanceM: Double? = null,
    bearingDeg: Double? = null,
    isMe: Boolean = false,
    presence: Presence = Presence.OK,
    awayRemainingMs: Long? = null,
    hasActiveAlarm: Boolean = false,
    hasSos: Boolean = false,
) = MemberView(
    id = MemberId("8c1e5b0a-3f2d-4c6e-9a7b-1d2e3f4a5b6c"),
    name = "Ольга",
    isLeader = false,
    isMe = isMe,
    presence = presence,
    lastContact = null,
    heardDirectlyNow = false,
    lastHeardBy = null,
    position = null,
    positionAge = null,
    accuracyM = null,
    distanceM = distanceM,
    bearingDeg = bearingDeg,
    batteryPercent = null,
    awayRemainingMs = awayRemainingMs,
    hasActiveAlarm = hasActiveAlarm,
    hasSos = hasSos,
)
