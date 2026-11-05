package com.silm.live.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 显示模式（设置页可选） */
enum class DisplayMode {
    AUTO, PHONE, TABLET, TV;

    companion object {
        fun fromName(name: String?): DisplayMode =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** 实际布局形态 */
enum class AppLayout { PHONE, TABLET, TV }

/**
 * 手机播放比例：
 *  - LANDSCAPE 横屏（强制横屏全屏）
 *  - PORTRAIT  竖屏（强制竖屏全屏）
 *  - AUTO      按视频方向：横屏视频横屏播放，竖屏视频竖屏播放
 */
enum class PhonePlayOrientation {
    AUTO, LANDSCAPE, PORTRAIT;

    companion object {
        fun fromName(name: String?): PhonePlayOrientation =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/**
 * 解析实际布局：
 *  - 手动模式直接生效
 *  - 自动：TV 设备（UiModeManager）→ TV；最小宽度 ≥600dp → 平板；否则手机
 */
fun resolveAppLayout(context: Context, mode: DisplayMode): AppLayout = when (mode) {
    DisplayMode.PHONE -> AppLayout.PHONE
    DisplayMode.TABLET -> AppLayout.TABLET
    DisplayMode.TV -> AppLayout.TV
    DisplayMode.AUTO -> {
        val isTv = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION
        val smallestWidth = context.resources.configuration.smallestScreenWidthDp
        when {
            isTv -> AppLayout.TV
            smallestWidth >= 600 -> AppLayout.TABLET
            else -> AppLayout.PHONE
        }
    }
}

/**
 * TV 焦点高亮：焦点态 = 30% 深灰底 (#4D333333) + 白色描边 (与设置页一致)。
 * 不做缩放 (缩放会让大卡片看起来像"白色阴影")，描边完整画在条目边界内，
 * 即使条目在 tvFocusHighlight 之前 clip 了圆角也不会被裁。
 * 仅在 TV 布局下使用；手机/平板返回原样。
 */
fun Modifier.tvFocusHighlight(
    enabled: Boolean,
    @Suppress("unused") scale: Float = 1f,
    cornerRadius: Dp = 10.dp,
    borderWidth: Dp = 2.dp,
    onFocused: (Boolean) -> Unit = {},
): Modifier = composed {
    if (!enabled) return@composed this
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusEvent {
            if (it.isFocused != focused) {
                focused = it.isFocused
                onFocused(focused)
            }
        }
        .drawWithContent {
            if (focused) {
                // 30% 深灰底 (内容之下), 为透明背景的条目提供焦点底色.
                val r = cornerRadius.toPx()
                val inset = 1.dp.toPx()
                drawRoundRect(
                    color = FocusFillBg,
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(r - inset, r - inset),
                )
            }
            drawContent()
            if (focused) {
                // 白色描边: 圆角半径随 inset 缩小, 整条描边落在条目自身 clip(...) 的
                // 圆角区域内, 圆角处不会被裁成断线.
                val r = cornerRadius.toPx()
                val half = borderWidth.toPx() / 2
                drawRoundRect(
                    color = Color.White,
                    style = Stroke(width = borderWidth.toPx()),
                    topLeft = Offset(half, half),
                    size = Size(size.width - borderWidth.toPx(), size.height - borderWidth.toPx()),
                    cornerRadius = CornerRadius(r - half, r - half),
                )
            }
        }
}

/**
 * TV 上的可点击条目：tvFocusHighlight 必须放在 clickable 之外层，才能观察到 clickable 的焦点
 * （之前顺序相反，焦点态读不到，只剩 Material 默认的白色焦点蒙层 = “白色阴影”，且没有白色边框）。
 * TV 上同时关闭 indication，去掉那层白色蒙层，只保留与其他页面一致的白色边框。
 */
fun Modifier.focusClickable(
    tv: Boolean,
    cornerRadius: Dp,
    onFocused: (Boolean) -> Unit = {},
    onClick: () -> Unit,
): Modifier = composed {
    if (tv) {
        this
            .tvFocusHighlight(true, cornerRadius = cornerRadius, onFocused = onFocused)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
    } else {
        this.clickable(onClick = onClick)
    }
}

/** blbl 焦点配色: 30% 深灰底. */
private val FocusFillBg = Color(0x4D333333)

/** 生成一个无涟漪的 interactionSource（TV 上涟漪不明显，避免多余开销） */
@Composable
fun rememberNoRipple(): MutableInteractionSource =
    remember { MutableInteractionSource() }

/**
 * 弹窗动作按钮 (AlertDialog 的确认/取消等)：
 *   TV = blbl 焦点样式的文字按钮 (深灰底 + 双描边 + 遥控器焦点清晰可见)；
 *   其他 = Material TextButton (原样式)。
 * AlertDialog 里的 TextButton 在 TV 上只有一圈几乎不可见的 ripple，故统一走本组件。
 */
@Composable
fun TvDialogButton(
    label: String,
    tv: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!tv) {
        TextButton(onClick = onClick) { Text(label) }
        return
    }
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .tvFocusHighlight(true, scale = 1.0f, cornerRadius = 8.dp) { focused = it }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
