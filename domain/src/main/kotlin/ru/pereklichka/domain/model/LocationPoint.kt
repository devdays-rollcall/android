package ru.pereklichka.domain.model

import kotlinx.serialization.Serializable

/**
 * Точка трека: одна позиция участника для карты на сервере.
 *
 * Создаёт её хранилище автора в `MeshStore.appendOwnLocation` из фиксации, которую пропустил
 * `TrackThinner`. Потом `SyncEngine` пересылает её соседям, а `UploadWorker` с любого телефона группы
 * отправляет на сервер. [seq] идёт подряд с единицы в потоке точек автора, отдельно от номеров событий.
 *
 * Поля повторяют `LocationData` из `docs/server/openapi.yaml`, единицы те же: метры, м/с, градусы, проценты.
 * Необязательные поля заполнены, только если измерены, нулями их не подменяют. [recordedAtMs] это
 * `Location.time` по часам автора, оно уходит в `recorded_at` и в расчётах на телефоне не участвует.
 */
@Serializable
data class LocationPoint(
    val id: EventId,
    val author: MemberId,
    val seq: Long,
    val latitude: Double,
    val longitude: Double,
    val recordedAtMs: Long,
    val accuracyM: Double? = null,
    val altitudeM: Double? = null,
    val speedMps: Double? = null,
    val bearingDeg: Double? = null,
    val batteryPercent: Int? = null,
) {
    /**
     * true, если все значения конечные и укладываются в диапазоны `LocationData` из `openapi.yaml`.
     *
     * Сервер отклоняет пачку целиком из-за одной неверной точки, поэтому хранилище помечает такие точки
     * `rejected`, а `UploadWorker` проверяет пачку ещё раз перед отправкой. Из непрерывного префикса автора
     * точку при этом не убирают, иначе его поток застрянет.
     *
     * NaN не проходит ни одно сравнение, а бесконечность не попадает в диапазон с конечными границами. Поэтому
     * `isFinite` проверяется только у полей, ограниченных лишь снизу или не ограниченных вовсе.
     */
    fun hasValidValues(): Boolean =
        latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            (accuracyM == null || accuracyM.isFinite() && accuracyM >= 0.0) &&
            (altitudeM == null || altitudeM.isFinite()) &&
            (speedMps == null || speedMps.isFinite() && speedMps >= 0.0) &&
            (bearingDeg == null || bearingDeg >= 0.0 && bearingDeg < 360.0) &&
            (batteryPercent == null || batteryPercent in 0..100)
}
