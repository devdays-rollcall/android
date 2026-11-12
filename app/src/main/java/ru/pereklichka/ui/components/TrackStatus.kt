package ru.pereklichka.ui.components

/**
 * Отправка трека активной группы: [points] точек в базе, из них [unsent] ещё ждут отправки, [hasServer] false,
 * если адрес сервера не задан и отправлять некуда.
 */
data class TrackStatus(val points: Int, val unsent: Int, val hasServer: Boolean)

/**
 * Строка о треке для стартовой карточки и экрана завершения: «трек отправлен», «12 точек трека ждут интернета»,
 * «адрес сервера не задан». null, если точек нет и говорить не о чем.
 */
fun trackLine(status: TrackStatus): String? = when {
    status.points == 0 -> null
    status.unsent == 0 -> "трек отправлен"
    !status.hasServer -> "адрес сервера не задан, трек не отправляется"
    else -> "${status.unsent} ${plural(status.unsent, "точка", "точки", "точек")} трека " +
        "${plural(status.unsent, "ждёт", "ждут", "ждут")} интернета"
}
