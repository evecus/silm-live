package com.silm.live.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silm.live.ui.focusClickable
import com.silm.live.ui.tvFocusHighlight

private val BarButtonBg = Color(0xFF2B2B2B)
private val AccentBlue = Color(0xFF4FC3F7)
private val FollowPink = Color(0xFFFF6B9D)

/** 弹幕字号档位 (sp → 名称)，与设置页一致 */
private val DANMAKU_SIZES = listOf(12 to "小", 16 to "标准", 20 to "大", 24 to "特大", 28 to "超大")

/** 弹幕屏占比档位 (%)，与设置页一致 */
private val DANMAKU_AREAS = listOf(10, 25, 50, 75, 100)

/**
 * TV 全屏播放控制层：
 *  - 左上角：标题 / 主播 · 平台 / 在线人数
 *  - 底部：一排文字按钮（清晰度、线路、画面、解码、弹幕开关、弹幕大小、屏占比、刷新、关注）
 *
 * 显示/隐藏由上层控制（遥控器下键/菜单键/确认键唤出，3s 无操作自动隐藏）。
 * 这里只负责：
 *  - 任意按钮点击 → [onInteract]，让上层重置 3s 计时
 *  - 下拉菜单展开/收起 → [onMenuOpenChange]，菜单展开期间上层暂停自动隐藏
 */
@Composable
fun TvPlayerControls(
    title: String,
    subtitle: String,
    online: String?,
    qualities: List<String>,
    selectedQuality: String?,
    onSelectQuality: (String) -> Unit,
    lineCount: Int,
    lineIndex: Int,
    onSelectLine: (Int) -> Unit,
    aspect: VideoAspect,
    onSelectAspect: (VideoAspect) -> Unit,
    hwDecode: Boolean,
    onToggleHwDecode: () -> Unit,
    danmakuEnabled: Boolean,
    onToggleDanmaku: () -> Unit,
    danmakuTextSp: Int,
    onSelectTextSp: (Int) -> Unit,
    danmakuArea: Int,
    onSelectArea: (Int) -> Unit,
    isFollowed: Boolean,
    onToggleFollow: () -> Unit,
    onRefresh: () -> Unit,
    firstFocus: FocusRequester,
    onInteract: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // ---------- 顶部：标题信息（左上角） ----------
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))
                )
                .padding(start = 24.dp, end = 24.dp, top = 10.dp, bottom = 24.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (online != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "在线 $online",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentBlue,
                    )
                }
            }
        }

        // ---------- 底部：按钮栏 ----------
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))
                )
                .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 16.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 第一个按钮承接「控制层出现时的默认焦点」
            var firstUsed = false
            fun first(): Modifier =
                if (!firstUsed) { firstUsed = true; Modifier.focusRequester(firstFocus) } else Modifier

            if (qualities.isNotEmpty()) {
                TvMenuButton(
                    label = selectedQuality ?: "清晰度",
                    items = qualities.map { it to it },
                    selected = selectedQuality,
                    onSelect = { onSelectQuality(it) },
                    onInteract = onInteract,
                    onMenuOpenChange = onMenuOpenChange,
                    modifier = first(),
                )
            }
            if (lineCount > 1) {
                TvMenuButton(
                    label = "线路 ${lineIndex + 1}/$lineCount",
                    items = (0 until lineCount).map { it to "线路 ${it + 1}" },
                    selected = lineIndex,
                    onSelect = { onSelectLine(it) },
                    onInteract = onInteract,
                    onMenuOpenChange = onMenuOpenChange,
                    modifier = first(),
                )
            }
            TvMenuButton(
                label = "画面 ${aspect.label}",
                items = VideoAspect.entries.map { it to it.label },
                selected = aspect,
                onSelect = { onSelectAspect(it) },
                onInteract = onInteract,
                onMenuOpenChange = onMenuOpenChange,
                modifier = first(),
            )
            TvBarButton(
                label = if (hwDecode) "解码 硬解" else "解码 软解",
                onClick = { onInteract(); onToggleHwDecode() },
                modifier = first(),
            )
            TvBarButton(
                label = if (danmakuEnabled) "弹幕 开" else "弹幕 关",
                onClick = { onInteract(); onToggleDanmaku() },
                modifier = first(),
            )
            TvMenuButton(
                label = "弹幕大小 ${DANMAKU_SIZES.firstOrNull { it.first == danmakuTextSp }?.second ?: "${danmakuTextSp}sp"}",
                items = DANMAKU_SIZES.map { it.first to it.second },
                selected = danmakuTextSp,
                onSelect = { onSelectTextSp(it) },
                onInteract = onInteract,
                onMenuOpenChange = onMenuOpenChange,
                modifier = first(),
            )
            TvMenuButton(
                label = "屏占比 $danmakuArea%",
                items = DANMAKU_AREAS.map { it to "$it%" },
                selected = danmakuArea,
                onSelect = { onSelectArea(it) },
                onInteract = onInteract,
                onMenuOpenChange = onMenuOpenChange,
                modifier = first(),
            )
            TvBarButton(
                label = "刷新",
                onClick = { onInteract(); onRefresh() },
                modifier = first(),
            )
            TvBarButton(
                label = if (isFollowed) "已关注" else "关注",
                onClick = { onInteract(); onToggleFollow() },
                textColor = if (isFollowed) FollowPink else Color.White,
                modifier = first(),
            )
        }
    }
}

/** 底部栏文字按钮：深灰圆角底；遥控器聚焦时白色描边 + 字体加粗 */
@Composable
private fun TvBarButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    textColor: Color = Color.White,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BarButtonBg)
            .focusClickable(tv = true, cornerRadius = 8.dp, onFocused = { focused = it }, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 点击后向上弹出选择菜单的按钮；展开期间通知上层暂停 3s 自动隐藏 */
@Composable
private fun <T> TvMenuButton(
    label: String,
    items: List<Pair<T, String>>,
    selected: T?,
    onSelect: (T) -> Unit,
    onInteract: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    fun setExpanded(v: Boolean) {
        expanded = v
        onMenuOpenChange(v)
        if (!v) onInteract() // 菜单收起后重新计 3s
    }
    Box {
        TvBarButton(label = label, onClick = { setExpanded(true) }, modifier = modifier)
        DropdownMenu(expanded = expanded, onDismissRequest = { setExpanded(false) }) {
            items.forEach { (value, text) ->
                val isSel = value == selected
                DropdownMenuItem(
                    text = {
                        Text(
                            if (isSel) "✓ $text" else text,
                            color = if (isSel) AccentBlue else Color.Unspecified,
                        )
                    },
                    modifier = Modifier.tvFocusHighlight(true, scale = 1.0f, cornerRadius = 8.dp),
                    onClick = {
                        setExpanded(false)
                        onSelect(value)
                    },
                )
            }
        }
    }
}
