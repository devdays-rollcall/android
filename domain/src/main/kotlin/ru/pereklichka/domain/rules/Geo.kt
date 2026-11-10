package ru.pereklichka.domain.rules

import ru.pereklichka.domain.model.LatLon

/** Расстояние и направление между точками на сфере. Нужны `GroupCalculator` и `TrackThinner`. */
object Geo {

    /** Расстояние в метрах по формуле гаверсинусов. */
    fun distanceM(from: LatLon, to: LatLon): Double {
        // TODO(Никита): расстояние по формуле гаверсинусов, радиус Земли 6 371 000 м.
        //  От него зависит TrackThinner Василисы, поэтому его стоит сделать первым. docs/team/techlead.md
        throw NotImplementedError("TODO(Никита): расстояние по формуле гаверсинусов, радиус Земли 6 371 000 м.")
    }

    /** Начальный азимут от [from] на [to] в градусах от севера по часовой стрелке, в полуинтервале [0, 360). */
    fun bearingDeg(from: LatLon, to: LatLon): Double {
        // TODO(Никита): начальный азимут от севера, результат в [0, 360).
        //  docs/team/techlead.md, раздел «Правила», расстояние и направление
        throw NotImplementedError("TODO(Никита): начальный азимут от севера, результат в [0, 360).")
    }
}
