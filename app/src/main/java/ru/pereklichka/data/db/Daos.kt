package ru.pereklichka.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Ключ активной группы внутри запроса; без активной группы подзапрос даёт NULL, и запрос ничего не находит. */
private const val ACTIVE_KEY = "(SELECT myMemberId FROM group_info WHERE active = 1)"

/** Запросы к группам: активной и прошлым маршрутам. */
@Dao
interface GroupDao {
    @Query("SELECT * FROM group_info WHERE active = 1 LIMIT 1")
    suspend fun active(): GroupInfoEntity?

    @Query("SELECT * FROM group_info WHERE active = 1 LIMIT 1")
    fun observeActive(): Flow<GroupInfoEntity?>

    @Query("SELECT * FROM group_info WHERE myMemberId = :key")
    suspend fun get(key: String): GroupInfoEntity?

    @Query("SELECT * FROM group_info WHERE myMemberId = :key")
    fun observe(key: String): Flow<GroupInfoEntity?>

    @Insert
    suspend fun insert(group: GroupInfoEntity)

    /** Переводит активную группу в прошлые маршруты. */
    @Query("UPDATE group_info SET active = 0 WHERE active = 1")
    suspend fun deactivate()

    @Query("DELETE FROM group_info WHERE myMemberId = :key")
    suspend fun delete(key: String)

    /** Прошлые маршруты, у которых остались неотправленные точки, старые первыми. */
    @Query(
        "SELECT * FROM group_info g WHERE active = 0 AND EXISTS (SELECT 1 FROM location_point p " +
            "WHERE p.groupKey = g.myMemberId AND p.uploaded = 0 AND p.rejected = 0) ORDER BY createdAtWallMs",
    )
    suspend fun pastWithUnsentPoints(): List<GroupInfoEntity>

    /** Прошлые маршруты со сводкой для стартового экрана, новые первыми. */
    @Query(
        "SELECT g.*, " +
            "(SELECT MIN(e.receivedAtWallMs - e.ageAtReceiptMs) FROM control_event e WHERE e.groupKey = g.myMemberId " +
            "AND e.author = g.leaderId AND e.type = 'ROUTE_STARTED') AS startedAtWallMs, " +
            "(SELECT MIN(e.receivedAtWallMs - e.ageAtReceiptMs) FROM control_event e WHERE e.groupKey = g.myMemberId " +
            "AND e.author = g.leaderId AND e.type = 'ROUTE_FINISHED') AS finishedAtWallMs, " +
            "(SELECT COUNT(DISTINCT e.author) FROM control_event e WHERE e.groupKey = g.myMemberId " +
            "AND e.type = 'JOINED') AS members, " +
            "(SELECT COUNT(*) FROM location_point p WHERE p.groupKey = g.myMemberId) AS points, " +
            "(SELECT COUNT(*) FROM location_point p WHERE p.groupKey = g.myMemberId " +
            "AND p.uploaded = 0 AND p.rejected = 0) AS unsentPoints, " +
            "EXISTS (SELECT 1 FROM control_event e WHERE e.groupKey = g.myMemberId " +
            "AND e.author = g.myMemberId AND e.type = 'LEFT') AS iLeft " +
            "FROM group_info g WHERE g.active = 0 ORDER BY g.createdAtWallMs DESC",
    )
    fun observePast(): Flow<List<PastRouteRow>>
}

/** Запросы к событиям для `RoomMeshStore` и `RouteHistory`. */
@Dao
interface ControlEventDao {
    /** Падает на повторном id или паре (author, seq) в группе: хранилище проверяет это до вставки. */
    @Insert
    suspend fun insert(event: ControlEventEntity)

    @Query("SELECT author, MAX(seq) AS maxSeq FROM control_event WHERE groupKey = :groupKey GROUP BY author")
    suspend fun maxSeqByAuthor(groupKey: String): List<AuthorMaxSeq>

    /** null, если у автора нет событий. */
    @Query("SELECT MAX(seq) FROM control_event WHERE groupKey = :groupKey AND author = :author")
    suspend fun maxSeq(groupKey: String, author: String): Long?

    @Query(
        "SELECT * FROM control_event WHERE groupKey = :groupKey AND author = :author AND seq > :afterSeq " +
            "ORDER BY seq LIMIT :limit",
    )
    suspend fun range(groupKey: String, author: String, afterSeq: Long, limit: Int): List<ControlEventEntity>

    @Query("SELECT * FROM control_event WHERE groupKey = :groupKey AND author = :author AND seq = :seq")
    suspend fun find(groupKey: String, author: String, seq: Long): ControlEventEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM control_event WHERE groupKey = :groupKey AND id = :id)")
    suspend fun exists(groupKey: String, id: String): Boolean

    /** Имена из событий JOINED группы по порядку номеров: у автора с двумя JOINED действует последнее. */
    @Query(
        "SELECT author, name FROM control_event WHERE groupKey = :groupKey AND type = 'JOINED' AND name IS NOT NULL " +
            "ORDER BY author, seq",
    )
    suspend fun joinedNames(groupKey: String): List<AuthorName>

    @Query("SELECT * FROM control_event WHERE groupKey = :groupKey")
    fun observeAll(groupKey: String): Flow<List<ControlEventEntity>>

    @Query("SELECT COUNT(*) FROM control_event WHERE groupKey = $ACTIVE_KEY")
    fun observeActiveCount(): Flow<Int>

    @Query("DELETE FROM control_event WHERE groupKey = :groupKey")
    suspend fun deleteGroup(groupKey: String)
}

