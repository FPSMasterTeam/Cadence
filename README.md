# Cadence — 网易云 / QQ 音乐 数据客户端（独立可复用库）

[![build](https://github.com/FPSMasterTeam/Cadence/actions/workflows/build.yml/badge.svg)](https://github.com/FPSMasterTeam/Cadence/actions/workflows/build.yml)
[![](https://jitpack.io/v/FPSMasterTeam/Cadence.svg)](https://jitpack.io/#FPSMasterTeam/Cadence)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

一个**零 Minecraft 依赖、兼容 Java 8** 的 JVM 库（maven 坐标 `top.fpsmaster:music-api`），提供网易云音乐 +
QQ 音乐的**取数据**能力：搜索、播放直链、歌词（含逐字/翻译解析）、歌单、每日推荐、二维码登录、凭证持久化。

**只取数据，不播放、不带界面。** 拿到 `SongUrl.url` 后交给你自己的原生音频播放器解码即可
（例如 `javax.sound.sampled` + mp3spi，或 JavaFX / VLCJ）。

> 从 FPSMaster-Nova 抽取而来。原实现里播放与界面在 CEF webview 的 `<audio>` 完成；本库只保留
> 后端数据层，拆掉了对 mod 运行时的耦合，并回退到 Java 8 API，可直接用于任何 JVM 项目 / Minecraft mod
> （含 1.8.9 Forge / Fabric）。

## 依赖

- **Java 8+**（仅用 JDK 内置：`HttpURLConnection`、`javax.crypto`、`BigInteger`、`MessageDigest`、`java.nio.file`）
- 唯一第三方依赖：**Gson**（JSON 解析）

其余全部走 JDK 内置——加密（AES/RSA/SHA-1/Base64）、HTTP、文件 IO 都不引额外库。

## 结构

| 包 / 文件 | 作用 |
|---|---|
| `MusicModels.kt` | 跨平台数据模型（`Track` / `SongUrl` / `Lyric` / `LyricLine` / `QrCode` …） |
| `MusicService.kt` | 统一门面，按 `MusicSource` 分发（**推荐入口**） |
| `NeteaseMusicApi.kt` | 网易云客户端 |
| `QQMusicApi.kt` | QQ 音乐客户端 |
| `MusicLog.kt` | 日志 SPI（默认无操作，宿主可注入自己的 logger） |
| `crypto/` | 网易 weapi 加密、QQ zzc 签名、hash33 |
| `http/MusicHttp.kt` | 轻量 HTTP 助手（禁自动跳转、可控 cookie） |
| `lyric/LyricParser.kt` | **LRC + 网易云 YRC 逐字** 歌词解析器 |
| `store/MusicCredentialStore.kt` | 登录凭证磁盘持久化（路径注入，可选） |

## 快速上手

```kotlin
import top.fpsmaster.music.*

val music = MusicService()

// 搜索
val tracks = music.search(MusicSource.NETEASE, "周杰伦")
val qqTracks = music.search(MusicSource.QQ, "告白气球")

// 播放直链（交给你的原生播放器）
val url = music.getSongUrl(tracks.first(), AudioQuality.HIGH)
if (url.available) myPlayer.play(url.url!!)
// url.isTrial / url.reason 可用于试听角标与"跳过不可播"

// 歌词（已解析好时间轴）
val lyric = music.getLyric(tracks.first())
for (line in lyric.lines) {
    println("${line.startMs}ms  ${line.text}  ${line.translation ?: ""}")
    if (line.words.isNotEmpty()) {         // 网易云逐字（卡拉OK）
        line.words.forEach { w -> /* w.startMs / w.durationMs / w.text 高亮 */ }
    }
}

// 二维码登录（每 2~3 秒轮询）
val qr = music.createQrCode(MusicSource.NETEASE)
// 网易云：qr.qrContent 是 URL，自己渲染成二维码；QQ：qr.qrContent 已是 data:image/png;base64 图片
val state = music.checkQrCode(MusicSource.NETEASE, qr)
// state == CONFIRMED 时，登录凭证已写入 music.netease.cookie
```

## 歌词解析（本库新增）

`LyricParser` 把原始文本歌词解析成带时间轴的 `List<LyricLine>`，两个源统一由 provider 自动调用，
你直接读 `lyric.lines` 即可，无需自己写解析器：

- **标准 LRC**：`[mm:ss.xx]文本`，兼容单行多时间戳、`[offset:±ms]`、灵活位数；无时间戳的
  元信息标签（`[ti:]`/`[ar:]`…）自动跳过。行时长由下一行推算。
- **网易云 YRC 逐字**：`[行起点,行时长](字起点,字时长,0)字...`，解析出每个字的时间，
  供卡拉OK高亮（`LyricLine.words`）；开头 `{...}` JSON 元信息行标记 `isMetadata`。
- **翻译**：按时间戳对齐到主歌词行（精确匹配，再取 500ms 内最近），落在 `LyricLine.translation`。

也可脱离网络单独用于任何已有歌词文本：

```kotlin
val lines = LyricParser.parse(lrc = someLrc, translated = someTrans, yrc = someYrc)
```

各源歌词能力：网易云 = LRC + 翻译 + **YRC 逐字**；QQ = LRC + 翻译（QQ 无逐字，其 QRC 为加密格式，本库不解）。

## 登录凭证持久化（可选）

不再是写死路径的全局单例，改为**路径注入**的普通类，多宿主同 JVM 也不冲突：

```kotlin
val store = MusicCredentialStore.inDir(myModDataDir)   // 或 MusicCredentialStore.default("MyMod")
store.load()
music.netease.cookie = store.neteaseCookie
music.qq.musicid = store.qqMusicId; music.qq.musicKey = store.qqMusicKey
// 登录成功后回写：
store.setNetease(music.netease.cookie)
store.setQq(music.qq.musicid, music.qq.musicKey)
```

## 错误处理

所有公开方法失败时只抛 `MusicApiException`，用 `kind`（`MusicErrorKind`）区分原因，`platformCode` 是平台原始业务码：

| kind | 含义 | 建议界面 |
|---|---|---|
| `NOT_LOGGED_IN` | 网易云 code=301、QQ code=1000：未登录或登录过期 | 切回未登录，提示重新扫码 |
| `RISK_CONTROL` | 网易云 -460/-461/-462/405：风控、需验证、操作频繁 | 提示稍后再试 |
| `NETWORK` / `TIMEOUT` | 连接失败 / 超过单次请求总时限（默认 30 秒） | 「重试」按钮 |
| `BAD_RESPONSE` | 非 JSON、空 body、结构不符、响应体超过上限 | 显示错误信息 |
| `API_ERROR` | 其它业务错误码 | 显示错误信息 |

`netease.getLoginUid()` 在 cookie 失效时返回 `null`，可用来校验持久化回来的登录态。

## 接管日志（可选）

```kotlin
MusicLog.logger = object : MusicLogger {
    override fun info(msg: String) = myLogger.info(msg)
    override fun error(msg: String, t: Throwable?) = myLogger.error(msg, t)
}
```

默认无操作，不打印任何东西。凭证、cookie 值、搜索词不会出现在 info 及以上级别的日志里。

## 功能矩阵

| 功能 | 网易云 | QQ 音乐 |
|---|---|---|
| 搜索 | ✅ | ✅ |
| 播放直链 | ✅ | ✅（需登录取，高音质/VIP 需绿钻） |
| 歌词 | ✅ LRC+翻译+**逐字** | ✅ LRC+翻译 |
| 二维码登录 | ✅（unikey） | ✅（ptlogin，上线前建议真机联调） |
| 手动 Cookie 登录 | 直接赋值 `netease.cookie` | 直接赋值 `qq.musicid`/`qq.musicKey` |
| 每日推荐 / 用户歌单 / 歌单详情 | ✅ | 排行榜 / 推荐歌单 / 歌单详情 ✅ |

## 引用（JitPack）

本库由 [JitPack](https://jitpack.io/#FPSMasterTeam/Cadence) 托管，**不需要**本地构建或发布：

```kotlin
repositories { maven("https://jitpack.io") }
dependencies { implementation("com.github.FPSMasterTeam:Cadence:v0.1.2") }
```

版本号就是 git tag（带 `v` 前缀）。JitPack 会在首次拉取时按 `jitpack.yml` 现场构建并把坐标
重写成 `com.github.FPSMasterTeam:Cadence:<tag>`（源码包名仍是 `top.fpsmaster.music.*`，
本地 `publishToMavenLocal` 的坐标仍是 `top.fpsmaster:music-api`）。

**发新版**：改 `build.gradle.kts` 里的 `version` → 提交 → 打 tag 并推送，即可被引用：

```bash
git tag v0.1.3 && git push origin v0.1.3
```

已知消费方：**FPSMaster-Edge**（1.8.9 / Forge，shadow 进 jar）、**FPSMaster-Nova**
（Fabric 多版本，bundle 进 jar）。两边都不打传递依赖——gson 由 MC 提供，kotlin-stdlib 各自单独引。

## 构建（开发本库时）

```bash
./gradlew build                 # 编译 + 跑歌词解析单测（输出 Java 8 字节码）
./gradlew publishToMavenLocal   # 装到本地 mavenLocal（top.fpsmaster:music-api），仅本地联调用
```

> 从 Java 调用完全可行（Kotlin 编译为普通 JVM 类），运行期需要 `kotlin-stdlib` 在 classpath 上。

## 已知限制

1. **会员墙**：高音质/VIP 歌两个平台都要求登录会员账号，未登录/非会员时直链回退低音质或为空
   （`SongUrl.available == false`，原因见 `SongUrl.reason`）。
2. **QQ 登录脆弱**：ptlogin 流程的 `aid/daid/pt_3rd_aid` 等常量会随腾讯改版漂移；失效时优先核对
   `QQMusicApi.createQrCode/checkQrCode` 里的常量。
3. **逆向接口**：签名参数腾讯偶尔更新（网易云 weapi 多年稳定，QQ 需更勤跟进）。
4. **风控**：机房/境外 IP 可能被网易云风控（`code:50000005`）；住宅/国内 IP + 登录态为正常可用环境。

## 许可与合规

- 本库为**独立实现**，依据公开的算法事实（加密步骤、端点、参数）编写，不含任何开源仓库的源码。
- 逆向接口仅供学习/个人使用，请遵守各平台版权与服务条款。
