# 简播（silm-live）

用 **Jetpack Compose** 重写的多平台直播客户端，接口逻辑移植自开源项目
[dart_simple_live](https://github.com/simple-live/simple_live)，仅供学习交流使用。

- 包名：`com.silm.live`
- 应用名：简播
- 播放内核：IjkPlayer（GSY 完整 ffmpeg，arm64-v8a）

## 功能

- **关注页**：关注列表、开播状态并发刷新、按状态筛选（全部 / 直播中 / 未开播）、置顶、删除
- **播放页**：IjkPlayer 播放（硬解、自动多线路切换）、清晰度切换、实时弹幕、在线人数、收藏
- **设置页**：数据同步（JSON 导出/导入关注数据）、链接解析（粘贴任意平台分享链接直接进直播间）
- **弹幕**：B站（二进制 + brotli）、斗鱼（STT）、虎牙（TARS）、抖音（protobuf + 签名）
- 底部导航：关注 / 设置

## 支持平台

| 平台 | 房间详情 | 播放地址 | 弹幕 | 备注 |
|------|---------|---------|------|------|
| 哔哩哔哩 | ✅ | ✅ flv/hls | ✅ | wbi 签名、游客弹幕 |
| 斗鱼 | ✅ | ✅ flv 多线路 | ✅ | WebView 执行签名脚本 |
| 虎牙 | ✅ | ✅ flv 多线路 | ✅ | TUP(TARS) 取流 token |
| 抖音 | ✅ | ✅ flv/hls | ✅ | a_bogus/MSSDK 签名（WebView） |

## 技术栈

- Kotlin 2.0 + Jetpack Compose（Material 3）+ Navigation
- IjkPlayer（GSY 维护版，完整 ffmpeg，仅 arm64-v8a）
- OkHttp（REST + WebSocket）
- DataStore Preferences（关注数据 / 配置）
- Compose Canvas 自绘弹幕层
- WebView JS 引擎执行斗鱼 / 抖音签名脚本（assets 内置）

## 构建

```bash
./gradlew assembleDebug
# 或
./gradlew assembleRelease
```

仅输出 **arm64-v8a**（见 `app/build.gradle.kts` 中 `abiList`）。

GitHub Actions：

- `.github/workflows/android.yml` — push / PR / 手动触发，产出 debug + release APK
- `.github/workflows/build-arm64.yml` — 手动或打 tag `v*` 时构建 arm64 包

Artifacts 中下载 APK。

## 工程结构

- `app/src/main/java/com/silm/live/core/` — 平台核心（HTTP/模型/站点实现/TARS/protobuf/签名）
- `app/src/main/java/com/silm/live/danmaku/` — 各平台弹幕协议
- `app/src/main/java/com/silm/live/data/` — 关注数据存储
- `app/src/main/java/com/silm/live/ui/` — 关注 / 播放 / 设置页面与弹幕渲染
- `app/src/main/assets/*.js` — 斗鱼 crypto-js、抖音 a_bogus / MSSDK 签名脚本

## 免责声明

本项目不存储任何平台数据，仅供个人学习研究，请勿用于商业用途。

## Release 构建与体积

- Release 默认开启 **R8 混淆 + 资源收缩**，并压缩 native `.so`（`useLegacyPackaging`）。
- 单 ABI 打包：`-Pabi=arm64-v8a` 或 `-Pabi=armeabi-v7a`（勿同时打多架构）。
- CI 签名使用仓库 Secrets：
  - `KEYSTORE_BASE64`：keystore 文件的 base64
  - `KEY_ALIAS` / `KEY_PASSWORD` / `STORE_PASSWORD`
- Workflows：
  - `build-arm64.yml`：签名 arm64-v8a release
  - `build-arm32.yml`：签名 armeabi-v7a release

本地签名示例：

```bash
export STORE_FILE=/path/to/keystore.jks
export STORE_PASSWORD=***
export KEY_ALIAS=***
export KEY_PASSWORD=***
./gradlew assembleRelease -Pabi=arm64-v8a
```
