# Changelog

## 0.1.1

- 修复：成员为 JSON `null`（JsonNull）时 `getAsJsonObject/getAsJsonArray` 抛 `ClassCastException` 导致取直链/歌词崩溃
  （真实响应里 `freeTrialInfo:null` 等很常见）。新增 `objOrNull()/arrOrNull()` 安全访问器并全面替换。

## 0.1.0

首个公开版本。从 FPSMaster-Nova 抽取的网易云 / QQ 音乐数据客户端。

- 网易云：搜索、播放直链、歌词（LRC + 翻译 + **YRC 逐字**）、二维码登录、登录态、每日推荐、用户歌单、歌单详情。
- QQ 音乐：搜索、播放直链（vkey）、歌词（LRC + 翻译）、排行榜、推荐歌单、歌单详情、扫码登录、手动 Cookie 登录、用户信息。
- 新增 `LyricParser`：把 LRC / 网易云 YRC 逐字歌词解析成带时间轴的 `LyricLine`（含逐字 `words` 与翻译对齐）。
- 拆除 Minecraft / mod 运行时耦合：日志改为 `MusicLog` SPI；`MusicCredentialStore` 改为路径注入的普通类。
- 回退到 **Java 8** API（`HttpURLConnection` 取代 `java.net.http`），可用于 1.8.9 Forge / Fabric 等旧环境。
- 唯一第三方依赖：Gson。
