package app.bililisten.shared

import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

@Serializable data class AccountRef(val key: String) { init { require(key == "guest" || key.toLongOrNull()?.let { it > 0 } == true) } }
@Serializable data class SessionStamp(val account: String, val generation: Long)
enum class SessionStatus { UNVERIFIED, AUTHENTICATED, GUEST }
data class AccountSession(val stamp: SessionStamp, val status: SessionStatus, val account: Account? = null)

@Serializable data class VideoRef(val bvid: String, val cid: Long, val part: Int) {
    init { require(Bvid.parse(bvid) == bvid && cid > 0 && part > 0) }
}
@Serializable data class LiveRef(val roomId: Long) { init { require(roomId > 0) } }
@Serializable sealed interface PlayableRef {
    @Serializable data class Vod(val video: VideoRef) : PlayableRef
    @Serializable data class Live(val room: LiveRef) : PlayableRef
}
@Serializable data class ContentMetadata(val title: String, val creator: String = "", val cover: String? = null)
@Serializable data class FavoriteMembership(val account: String, val folder: Long, val aid: Long, val present: Boolean?)
@Serializable enum class MutationState { PENDING, CONFIRMED, RECONCILED }
@Serializable data class MutationRecord(val id: String, val account: String, val aid: Long, val folder: Long, val add: Boolean, val createdAt: Long, val state: MutationState = MutationState.PENDING, val followSource: SourceRef? = null, val engagement: EngagementIntent? = null)
@Serializable data class CollectionSnapshot(val account: String, val source: SourceRef, val items: List<ContentItem>, val complete: Boolean, val refreshedAt: Long, val revision: Long,
    val title: String = "", val ownerName: String = "", val total: Int = 0, val bookmarked: Boolean = false, val cover:String="") {
    init { require(revision >= 0 && refreshedAt >= 0); source.checked() }
}
@Serializable data class ContentItem(val bvid: String, val title: String)
@Serializable enum class PauseReason { USER, FOCUS_LOSS, NOISY, EXTERNAL_VIDEO, RESTORED, NETWORK, TIMER, ENDED, ERROR, UNKNOWN }
@Serializable data class LocalHistoryEntry(val account: String, val video: VideoRef, val title: String, val source: SourceRef?, val positionMs: Long, val playedAt: Long, val origin: HistoryOrigin = HistoryOrigin.UNKNOWN,val finished:Boolean=false) {
    fun entry(id: String) = QueueEntry(id, video.bvid, video.cid, video.part, title, source = source, origin = origin)
}
@Serializable data class PlaybackSnapshot(val queue: ResumeSnapshot, val savedAt: Long, val pauseReason: PauseReason = PauseReason.UNKNOWN, val shuffleVersion: Int = 1) {
    fun checked(): PlaybackSnapshot { queue.checked(); require(savedAt >= 0 && shuffleVersion == 1); return this }
}
data class ResumeContext(val session: SessionStamp, val snapshot: PlaybackSnapshot, val autoplay: Boolean = false)
data class HistoryRetentionPolicy(val maxEntries: Int = 500, val maxAgeMs: Long = 90L * 24 * 60 * 60 * 1000, val keepAll: Boolean = false) {
    init { require(maxEntries in 1..10000 && maxAgeMs > 0) }
}
@Serializable enum class StreamKind { AUDIO_ONLY, MIXED }
data class MediaStreamDescriptor(val id: Int, val mime: String, val codec: String, val bitrate: Long, val kind: StreamKind)
data class PlaybackCapabilities(val audioOnly: Boolean, val mixed: Boolean, val seekable: Boolean, val supportedMimes: Set<String>)
enum class EntitlementStatus { AVAILABLE, LOGIN_REQUIRED, CONTENT_RESTRICTED, PLATFORM_LIMITED, DEVICE_UNSUPPORTED, UNKNOWN }
data class EntitlementSnapshot(val video: VideoRef, val observedTracks: List<MediaStreamDescriptor>, val status: EntitlementStatus, val checkedAt: Long,
    val session: SessionStamp? = null, val requestMethod: String = "web-dash-4048")