/** Запросы к точкам трека для `RoomMeshStore`, `UploadWorker` (неотправленные и пометки) и счётчиков на экранах. */
@Dao
interface LocationDao {
    /** Падает на повторном id или паре (author, seq) в группе: хранилище проверяет это до вставки. */
    @Insert
    suspend fun insert(point: LocationPointEntity)

    @Insert
    suspend fun insertAll(points: List<LocationPointEntity>)

    @Query("SELECT author, MAX(seq) AS maxSeq FROM location_point WHERE groupKey = :groupKey GROUP BY author")
    suspend fun maxSeqByAuthor(groupKey: String): List<AuthorMaxSeq>

    /** null, если у автора нет точек. */
    @Query("SELECT MAX(seq) FROM location_point WHERE groupKey = :groupKey AND author = :author")
    suspend fun maxSeq(groupKey: String, author: String): Long?

    @Query(
        "SELECT * FROM location_point WHERE groupKey = :groupKey AND author = :author AND seq > :afterSeq " +
            "ORDER BY seq LIMIT :limit",
    )
    suspend fun range(groupKey: String, author: String, afterSeq: Long, limit: Int): List<LocationPointEntity>

    @Query("SELECT * FROM location_point WHERE groupKey = :groupKey AND author = :author AND seq = :seq")
    suspend fun find(groupKey: String, author: String, seq: Long): LocationPointEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM location_point WHERE groupKey = :groupKey AND id = :id)")
    suspend fun exists(groupKey: String, id: String): Boolean

    /** Неотправленные и не отклонённые точки группы по порядку (автор, номер). */
    @Query(
        "SELECT * FROM location_point WHERE groupKey = :groupKey AND uploaded = 0 AND rejected = 0 " +
            "ORDER BY author, seq LIMIT :limit",
    )
    suspend fun unsent(groupKey: String, limit: Int): List<LocationPointEntity>

    /** null, если такой точки нет. Нужен контрактному тесту хранилища и проверке пометок. */
    @Query("SELECT rejected FROM location_point WHERE groupKey = :groupKey AND id = :id")
    suspend fun isRejected(groupKey: String, id: String): Boolean?

    @Query("UPDATE location_point SET uploaded = 1 WHERE groupKey = :groupKey AND id IN (:ids)")
    suspend fun markUploaded(groupKey: String, ids: List<String>)

    @Query("UPDATE location_point SET rejected = 1 WHERE groupKey = :groupKey AND id IN (:ids)")
    suspend fun markRejected(groupKey: String, ids: List<String>)

    /** Есть ли неотправленные точки в какой-нибудь группе, активной или прошлой. */
    @Query("SELECT EXISTS(SELECT 1 FROM location_point WHERE uploaded = 0 AND rejected = 0)")
    suspend fun hasUnsent(): Boolean

    @Query("SELECT COUNT(*) FROM location_point WHERE groupKey = $ACTIVE_KEY")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM location_point WHERE groupKey = $ACTIVE_KEY AND uploaded = 0 AND rejected = 0")
    fun observeActiveUnsentCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM location_point WHERE groupKey = $ACTIVE_KEY AND rejected = 1")
    fun observeActiveRejectedCount(): Flow<Int>

    /** Сколько неотправленных точек у прошлых маршрутов вместе. */
    @Query(
        "SELECT COUNT(*) FROM location_point WHERE uploaded = 0 AND rejected = 0 " +
            "AND groupKey IN (SELECT myMemberId FROM group_info WHERE active = 0)",
    )
    fun observePastUnsentCount(): Flow<Int>

    @Query("DELETE FROM location_point WHERE groupKey = :groupKey")
    suspend fun deleteGroup(groupKey: String)
}

/** Запросы к последним состояниям участников для `RoomMeshStore` и `RouteHistory`. */
@Dao
interface MemberStateDao {
    @Upsert
    suspend fun upsert(state: MemberStateEntity)

    @Query("SELECT * FROM member_state WHERE groupKey = :groupKey AND memberId = :memberId")
    suspend fun get(groupKey: String, memberId: String): MemberStateEntity?

    @Query("SELECT * FROM member_state WHERE groupKey = :groupKey")
    suspend fun getAll(groupKey: String): List<MemberStateEntity>

    @Query("SELECT * FROM member_state WHERE groupKey = :groupKey")
    fun observeAll(groupKey: String): Flow<List<MemberStateEntity>>

    @Query("SELECT COUNT(*) FROM member_state WHERE groupKey = $ACTIVE_KEY")
    fun observeActiveCount(): Flow<Int>

    @Query("DELETE FROM member_state WHERE groupKey = :groupKey")
    suspend fun deleteGroup(groupKey: String)
}
