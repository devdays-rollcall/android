package ru.pereklichka.ui.components

import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.time.Age
import java.util.Locale
import kotlin.math.roundToInt


private val SIDES_SPOKEN = listOf(
    "север", "северо-восток", "восток", "юго-восток", "юг", "юго-запад", "запад", "северо-запад",
)

/** Выбор формы слова по числу: 1 минуту, 2 минуты, 5 минут; 11 минут, 21 минуту. */
fun plural(n: Int, one: String, few: String, many: String): String = when {
    n % 10 == 1 && n % 100 != 11 -> one
    n % 10 in 2..4 && n % 100 !in 12..14 -> few
    else -> many
}

fun spokenAge(age: Age?): String {
    if (age == null) return "не слышали"
    val minutes = (age.ms / 60_000).toInt()
    val base = when {
        minutes < 1 -> return "слышали только что"
        minutes < 60 -> "$minutes ${plural(minutes, "минуту", "минуты", "минут")} назад"
        else -> (minutes / 60).let { "$it ${plural(it, "час", "часа", "часов")} назад" }
    }
    return "слышали ${if (age.approximate) "примерно " else ""}$base"
}

fun spokenPlace(distanceM: Double?, bearingDeg: Double?): String? {
    if (distanceM == null) return null
    if (distanceM < 5) return "рядом"
    val rounded = (distanceM / 5).roundToInt() * 5
    val distance = if (rounded < 1000) {
        "$rounded ${plural(rounded, "метр", "метра", "метров")}"
    } else {
        String.format(Locale.forLanguageTag("ru"), "%.1f километра", distanceM / 1000)
    }
    val side = bearingDeg?.let {
        val normalized = ((it % 360) + 360) % 360
        SIDES_SPOKEN[((normalized + 22.5) / 45).toInt() % 8]
    }
    return listOfNotNull(distance, side).joinToString(", ")
}

/** Одно описание строки для TalkBack: «Мария, не слышно, слышали 3 минуты назад, 340 метров, юго-запад, заряд 18 процентов». */
fun spokenSummary(member: MemberView, status: StatusInfo): String = buildList {
    add(if (member.isMe) "${member.name}, это вы" else member.name)
    if (member.isLeader) add("руководитель")
    add(status.text)
    if (!member.isMe) add(spokenAge(member.lastContact))
    spokenPlace(member.distanceM, member.bearingDeg)?.let { add(it) }
    member.batteryPercent?.let { add("заряд $it ${plural(it, "процент", "процента", "процентов")}") }
}.joinToString(", ")