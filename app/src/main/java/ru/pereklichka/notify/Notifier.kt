package ru.pereklichka.notify

import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.MemberId

/**
 * Показывает и убирает системные уведомления. Реализация на телефоне [AndroidNotifier], в тестах фейк.
 *
 * Что и когда показывать, решает `StatusMonitor`, а реализация решает только как. Одно событие даёт одно
 * уведомление: повторный [show] с тем же [NotifyEvent.key] обновляет его, а [dismiss] убирает. Снятая тревога
 * приходит через [show] как [NotifyEvent.AlarmResolved], и её уведомление с тем же ключом нужно убрать.
 */
interface Notifier {
    fun show(event: NotifyEvent)

    /** Убирает уведомление с ключом [key], если оно есть. */
    fun dismiss(key: String)
}

/** Повод для уведомления. [key] одинаков у всех событий об одной тревоге, участнике или перекличке, его берут как tag уведомления. */
sealed interface NotifyEvent {
    val key: String

    /** Новая тревога или SOS. [isAboutMe] значит, что тревога обо мне, и тексту уведомления нужен свой вариант. */
    data class AlarmRaised(
        val alarmId: EventId,
        val target: MemberId,
        val targetName: String,
        val isSos: Boolean,
        val isAboutMe: Boolean,
    ) : NotifyEvent {
        override val key: String get() = alarmKey(alarmId)
    }

    /** Тревогу сняли: её уведомление нужно убрать. */
    data class AlarmResolved(val alarmId: EventId) : NotifyEvent {
        override val key: String get() = alarmKey(alarmId)
    }

    /** Участника «Не слышно». Приходит только руководителю на маршруте. */
    data class MemberLost(val member: MemberId, val name: String) : NotifyEvent {
        override val key: String get() = lostKey(member)
    }

    /** Руководитель начал перекличку, а я ещё не ответил. */
    data class RollcallStarted(val rollcallId: EventId) : NotifyEvent {
        override val key: String get() = rollcallKey(rollcallId)
    }

    /** «Рядом телефон участника <имя>. Вы видите этого человека?» */
    data class NearbyConfirm(val member: MemberId, val name: String) : NotifyEvent {
        override val key: String get() = nearbyKey(member)
    }

    /**
     * До конца моей отлучки осталось не больше минуты: «Вернулись?» с кнопками «Я вернулся» и «Ещё 10 минут».
     * Уведомление одно на телефон, поэтому ключ постоянный.
     */
    data object AwayEnding : NotifyEvent {
        override val key: String get() = AWAY_ENDING_KEY
    }

    companion object {
        const val AWAY_ENDING_KEY = "away:ending"

        fun alarmKey(alarmId: EventId) = "alarm:$alarmId"
        fun lostKey(member: MemberId) = "lost:$member"
        fun rollcallKey(rollcallId: EventId) = "rollcall:$rollcallId"
        fun nearbyKey(member: MemberId) = "nearby:$member"
    }
}
