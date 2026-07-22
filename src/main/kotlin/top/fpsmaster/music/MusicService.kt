package top.fpsmaster.music

/**
 * 统一门面：按 [MusicSource] 分发到具体平台客户端。这是本库的推荐入口。
 *
 * 只负责返回直链/元数据/歌词/登录态；**不负责播放**——拿到 [SongUrl.url] 后交给你自己的
 * 原生音频播放器解码即可。
 *
 * 每个平台客户端各自持有登录态；持久化/回填登录凭证请分别操作
 * [netease].cookie 与 [qq].musicid/[qq].musicKey（可配合 [top.fpsmaster.music.store.MusicCredentialStore]）。
 */
class MusicService(
    val netease: NeteaseMusicApi = NeteaseMusicApi(),
    val qq: QQMusicApi = QQMusicApi(),
) {
    fun search(source: MusicSource, keyword: String, limit: Int = 30): List<Track> =
        when (source) {
            MusicSource.NETEASE -> netease.search(keyword, limit)
            MusicSource.QQ -> qq.search(keyword, limit)
        }

    fun getSongUrl(track: Track, quality: AudioQuality = AudioQuality.STANDARD): SongUrl =
        when (track.source) {
            MusicSource.NETEASE -> netease.getSongUrl(track, quality)
            MusicSource.QQ -> qq.getSongUrl(track, quality)
        }

    fun getLyric(track: Track): Lyric =
        when (track.source) {
            MusicSource.NETEASE -> netease.getLyric(track)
            MusicSource.QQ -> qq.getLyric(track)
        }

    fun createQrCode(source: MusicSource): QrCode =
        when (source) {
            MusicSource.NETEASE -> netease.createQrCode()
            MusicSource.QQ -> qq.createQrCode()
        }

    fun checkQrCode(source: MusicSource, qr: QrCode): QrLoginState =
        when (source) {
            MusicSource.NETEASE -> netease.checkQrCode(qr)
            MusicSource.QQ -> qq.checkQrCode(qr)
        }
}
