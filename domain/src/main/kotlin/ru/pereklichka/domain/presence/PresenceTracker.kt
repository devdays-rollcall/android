package ru.pereklichka.domain.presence

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Помнит, когда телефон последний раз слышал каждого соседа напрямую. Слышать значит получить любое сообщение;
 * событие Nearby о разрыве для этого не используется, потому что неизвестно, через сколько оно срабатывает.
 *
 * [onHeard] должен вызывать `SyncEngine` на каждое входящее сообщение. [heardAgo] читают `SelfStateProducer`
 * (поле `heardAgoMs` своего состояния) и `GroupViewProvider` (для `GroupCalculator`). Моменты хранятся
 * по монотонным часам, поэтому перевод настенных часов на присутствие не влияет. Методы можно вызывать
 * из любых потоков.
 */
@Singleton
class PresenceTracker @Inject constructor(private val clock: Clock) {

    private val lastHeardState = MutableStateFlow<Map<MemberId, Long>>(emptyMap())

    /** `Clock.elapsedMs()` последнего сообщения от каждого, кого телефон слышал с момента запуска или [clear]. */
    val lastHeard: StateFlow<Map<MemberId, Long>> = lastHeardState.asStateFlow()

    fun onHeard(member: MemberId) {
        val now = clock.elapsedMs()
        lastHeardState.update { it + (member to now) }
    }

    /** true, если сообщение от участника было не раньше чем [HEARD_NOW_MS] назад. */
    fun isHeardNow(member: MemberId): Boolean {
        val heardAt = lastHeardState.value[member] ?: return false
        return clock.elapsedMs() - heardAt <= HEARD_NOW_MS
    }

    /** Забывает всех. Вызывает `RouteService` при запуске для новой группы: у неё другие участники. */
    fun clear() {
        lastHeardState.value = emptyMap()
    }

    /** Кого телефон слышал за последние [HEARD_AGO_WINDOW_MS] и сколько миллисекунд назад. */
    fun heardAgo(): Map<MemberId, Long> {
        val now = clock.elapsedMs()
        return lastHeardState.value
            .mapValues { (_, heardAt) -> now - heardAt }
            .filterValues { it <= HEARD_AGO_WINDOW_MS }
    }

    companion object {
        /**
         * Сосед слышен сейчас, если от него было сообщение за это время. При сводке раз в 10 с
         * сосед перестаёт быть слышимым после трёх пропущенных.
         */
        const val HEARD_NOW_MS: Long = 30_000

        /** Сколько помнить, что слышал участника, для поля `heardAgoMs` своего состояния. */
        const val HEARD_AGO_WINDOW_MS: Long = 10 * 60_000
    }
}
