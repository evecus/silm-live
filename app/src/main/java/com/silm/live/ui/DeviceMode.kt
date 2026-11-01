package com.silm.live.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
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
 * TV 焦点高亮：获得焦点时放大 + 白色描边，保证遥控器操作时焦点清晰可见。
 * 仅在 TV 布局下使用；手机/平板返回原样。
 */
fun Modifier.tvFocusHighlight(
    enabled: Boolean,
    scale: Float = 1.06f,
    cornerRadius: Dp = 10.dp,
    borderWidth: Dp = 2.5f.dp,
): Modifier = composed {
    if (!enabled) return@composed this
    var focused by remember { mutableStateOf(false) }
    val scaleAnim by animateFloatAsState(
        targetValue = if (focused) scale else 1f,
        label = "tvFocusScale",
    )
    this
        .onFocusEvent { focused = it.isFocused }
        .scale(scaleAnim)
        .border(
            width = if (focused) borderWidth else 0.dp,
            color = if (focused) Color.White else Color.Transparent,
            shape = RoundedCornerShape(cornerRadius),
        )
}

/** 生成一个无涟漪的 interactionSource（TV 上涟漪不明显，避免多余开销） */
@Composable
fun rememberNoRipple(): MutableInteractionSource =
    remember { MutableInteractionSource() }
