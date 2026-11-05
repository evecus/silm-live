package com.silm.live.core

/**
 * 站点登录 Cookie 的内存缓存。
 *
 * 由 data 层（SettingsStore）在应用启动时灌入、保存/清除时同步更新；
 * core 层的站点（无 Context）在构造请求头时同步读取。
 */
object AccountCookies {

    /** 抖音完整 Cookie（手动粘贴，含 sessionid/sid_guard 等登录态） */
    @Volatile
    var douyin: String = ""

    /** 快手完整 Cookie（手动粘贴，含 kuaishou.server.web_st 等登录态） */
    @Volatile
    var kuaishou: String = ""
}
