package ru.pereklichka.qr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Сканер QR для экрана вступления. Возвращает функцию, которая открывает камеру; результат приходит
 * в [onResult] уже разобранным. Если человек закрыл сканер, [onResult] не вызывается.
 *
 * Сканер zxing-android-embedded работает без интернета и сам спрашивает разрешение на камеру. Сканер Google
 * не подходит: он скачивает свой модуль при первом запуске и в авиарежиме может не открыться.
 */
@Composable
fun rememberQrScanner(onResult: (QrParseResult) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { onResult(QrFormat.parse(it)) }
    }
    return {
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Наведите камеру на QR-код руководителя")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }
}