enum class LivePlaybackState { OFFLINE, READY, CONNECTING, PLAYING, PAUSED, ENDED, FAILED }
data class CachePolicy(val automaticAudioCache: Boolean = false, val maxImageBytes: Long = 32L * 1024 * 1024)
@Serializable data class RecommendationPreferences(val musicBoost: Boolean = true)
@Serializable data class UserSettings(val historyEnabled: Boolean = true, val historyDays: Int = 90, val historyLimit: Int = 500, val theme: Theme = Theme.SYSTEM, val mobilePlayback: Boolean? = null,
    val historyKeepAll: Boolean = false, val historyDeleteOnExit: Boolean = false,
    val continuousParts: Boolean = true, val musicBoost: Boolean = true, val musicRecommendations:Boolean = true,
    val audioChoice: AudioChoice = AudioChoice(), val preferredOutputType: Int? = null, val externalOutputOnly: Boolean = false,
    val subtitleLanguage: String = "", val subtitleLineMode: Boolean = false,val effects:EffectsSettings=EffectsSettings(), val storage:StorageSettings=StorageSettings(),
    val defaultFavoriteFolders:Map<String,DefaultFavoriteFolder> = emptyMap(),val localLiveRooms:Map<String,List<LiveBookmark>> = emptyMap()) {
    fun checked(): UserSettings { require(historyDays in 1..3650 && historyLimit in 1..10000); audioChoice.checked(); effects.checked();storage.checked();require(preferredOutputType == null || preferredOutputType > 0); require(subtitleLanguage.length <= 32 && subtitleLanguage.all { it.isLetterOrDigit() || it=='-' || it=='_' }); require(defaultFavoriteFolders.all{(owner,folder)->owner.toLongOrNull()?.let{it>0}==true && folder.id>0 && folder.title.isNotBlank() && folder.title.length<=100});require(localLiveRooms.all{(owner,rows)->(owner=="guest" || owner.toLongOrNull()?.let{it>0}==true) && rows.size<=100 && rows.map{it.checked().roomId}.distinct().size==rows.size}); return this }
}
@Serializable data class DefaultFavoriteFolder(val id:Long,val title:String)
@Serializable enum class Theme { SYSTEM, LIGHT, DARK }
data class JumpSnapshot(val video: VideoRef, val positionMs: Long, val resume: ResumeSnapshot?)
interface Clock { fun nowMs(): Long }
interface CredentialStore { fun read(): String?; fun save(cookie: String); fun clear(); val generation: Long }
interface SettingsRepository { suspend fun current(): UserSettings = settings.first(); val settings: kotlinx.coroutines.flow.Flow<UserSettings>; suspend fun update(value: UserSettings) }
interface AccountRepository {
    val session: StateFlow<AccountSession>
    suspend fun verify(): Account?
    suspend fun accept(cookie: String): Account
    suspend fun logout()
    fun requireCurrent(stamp: SessionStamp)
}
interface FavoriteRepository {
    suspend fun folders(account: Long, aid: Long? = null): List<FavoriteFolder>
    suspend fun page(folder: Long, page: Int): FavoritePage
    suspend fun change(account: Long, aid: Long, folder: Long, add: Boolean): MutationOutcome
    suspend fun pending(account: String): MutationRecord?
    suspend fun reconcile(account: Long): FavoriteMembership?
}
interface ContentRepository {
    suspend fun video(bvid: String): Video
    suspend fun search(keyword: String, page: Int = 1): SearchPage
    suspend fun audio(bvid: String, cid: Long): String
    suspend fun resolve(input: String): String = Bvid.parse(input) ?: throw PlatformFailure("请输入 B 站视频链接或 BV 号")
    suspend fun followingCount(): Long? = null
}
interface EntitlementRepository { suspend fun inspect(video: VideoRef): EntitlementSnapshot; suspend fun probe(bvid: String, cid: Long, extended: Boolean = false): AudioProbe }
interface LiveRepository {
    suspend fun room(id: Long): LiveRoom
    suspend fun streams(id: Long): List<LiveStream>
    suspend fun resolve(input:String):LiveRoom = room(LiveInput.room(input) ?: throw PlatformFailure("请输入 B站直播链接或房间号"))
    suspend fun ranking():List<LiveRanking> = throw PlatformFailure("直播排行暂不可用")
}
interface RecommendationRepository {
    suspend fun accountHome(previous:Set<String>,ready:suspend (List<PopularMusic>)->Unit):List<PopularMusic> = throw PlatformFailure("B站首页推荐暂不可用")
    suspend fun accountCreators(previous:Set<Long>):List<UpProfile> = throw PlatformFailure("B站推荐 UP 暂不可用")
    suspend fun popularMusic():List<PopularMusic> = emptyList()
    suspend fun popularMusic(previous:Set<String>,ready:suspend (List<PopularMusic>)->Unit):List<PopularMusic> = popularMusic()
    suspend fun creators():List<UpProfile> = recommendedCreators(candidates(HomeCategory.MUSIC))
    suspend fun creators(previous:Set<Long>):List<UpProfile> = creators()
    suspend fun candidates(): List<Recommendation>
    suspend fun candidates(category:HomeCategory):List<Recommendation> {
        if(category==HomeCategory.ALL)return candidates()
        throw PlatformFailure("当前分类推荐暂不可用")
    }
}
interface LocalHistoryRepository {
    suspend fun latestVideo(account: String, bvid: String): LocalHistoryEntry? = observe(account).first()
        .filter { it.account == account && it.video.bvid == bvid }.maxByOrNull { it.playedAt }
    fun observeLive(account: String): kotlinx.coroutines.flow.Flow<List<LiveHistoryEntry>> = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun deleteLive(account: String, roomId: Long) {}
    fun observe(account: String): kotlinx.coroutines.flow.Flow<List<LocalHistoryEntry>>
    suspend fun delete(account: String, video: VideoRef? = null)
    suspend fun prune(account: String, policy: HistoryRetentionPolicy, now: Long)
}
interface PlaybackStateStore {
    suspend fun load(account: String): PlaybackSnapshot?
    suspend fun checkpoint(snapshot: PlaybackSnapshot, heard: LocalHistoryEntry?)
}
interface CollectionStore {
    suspend fun bookmarks(account: String): List<CollectionSnapshot> = emptyList()
    suspend fun bookmark(account: String, source: ContentSource, saved: Boolean) { throw UnsupportedOperationException() }
    suspend fun collection(account: String, source: SourceRef): CollectionSnapshot?
    suspend fun saveCollection(snapshot: CollectionSnapshot)
    suspend fun mutation(account: String): MutationRecord?
    suspend fun saveMutation(record: MutationRecord)
    suspend fun removeMutation(account: String)
    suspend fun updateCheckpoint(account: String, source: SourceRef): SourceCheckpoint?
    suspend fun saveUpdate(checkpoint: SourceCheckpoint)
    suspend fun markHeard(account: String, source: SourceRef, bvid: String) {
        val prior = updateCheckpoint(account, source) ?: SourceCheckpoint(account, source, emptySet(), completeBaseline = false)
        saveUpdate(prior.copy(heard = prior.heard + bvid))
    }
}
data class PlaybackView(val connected: Boolean = false, val title: String = "尚未播放", val positionMs: Long = 0, val playing: Boolean = false, val requested: Boolean = false, val buffering: Boolean = false, val queue: List<QueueEntry> = emptyList(), val currentId: String? = null, val mode: PlayMode = PlayMode.SEQUENTIAL, val live: Boolean = false, val error: String? = null,
    val durationMs: Long = 0, val queueVersion: Long = 0, val pauseReason: PauseReason = PauseReason.UNKNOWN,
    val canSeek: Boolean = false, val canNext: Boolean = false, val canPrevious: Boolean = false, val canEditQueue: Boolean = false,
    val issue: PlaybackIssue? = null, val speed: Float = 1f, val timerRemainingMs: Long = 0,
    val audio: AudioExperience = AudioExperience(), val output: OutputExperience = OutputExperience(),val effects:EffectsView=EffectsView(),val liveExperience:LiveExperience=LiveExperience()) {
    val capabilities: PlaybackCapabilities get() = PlaybackCapabilities(
        audioOnly = if(live)liveExperience.kind==StreamKind.AUDIO_ONLY else queue.isNotEmpty(), mixed = live && liveExperience.kind==StreamKind.MIXED, seekable = canSeek, supportedMimes = emptySet())
}
interface PlaybackPort {
    val state: StateFlow<PlaybackView>
    /** Faster local position sampling while a foreground timed-text reader is visible. */
    fun precisePosition(enabled: Boolean) {}
    fun liveEdge() {}
    suspend fun replace(snapshot: ResumeSnapshot, play: Boolean)
    suspend fun flush()
    suspend fun clear()
    suspend fun speed(value: Float) {}
    suspend fun audioQuality(choice: AudioChoice? = null) {}
    suspend fun audioOutput(id: Int?) {}
    suspend fun audioEffects(value:EffectsSettings?=null) {}
    suspend fun sleepTimer(seconds: Int) {}
    suspend fun exitListening() { pause(); flush(); clear() }
    suspend fun mode(mode: PlayMode)
    suspend fun forget(video: VideoRef?)
    suspend fun live(room: LiveRoom, consent: Boolean)
    suspend fun edit(edit: QueueEdit, expectedVersion: Long)
    fun seekTo(positionMs: Long)
    fun pause(reason: PauseReason = PauseReason.USER)
    fun toggle(); fun next(); fun previous(); fun seekBy(delta: Long); fun close()
}
interface JumpPort { fun url(snapshot: JumpSnapshot): String }
object BiliJumpPort : JumpPort {
    override fun url(snapshot: JumpSnapshot): String = "https://www.bilibili.com/video/${snapshot.video.bvid}/?p=${snapshot.video.part}&t=${snapshot.positionMs.coerceAtLeast(0) / 1000}"
}
