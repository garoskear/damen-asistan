package com.damen.asistan

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/** Asistan-dışı ekran karesini kırpma ekranı. Sonuç cacheDir/crop.png, OK ile döner. */
class CropActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH)
        val bmp = try { path?.let { BitmapFactory.decodeFile(it) } } catch (_: Exception) { null }
        if (bmp == null) { setResult(RESULT_CANCELED); finish(); return }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Damen.Bg, surface = Damen.Bg)) {
                Surface(Modifier.fillMaxSize(), color = Damen.Bg) {
                    CropScreen(bmp,
                        onCancel = { setResult(RESULT_CANCELED); finish() },
                        onCrop = { rect ->
                            try {
                                val bw = bmp.width; val bh = bmp.height
                                val x = rect.left.toInt().coerceIn(0, bw - 1)
                                val y = rect.top.toInt().coerceIn(0, bh - 1)
                                val w = max(8, min(rect.width.toInt(), bw - x))
                                val h = max(8, min(rect.height.toInt(), bh - y))
                                val out = Bitmap.createBitmap(bmp, x, y, w, h)
                                val f = File(cacheDir, "crop.png")
                                FileOutputStream(f).use { fos -> out.compress(Bitmap.CompressFormat.PNG, 100, fos) }
                                out.recycle()
                                setResult(RESULT_OK, Intent().putExtra(EXTRA_OUT, f.absolutePath))
                            } catch (_: Exception) {
                                setResult(RESULT_CANCELED)
                            }
                            finish()
                        },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_OUT = "out"
    }
}

@Composable
private fun CropScreen(src: Bitmap, onCancel: () -> Unit, onCrop: (Rect) -> Unit) {
    val img = remember(src) { src.asImageBitmap() }
    val bw = src.width.toFloat(); val bh = src.height.toFloat()
    var rect by remember { mutableStateOf<Rect?>(null) }
    var moving by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(Offset.Zero) }

    Column(Modifier.fillMaxSize()) {
        Text(
            "KIRP", fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            letterSpacing = 2.8.sp, color = Damen.Fg,
            modifier = Modifier.padding(12.dp),
        )
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            val density = LocalDensity.current
            val vwPx = with(density) { maxWidth.toPx() }
            val vhPx = with(density) { maxHeight.toPx() }
            val scale = min(vwPx / bw, vhPx / bh)
            val dw = bw * scale; val dh = bh * scale
            val ox = (vwPx - dw) / 2; val oy = (vhPx - dh) / 2
            Canvas(
                Modifier.fillMaxSize().pointerInput(bw, bh) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            val r = rect
                            if (r != null && r.contains(pos)) {
                                moving = true
                            } else {
                                moving = false
                                anchor = pos
                                rect = Rect(pos, pos)
                            }
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            val r = rect ?: return@detectDragGestures
                            rect = if (moving) {
                                val nr = r.translate(drag.x, drag.y)
                                // Görüntü dışına taşma
                                val dx = when {
                                    nr.left < ox -> ox - nr.left
                                    nr.right > ox + dw -> ox + dw - nr.right
                                    else -> 0f
                                }
                                val dy = when {
                                    nr.top < oy -> oy - nr.top
                                    nr.bottom > oy + dh -> oy + dh - nr.bottom
                                    else -> 0f
                                }
                                nr.translate(dx, dy)
                            } else {
                                val l = min(anchor.x, change.position.x).coerceIn(ox, ox + dw)
                                val t = min(anchor.y, change.position.y).coerceIn(oy, oy + dh)
                                val rr = max(anchor.x, change.position.x).coerceIn(ox, ox + dw)
                                val bb = max(anchor.y, change.position.y).coerceIn(oy, oy + dh)
                                Rect(l, t, rr, bb)
                            }
                        },
                        onDragEnd = { moving = false },
                    )
                },
            ) {
                drawImage(
                    img,
                    dstOffset = IntOffset(ox.toInt(), oy.toInt()),
                    dstSize = IntSize(dw.toInt(), dh.toInt()),
                )
                val r = rect
                if (r != null && r.width > 4 && r.height > 4) {
                    // Dışını karart
                    drawRect(Color(0x99000000), topLeft = Offset.Zero, size = Size(size.width, r.top))
                    drawRect(Color(0x99000000), topLeft = Offset(0f, r.bottom), size = Size(size.width, size.height - r.bottom))
                    drawRect(Color(0x99000000), topLeft = Offset(0f, r.top), size = Size(r.left, r.height))
                    drawRect(Color(0x99000000), topLeft = Offset(r.right, r.top), size = Size(size.width - r.right, r.height))
                    drawRect(Damen.Accent, topLeft = r.topLeft, size = r.size, style = Stroke(width = 3f))
                }
            }
            // Bitmap koordinatına çevir
            val toBmp: (Rect) -> Rect = { r ->
                Rect(
                    ((r.left - ox) / scale).coerceIn(0f, bw),
                    ((r.top - oy) / scale).coerceIn(0f, bh),
                    ((r.right - ox) / scale).coerceIn(0f, bw),
                    ((r.bottom - oy) / scale).coerceIn(0f, bh),
                )
            }
            Row(
                Modifier.align(Alignment.BottomCenter).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CropBtn("VAZGEÇ", Damen.Line, onCancel)
                CropBtn("EKLE", Damen.Accent, enabled = rect != null && rect!!.width > 10 && rect!!.height > 10) {
                    rect?.let { onCrop(toBmp(it)) }
                }
            }
        }
    }
}

@Composable
private fun CropBtn(text: String, border: Color, onClick: () -> Unit) {
    CropBtn(text, border, true, onClick)
}

@Composable
private fun CropBtn(text: String, border: Color, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.background(Color(0xCC000000))
            .border(1.dp, if (enabled) border else Damen.LineDim)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = if (enabled) Damen.Fg else Damen.Faint)
    }
}

@Composable
private fun Modifier.clickable(enabled: Boolean, onClick: () -> Unit): Modifier {
    return if (enabled) this.then(
        androidx.compose.foundation.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
        ) { onClick() },
    ) else this
}
