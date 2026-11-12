package ru.pereklichka.ui.components

import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.time.Age
import java.util.Locale
import kotlin.math.roundToInt

internal val RU: Locale = Locale.forLanguageTag("ru")

private val SIDES = listOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
private const val NEAR_M = 5
private const val STEP_M = 5

/**
 * «2 мин назад», «примерно 2 мин назад» при `approximate`, «только что» до минуты («около минуты назад»
 * при `approximate`), «не слышали» без данных.
 */
fun formatAge(age: Age?): String {
    if (age == null) return "не слышали"
    val minutes = age.ms / 60_000
    val base = when {
        minutes < 1 -> return if (age.approximate) "около минуты назад" else "только что"
        minutes < 60 -> "$minutes мин назад"
        else -> "${minutes / 60} ч назад"
    }
    return if (age.approximate) "примерно $base" else base
}

/**
 * Расстояние и сторона света до человека без подписей, общие для текста на экране и для TalkBack ([spokenPlace]).
 * [meters] округлены до 5 м, чтобы цифры не прыгали, и равны null ближе 5 м: там пишем «рядом», и направление
 * не нужно. [sector] это номер одного из восьми секторов по 45° по часовой от севера.
 */
internal data class Place(val meters: Int?, val sector: Int?)

internal fun placeOf(distanceM: Double, bearingDeg: Double?): Place =
    if (distanceM < NEAR_M) Place(null, null) else Place((distanceM / STEP_M).roundToInt() * STEP_M, bearingDeg?.let(::sectorOf))

/** Место участника в списке; null для меня самого и для того, до кого расстояния нет. */
internal fun placeOf(member: MemberView): Place? {
    if (member.isMe) return null
    return placeOf(member.distanceM ?: return null, member.bearingDeg)
}

/** Сектор 0..7, «С» занимает от −22,5° до +22,5°. */
private fun sectorOf(deg: Double): Int {
    val normalized = ((deg % 360) + 360) % 360
    return ((normalized + 22.5) / 45).toInt() % 8
}

/** Расстояние с округлением до 5 м: «120 м», «1,2 км», «рядом». */
fun formatDistance(m: Double?): String? {
    if (m == null) return null
    val meters = placeOf(m, null).meters ?: return "рядом"
    return if (meters < 1000) "$meters м" else String.format(RU, "%.1f км", meters / 1000.0)
}

/** «120 м, СВ», «рядом» без направления. null для меня самого и без расстояния. */
fun formatPlace(member: MemberView): String? {
    val place = placeOf(member) ?: return null
    if (place.meters == null) return "рядом"
    return listOfNotNull(formatDistance(member.distanceM), place.sector?.let { SIDES[it] }).joinToString(", ")
}

fun formatBattery(percent: Int?): String? = percent?.let { "$it%" }

/** Сколько осталось до конца отлучки или окна переклички: «ещё 8 мин». Минуты округляются вверх. */
fun formatRemaining(ms: Long): String {
    if (ms <= 0) return "время вышло"
    return "ещё ${(ms + 59_999) / 60_000} мин"
}

/** Вторая строка в списке: «1 мин назад · 120 м, СВ · 64%». Пустые части пропускаются. */
fun lastSeenLine(member: MemberView): String = listOfNotNull(
    if (member.isMe) "сейчас" else formatAge(member.lastContact),
    formatPlace(member),
    formatBattery(member.batteryPercent),
).joinToString(" · ")
