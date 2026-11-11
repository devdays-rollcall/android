package ru.pereklichka.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Переключатель фейковых данных. Пока он true, экраны показывают `FakeGroupViews` и их кнопки ничего не пишут,
 * а `RouteService` не запускает обмен и пишет только свой трек для сервера. Так по экранам можно ходить,
 * пока не готовы `RoomMeshStore`, `SyncEngine` и Nearby. Создание группы и вступление работают и в этом режиме.
 *
 * Когда части готовы, здесь ставят false: экраны получат настоящий `GroupView`, а служба запустит всё.
 * Любая оставшаяся заглушка с `NotImplementedError` тогда уронит приложение.
 *
 * Задача: docs/team/techlead.md
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModeModule {

    @Provides
    @FakeGroupData
    fun useFakeGroupData(): Boolean = true
}
