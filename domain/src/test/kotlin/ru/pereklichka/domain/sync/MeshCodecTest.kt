package ru.pereklichka.domain.sync

import ru.pereklichka.domain.model.ControlEvent
import ru.pereklichka.domain.model.EventId
import ru.pereklichka.domain.model.EventType
import ru.pereklichka.domain.model.LatLon
import ru.pereklichka.domain.model.LocationPoint
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.MemberState
import ru.pereklichka.domain.model.ResolveKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Кодирование сообщений: туда и обратно, предел 32 КБ, незнакомые типы, точки с NaN и битые байты. */
class MeshCodecTest {

    private val codec = MeshCodec(SyncConfig())
    private val alice = MemberId("6f1c2b1e-0a4d-4f7e-9a51-1f0e2d3c4b5a")
    private val bob = MemberId("0b7e4c2a-9d3f-4a8e-b6c1-5e4d3c2b1a09")

    @Test
    fun `Hello проходит туда и обратно`() {
        val hello = Hello.of(
            SyncSummary(control = mapOf(alice to 57), locations = mapOf(alice to 412, bob to 3), states = mapOf(bob to 9)),
            groupTag = "a1b2c3d4e5f60718",
            from = alice,
        )

        val decoded = codec.decode(encode(hello))

        assertEquals(hello, decoded)
        assertEquals(Hello.PROTOCOL_VERSION, (decoded as Hello).v)
    }

    @Test
    fun `Delta проходит туда и обратно`() {
        val delta = Delta(
            control = listOf(
                WireControlEvent(event(alice, 1, EventType.JOINED, name = "Алиса"), ageMs = 1_500),
                WireControlEvent(
                    event(bob, 2, EventType.RESOLVE, target = alice, refs = listOf(EventId("alarm")), kind = ResolveKind.SEEN_OK),
                    ageMs = 0,
                ),
            ),
            states = listOf(
                WireMemberState(
                    MemberState(bob, 4, LatLon(43.35, 42.44), accuracyM = 6.0, fixAgeMs = 2_000, batteryPercent = 64,
                        heardAgoMs = mapOf(alice to 3_000)),
                    ageMs = 700,
                ),
            ),
            locations = listOf(LocationPoint(EventId("p"), alice, 1, 43.35, 42.44, recordedAtMs = 1_794_300_000_000, accuracyM = 8.5)),
            more = true,
        )

        assertEquals(delta, codec.decode(encode(delta)))
    }

    @Test
    fun `в JSON нет пустых полей и id выглядят как строки`() {
        val hello = Hello.of(SyncSummary(mapOf(alice to 1), emptyMap(), emptyMap()), "tag", alice)
        val delta = Delta(control = listOf(WireControlEvent(event(alice, 1, EventType.SOS), ageMs = 0)))

        val helloJson = jsonOf(encode(hello))
        val deltaJson = jsonOf(encode(delta))

        assertEquals("""{"type":"hello","v":1,"groupTag":"tag","from":"$alice","control":{"$alice":1}}""", helloJson)
        assertTrue("null" !in deltaJson && "more" !in deltaJson, deltaJson)
    }

    @Test
    fun `незнакомый тип события становится UNKNOWN, незнакомый вид снятия пустым`() {
        val json = """{"type":"delta","control":[{"event":{"id":"e","author":"$alice","seq":1,"createdAtWallMs":0,""" +
            """"type":"CHECKPOINT","resolveKind":"MAYBE_OK","newField":42},"ageMs":0}],"futureField":true}"""

        val delta = codec.decode(bytesOf(json)) as Delta

        val event = delta.control.single().event
        assertEquals(EventType.UNKNOWN, event.type)
        assertNull(event.resolveKind)
    }

    @Test
    fun `точка с NaN пересылается как есть`() {
        val point = LocationPoint(EventId("p"), alice, 1, Double.NaN, 42.44, recordedAtMs = 0, accuracyM = Double.POSITIVE_INFINITY)

        val decoded = codec.decode(encode(Delta(locations = listOf(point)))) as Delta

        assertEquals(point, decoded.locations.single())
    }

    @Test
    fun `сообщение больше предела не кодируется`() {
        val random = Random(1)
        val points = List(2_000) {
            LocationPoint(EventId(random.nextLong().toString()), alice, it + 1L, random.nextDouble(), random.nextDouble(), random.nextLong())
        }

        assertNull(codec.encode(Delta(locations = points)))
        assertNotNull(codec.encode(Delta(locations = points.take(100))))
    }

    @Test
    fun `неразборчивые байты дают MeshFormatException`() {
        val valid = encode(Delta(more = true))
        val broken = listOf(
            ByteArray(0),
            byteArrayOf(9) + valid.copyOfRange(1, valid.size),
            byteArrayOf(1, 2, 3, 4),
            valid.copyOf(valid.size / 2),
            bytesOf("""{"type":"delta","control":[{"ageMs":0}]}"""),
            bytesOf("""{"type":"ping"}"""),
            bytesOf("не json"),
        )

        broken.forEach { bytes -> assertFailsWith<MeshFormatException>(bytes.contentToString()) { codec.decode(bytes) } }
    }

    @Test
    fun `сообщение, которое распаковывается в гигабайты, отбрасывается`() {
        val bomb = gzip(ByteArray(8 * 1024 * 1024) { ' '.code.toByte() })

        assertFailsWith<MeshFormatException> { codec.decode(byteArrayOf(1) + bomb) }
    }

    private fun encode(message: MeshMessage): ByteArray = assertNotNull(codec.encode(message))

    private fun bytesOf(json: String): ByteArray = byteArrayOf(1) + gzip(json.encodeToByteArray())

    private fun jsonOf(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes, 1, bytes.size - 1)).use { it.readBytes() }.decodeToString()

    private fun gzip(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    private fun event(
        author: MemberId,
        seq: Long,
        type: EventType,
        target: MemberId? = null,
        refs: List<EventId> = emptyList(),
        name: String? = null,
        kind: ResolveKind? = null,
    ) = ControlEvent(EventId("$author-$seq"), author, seq, createdAtWallMs = 1_794_300_000_000, type, target, refs, name,
        resolveKind = kind)
}
