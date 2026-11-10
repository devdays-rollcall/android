package ru.pereklichka.notify

import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.MemberId

/**
 * Показывает уведомления. На телефоне это `AndroidNotifier`, в тестах фейк.
 *
 * Что и когда показывать, решает `StatusMonitor`; здесь только как. Одно событие даёт одно уведомление:
 * повторный [show] с тем же [NotifyEvent.key] обновляет его, а [dismiss] убирает. Снятая тревога приходит
 * через [show] как [NotifyEvent.AlarmResolved]: её уведомление с тем же ключом нужно убрать или сменить на «снята».
 */
interface Notifier {
    fun show(event: NotifyEvent)

    /** Убирает уведомление с ключом [key], если оно есть. */
    fun dismiss(key: String)
}

/** Повод для уведомления. [key] годится как tag уведомления. */
sealed interface NotifyEvent {
    val key: String

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

    data class MemberLost(val member: MemberId, val name: String) : NotifyEvent {
        override val key: String get() = lostKey(member)
    }

    data class RollcallStarted(val rollcallId: EventId) : NotifyEvent {
        override val key: String get() = rollcallKey(rollcallId)
    }

    /** «Рядом телефон участника <имя>. Вы видите этого человека?» */
    data class NearbyConfirm(val member: MemberId, val name: String) : NotifyEvent {
        override val key: String get() = nearbyKey(member)
    }

    companion object {
        fun alarmKey(alarmId: EventId) = "alarm:$alarmId"
        fun lostKey(member: MemberId) = "lost:$member"
        fun rollcallKey(rollcallId: EventId) = "rollcall:$rollcallId"
        fun nearbyKey(member: MemberId) = "nearby:$member"
    }
}
