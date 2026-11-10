package ru.pereklichka.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.pereklichka.data.db.AppDatabase
import ru.pereklichka.data.db.toEntity
import ru.pereklichka.data.db.toModel
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.QrPayload
import ru.pereklichka.domain.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Создаёт активную группу, вступает в неё и отдаёт её остальным частям. Вызывают `GroupActions`, `GroupViewModel`,
 * `RouteService`, `RoomMeshStore` и `UploadWorker`.
 *
 * Свой `member_id` новый для каждой группы: после очистки данных телефон нумерует свои записи с единицы,
 * и со старым id соседи приняли бы их за давно полученные повторы. Поэтому смена группы очищает все таблицы,
 * а повторное вступление в ту же группу ничего не меняет.
 *
 * Менять группу можно, только пока служба не работает для другой группы, иначе движок старой группы успеет
 * записать чужие Delta в чистые таблицы. Интерфейс это соблюдает: создание и вступление доступны лишь без
 * активной группы.
 */
@Singleton
class GroupRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    /** Активная группа или null, пока её нет; новое значение после каждого изменения. */
    val activeGroup: Flow<GroupInfo?> = db.groupDao().observe().map { it?.toModel() }

    /** Активная группа на этот момент или null. */
    suspend fun activeGroup(): GroupInfo? = db.groupDao().get()?.toModel()

    /** Новая группа, где я руководитель. Старые данные стираются. */
    suspend fun createGroup(groupName: String): GroupInfo {
        require(groupName.isNotBlank()) { "Пустое название группы" }
        val token = GroupToken.generate()
        val me = MemberId.random()
        return replaceActiveGroup(
            GroupInfo(GroupToken.tagOf(token), token, groupName.trim(), leaderId = me, me = me, clock.wallMs()),
        )
    }

    /** Вступление по QR. Если это та же группа, возвращает её как есть, иначе стирает старые данные. */
    suspend fun join(qr: QrPayload): GroupInfo {
        activeGroup()?.takeIf { it.token == qr.token }?.let { return it }
        return replaceActiveGroup(
            GroupInfo(GroupToken.tagOf(qr.token), qr.token, qr.groupName, qr.leaderId, me = MemberId.random(), clock.wallMs()),
        )
    }

    private suspend fun replaceActiveGroup(group: GroupInfo): GroupInfo = db.withTransaction {
        db.controlEventDao().deleteAll()
        db.locationDao().deleteAll()
        db.memberStateDao().deleteAll()
        db.groupDao().deleteAll()
        db.groupDao().insert(group.toEntity())
        group
    }
}
