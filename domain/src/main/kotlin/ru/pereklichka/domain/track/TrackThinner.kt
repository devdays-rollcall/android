package ru.pereklichka.domain.track

import ru.pereklichka.domain.model.Fix
import ru.pereklichka.domain.rules.Geo
import javax.inject.Inject

/**
 * Решает, какие фиксации GPS идут в трек. GPS отдаёт фиксацию раз в 5 секунд, а в трек нужна точка,
 * если человек сместился на [MIN_DISTANCE_M] или с прошлой точки прошло [MAX_INTERVAL_MS]. Так стоящий человек
 * даёт точку раз в минуту, а идущий примерно каждые 15 м.
 *
 * Вызывает `TrackRecorder` для каждой фиксации, пока идёт маршрут. Помнит последнюю взятую фиксацию,
 * поэтому один экземпляр обслуживает один поток фиксаций.
 */
class TrackThinner @Inject constructor() {

    private var lastKept: Fix? = null

    /** true, если фиксацию нужно сохранить как точку трека. Обе границы включаются. */
    fun shouldKeep(fix: Fix): Boolean {
        val previous = lastKept
        // Время считается по монотонным часам: Location.time можно перевести, и тогда пропала бы минутная точка.
        val keep = previous == null ||
            fix.elapsedRealtimeMs - previous.elapsedRealtimeMs >= MAX_INTERVAL_MS ||
            hasMoved(previous, fix)
        if (keep) lastKept = fix
        return keep
    }

    private fun hasMoved(previous: Fix, fix: Fix): Boolean {
        // Неточная фиксация «гуляет» на десятки метров у стоящего человека, смещение по ней не считаем.
        if (!fix.isPreciseEnough() || !previous.isPreciseEnough()) return false
        return Geo.distanceM(previous.position, fix.position) >= MIN_DISTANCE_M
    }

    private fun Fix.isPreciseEnough(): Boolean = accuracyM == null || accuracyM <= MAX_ACCURACY_FOR_DISTANCE_M

    companion object {
        const val MIN_DISTANCE_M = 15.0
        const val MAX_INTERVAL_MS = 60_000L

        /** Фиксация хуже этой точности идёт в трек только по времени. */
        const val MAX_ACCURACY_FOR_DISTANCE_M = 50.0
    }
}
