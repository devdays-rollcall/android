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
 * Создаёт активную группу, вступает в неё, отдаёт её остальным частям приложения и стирает при выходе.
 *
 * Смена группы и выход ([leaveGroup]) очищают все таблицы, а повторное вступление в ту же группу ничего не меняет. После очистки телефон
 * нумерует свои записи с единицы, поэтому `member_id` новый для каждой группы: под старым id соседи увидели бы
 * на тех же номерах другие записи и отбросили бы их как конфликт.
 *
 * Менять группу можно, только пока служба не работает для другой группы, иначе движок старой группы успеет
 * записать чужие Delta в чистые таблицы. Интерфейс это соблюдает: создание и вступление доступны лишь без
 * активной группы, а [leaveGroup] вызывает только `RouteService` после остановки сеанса.
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

    /** Стирает группу со всеми событиями, точками и состояниями одной транзакцией: «Новая группа» после маршрута. */
    suspend fun leaveGroup() = db.withTransaction { deleteAll() }

    private suspend fun replaceActiveGroup(group: GroupInfo): GroupInfo = db.withTransaction {
        deleteAll()
        db.groupDao().insert(group.toEntity())
        group
    }

    private suspend fun deleteAll() {
        db.controlEventDao().deleteAll()
        db.locationDao().deleteAll()
        db.memberStateDao().deleteAll()
        db.groupDao().deleteAll()
    }
}
