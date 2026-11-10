package ru.pereklichka.data

import ru.pereklichka.data.db.toEntity
import ru.pereklichka.data.db.toModel
import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.GroupInfo
import ru.pereklichka.domain.model.LatLon
import ru.pereklichka.domain.model.LocationPoint
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.MemberState
import ru.pereklichka.domain.model.ResolveKind
import ru.pereklichka.domain.time.Stamped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MappersTest {

    private val bob = MemberId("bob")

    @Test
    fun `событие проходит в строку базы и обратно`() {
        val event = Stamped(
            ControlEvent(EventId("e"), bob, 3, createdAtWallMs = 5, EventType.RESOLVE, target = MemberId("ann"),
                refs = listOf(EventId("a1"), EventId("a2")), resolveKind = ResolveKind.SEEN_OK),
            ageAtReceiptMs = 1_000, receivedAtElapsedMs = 2_000, receivedBootId = "7", receivedAtWallMs = 3_000,
        )

        assertEquals(event, event.toEntity().toModel())
        assertFalse(event.toEntity().ignored)
    }

    @Test
    fun `событие незнакомого типа помечено «не учитывать», а незнакомый тип из базы становится UNKNOWN`() {
        val unknown = Stamped(ControlEvent(EventId("e"), bob, 1, createdAtWallMs = 0, EventType.UNKNOWN), 0, 0, "1", 0)

        assertTrue(unknown.toEntity().ignored)
        assertEquals(EventType.UNKNOWN, unknown.toEntity().copy(type = "CHECKPOINT").toModel().value.type)
    }

    @Test
    fun `точка с недопустимыми значениями получает пометку rejected`() {
        val point = LocationPoint(EventId("p"), bob, 1, 43.35, 42.44, recordedAtMs = 0, bearingDeg = 360.0)

        assertTrue(point.toEntity().rejected)
        assertFalse(point.copy(bearingDeg = 10.0).toEntity().rejected)
        assertEquals(point, point.toEntity().toModel())
    }

    @Test
    fun `состояние и группа проходят туда и обратно`() {
        val state = Stamped(
            MemberState(bob, 4, LatLon(43.35, 42.44), 6.0, fixAgeMs = 2_000, batteryPercent = 70, heardAgoMs = mapOf(MemberId("ann") to 3_000L)),
            ageAtReceiptMs = 500, receivedAtElapsedMs = 1_500, receivedBootId = "2", receivedAtWallMs = 9_000,
        )
        val group = GroupInfo("tag", "token", "Поход", leaderId = bob, me = MemberId("me"), createdAtWallMs = 1)

        assertEquals(state, state.toEntity().toModel())
        assertEquals(group, group.toEntity().toModel())
    }
}
