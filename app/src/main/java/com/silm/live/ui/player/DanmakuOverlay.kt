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
    /** 入队时间，排队过久（轨道全满）的弹幕直接丢弃，避免积压 */
    val bornAt: Long = System.nanoTime(),
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
            // 同一轨道上弹幕速度相同，只要入场时前一条的尾部已让出间隙就不会重叠
            val gap = 15.dp.toPx()
            val nowNs = System.nanoTime()
            // 每条轨道当前最靠右的弹幕尾部位置（无弹幕为负无穷）
            val tailOfTrack = FloatArray(trackCount) { Float.NEGATIVE_INFINITY }
            items.forEach { it0 ->
                if (it0.x != Float.MAX_VALUE && it0.track in 0 until trackCount) {
                    val tail = it0.x + it0.width
                    if (tail > tailOfTrack[it0.track]) tailOfTrack[it0.track] = tail
                }
            }
            val expired = ArrayList<DanmuItem>()
            items.forEach { item ->
                if (item.x == Float.MAX_VALUE) {
                    item.width = paint.measureText(item.text)
                    // 自上而下找第一条尾部已让出间隙的轨道
                    val free = (0 until trackCount).firstOrNull { tailOfTrack[it] + gap <= canvasWidth }
                    if (free == null) {
                        // 轨道全满：保持待入场，下一帧再试；排队超过 4 秒则丢弃
                        if (nowNs - item.bornAt > 4_000_000_000L) expired.add(item)
                        return@forEach
                    }
                    item.x = canvasWidth
                    item.track = free
                    tailOfTrack[free] = canvasWidth + item.width
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
            if (expired.isNotEmpty()) items.removeAll(expired)
        }
    }
}
