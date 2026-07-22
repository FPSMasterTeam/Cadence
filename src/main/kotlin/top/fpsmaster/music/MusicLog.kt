package top.fpsmaster.music

/**
 * 日志接口（SPI）。
 *
 * 本库不依赖任何具体日志框架，也不依赖宿主（Minecraft / mod）运行时。默认实现为无操作。
 * 宿主若想看日志，注入自己的实现即可，例如接到 SLF4J / Log4j / mod 自己的 logger：
 *
 * ```kotlin
 * MusicLog.logger = object : MusicLogger {
 *     override fun info(msg: String) = myLogger.info(msg)
 *     override fun error(msg: String, t: Throwable?) = myLogger.error(msg, t)
 * }
 * ```
 */
interface MusicLogger {
    fun debug(msg: String) {}
    fun info(msg: String) {}
    fun warn(msg: String) {}
    fun error(msg: String, t: Throwable? = null) {}
}

/**
 * 全局日志门面。默认无操作；宿主可替换 [logger]。
 */
object MusicLog {
    /** 默认无操作实现，替换它以接管日志输出。 */
    @Volatile
    var logger: MusicLogger = object : MusicLogger {}

    fun debug(msg: String) = logger.debug(msg)
    fun info(msg: String) = logger.info(msg)
    fun warn(msg: String) = logger.warn(msg)
    fun error(msg: String, t: Throwable? = null) = logger.error(msg, t)
}
