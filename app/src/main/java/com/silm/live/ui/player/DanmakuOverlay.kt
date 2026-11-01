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
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Compose 实现的弹幕层：轨道制、从右向左滚动。
 * 对齐 Flutter 版（canvas_danmaku）：仅渲染弹幕内容本身（不带用户名前缀），
 * 由事件流驱动（SharedFlow），避免按索引消费导致的错位/停摆。
 */
data class DanmuItem(
    val id: Long,
    val text: String,
    val color: Color,
    var x: Float = Float.MAX_VALUE,
    var width: Float = 0f,
    /** 轨道号在绘制阶段按当前弹幕区域高度动态分配，-1 表示尚未分配 */
    var track: Int = -1,
)

@Composable
fun DanmakuOverlay(
    events: SharedFlow<com.silm.live.core.DanmakuMessage>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /** 弹幕字号（sp）；全屏/TV 可加大 */
    textSp: Float = 13f,
    /** 弹幕屏占比（0.1..1）：弹幕从屏幕顶部开始占屏幕高度的比例 */
    areaRatio: Float = 0.25f,
) {
    val items = remember { mutableStateListOf<DanmuItem>() }
    var frame by remember { mutableIntStateOf(0) }
    // 轮转游标用 IntArray 存放，避免在绘制阶段写 Compose 状态
    val nextTrackRef = remember { intArrayOf(0) }
    val density = LocalDensity.current

    // 事件流消费：enabled 关闭时清空并停止收集
    LaunchedEffect(events, enabled) {
        if (!enabled) {
            items.clear()
            return@LaunchedEffect
        }
        events.collect { msg ->
            if (msg.type != com.silm.live.core.DanmakuMessage.TYPE_CHAT) return@collect
            if (items.size >= 60) return@collect
            items.add(
                DanmuItem(
                    id = System.nanoTime(),
                    text = msg.message,
                    color = if (msg.color == 0L) Color.White
                    else Color(0xFF000000 or msg.color),
                )
            )
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
                items.forEach {
                    // 只移动已初始化的弹幕（x 仍为 MAX_VALUE 的项等待绘制阶段测宽并放到右缘，
                    // 提前移动会破坏 MAX_VALUE 哨兵值，导致它永远无法初始化、画在屏幕外）
                    if (it.x != Float.MAX_VALUE) it.x -= speed * dt
                }
                items.removeAll { it.x != Float.MAX_VALUE && it.x + it.width < 0 }
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
                textSize = textSp.sp.toPx()
                isAntiAlias = true
                setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val trackHeight = (textSp + 13).dp.toPx()
            // 弹幕区域：从屏幕顶部开始，占屏幕高度 areaRatio；轨道数按区域高度动态计算
            val areaHeight = size.height * areaRatio.coerceIn(0.1f, 1f)
            val trackCount = max(1, (areaHeight / trackHeight).toInt())
            val canvasWidth = size.width
            items.forEach { item ->
                if (item.x == Float.MAX_VALUE) {
                    item.width = paint.measureText(item.text)
                    item.x = canvasWidth
                    item.track = nextTrackRef[0] % trackCount
                    nextTrackRef[0] = (nextTrackRef[0] + 1) % trackCount
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
