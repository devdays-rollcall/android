package ru.pereklichka.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Группа на телефоне: активная, с которой идёт обмен, или прошлый маршрут. Ключ строки [myMemberId], то есть мой
 * `member_id` в группе. Он новый при каждом создании и вступлении, поэтому повторное вступление по тому же QR после
 * выхода даёт отдельную строку, и записи двух вступлений не смешиваются. Записи группы в остальных таблицах несут
 * этот ключ в колонке `groupKey`. [active] стоит не больше чем у одной строки. Пишет таблицу только `GroupRepository`.
 */
@Entity(tableName = "group_info", indices = [Index(value = ["active"])])
data class GroupInfoEntity(
    @PrimaryKey val myMemberId: String,
    val tag: String,
    val token: String,
    val name: String,
    val leaderId: String,
    val createdAtWallMs: Long,
    val active: Boolean,
)

/** Колонки отметки свежести (`Stamped`), встраиваются в строки событий и состояний. */
data class StampColumns(
    val ageAtReceiptMs: Long,
    val receivedAtElapsedMs: Long,
    val receivedBootId: String,
    val receivedAtWallMs: Long,
)

/**
 * Строка события любого автора в группе [groupKey]. Тройка (groupKey, author, seq) уникальна, поэтому база не даст
 * записать в поток автора два события с одним номером. [ignored] стоит у события незнакомого типа, чтобы такие строки
 * было видно в базе. Правила отсеивают их по `EventType.UNKNOWN` после чтения.
 */
@Entity(
    tableName = "control_event",
    primaryKeys = ["groupKey", "id"],
    indices = [Index(value = ["groupKey", "author", "seq"], unique = true)],
)
data class ControlEventEntity(
    val groupKey: String,
    val id: String,
    val author: String,
    val seq: Long,
    val type: String,
    val target: String?,
    val refs: List<String>,
    val name: String?,
    val durationMs: Long?,
    val resolveKind: String?,
    val createdAtWallMs: Long,
    val ignored: Boolean,
    @Embedded val stamp: StampColumns,
)

/**
 * Строка точки трека любого автора в группе [groupKey]. [uploaded] ставит `UploadWorker` после ответа 202,
 * [rejected] стоит у точки, которую нельзя отправлять на сервер. Других изменений у точки после записи не бывает.
 *
 * Широта и долгота допускают NULL: SQLite хранит NaN как NULL, а чужую точку с NaN всё равно нужно сохранить,
 * иначе поток её автора встанет. Маппер возвращает NULL обратно как NaN.
 */
@Entity(
    tableName = "location_point",
    primaryKeys = ["groupKey", "id"],
    indices = [Index(value = ["groupKey", "author", "seq"], unique = true), Index(value = ["groupKey", "uploaded"])],
)
data class LocationPointEntity(
    val groupKey: String,
    val id: String,
    val author: String,
    val seq: Long,
    val latitude: Double?,
    val longitude: Double?,
    val recordedAtMs: Long,
    val accuracyM: Double?,
    val altitudeM: Double?,
    val speedMps: Double?,
    val bearingDeg: Double?,
    val batteryPercent: Int?,
    val uploaded: Boolean = false,
    val rejected: Boolean = false,
)

/** Строка последнего состояния участника, одна на участника в группе [groupKey]. */
@Entity(tableName = "member_state", primaryKeys = ["groupKey", "memberId"])
data class MemberStateEntity(
    val groupKey: String,
    val memberId: String,
    val seq: Long,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Double?,
    val fixAgeMs: Long?,
    val batteryPercent: Int?,
    val heardAgoMs: Map<String, Long>,
    @Embedded val stamp: StampColumns,
)

/** Строка запроса «последний номер по каждому автору». */
data class AuthorMaxSeq(val author: String, val maxSeq: Long)

/** Строка запроса имён из событий JOINED. */
data class AuthorName(val author: String, val name: String)

/**
 * Строка списка прошлых маршрутов: группа и сводка по её записям. Время начала и конца маршрута берётся
 * из событий руководителя по часам этого телефона (время приёма минус возраст записи при приёме), [members]
 * считает участников с событием JOINED, [iLeft] значит, что я сам вышел из группы событием LEFT.
 */
data class PastRouteRow(
    @Embedded val group: GroupInfoEntity,
    val startedAtWallMs: Long?,
    val finishedAtWallMs: Long?,
    val members: Int,
    val points: Int,
    val unsentPoints: Int,
    val iLeft: Boolean,
)
