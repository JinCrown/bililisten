package app.bililisten.shared

import kotlinx.serialization.Serializable

@Serializable data class AudioChoice(val id: Int? = null, val codec: String = "") {
    fun checked(): AudioChoice { require(id == null && codec.isEmpty() || id != null && id > 0 && codec.lowercase() in setOf("flac", "ec-3", "eac3", "ec+3", "mp4a.40.2", "mp4a.40.5", "mp4a.40.29")); return this }
    fun matches(track: AudioTrack) = id == track.id && codec.equals(track.codec, true)
}
@Serializable enum class AudioRestriction(val message: String) {
    ACCESS_REQUIRED("平台提示登录或内容权限不足，请在官方确认实际权限"),
    SOURCE_MISSING("本次响应未返回所选音轨，已尝试兼容音轨"),
    PLATFORM_LIMITED("当前请求链路受到平台限制或需要验证"),
    DEVICE_UNSUPPORTED("设备无法解码所选音轨，已尝试兼容音轨"),
    NETWORK("网络读取失败，当前音质与权限未重新确认"),
    UNKNOWN("未能确认独立音轨或受限原因，不能据此判断会员权益")
}
@Serializable data class AudioOption(val choice: AudioChoice, val label: String, val bitrate: Long, val sampleRate: Int?, val channels: Int?, val supported: Boolean)
@Serializable data class AudioExperience(val contentKey: String = "", val options: List<AudioOption> = emptyList(), val selected: AudioChoice? = null,
    val decoded: Boolean = false, val decodedLabel: String = "", val restriction: AudioRestriction? = null, val checking: Boolean = false, val notice: String = "")
@Serializable data class AudioOutput(val id: Int, val type: Int, val name: String, val sampleRates: List<Int> = emptyList(), val channelCounts: List<Int> = emptyList())
@Serializable data class OutputExperience(val devices: List<AudioOutput> = emptyList(), val preferredId: Int? = null, val notice: String = "", val externalOnly: Boolean = false,
    val routedId: Int? = null, val routedName: String = "", val sinkSampleRate: Int? = null)
data class AudioDecision(val track: AudioTrack?, val restriction: AudioRestriction?)

object AudioExperienceRules {
    fun preference(choice: AudioChoice): String = if(choice.id==null) "自动最高可用" else when(choice.codec.lowercase()) {
        "flac" -> "优先无损音轨"
        "ec+3" -> "优先杜比全景声音轨"
        "ec-3", "eac3" -> "优先杜比音轨"
        else -> "已保存 AAC 音轨选择"
    }
    fun label(track: AudioTrack): String = when (AudioQuality.decoderMime(track)) {
        "audio/flac" -> if ((track.sampleRate ?: 0) > 48000 && (track.bitDepth ?: 0) >= 24) "Hi-Res 无损（源格式）" else "FLAC 无损" + if ((track.sampleRate ?: 0) > 48000) " · 高采样率（位深未确认）" else ""
        "audio/eac3-joc" -> "杜比全景声（源格式）"
        "audio/eac3" -> "杜比 E-AC-3（源格式）"
        "audio/mp4a-latm" -> "AAC · ${track.bandwidth / 1000} kbps"
        else -> "未知编码"
    }
    fun options(tracks: List<AudioTrack>, supported: (AudioTrack) -> Boolean) = tracks.filter { it.url.isNotBlank() }.distinctBy { AudioChoice(it.id,it.codec.lowercase()) }
        .sortedByDescending { it.bandwidth }.map { AudioOption(AudioChoice(it.id,it.codec.lowercase()),label(it),it.bandwidth,it.sampleRate,it.channels,supported(it)) }
    fun choose(tracks: List<AudioTrack>, choice: AudioChoice, supported: (AudioTrack) -> Boolean): AudioDecision {
        choice.checked()
        val wanted = tracks.firstOrNull(choice::matches)
        val reason = when {
            choice.id == null -> null
            wanted == null || wanted.url.isBlank() -> AudioRestriction.SOURCE_MISSING
            !supported(wanted) -> AudioRestriction.DEVICE_UNSUPPORTED
            else -> null
        }
        val selected = wanted?.takeIf { it.url.isNotBlank() && supported(it) } ?: AudioQuality.best(tracks,supported)
        return AudioDecision(selected, reason ?: if (selected == null) {
            if (tracks.any { it.url.isNotBlank() }) AudioRestriction.DEVICE_UNSUPPORTED else AudioRestriction.UNKNOWN
        } else null)
    }
    fun failure(error: PlatformFailure): AudioRestriction = when (error.kind()) {
        FailureKind.SESSION_EXPIRED, FailureKind.ACCESS_DENIED -> AudioRestriction.ACCESS_REQUIRED
        FailureKind.PLATFORM_BLOCKED -> AudioRestriction.PLATFORM_LIMITED
        FailureKind.NETWORK -> AudioRestriction.NETWORK
        else -> AudioRestriction.UNKNOWN
    }
    fun safeToResume(wasRequested: Boolean, sameContent: Boolean, unchangedPause: Boolean, routeAllowed: Boolean, networkAllowed: Boolean) =
        wasRequested && sameContent && unchangedPause && routeAllowed && networkAllowed
}
