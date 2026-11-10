package ru.pereklichka.di

import javax.inject.Qualifier

/** Метка Hilt для области корутин на всё время жизни процесса (`Dispatchers.Default`). Её берут `GroupViewProvider` и `NearbyPeerTransport`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Метка Hilt для переключателя фейковых данных. Значение задаёт [DataModeModule], читают `GroupViewModel` и `RouteService`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FakeGroupData
