package ru.pereklichka.ui.components

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import ru.pereklichka.R
import ru.pereklichka.ui.theme.statusColors

private val ViewfinderShape = RoundedCornerShape(28.dp)

/**
 * Видоискатель камеры прямо на экране вступления. Распознаёт только QR и отдаёт текст каждого распознанного кадра
 * в [onCode], поэтому повторы одного и того же кода отсеивает вызывающий. Камера работает, пока экран в RESUMED,
 * и выключается, когда видоискатель уходит с экрана.
 *
 * Разрешение на камеру видоискатель спрашивает сам один раз при первом показе. Без него вместо картинки камеры
 * объяснение и кнопка: «Разрешить камеру», а если система больше не покажет диалог, «Открыть настройки». Разрешение
 * перечитывается при каждом возврате на экран. Кнопка фонарика видна, если у телефона есть вспышка.
 *
 * Сканер zxing-android-embedded работает без интернета. Сканер Google не подходит: он скачивает свой модуль при
 * первом запуске и в авиарежиме может не открыться.
 */
@Composable
fun QrViewfinder(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val inPreview = LocalInspectionMode.current
    var granted by remember { mutableStateOf(!inPreview && hasCameraPermission(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var blocked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        blocked = !ok && (activity == null || !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA))
    }
    LifecycleResumeEffect(Unit) {
        if (!inPreview) granted = hasCameraPermission(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) {
        if (!inPreview && !granted && !asked) {
            asked = true
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(330.dp)
            .clip(ViewfinderShape)
            .background(Color.Black),
    ) {
        when {
            granted -> {
                var torchOn by remember { mutableStateOf(false) }
                CameraPreview(onCode, torchOn)
                ScanOverlay()
                if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
                    TorchButton(torchOn, { torchOn = !torchOn }, Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
            }
            inPreview -> ScanOverlay()
            else -> NoCamera(
                blocked = blocked,
                onRequest = { launcher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { context.openAppSettings() },
            )
        }
    }
}

private fun hasCameraPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    try {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    } catch (e: ActivityNotFoundException) {
        // Настроек приложения на телефоне нет: остаётся только кнопка «Разрешить камеру».
    }
}

/** Картинка камеры с распознаванием QR. Камера включается в onResume и выключается в onPause экрана. */
@Composable
private fun CameraPreview(onCode: (String) -> Unit, torchOn: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCode by rememberUpdatedState(onCode)
    val barcodeView = remember(context) {
        BarcodeView(context).apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            // Колбэк приходит в главном потоке.
            decodeContinuous { result -> result.text?.let { latestOnCode(it) } }
        }
    }
    DisposableEffect(lifecycleOwner, barcodeView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> barcodeView.resume()
                Lifecycle.Event.ON_PAUSE -> barcodeView.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            barcodeView.pause()
        }
    }
    AndroidView(factory = { barcodeView }, modifier = Modifier.fillMaxSize(), update = { it.setTorch(torchOn) })
}

/** Белые уголки рамки 200 dp, бегущая линия сканирования и подсказка внизу. */
@Composable
private fun ScanOverlay() {
    val line = MaterialTheme.statusColors.ok.accent
    val progress by rememberInfiniteTransition(label = "scan").animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Reverse),
        label = "scanLine",
    )
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.size(200.dp).align(Alignment.Center)) {
            val stroke = 5.dp.toPx()
            val arm = 44.dp.toPx()
            val radius = 18.dp.toPx()
            val half = stroke / 2
            val corner = Path().apply {
                moveTo(half, arm)
                lineTo(half, half + radius)
                arcTo(Rect(Offset(half, half), Size(radius * 2, radius * 2)), 180f, 90f, false)
                lineTo(arm, half)
            }
            repeat(4) { index ->
                rotate(90f * index) { drawPath(corner, Color.White, style = Stroke(stroke, cap = StrokeCap.Butt)) }
            }
            val inset = 20.dp.toPx()
            val y = size.height * progress
            drawLine(line, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
        }
        Text(
            "Наведите камеру на QR-код руководителя",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }
}

/** Круглая полупрозрачная кнопка фонарика в углу видоискателя. */
@Composable
private fun TorchButton(on: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    IconButton(onClick = onToggle, modifier = modifier.size(48.dp)) {
        Box(
            Modifier.size(44.dp).background(Color.White.copy(alpha = if (on) 0.9f else 0.15f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            SymIcon(
                if (on) R.drawable.sym_flashlight_off else R.drawable.sym_flashlight_on,
                contentDescription = if (on) "Выключить фонарик" else "Включить фонарик",
                tint = if (on) Color.Black else Color.White,
            )
        }
    }
}

/** Камера не разрешена: объяснение и кнопка, которая спрашивает разрешение или ведёт в настройки. */
@Composable
private fun NoCamera(blocked: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SymIcon(R.drawable.sym_no_photography, tint = Color.White, size = 40.dp)
        Text("Нет доступа к камере", style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center)
        Text(
            if (blocked) {
                "Без камеры код не прочитать. Разрешите её в настройках приложения, в разделе «Разрешения»."
            } else {
                "Без камеры код не прочитать. Разрешение нужно только для сканирования."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
        BigButton(
            if (blocked) "Открыть настройки" else "Разрешить камеру",
            if (blocked) onOpenSettings else onRequest,
            kind = ButtonKind.TONAL,
        )
    }
}
