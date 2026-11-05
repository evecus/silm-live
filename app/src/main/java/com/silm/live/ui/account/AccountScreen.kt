package com.silm.live.ui.account

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.silm.live.core.CookieText
import com.silm.live.data.SettingsStore
import com.silm.live.ui.AppLayout
import com.silm.live.ui.focusClickable
import kotlinx.coroutines.launch

/**
 * 账号管理（对齐 Flutter 版机制：不内置登录页，手动粘贴 Cookie）。
 *
 * 抖音搜索需要登录态：粘贴 www.douyin.com 的完整 Cookie（含 sessionid/sid_guard）；
 * 快手粘贴 live.kuaishou.com 的完整 Cookie（含 kuaishou.server.web_st）。
 * 支持从整段 Request Headers 文本中自动提取 cookie 行，支持从 txt 文件导入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    layout: AppLayout,
    onBack: () -> Unit,
) {
    val isTv = layout == AppLayout.TV
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    val douyinCookie by SettingsStore.douyinCookie(context).collectAsState(initial = "")
    val kuaishouCookie by SettingsStore.kuaishouCookie(context).collectAsState(initial = "")

    // 当前打开底部面板的平台（"douyin" / "kuaishou"）
    var sheetPlatform by remember { mutableStateOf<String?>(null) }
    // 编辑（粘贴）对话框打开的平台
    var editingPlatform by remember { mutableStateOf<String?>(null) }
    var viewingPlatform by remember { mutableStateOf<String?>(null) }
    var clearingPlatform by remember { mutableStateOf<String?>(null) }
    // 文件导入目标平台
    var importTarget by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val platform = importTarget
        importTarget = null
        if (uri != null && platform != null) {
            scope.launch {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: ""
                    saveCookie(context, platform, CookieText.normalize(text), snackbar)
                } catch (e: Exception) {
                    snackbar.showSnackbar("导入失败：${e.message}")
                }
            }
        }
    }

    fun toast(message: String) = scope.launch { snackbar.showSnackbar(message) }

    fun currentCookie(platform: String): String =
        if (platform == "douyin") douyinCookie else kuaishouCookie

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("账号管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                "抖音搜索需要登录态，粘贴 www.douyin.com 完整 Cookie；快手粘贴 live.kuaishou.com 完整 Cookie（用于搜索与弹幕）。可直接粘贴整段 Request Headers，会自动提取 Cookie。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))

            listOf(
                "douyin" to Triple("抖音直播", "手动粘贴自己的 www.douyin.com 完整 Cookie", douyinCookie),
                "kuaishou" to Triple("快手直播", "手动粘贴 live.kuaishou.com 完整 Cookie", kuaishouCookie),
            ).forEach { (platform, info) ->
                val (label, hint, cookie) = info
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusClickable(isTv, 12.dp) { sheetPlatform = platform }
                        .padding(vertical = 14.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (platform == "douyin") douyinSummary(cookie) else kuaishouSummary(cookie),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }

    sheetPlatform?.let { platform ->
        val label = if (platform == "douyin") "抖音账号" else "快手账号"
        val hasCookie = currentCookie(platform).isNotBlank()
        ModalBottomSheet(onDismissRequest = { sheetPlatform = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SheetItem(
                    icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    title = "Cookie登录",
                    subtitle = if (platform == "douyin") {
                        "手动粘贴自己的 www.douyin.com 完整 Cookie"
                    } else {
                        "手动粘贴 live.kuaishou.com 完整 Cookie"
                    },
                    isTv = isTv,
                ) {
                    sheetPlatform = null
                    editingPlatform = platform
                }
                SheetItem(
                    icon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                    title = "从文件导入 Cookie",
                    subtitle = "选择电脑传到手机上的 txt/cookie 文件",
                    isTv = isTv,
                ) {
                    sheetPlatform = null
                    importTarget = platform
                    importLauncher.launch(arrayOf("*/*"))
                }
                if (hasCookie) {
                    SheetItem(
                        icon = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                        title = "查看当前 Cookie",
                        subtitle = "可直接查看当前保存内容",
                        isTv = isTv,
                    ) {
                        sheetPlatform = null
                        viewingPlatform = platform
                    }
                    SheetItem(
                        icon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        title = "导出到剪贴板",
                        subtitle = "复制当前 Cookie 文本",
                        isTv = isTv,
                    ) {
                        sheetPlatform = null
                        clipboard.setText(AnnotatedString(currentCookie(platform)))
                        toast("Cookie 已复制到剪贴板")
                    }
                    SheetItem(
                        icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        title = "清除 Cookie",
                        subtitle = if (platform == "douyin") "清除后恢复默认 ttwid" else "清除后快手搜索和弹幕可能受限",
                        isTv = isTv,
                    ) {
                        sheetPlatform = null
                        clearingPlatform = platform
                    }
                }
            }
        }
    }

    editingPlatform?.let { platform ->
        val initial = currentCookie(platform)
        var text by remember(platform) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { editingPlatform = null },
            title = { Text(if (platform == "douyin") "配置抖音 Cookie" else "配置快手 Cookie") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    maxLines = 10,
                    label = { Text("Cookie") },
                    placeholder = {
                        Text(
                            if (platform == "douyin") "粘贴 www.douyin.com 的完整 Cookie" else "粘贴 live.kuaishou.com 的完整 Cookie",
                        )
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val platformToSave = platform
                    editingPlatform = null
                    scope.launch {
                        saveCookie(context, platformToSave, CookieText.normalize(text), snackbar)
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingPlatform = null }) { Text("取消") }
            },
        )
    }

    viewingPlatform?.let { platform ->
        AlertDialog(
            onDismissRequest = { viewingPlatform = null },
            title = { Text("当前 Cookie") },
            text = {
                Text(
                    currentCookie(platform),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(currentCookie(platform)))
                    viewingPlatform = null
                    toast("Cookie 已复制到剪贴板")
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = { viewingPlatform = null }) { Text("关闭") }
            },
        )
    }

    clearingPlatform?.let { platform ->
        AlertDialog(
            onDismissRequest = { clearingPlatform = null },
            title = { Text("清除配置") },
            text = {
                Text(
                    if (platform == "douyin") "确定要清除自定义抖音 Cookie 吗？" else "确定要清除快手 Cookie 吗？",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val platformToClear = platform
                    clearingPlatform = null
                    scope.launch {
                        saveCookie(context, platformToClear, "", snackbar)
                    }
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { clearingPlatform = null }) { Text("取消") }
            },
        )
    }
}

