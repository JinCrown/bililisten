package app.bililisten.shared

/** Product preference, not a claim that spatial audio and lossless have a common quality scale. */
object AudioQuality {
    fun decoderMime(track: AudioTrack): String? = when (track.codec.lowercase()) {
        "flac" -> "audio/flac"
        "ec-3", "eac3" -> "audio/eac3"
        "ec+3" -> "audio/eac3-joc"
        else -> if (track.codec.lowercase().startsWith("mp4a.40.")) "audio/mp4a-latm" else null
    }

    private fun preference(track: AudioTrack): Int = when (decoderMime(track)) {
        "audio/flac" -> 3
        "audio/eac3", "audio/eac3-joc" -> 2
        "audio/mp4a-latm" -> 1
        else -> 0
    }

    // Only rank the streams actually authorized by the platform. Account labels never cap AAC.
    fun best(tracks: List<AudioTrack>, supported: (AudioTrack) -> Boolean = { true }): AudioTrack? =
        tracks.filter { it.url.isNotBlank() && preference(it) > 0 && supported(it) }
            .maxWithOrNull(compareBy<AudioTrack> { preference(it) }.thenBy { it.bandwidth }.thenBy { it.id })
}
