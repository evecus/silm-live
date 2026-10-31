package com.silm.live.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silm.live.core.SiteManager
import com.silm.live.data.FollowStore
import com.silm.live.ui.follow.FollowViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenRoom: (platform: String, roomId: String) -> Unit,
    followViewModel: FollowViewModel = viewModel(factory = FollowViewModel.Factory(LocalContext.current)),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var linkText by remember { mutableStateOf("") }
    var parsing by remember { mutableStateOf(false) }

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

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            androidx.compose.material3.TopAppBar(title = { Text("设置") })

            // ---------- 链接解析 ----------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("链接解析", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "粘贴直播间分享链接（支持 B站 / 斗鱼 / 虎牙 / 抖音，含抖音分享短链）",
                        style = MaterialTheme.typography.bodySmall,
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
                            enabled = linkText.isNotBlank() && !parsing
                        ) {
                            if (parsing) {
                                CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("解析并打开")
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                            val text = clip?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            if (text.isNotEmpty()) linkText = text
                        }) {
                            Text("粘贴")
                        }
                    }
                }
            }

            // ---------- 数据同步 ----------
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("数据同步", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "通过 JSON 文件备份/恢复关注列表，可在多设备间迁移",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Button(onClick = {
                            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
                            exportLauncher.launch("silm_follows_$ts.json")
                        }) {
                            Text("导出关注数据")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        }) {
                            Text("导入关注数据")
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
                    Text("关于", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "简播 1.0.0\n" +
                            "Jetpack Compose 多平台直播客户端，支持哔哩哔哩 / 斗鱼 / 虎牙 / 抖音直播观看与弹幕。\n\n" +
                            "核心接口逻辑参考开源项目 dart_simple_live，仅供学习交流使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
