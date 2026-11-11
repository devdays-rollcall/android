package ru.pereklichka.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Переключатель фейковых данных, в приложении он false. С true экраны показывают `FakeGroupViews`, кнопки
 * ничего не пишут и SOS не уходит на сервер, а `RouteService` не запускает обмен и пишет только свой трек
 * для сервера. Создание группы и вступление работают в обоих режимах. Режим оставлен запасным на случай,
 * если настоящие данные откажут на демо.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModeModule {

    @Provides
    @FakeGroupData
    fun useFakeGroupData(): Boolean = false
}
