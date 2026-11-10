package ru.pereklichka.ui.components

import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.time.Age
import java.util.Locale
import kotlin.math.roundToInt


private val SIDES = listOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
private const val NEAR_M = 5

/** «2 мин назад», «примерно 2 мин назад» при `approximate`, «только что» до минуты, «не слышали» без данных. */
fun formatAge(age: Age?): String {
    if (age == null) return "не слышали"
    val minutes = age.ms / 60_000
    val base = when {
        minutes < 1 -> return "только что"
        minutes < 60 -> "$minutes мин назад"
        else -> "${minutes / 60} ч назад"
    }
    return if (age.approximate) "примерно $base" else base
}

/** Расстояние с округлением до 5 м, чтобы цифры не прыгали: «120 м», «1,2 км», «рядом». */
fun formatDistance(m: Double?): String? {
    if (m == null) return null
    if (m < NEAR_M) return "рядом"
    val rounded = (m / 5).roundToInt() * 5
    return if (rounded < 1000) "$rounded м" else String.format(Locale.forLanguageTag("ru"), "%.1f км", m / 1000)
}

/** Сторона света по азимуту от севера: восемь секторов по 45°, «С» занимает от −22,5° до +22,5°. */
fun bearingText(deg: Double?): String? {
    if (deg == null) return null
    val normalized = ((deg % 360) + 360) % 360
    return SIDES[((normalized + 22.5) / 45).toInt() % 8]
}

/** «120 м, СВ». Если человек рядом, направление не нужно. */
fun formatPlace(distanceM: Double?, bearingDeg: Double?): String? {
    val distance = formatDistance(distanceM) ?: return null
    if (distanceM != null && distanceM < NEAR_M) return distance
    return listOfNotNull(distance, bearingText(bearingDeg)).joinToString(", ")
}

fun formatBattery(percent: Int?): String? = percent?.let { "$it%" }

/** Сколько осталось до конца отлучки или окна переклички: «ещё 8 мин». Минуты округляются вверх. */
fun formatRemaining(ms: Long): String {
    if (ms <= 0) return "время вышло"
    val minutes = (ms + 59_999) / 60_000
    return if (minutes < 1) "меньше минуты" else "ещё $minutes мин"
}

/** Вторая строка в списке: «1 мин назад · 120 м, СВ · 64%». Пустые части пропускаются. */
fun lastSeenLine(member: MemberView): String = listOfNotNull(
    if (member.isMe) "сейчас" else formatAge(member.lastContact),
    formatPlace(member.distanceM, member.bearingDeg),
    formatBattery(member.batteryPercent),
).joinToString(" · ")