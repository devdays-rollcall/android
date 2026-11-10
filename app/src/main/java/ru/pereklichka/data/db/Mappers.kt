package ru.pereklichka.data.db

import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.LatLon
import ru.pereklichka.domain.model.LocationPoint
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.MemberState
import ru.pereklichka.domain.model.ResolveKind
import ru.pereklichka.domain.time.Stamped

// Перевод строк базы в модели domain и обратно. Пользуются ими RoomMeshStore и GroupRepository.
// Строки хранят id и перечисления текстом, поэтому незнакомое значение из базы становится
// EventType.UNKNOWN или null, и чтение не падает.

fun GroupInfoEntity.toModel(): GroupInfo = GroupInfo(
    tag = tag,
    token = token,
    name = name,
    leaderId = MemberId(leaderId),
    me = MemberId(myMemberId),
    createdAtWallMs = createdAtWallMs,
)

fun GroupInfo.toEntity(): GroupInfoEntity = GroupInfoEntity(
    tag = tag,
    token = token,
    name = name,
    leaderId = leaderId.value,
    myMemberId = me.value,
    createdAtWallMs = createdAtWallMs,
)

fun ControlEventEntity.toModel(): Stamped<ControlEvent> = stamp.toStamped(
    ControlEvent(
        id = EventId(id),
        author = MemberId(author),
        seq = seq,
        createdAtWallMs = createdAtWallMs,
        type = EventType.entries.firstOrNull { it.name == type } ?: EventType.UNKNOWN,
        target = target?.let(::MemberId),
        refs = refs.map(::EventId),
        name = name,
        durationMs = durationMs,
        resolveKind = ResolveKind.entries.firstOrNull { it.name == resolveKind },
    ),
)

fun Stamped<ControlEvent>.toEntity(): ControlEventEntity = ControlEventEntity(
    id = value.id.value,
    author = value.author.value,
    seq = value.seq,
    type = value.type.name,
    target = value.target?.value,
    refs = value.refs.map { it.value },
    name = value.name,
    durationMs = value.durationMs,
    resolveKind = value.resolveKind?.name,
    createdAtWallMs = value.createdAtWallMs,
    ignored = value.type == EventType.UNKNOWN,
    stamp = toStampColumns(),
)

fun LocationPointEntity.toModel(): LocationPoint = LocationPoint(
    id = EventId(id),
    author = MemberId(author),
    seq = seq,
    latitude = latitude ?: Double.NaN,
    longitude = longitude ?: Double.NaN,
    recordedAtMs = recordedAtMs,
    accuracyM = accuracyM,
    altitudeM = altitudeM,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    batteryPercent = batteryPercent,
)

/** Строка для новой точки. [LocationPointEntity.rejected] ставится, если точка не проходит `hasValidValues()`. */
fun LocationPoint.toEntity(): LocationPointEntity = LocationPointEntity(
    id = id.value,
    author = author.value,
    seq = seq,
    latitude = latitude,
    longitude = longitude,
    recordedAtMs = recordedAtMs,
    accuracyM = accuracyM,
    altitudeM = altitudeM,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    batteryPercent = batteryPercent,
    rejected = !hasValidValues(),
)

fun MemberStateEntity.toModel(): Stamped<MemberState> = stamp.toStamped(
    MemberState(
        member = MemberId(memberId),
        seq = seq,
        position = if (latitude != null && longitude != null) LatLon(latitude, longitude) else null,
        accuracyM = accuracyM,
        fixAgeMs = fixAgeMs,
        batteryPercent = batteryPercent,
        heardAgoMs = heardAgoMs.mapKeys { (member, _) -> MemberId(member) },
    ),
)

fun Stamped<MemberState>.toEntity(): MemberStateEntity = MemberStateEntity(
    memberId = value.member.value,
    seq = value.seq,
    latitude = value.position?.latitude,
    longitude = value.position?.longitude,
    accuracyM = value.accuracyM,
    fixAgeMs = value.fixAgeMs,
    batteryPercent = value.batteryPercent,
    heardAgoMs = value.heardAgoMs.mapKeys { (member, _) -> member.value },
    stamp = toStampColumns(),
)

private fun <T> StampColumns.toStamped(value: T): Stamped<T> =
    Stamped(value, ageAtReceiptMs, receivedAtElapsedMs, receivedBootId, receivedAtWallMs)

private fun Stamped<*>.toStampColumns(): StampColumns =
    StampColumns(ageAtReceiptMs, receivedAtElapsedMs, receivedBootId, receivedAtWallMs)
