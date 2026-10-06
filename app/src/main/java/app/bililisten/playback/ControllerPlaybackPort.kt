package app.bililisten.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.Json

@UnstableApi
class ControllerPlaybackPort(context: Context) : PlaybackPort, AudioEffectsPort {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(PlaybackView())
    override val state = mutable.asStateFlow()
    private val executor = ContextCompat.getMainExecutor(context)
    private val ready = CompletableDeferred<MediaController>()
    private var controller: MediaController? = null
    private val handoff=QueueHandoff(java.io.File(context.cacheDir,"queue-handoff"))
    private val generation={ (context.applicationContext as app.bililisten.ListenApplication).vault.generation }
    private var pendingPause: Job? = null
    private var precisePosition = false
    private val positionTicker = PlaybackPositionTicker(scope) {
        controller?.let { media ->
            if (media.isPlaying) mutable.value = mutable.value.copy(positionMs = media.currentPosition.coerceAtLeast(0))
            else refresh()
        }
    }
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, ListenService::class.java)))
        .setListener(object : MediaController.Listener {
            override fun onExtrasChanged(controller: MediaController, extras: Bundle) { refresh() }
        }).buildAsync()
    init {
        future.addListener({
            try {
                controller = future.get().also { media ->
                    media.addListener(object : Player.Listener {
                        override fun onEvents(player: Player, events: Player.Events) = refresh(events.contains(Player.EVENT_TIMELINE_CHANGED)||events.contains(Player.EVENT_MEDIA_METADATA_CHANGED))
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            mutable.value = mutable.value.copy(error = error.errorCodeName)
                        }
                    })
                    ready.complete(media)
                }; refresh(true)
            } catch (e: Exception) { ready.completeExceptionally(e); mutable.value = mutable.value.copy(error = "CONNECTION_FAILED") }
        }, executor)
    }
    private fun refresh(readQueue:Boolean=false) {
        controller?.let { media ->
            positionTicker.update(media.isPlaying, precisePosition)
            val liveState=media.sessionExtras.getString("liveExperience")?.let { runCatching { Json.decodeFromString<LiveExperience>(it) }.getOrNull() } ?: LiveExperience()
            mutable.value = PlaybackView(true, media.mediaMetadata.title?.toString() ?: "尚未播放", media.currentPosition.coerceAtLeast(0), media.isPlaying,
                media.playWhenReady, media.playbackState == Player.STATE_BUFFERING,
                if(readQueue)(0 until media.mediaItemCount).mapNotNull { media.getMediaItemAt(it).queueEntry() } else mutable.value.queue, media.currentMediaItem?.mediaId,
                media.sessionExtras.getString("mode")?.let { runCatching { PlayMode.valueOf(it) }.getOrNull() } ?: PlayMode.SEQUENTIAL,
                media.mediaMetadata.extras?.getBoolean("live") == true, media.playerError?.errorCodeName,
                media.duration.coerceAtLeast(0), media.sessionExtras.getLong("queueVersion"),
                media.sessionExtras.getString("pauseReason")?.let { runCatching { PauseReason.valueOf(it) }.getOrNull() } ?: PauseReason.UNKNOWN,
                media.isCurrentMediaItemSeekable && (media.mediaMetadata.extras?.getBoolean("live") != true || liveState.canSeekWindow),
                media.hasNextMediaItem() && media.mediaMetadata.extras?.getBoolean("live") != true,
                media.mediaItemCount > 0 && media.mediaMetadata.extras?.getBoolean("live") != true,
                media.mediaItemCount > 0 && media.mediaMetadata.extras?.getBoolean("live") != true,
                media.sessionExtras.getString("issue")?.let { runCatching { PlaybackIssue.valueOf(it) }.getOrNull() },
                media.sessionExtras.getFloat("speed", 1f), media.sessionExtras.getLong("timerRemainingMs"),
                media.sessionExtras.getString("audio")?.let { runCatching { Json.decodeFromString<AudioExperience>(it) }.getOrNull() } ?: AudioExperience(),
                media.sessionExtras.getString("output")?.let { runCatching { Json.decodeFromString<OutputExperience>(it) }.getOrNull() } ?: OutputExperience(),
                media.sessionExtras.getString("effects")?.let { runCatching { Json.decodeFromString<EffectsView>(it) }.getOrNull() } ?: EffectsView(),
                liveState)
        }
    }
    override fun precisePosition(enabled: Boolean) {
        if (precisePosition == enabled) return
        precisePosition = enabled
        refresh()
    }
    private suspend fun command(action: String, args: Bundle = Bundle.EMPTY) {
        val media = ready.await()
        val result = suspendCancellableCoroutine<SessionResult> { continuation ->
            val pending = media.sendCustomCommand(PlaybackCommands.command(action), args)
            pending.addListener({ if (continuation.isActive) try { continuation.resume(pending.get()) } catch (e: Exception) { continuation.resumeWithException(e) } }, executor)
        }
        if (result.resultCode != SessionResult.RESULT_SUCCESS) throw PlatformFailure(result.extras.getString("notice") ?: "播放状态操作未完成", result.resultCode)
        refresh(true)
    }
    override suspend fun replace(snapshot: ResumeSnapshot, play: Boolean) {
        val stamp=generation()
        val name=withContext(Dispatchers.IO){handoff.write(snapshot)}
        try { command(PlaybackCommands.REPLACE_QUEUE,Bundle().apply{putString("queueFile",name);putLong("generation",stamp);putBoolean("play",play)}) }
        finally {withContext(NonCancellable+Dispatchers.IO){handoff.discard(name)}}
    }
    override suspend fun flush() { pendingPause?.join(); command(PlaybackCommands.FLUSH) }
    override suspend fun clear() { pendingPause?.join(); command(PlaybackCommands.CLEAR) }
    override suspend fun speed(value: Float) = command(PlaybackCommands.SET_SPEED, Bundle().apply { putFloat("speed",value) })
    override suspend fun audioQuality(choice: AudioChoice?) = command(PlaybackCommands.AUDIO_QUALITY, Bundle().apply { choice?.let { putString("choice",Json.encodeToString(it.checked())) } })
    override suspend fun audioOutput(id: Int?) = command(PlaybackCommands.AUDIO_OUTPUT, Bundle().apply { id?.let { putInt("device",it) } })
    override suspend fun audioEffects(value:EffectsSettings?)=command(PlaybackCommands.AUDIO_EFFECTS,Bundle().apply{value?.let{putString("effects",Json.encodeToString(it.checked()))}})
    override suspend fun sleepTimer(seconds: Int) = command(PlaybackCommands.SLEEP_TIMER, Bundle().apply { putInt("seconds",seconds) })
    override suspend fun exitListening() = command(PlaybackCommands.EXIT_LISTENING)
    override suspend fun mode(mode: PlayMode) = command(PlaybackCommands.SET_MODE, Bundle().apply { putString("mode", mode.name) })
    override suspend fun forget(video: VideoRef?) = command(PlaybackCommands.FORGET_HISTORY, Bundle().apply { video?.let { putString("bvid", it.bvid); putLong("cid", it.cid) } })
    override suspend fun live(room: LiveRoom, consent: Boolean) = command(PlaybackCommands.PLAY_LIVE, Bundle().apply { putLong("room", room.roomId); putString("title", room.title); putBoolean("mixedConsent", consent);putLong("generation",generation()) })
    override fun liveEdge(){scope.launch{try{command(PlaybackCommands.LIVE_EDGE)}catch(e:CancellationException){throw e}catch(_:Exception){mutable.value=mutable.value.copy(error="返回直播未完成")}}}
    override suspend fun edit(edit: QueueEdit, expectedVersion: Long) = command(PlaybackCommands.EDIT_QUEUE, Bundle().apply { putString("edit", Json.encodeToString(edit)); putLong("version", expectedVersion) })
    override fun pause(reason: PauseReason) { pendingPause = scope.launch {
        try { command(PlaybackCommands.PAUSE_REASON, Bundle().apply { putString("reason", reason.name) }) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = mutable.value.copy(error = "暂停原因保存失败") }
    } }
    override fun toggle() { controller?.let { if (it.playWhenReady) pause() else { if (it.playbackState == Player.STATE_IDLE && !state.value.live) it.prepare(); it.play() } } }
    override fun next() { controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    override fun previous() { controller?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) } }
    override fun seekBy(delta: Long) { controller?.let { if (state.value.canSeek) it.seekTo((it.currentPosition + delta).coerceIn(0, it.duration.takeIf { n -> n > 0 } ?: Long.MAX_VALUE)) } }
    override fun seekTo(positionMs: Long) { controller?.let { if (state.value.canSeek) it.seekTo(positionMs.coerceIn(0, state.value.durationMs)) } }
    override fun capability() = AudioEffectsCapability(available = state.value.effects.let{it.equalizer.phase==EffectPhase.APPLIED||it.bass.phase==EffectPhase.APPLIED}, audioSessionId = controller?.sessionExtras?.getInt("audioSessionId")?.takeIf { it > 0 })
    override fun close() { scope.cancel(); ready.cancel(); MediaController.releaseFuture(future) }
}
