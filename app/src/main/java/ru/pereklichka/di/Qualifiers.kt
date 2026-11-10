package ru.pereklichka.di

import javax.inject.Qualifier

/** Область корутин на всё время жизни процесса, на `Dispatchers.Default`. Нужна общим потокам вроде `GroupViewProvider`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Переключатель фейковых данных для экранов, значение задаёт `DataModeModule`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FakeGroupData
