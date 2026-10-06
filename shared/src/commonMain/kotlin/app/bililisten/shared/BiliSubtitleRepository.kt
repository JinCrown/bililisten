package app.bililisten.shared

class BiliSubtitleRepository(private val api: BiliApi): SubtitleRepository {
    override suspend fun tracks(video: VideoRef) = api.subtitleTracks(video)
    override suspend fun cues(track: SubtitleTrack) = api.subtitleCues(track)
}
