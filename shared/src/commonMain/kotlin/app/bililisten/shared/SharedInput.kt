package app.bililisten.shared

import io.ktor.http.Url

sealed interface SharedTarget {
    data class Video(val bvid: String, val part: Int = 1) : SharedTarget
    data class Live(val room: Long) : SharedTarget
}

object SharedInput {
    fun url(input: String): String {
        require(input.length <= 8192)
        return Regex("https://(?:b23\\.tv|live\\.bilibili\\.com|(?:www\\.|m\\.)?bilibili\\.com)/[^\\s<>\"「」]+")
            .find(input)?.value?.trimEnd('。', '，', ')', '）') ?: throw PlatformFailure("分享中没有支持的 B 站 HTTPS 链接")
    }
    fun direct(input: String): SharedTarget? {
        if (Bvid.parse(input) == input) return SharedTarget.Video(input)
        val url = try { Url(input) } catch (_: Exception) { return null }
        if (url.protocol.name != "https" || url.port != 443 || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) return null
        if (url.host == "live.bilibili.com") {
            val room = LiveInput.room(input) ?: return null
            return SharedTarget.Live(room)
        }
        if (url.host !in setOf("www.bilibili.com", "m.bilibili.com", "bilibili.com")) return null
        val bv = Bvid.parse(input) ?: return null
        return SharedTarget.Video(bv, url.parameters["p"]?.toIntOrNull()?.takeIf { it > 0 } ?: 1)
    }
}
