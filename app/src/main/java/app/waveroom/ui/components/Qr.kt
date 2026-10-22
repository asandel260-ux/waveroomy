package app.waveroom.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.waveroom.ui.theme.WR
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

private const val FRAME_MAX = 1100

fun qrBitmap(text: String, px: Int): Bitmap? = try {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, hints)
    val w = m.width
    val h = m.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) for (x in 0 until w) pixels[y * w + x] = if (m.get(x, y)) AColor.BLACK else AColor.WHITE
    Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
} catch (e: Exception) {
    null
}

/** Splits a long code into numbered QR frames; the scanner reassembles them. */
fun qrFrames(code: String): List<String> {
    if (code.length <= FRAME_MAX) return listOf(code)
    val n = (code.length + FRAME_MAX - 1) / FRAME_MAX
    val per = (code.length + n - 1) / n
    return (0 until n).map { i -> "WRQ|${i + 1}|$n|" + code.substring(i * per, minOf(code.length, (i + 1) * per)) }
}

/** A white card with the QR code; very long codes cycle through several frames. */
@Composable
fun QrDisplay(code: String, modifier: Modifier = Modifier, size: Dp = 280.dp) {
    val frames = remember(code) { qrFrames(code) }
    var idx by remember(code) { mutableStateOf(0) }
    LaunchedEffect(frames) {
        while (frames.size > 1) {
            delay(900)
            idx = (idx + 1) % frames.size
        }
    }
    val bmp = remember(frames, idx) { qrBitmap(frames[idx.coerceIn(0, frames.size - 1)], 720) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp), contentAlignment = Alignment.Center) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), "QR code", modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
            } else {
                Text("Code is too large for a QR image.\nShare it as text instead.", color = Color.Black, fontSize = 13.sp)
            }
        }
        if (frames.size > 1) {
            Spacer(Modifier.height(8.dp))
            Tiny("Frame ${idx + 1} of ${frames.size} — keep the scanner pointed here")
        }
    }
}

/** Camera preview that reports the first complete HoMit code it sees. */
@OptIn(ExperimentalGetImage::class)
@Composable
fun QrScanner(onResult: (String) -> Unit, modifier: Modifier = Modifier, onProgress: ((Int, Int) -> Unit)? = null) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    val parts = remember { HashMap<Int, String>() }
    var finished by remember { mutableStateOf(false) }
    var providerRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    fun handle(raw: String?) {
        if (raw == null || finished) return
        if (raw.startsWith("WRQ|")) {
            val p = raw.split("|", limit = 4)
            if (p.size < 4) return
            val i = p[1].toIntOrNull() ?: return
            val n = p[2].toIntOrNull() ?: return
            parts[i] = p[3]
            onProgress?.invoke(parts.size, n)
            if (parts.size == n) {
                val sb = StringBuilder()
                for (k in 1..n) sb.append(parts[k] ?: return)
                finished = true
                onResult(sb.toString())
            }
        } else if (raw.contains("WR1.")) {
            finished = true
            onResult(raw)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { c ->
            val pv = PreviewView(c)
            pv.scaleType = PreviewView.ScaleType.FILL_CENTER
            val fut = ProcessCameraProvider.getInstance(c)
            fut.addListener({
                try {
                    val provider = fut.get()
                    providerRef = provider
                    val preview = Preview.Builder().build()
                    preview.setSurfaceProvider(pv.surfaceProvider)
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(executor) { proxy ->
                        val media = proxy.image
                        if (media == null) {
                            proxy.close()
                        } else {
                            val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                            scanner.process(img)
                                .addOnSuccessListener { list -> for (b in list) handle(b.rawValue) }
                                .addOnCompleteListener { proxy.close() }
                        }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (e: Exception) {
                }
            }, ContextCompat.getMainExecutor(c))
            pv
        }
    )
    DisposableEffect(Unit) {
        onDispose {
            try {
                providerRef?.unbindAll()
            } catch (e: Exception) {
            }
            try {
                scanner.close()
            } catch (e: Exception) {
            }
            executor.shutdown()
        }
    }
}
