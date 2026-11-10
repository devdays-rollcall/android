package ru.pereklichka.data

import android.app.Application
import org.junit.After
import org.junit.Ignore
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.pereklichka.data.db.AppDatabase
import ru.pereklichka.data.db.toEntity
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.sync.MeshStore
import ru.pereklichka.domain.sync.MeshStoreContractTest
import ru.pereklichka.domain.time.Clock

/** Тот же контракт, что проходит `InMemoryMeshStore`, на Room в памяти через Robolectric, без эмулятора. */
@Ignore("включи, когда сделаешь RoomMeshStore")
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class RoomMeshStoreContractTest : MeshStoreContractTest() {

    private lateinit var db: AppDatabase

    override suspend fun createStore(group: GroupInfo, clock: Clock): MeshStore {
        db = inMemoryDatabase()
        db.groupDao().insert(group.toEntity())
        return RoomMeshStore(db, GroupRepository(db, clock), clock)
    }

    override suspend fun isRejected(pointId: EventId): Boolean = db.locationDao().isRejected(pointId.value) == true

    @After
    fun close() = db.close()
}
