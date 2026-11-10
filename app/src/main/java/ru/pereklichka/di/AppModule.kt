package ru.pereklichka.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import ru.pereklichka.AndroidClock
import ru.pereklichka.data.RoomMeshStore
import ru.pereklichka.domain.sync.MeshStore
import ru.pereklichka.domain.sync.PeerTransport
import ru.pereklichka.domain.sync.SyncConfig
import ru.pereklichka.domain.time.Clock
import ru.pereklichka.notify.AndroidNotifier
import ru.pereklichka.notify.Notifier
import ru.pereklichka.service.FusedLocationSource
import ru.pereklichka.service.LocationSource
import ru.pereklichka.transport.NearbyPeerTransport
import javax.inject.Singleton

/**
 * Модуль Hilt: связывает интерфейсы `domain` и `app` с реализациями на телефоне ([MeshStore] с `RoomMeshStore`,
 * [PeerTransport] с `NearbyPeerTransport` и так далее). Классы с `@Inject constructor` (`SyncEngine`,
 * `PresenceTracker`, `GroupActions` и другие) Hilt создаёт сам, здесь их нет.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    abstract fun clock(impl: AndroidClock): Clock

    @Binds
    abstract fun meshStore(impl: RoomMeshStore): MeshStore

    @Binds
    abstract fun peerTransport(impl: NearbyPeerTransport): PeerTransport

    @Binds
    abstract fun locationSource(impl: FusedLocationSource): LocationSource

    @Binds
    abstract fun notifier(impl: AndroidNotifier): Notifier

    companion object {
        @Provides
        fun syncConfig(): SyncConfig = SyncConfig()

        @Provides
        @Singleton
        @ApplicationScope
        fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
