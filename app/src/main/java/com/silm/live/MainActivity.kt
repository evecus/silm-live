package com.silm.live

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.silm.live.core.sites.DouyuSite
import com.silm.live.core.sites.DouyinSite
import com.silm.live.ui.SilmApp
import com.silm.live.ui.theme.SilmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DouyuSite.appContext = applicationContext
        DouyinSite.appContext = applicationContext
        // 处理分享链接
        val sharedText = intent?.getStringExtra("shared_text")
        setContent {
            SilmTheme {
                SilmApp(initialLink = sharedText)
            }
        }
    }
}
