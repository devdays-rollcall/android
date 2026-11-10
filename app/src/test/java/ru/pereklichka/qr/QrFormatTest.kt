package ru.pereklichka.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import ru.pereklichka.data.GroupToken
import ru.pereklichka.domain.model.MemberId
import ru.pereklichka.domain.model.QrPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Строка QR: туда и обратно, понятные ошибки для чужого и битого кода, картинка считывается обратно. */
class QrFormatTest {

    private val payload = QrPayload(
        token = GroupToken.generate(),
        groupName = "Эльбрус & Ко, 2 дня",
        leaderId = MemberId("8c1e5b0a-3f2d-4c6e-9a7b-1d2e3f4a5b6c"),
    )

    @Test
    fun `строка проходит туда и обратно, название с пробелами и амперсандом`() {
        val text = QrFormat.encode(payload)

        assertEquals(QrParseResult.Valid(payload), QrFormat.parse(text))
        assertEquals(true, text.startsWith("pereklichka://join?v=1&t=${payload.token}&g="))
    }

    @Test
    fun `чужой QR и другая версия дают понятную ошибку`() {
        assertIs<QrParseResult.Invalid>(QrFormat.parse("https://example.com/join?v=1"))
        val otherVersion = QrFormat.encode(payload).replace("v=1", "v=2")
        assertEquals(QrParseResult.Invalid("Код создан другой версией приложения"), QrFormat.parse(otherVersion))
    }

    @Test
    fun `обрезанный токен и битый id руководителя не принимаются`() {
        val text = QrFormat.encode(payload)

        assertIs<QrParseResult.Invalid>(QrFormat.parse(text.replace(payload.token, payload.token.dropLast(1))))
        assertIs<QrParseResult.Invalid>(QrFormat.parse(text.replace(payload.leaderId.value, "not-a-uuid")))
        assertIs<QrParseResult.Invalid>(QrFormat.parse(text.substringBefore("&l=")))
    }

    @Test
    fun `пустое название не принимается`() {
        val result = QrFormat.parse(QrFormat.encode(payload.copy(groupName = "  ")))

        assertEquals(QrParseResult.Invalid("В коде нет названия группы"), result)
    }

    @Test
    fun `картинка считывается обратно в ту же строку`() {
        val text = QrFormat.encode(payload)
        val matrix = QrImage.matrix(text, 400)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }

        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)))

        assertEquals(text, decoded.text)
    }
}
