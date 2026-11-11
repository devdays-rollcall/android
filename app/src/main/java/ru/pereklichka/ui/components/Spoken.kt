package ru.pereklichka.ui.components

import ru.pereklichka.domain.rules.MemberView
import ru.pereklichka.domain.time.Age

private val SIDES_SPOKEN = listOf(
    "север", "северо-восток", "восток", "юго-восток", "юг", "юго-запад", "запад", "северо-запад",
)

/** Выбор формы слова по числу: 1 минуту, 2 минуты, 5 минут; 11 минут, 21 минуту. */
fun plural(n: Int, one: String, few: String, many: String): String = when {
    n % 10 == 1 && n % 100 != 11 -> one
    n % 10 in 2..4 && n % 100 !in 12..14 -> few
    else -> many
}

/** «слышали 3 минуты назад», «слышали примерно 2 часа назад», «слышали около минуты назад», «не слышали». */
fun spokenAge(age: Age?): String {
    if (age == null) return "не слышали"
    val minutes = (age.ms / 60_000).toInt()
    val base = when {
        minutes < 1 -> return if (age.approximate) "слышали около минуты назад" else "слышали только что"
        minutes < 60 -> "$minutes ${plural(minutes, "минуту", "минуты", "минут")} назад"
        else -> (minutes / 60).let { "$it ${plural(it, "час", "часа", "часов")} назад" }
    }
    return "слышали ${if (age.approximate) "примерно " else ""}$base"
}

/** «340 метров, юго-запад», «рядом». null для меня самого и без расстояния. Числа те же, что в [formatPlace]. */
fun spokenPlace(member: MemberView): String? {
    val place = placeOf(member) ?: return null
    val meters = place.meters ?: return "рядом"
    val distance = if (meters < 1000) {
        "$meters ${plural(meters, "метр", "метра", "метров")}"
    } else {
        String.format(RU, "%.1f километра", meters / 1000.0)
    }
    return listOfNotNull(distance, place.sector?.let { SIDES_SPOKEN[it] }).joinToString(", ")
}

/** Одно описание строки для TalkBack: «Мария, не слышно, слышали 3 минуты назад, 340 метров, юго-запад, заряд 18 процентов». */
fun spokenSummary(member: MemberView, status: StatusInfo): String = buildList {
    add(if (member.isMe) "${member.name}, это вы" else member.name)
    if (member.isLeader) add("руководитель")
    add(status.text)
    if (!member.isMe) add(spokenAge(member.lastContact))
    spokenPlace(member)?.let { add(it) }
    member.batteryPercent?.let { add("заряд $it ${plural(it, "процент", "процента", "процентов")}") }
}.joinToString(", ")
