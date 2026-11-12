package ru.pereklichka.upload

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.pereklichka.domain.model.LocationPoint
import java.time.Instant

/**
 * `EventBatch`: пачка не больше 500 событий (`UploadWorker` шлёт по 200), [senderId] равен `member_id` этого телефона.
 * Пустую пачку `UploadWorker` шлёт как heartbeat, когда маршрут идёт, а новых точек нет, не чаще раза в минуту.
 * Её же шлёт `SosWorker`, если сервер ещё не знает группу: группа появляется на сервере после первой пачки.
 */
@Serializable
data class EventBatchDto(
    @SerialName("sender_id") val senderId: String,
    val events: List<EventInputDto>,
)

/**
 * `EventInput`. В [id] лежит UUID точки, он не меняется ни при пересылке, ни при повторной отправке. [username]
 * показывает карта рядом с участником и уведомление о SOS. Имя берётся из JOINED автора точки; если оно ещё не дошло
 * до этого телефона, поле не отправляется, и карта покажет только `member_id`.
 */
@Serializable
data class EventInputDto(
    val id: String,
    @SerialName("member_id") val memberId: String,
    val type: EventTypeDto,
    /** RFC 3339 с `Z`, например `2026-11-09T12:00:00.120Z`. */
    @SerialName("recorded_at") val recordedAt: String,
    val data: LocationDataDto,
    /** От 1 до [MAX_USERNAME_LENGTH] кодовых точек, пустое или более длинное имя сервер отклоняет ответом 422. */
    val username: String? = null,
)

/** Контракт сервера допускает единственный тип события; другой отклоняет всю пачку. */
@Serializable
enum class EventTypeDto {
    @SerialName("location")
    LOCATION,
}

/**
 * `LocationData`. Отсутствующие измерения не отправляются вовсе: JSON настроен с `explicitNulls = false`,
 * а подменять их нулями спецификация запрещает.
 */
@Serializable
data class LocationDataDto(
    val latitude: Double,
    val longitude: Double,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
    @SerialName("altitude_m") val altitudeM: Double? = null,
    @SerialName("speed_mps") val speedMps: Double? = null,
    @SerialName("bearing_deg") val bearingDeg: Double? = null,
    @SerialName("battery_percent") val batteryPercent: Int? = null,
)

/** `Accepted`: тело ответа 202, сервер принял всю пачку. */
@Serializable
data class AcceptedDto(val accepted: Int)

@Serializable
data class EventOutputDto(
    val id: String,
    @SerialName("member_id") val memberId: String,
    val type: EventTypeDto,
    @SerialName("recorded_at") val recordedAt: String,
    @SerialName("received_at") val receivedAt: String,
    val data: LocationDataDto,
    val username: String? = null,
)

@Serializable
data class EventPageDto(
    val events: List<EventOutputDto>,
    @SerialName("next_cursor") val nextCursor: String?,
)

@Serializable
data class AlertResultDto(val notified: Boolean)

@Serializable
data class ShareTokenDto(val token: String)

/** Предел длины `username` в контракте сервера, в кодовых точках Unicode. */
const val MAX_USERNAME_LENGTH = 100

/**
 * Точка трека в виде события для сервера, [username] это имя её автора. Проверку значений перед отправкой делает
 * `UploadWorker`. Пустое имя не отправляется, а длинное укорачивается до [MAX_USERNAME_LENGTH] кодовых точек:
 * иначе сервер отклонил бы точку из-за имени.
 */
fun LocationPoint.toEventInput(username: String? = null): EventInputDto = EventInputDto(
    id = id.value,
    memberId = author.value,
    type = EventTypeDto.LOCATION,
    recordedAt = Instant.ofEpochMilli(recordedAtMs).toString(),
    data = LocationDataDto(
        latitude = latitude,
        longitude = longitude,
        accuracyM = accuracyM,
        altitudeM = altitudeM,
        speedMps = speedMps,
        bearingDeg = bearingDeg,
        batteryPercent = batteryPercent,
    ),
    username = username?.trim()?.takeIf { it.isNotEmpty() }?.let(::limitUsername),
)

private fun limitUsername(name: String): String {
    if (name.codePointCount(0, name.length) <= MAX_USERNAME_LENGTH) return name
    return name.substring(0, name.offsetByCodePoints(0, MAX_USERNAME_LENGTH)).trimEnd()
}
