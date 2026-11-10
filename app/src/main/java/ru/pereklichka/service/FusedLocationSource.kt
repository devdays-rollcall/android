package ru.pereklichka.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import ru.pereklichka.domain.model.Fix
import javax.inject.Inject

/**
 * [LocationSource] на Fused Location Provider из Google Play Services. Подписывается на него [TrackRecorder].
 *
 * Задача: docs/team/vasilisa-service-storage.md
 */
class FusedLocationSource @Inject constructor(@ApplicationContext private val context: Context) : LocationSource {

    override val fixes: Flow<Fix> = flow {
        // TODO(Василиса): callbackFlow поверх requestLocationUpdates, PRIORITY_HIGH_ACCURACY раз в 5 с, отписка в awaitClose.
        //  Необязательные поля Fix заполнять, только если hasAccuracy() и прочие вернули true.
        //  docs/team/vasilisa-service-storage.md
        throw NotImplementedError("TODO(Василиса): callbackFlow поверх requestLocationUpdates, PRIORITY_HIGH_ACCURACY раз в 5 с, отписка в awaitClose.")
    }
}