/** 保存（或清除）Cookie 并弹出与 Flutter 一致的结果提示 */
private suspend fun saveCookie(
    context: android.content.Context,
    platform: String,
    cookie: String,
    snackbar: SnackbarHostState,
) {
    if (platform == "douyin") {
        SettingsStore.setDouyinCookie(context, cookie)
        snackbar.showSnackbar(douyinSaveHint(cookie))
    } else {
        SettingsStore.setKuaishouCookie(context, cookie)
        snackbar.showSnackbar(kuaishouSaveHint(cookie))
    }
}

private fun douyinSaveHint(cookie: String): String = when {
    cookie.isBlank() -> "已清除自定义 Cookie，将使用默认 ttwid"
    CookieText.isOnlyTtwid(cookie) -> "当前仅为 ttwid，播放可用，但搜索登录态需要完整 Cookie"
    else -> {
        val expiry = CookieText.douyinExpiresAt(cookie)
        when {
            expiry == null -> "Cookie 已保存；未解析到到期时间，实际有效期以抖音服务端为准"
            expiry < System.currentTimeMillis() ->
                "Cookie 已保存；可解析到期时间（${CookieText.formatDateTime(expiry)}）已过，搜索失败请重新获取"
            else ->
                "Cookie 已保存；预计剩余 ${CookieText.formatRemaining(expiry - System.currentTimeMillis())}"
        }
    }
}

private fun kuaishouSaveHint(cookie: String): String = when {
    cookie.isBlank() -> "已清除快手 Cookie"
    CookieText.kuaishouHasLoginFields(cookie) -> "快手 Cookie 已保存"
    else -> "Cookie 已保存，但未识别到典型登录字段（web_st），搜索可能仍受限"
}

/** 账号管理页抖音行的状态文案（对齐 Flutter getDouyinCookieSummaryText） */
private fun douyinSummary(cookie: String): String = when {
    cookie.isBlank() -> "使用默认 ttwid，搜索受限时可配置完整 Cookie"
    else -> {
        val expiry = CookieText.douyinExpiresAt(cookie)
        when {
            expiry == null -> "已自定义（${cookie.length} 字符），有效期无法判断"
            expiry < System.currentTimeMillis() -> "已自定义（${cookie.length} 字符），可解析有效期已过"
            else -> "已自定义（${cookie.length} 字符），预计剩余 ${CookieText.formatRemaining(expiry - System.currentTimeMillis())}"
        }
    }
}

/** 账号管理页快手行的状态文案（对齐 Flutter getKuaishouCookieSummaryText） */
private fun kuaishouSummary(cookie: String): String = when {
    cookie.isBlank() -> "建议配置 Cookie，用于搜索和弹幕"
    else -> "已配置 Cookie（${cookie.length} 字符），有效期无法判断"
}

@Composable
private fun SheetItem(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    isTv: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusClickable(isTv, 12.dp) { onClick() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        icon()
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
