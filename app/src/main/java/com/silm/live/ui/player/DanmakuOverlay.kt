package com.silm.live.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.android.awaitFrame
import kotlin.math.roundToInt

/**
 * Compose 实现的弹幕层：轨道制、从右向左滚动。
 */
data class DanmuItem(
    val id: Long,
    val text: String,
    val color: Color,
    var x: Float = Float.MAX_VALUE,
    var width: Float = 0f,
    val track: Int,
)

private const val trackCount = 6

@Composable
fun DanmakuOverlay(
    messages: List<com.silm.live.core.DanmakuMessage>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val items = remember { mutableStateListOf<DanmuItem>() }
    var frame by remember { mutableIntStateOf(0) }
    var lastConsumeIndex by remember { mutableIntStateOf(0) }
    var nextTrack by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // 消费新弹幕
    LaunchedEffect(messages, enabled) {
        if (!enabled) {
            lastConsumeIndex = messages.size
            return@LaunchedEffect
        }
        var added = 0
        while (lastConsumeIndex < messages.size && items.size < 40 && added < 3) {
            val msg = messages[lastConsumeIndex++]
            if (msg.type != com.silm.live.core.DanmakuMessage.TYPE_CHAT) continue
            items.add(
                DanmuItem(
                    id = System.nanoTime(),
                    text = if (msg.userName.isEmpty()) msg.message
                    else "${msg.userName}：${msg.message}",
                    color = if (msg.color == 0L) Color.White
                    else Color(0xFF000000 or msg.color),
                    track = nextTrack,
                )
            )
            nextTrack = (nextTrack + 1) % trackCount
            added++
        }
    }

    // 帧循环：更新位置并触发重绘
    LaunchedEffect(enabled) {
        var lastFrameTime = 0L
        while (true) {
            awaitFrame()
            val now = System.nanoTime()
            if (lastFrameTime != 0L) {
                val dt = ((now - lastFrameTime) / 1_000_000_000f).coerceAtMost(0.1f)
                val speed = 130f * density.density
                items.forEach { it.x -= speed * dt }
                items.removeAll { it.x + it.width < 0 }
                frame++
            }
            lastFrameTime = now
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!enabled) return@Canvas
        @Suppress("UNUSED_EXPRESSION") frame // 读取以触发每帧重绘
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                textSize = 13.sp.toPx()
                isAntiAlias = true
                setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val trackHeight = 26.dp.toPx()
            val canvasWidth = size.width
            items.forEach { item ->
                if (item.x == Float.MAX_VALUE) {
                    item.width = paint.measureText(item.text)
                    item.x = canvasWidth
                }
                paint.color = android.graphics.Color.argb(
                    255,
                    (item.color.red * 255).roundToInt(),
                    (item.color.green * 255).roundToInt(),
                    (item.color.blue * 255).roundToInt()
                )
                val y = item.track * trackHeight + trackHeight * 0.85f
                canvas.nativeCanvas.drawText(item.text, item.x, y, paint)
            }
        }
    }
}
