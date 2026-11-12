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
 * Создаёт активную группу, вступает в неё, отдаёт её остальным частям приложения, переводит в прошлые маршруты
 * и удаляет.
 *
 * Новая группа и вступление в другую ничего не стирают: прежняя активная группа становится прошлым маршрутом
 * со всеми своими записями, и её неотправленный трек `UploadWorker` отправит с её токеном. Повторное вступление
 * в активную группу ничего не меняет. `member_id` новый для каждой группы, и он же служит ключом её записей
 * в базе (`GroupInfo.key`), поэтому записи разных групп и разных вступлений в одну группу не смешиваются.
 *
 * Менять активную группу можно, только пока служба не работает для другой группы, иначе движок старой группы успеет
 * записать чужие Delta в новую. Интерфейс это соблюдает: создание и вступление сначала просят службу закрыть прежнюю
 * группу, а [archiveActiveGroup] и [deleteActiveGroup] вызывает только `RouteService` после остановки сеанса.
 */
@Singleton
class GroupRepository @Inject constructor(
    private val db: AppDatabase,
    private val clock: Clock,
) {
    /** Активная группа или null, пока её нет; новое значение после каждого изменения. */
    val activeGroup: Flow<GroupInfo?> = db.groupDao().observeActive().map { it?.toModel() }

    /** Активная группа на этот момент или null. */
    suspend fun activeGroup(): GroupInfo? = db.groupDao().active()?.toModel()

    /** Новая группа, где я руководитель. Прежняя активная группа становится прошлым маршрутом. */
    suspend fun createGroup(groupName: String): GroupInfo {
        require(groupName.isNotBlank()) { "Пустое название группы" }
        val token = GroupToken.generate()
        val me = MemberId.random()
        return replaceActiveGroup(
            GroupInfo(GroupToken.tagOf(token), token, groupName.trim(), leaderId = me, me = me, clock.wallMs()),
        )
    }

    /**
     * Вступление по QR. Если это активная группа, возвращает её как есть, иначе прежняя активная группа становится
     * прошлым маршрутом.
     */
    suspend fun join(qr: QrPayload): GroupInfo {
        activeGroup()?.takeIf { it.token == qr.token }?.let { return it }
        return replaceActiveGroup(
            GroupInfo(GroupToken.tagOf(qr.token), qr.token, qr.groupName, qr.leaderId, me = MemberId.random(), clock.wallMs()),
        )
    }

    /** Переводит активную группу в прошлые маршруты: «Выйти из группы» и смена группы. Записи остаются в базе. */
    suspend fun archiveActiveGroup() = db.groupDao().deactivate()

    /** Удаляет активную группу со всеми записями: руководитель отменил сбор, в который никто не вступил. */
    suspend fun deleteActiveGroup() = db.withTransaction {
        db.groupDao().active()?.let { deleteRows(it.myMemberId) }
    }

    /**
     * Удаляет прошлый маршрут с ключом [key] со всеми событиями, точками и состояниями одной транзакцией. Активную
     * группу не трогает: её записи ещё пишет служба.
     */
    suspend fun deletePastGroup(key: String) = db.withTransaction {
        db.groupDao().get(key)?.takeIf { !it.active }?.let { deleteRows(key) }
    }

    /** Есть ли неотправленный трек в какой-нибудь группе: тогда отправку на сервер снимать нельзя. */
    suspend fun hasUnsentTrack(): Boolean = db.locationDao().hasUnsent()

    /** Прошлые маршруты, чей трек ещё не весь ушёл на сервер, старые первыми. */
    suspend fun pastGroupsWithUnsentTrack(): List<GroupInfo> = db.groupDao().pastWithUnsentPoints().map { it.toModel() }

    private suspend fun replaceActiveGroup(group: GroupInfo): GroupInfo = db.withTransaction {
        db.groupDao().deactivate()
        db.groupDao().insert(group.toEntity(active = true))
        group
    }

    /** Удаляет группу [key] и её записи. Вызывать внутри транзакции. */
    private suspend fun deleteRows(key: String) {
        db.controlEventDao().deleteGroup(key)
        db.locationDao().deleteGroup(key)
        db.memberStateDao().deleteGroup(key)
        db.groupDao().delete(key)
    }
}
