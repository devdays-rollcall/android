package ru.pereklichka.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Рисует картинку QR для `LobbyScreen`. Поля в четыре модуля и коррекция ошибок уровня Q нужны, чтобы код
 * считывался с экрана на солнце и с бликом. Цвета чёрный и белый, потому что так контраст наибольший.
 */
object QrImage {

    /** Матрица кода размером [sizePx] на [sizePx] вместе с полями. */
    fun matrix(text: String, sizePx: Int): BitMatrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
            EncodeHintType.MARGIN to 4,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )

    /** Картинка кода [sizePx] на [sizePx] пикселей вместе с полями. */
    fun bitmap(text: String, sizePx: Int): Bitmap {
        val matrix = matrix(text, sizePx)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
        }
        return createBitmap(matrix.width, matrix.height).apply {
            setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
        }
    }
}
