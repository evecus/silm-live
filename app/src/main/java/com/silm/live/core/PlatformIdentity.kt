package com.silm.live.core

/** 各平台请求身份指纹（UA 等），与 Simple Live 核心库保持一致 */
object PlatformIdentity {
    const val DOUYU_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    const val DOUYU_ROOM_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.43"
    /** 斗鱼：搜索相关接口（Edg/114.0.1823.51 变体，与 simple_live_core 一致） */
    const val DOUYU_SEARCH_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.51"
    const val HUYA_UA =
        "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36 Edg/117.0.0.0"
    const val HUYA_HYSDK_UA =
        "HYSDK(Windows, 30000002)_APP(pc_exe&7060000&official)_SDK(trans&2.32.3.5646)"
    /** 手机端 HYSDK UA：与 m 站 anticode(ctype=tars_mobile, t=103) 配套，CDN 校验 UA 与 ctype 一致性 */
    const val HUYA_MOBILE_HYSDK_UA = "HYSDK(Android,20000323)"
    const val BILI_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"
    const val BILI_ROOM_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Safari/537.36 Edg/115.0.1901.188"
    const val DOUYIN_UA =
        "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.5845.97 Safari/537.36 Core/1.116.567.400 QQBrowser/19.7.6764.400"
    /** 快手网页端：与 simple_live_core 的 kuaishouUserAgent 保持一致 */
    const val KUAISHOU_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    /**
     * 小红书移动端 UA：房间页 __INITIAL_STATE__ 只在移动端 UA 下注入
     * liveStream.roomData.roomInfo（沙箱实测：桌面 UA 返回 roomStatus=-1、roomInfo 全空）。
     */
    const val XHS_MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
}
