package com.silm.live.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silm.live.core.SiteManager
import com.silm.live.data.FollowStore
import com.silm.live.data.SettingsStore
import com.silm.live.ui.AppLayout
import com.silm.live.ui.DisplayMode
import com.silm.live.ui.PhonePlayOrientation
import com.silm.live.ui.follow.FollowViewModel
import com.silm.live.ui.theme.TvBackground
import com.silm.live.ui.focusClickable
import com.silm.live.ui.tvFocusHighlight
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    layout: AppLayout,
    onBack: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenAccount: () -> Unit = {},
    onOpenRoom: (platform: String, roomId: String) -> Unit,
    followViewModel: FollowViewModel = viewModel(factory = FollowViewModel.Factory(LocalContext.current)),
) {
    val isTv = layout == AppLayout.TV
    // TV 端整体放大：卡片标题 / 说明文字 / chips 与按钮
    val titleStyle = if (isTv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium
    val bodyStyle = if (isTv) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodySmall
    val chipStyle = if (isTv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    val buttonStyle = if (isTv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var linkText by remember { mutableStateOf("") }
    var parsing by remember { mutableStateOf(false) }

    val displayModeName by SettingsStore.displayMode(context).collectAsState(initial = "AUTO")
    val phoneOrientationName by SettingsStore.phoneOrientation(context)
        .collectAsState(initial = PhonePlayOrientation.AUTO.name)
    val hwDecode by SettingsStore.hwDecode(context).collectAsState(initial = true)
    val defaultQuality by SettingsStore.defaultQuality(context).collectAsState(initial = 0)
    val danmakuTextSp by SettingsStore.danmakuTextSp(context).collectAsState(initial = 16)
    val danmakuArea by SettingsStore.danmakuArea(context).collectAsState(initial = 25)
    val platformOrder by SettingsStore.platformOrder(context)
        .collectAsState(initial = SettingsStore.defaultPlatformOrder())
    val douyinCookie by SettingsStore.douyinCookie(context).collectAsState(initial = "")
    val kuaishouCookie by SettingsStore.kuaishouCookie(context).collectAsState(initial = "")

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = FollowStore.exportJson(context)
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    }
                    snackbar.showSnackbar("关注数据已导出")
                } catch (e: Exception) {
                    snackbar.showSnackbar("导出失败：${e.message}")
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: ""
                    FollowStore.importJson(context, json, replace = false)
                    snackbar.showSnackbar("关注数据已导入（合并）")
                } catch (e: Exception) {
                    snackbar.showSnackbar("导入失败：${e.message}")
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (isTv) TvBackground else MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ---------- 紧凑头部（返回 + 标题） ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 20.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Text(
                    "设置",
                    style = if (isTv) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // ---------- 显示模式 ----------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("显示模式", style = titleStyle)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "自动：TV 设备显示电视界面，大屏（≥600dp）显示平板界面，其余显示手机界面",
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DisplayMode.entries.forEach { mode ->
                            val label = when (mode) {
                                DisplayMode.AUTO -> "自动"
                                DisplayMode.PHONE -> "手机"
                                DisplayMode.TABLET -> "平板"
                                DisplayMode.TV -> "电视"
                            }
                            FilterChip(
                                selected = displayModeName == mode.name,
                                onClick = {
                                    scope.launch { SettingsStore.setDisplayMode(context, mode.name) }
                                },
                                label = { Text(label, style = chipStyle) },
                                modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 8.dp)
                            )
                        }
                    }
                }
            }

            // ---------- 直播平台排序 ----------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("直播平台排序", style = titleStyle)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "调整平台次序，搜索页的平台标签与关注页的平台分类都会按此顺序显示",
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    val orderedSites = SettingsStore.sitesInOrder(platformOrder)
                    orderedSites.forEachIndexed { index, site ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${index + 1}",
                                style = chipStyle,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.width(if (isTv) 36.dp else 28.dp)
                            )
                            Text(
                                site.name.removeSuffix("直播"),
                                style = if (isTv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                enabled = index > 0,
                                onClick = {
                                    val ids = orderedSites.map { it.id }.toMutableList()
                                    ids.add(index - 1, ids.removeAt(index))
                                    scope.launch { SettingsStore.setPlatformOrder(context, ids) }
                                },
                                modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 20.dp)
                            ) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
                            }
                            IconButton(
                                enabled = index < orderedSites.lastIndex,
                                onClick = {
                                    val ids = orderedSites.map { it.id }.toMutableList()
                                    ids.add(index + 1, ids.removeAt(index))
                                    scope.launch { SettingsStore.setPlatformOrder(context, ids) }
                                },
                                modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 20.dp)
                            ) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { scope.launch { SettingsStore.resetPlatformOrder(context) } },
                        modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 20.dp)
                    ) {
                        Text("恢复默认顺序", style = buttonStyle)
                    }
                }
            }

            // ---------- 默认清晰度 ----------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("默认清晰度", style = titleStyle)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "进入直播间时默认选择的画质档位（按各平台从高到低排序取对应位置）",
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("最高", "较高", "中等", "较低", "最低").forEachIndexed { idx, label ->
                            FilterChip(
                                selected = defaultQuality == idx,
                                onClick = {
                                    scope.launch { SettingsStore.setDefaultQuality(context, idx) }
                                },
                                label = { Text(label, style = chipStyle) },
                                modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 8.dp)
                            )
                        }
                    }
                }
            }

            // ---------- 手机播放比例（仅在手机界面下显示） ----------
            if (layout == AppLayout.PHONE) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("手机播放比例", style = titleStyle)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "横屏：始终横屏全屏播放；竖屏：始终竖屏全屏播放；自动：横屏视频横屏播放，竖屏视频竖屏播放",
                            style = bodyStyle,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PhonePlayOrientation.entries.forEach { orientation ->
                                val label = when (orientation) {
                                    PhonePlayOrientation.LANDSCAPE -> "横屏"
                                    PhonePlayOrientation.PORTRAIT -> "竖屏"
                                    PhonePlayOrientation.AUTO -> "自动"
                                }
                                FilterChip(
                                    selected = phoneOrientationName == orientation.name,
                                    onClick = {
                                        scope.launch {
                                            SettingsStore.setPhoneOrientation(context, orientation.name)
                                        }
                                    },
                                    label = { Text(label, style = chipStyle) },
                                    modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // ---------- 解码方式 ----------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("播放解码", style = titleStyle)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "硬解：MediaCodec 硬件解码，省电流畅，个别设备可能花屏或黑屏；软解：软件解码器兜底，兼容性最好但更耗电。切换后重新进入直播间生效",
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = hwDecode,
                            onClick = { scope.launch { SettingsStore.setHwDecode(context, true) } },
                            label = { Text("硬件解码", style = chipStyle) },
                            modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 8.dp)
                        )
                        FilterChip(
                            selected = !hwDecode,
                            onClick = { scope.launch { SettingsStore.setHwDecode(context, false) } },
                            label = { Text("软件解码", style = chipStyle) },
                            modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 8.dp)
                        )
                    }
                }
            }

            // ---------- 弹幕设置 ----------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("弹幕设置", style = titleStyle)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "弹幕字号与弹幕屏占比（弹幕从屏幕顶部开始占屏幕高度的百分比）",
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("弹幕大小", style = bodyStyle)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(12 to "小", 16 to "标准", 20 to "大", 24 to "特大", 28 to "超大").forEach { (sp, label) ->
                            FilterChip(
                                selected = danmakuTextSp == sp,
                                onClick = {
                                    scope.launch { SettingsStore.setDanmakuTextSp(context, sp) }
                                },
                                label = { Text(label, style = chipStyle) },
                                modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("弹幕屏占比", style = bodyStyle)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 25, 50, 75, 100).forEach { pct ->
                            FilterChip(
                                selected = danmakuArea == pct,
                                onClick = {
                                    scope.launch { SettingsStore.setDanmakuArea(context, pct) }
                                },
                                label = { Text("$pct%", style = chipStyle) },
                                modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 8.dp)
                            )
                        }
                    }
                }
            }

            // ---------- 账号管理（抖音/快手 Cookie 粘贴配置） ----------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("账号管理", style = titleStyle)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "抖音搜索需要登录态，快手配置 Cookie 可降低搜索风控概率。粘贴完整 Cookie 或从文件导入。",
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    listOf(
                        "douyin" to ("抖音直播" to douyinCookie),
                        "kuaishou" to ("快手直播" to kuaishouCookie),
                    ).forEach { (platform, pair) ->
                        val (label, cookie) = pair
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusClickable(isTv, 12.dp) { onOpenAccount() }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(label, style = chipStyle)
                                Text(
                                    if (cookie.isBlank()) "未配置" else "已自定义（${cookie.length} 字符）",
                                    style = bodyStyle,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            TextButton(onClick = { onOpenAccount() }) {
                                Text("配置", style = buttonStyle)
                            }
                        }
                    }
                }
            }

            // ---------- 链接解析 ----------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("链接解析", style = titleStyle)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "粘贴直播间分享链接（支持 B站 / 斗鱼 / 虎牙 / 抖音 / 快手 / 小红书，含各平台分享短链）",
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = linkText,
                        onValueChange = { linkText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://live.bilibili.com/xxxxx") },
                        minLines = 2,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(
                            onClick = {
                                if (linkText.isBlank() || parsing) return@Button
                                parsing = true
                                scope.launch {
                                    try {
                                        val parsed = SiteManager.parseUrl(linkText.trim())
                                        parsed?.let { (site, roomId) ->
                                            linkText = ""
                                            onOpenRoom(site.id, roomId)
                                        } ?: snackbar.showSnackbar("无法识别链接，请检查内容")
                                    } catch (e: Exception) {
                                        snackbar.showSnackbar("解析失败：${e.message}")
                                    } finally {
                                        parsing = false
                                    }
                                }
                            },
                            enabled = linkText.isNotBlank() && !parsing,
                            modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 20.dp)
                        ) {
                            if (parsing) {
                                CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("解析并打开", style = buttonStyle)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 20.dp), onClick = {
                            val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                            val text = clip?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            if (text.isNotEmpty()) linkText = text
                        }) {
                            Text("粘贴", style = buttonStyle)
                        }
                    }
                }
            }

            // ---------- 远程关注同步 ----------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusClickable(isTv, 12.dp) { onOpenSync() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Devices, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("远程关注同步", style = titleStyle)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "扫描局域网设备，用本机关注列表覆盖其他设备",
                            style = bodyStyle,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }

            // ---------- 数据同步 ----------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("数据同步", style = titleStyle)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "通过 JSON 文件备份/恢复关注列表，可在多设备间迁移",
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 20.dp), onClick = {
                            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
                            exportLauncher.launch("silm_follows_$ts.json")
                        }) {
                            Text("导出关注数据", style = buttonStyle)
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(modifier = Modifier.tvFocusHighlight(isTv, cornerRadius = 20.dp), onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        }) {
                            Text("导入关注数据", style = buttonStyle)
                        }
                    }
                }
            }

            // ---------- 关于 ----------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("关于", style = titleStyle)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "简播 1.1.0\n" +
                            "Jetpack Compose 多端直播客户端（手机 / 平板 / 电视），支持哔哩哔哩 / 斗鱼 / 虎牙 / 抖音 / 快手 / 小红书直播观看与弹幕。\n\n" +
                            "核心接口逻辑参考开源项目 dart_simple_live，仅供学习交流使用。",
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}
