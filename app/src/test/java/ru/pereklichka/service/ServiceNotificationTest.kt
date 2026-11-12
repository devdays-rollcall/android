package ru.pereklichka.service

import ru.pereklichka.domain.rules.Presence
import ru.pereklichka.domain.rules.RouteState
import ru.pereklichka.ui.FakeGroupViews
import kotlin.test.Test
import kotlin.test.assertEquals

/** Сводка в постоянном уведомлении службы. */
class ServiceNotificationTest {

    private val view = FakeGroupViews.leader

    @Test
    fun `сводка называет, кого не слышно`() {
        val others = view.members.filterNot { it.isMe }
        val lost = others.filter { it.presence == Presence.LOST }
        assertEquals(
            "На связи ${others.size - lost.size} из ${others.size} · не слышно: ${lost.joinToString { it.name }}",
            ServiceNotification.statusText(view),
        )
    }

    @Test
    fun `без потерянных, без участников и после завершения`() {
        val calm = view.copy(members = view.members.map { it.copy(presence = Presence.OK) })
        val others = calm.members.count { !it.isMe }
        assertEquals("На связи $others из $others", ServiceNotification.statusText(calm))
        assertEquals("В группе пока никого нет", ServiceNotification.statusText(view.copy(members = view.members.filter { it.isMe })))
        assertEquals("Маршрут завершён, передаём это остальным", ServiceNotification.statusText(view.copy(route = RouteState.FINISHED)))
    }
}
