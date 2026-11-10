package ru.pereklichka.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Переключатель фейковых данных. Пока true, экраны показывают `FakeGroupViews` и их кнопки ничего не пишут,
 * а `RouteService` только показывает уведомление. Так по экранам можно ходить, пока не готовы `RoomMeshStore`,
 * Nearby и движок.
 *
 * Когда они готовы, поставь false: экраны получат настоящий `GroupView`, а служба запустит обмен и GPS.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModeModule {

    @Provides
    @FakeGroupData
    fun useFakeGroupData(): Boolean = true
}
