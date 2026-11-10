package ru.pereklichka.domain.track

import ru.pereklichka.domain.model.Fix
import javax.inject.Inject

/**
 * Готовит свою фиксацию к сохранению в трек: сервер отклоняет пачку целиком из-за одной неверной точки.
 *
 * Вызывает служба перед `MeshStore.appendOwnLocation`. Что сервер считает допустимым, записано
 * в `LocationPoint.hasValidValues()`; точка из очищенной фиксации должна эту проверку проходить.
 */
class LocationSanitizer @Inject constructor() {

    /** Очищенная фиксация или null, если широта и долгота недопустимы и сохранять нечего. */
    fun sanitize(fix: Fix): Fix? {
        // TODO(Василиса): очистить фиксацию перед сохранением в трек.
        //  Недопустимые необязательные значения (NaN, отрицательная точность) убрать, азимут привести к [0, 360),
        //  фиксацию без допустимых широты и долготы не сохранять вовсе. docs/team/vasilisa-service-storage.md
        throw NotImplementedError("TODO(Василиса): очистить фиксацию перед сохранением в трек.")
    }
}
