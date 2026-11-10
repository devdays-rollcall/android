package ru.pereklichka.domain.testing

import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.sync.MeshStore
import ru.pereklichka.domain.sync.MeshStoreContractTest
import ru.pereklichka.domain.time.Clock

class InMemoryMeshStoreContractTest : MeshStoreContractTest() {

    private lateinit var store: InMemoryMeshStore

    override suspend fun createStore(group: GroupInfo, clock: Clock): MeshStore =
        InMemoryMeshStore(group, clock).also { store = it }

    override suspend fun isRejected(pointId: EventId): Boolean = store.isRejected(pointId)
}
