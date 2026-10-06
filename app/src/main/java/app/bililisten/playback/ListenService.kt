package app.bililisten.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import app.bililisten.ListenApplication
import app.bililisten.MainActivity
import app.bililisten.R
import app.bililisten.storage.StorageDataSource
import app.bililisten.storage.OfflineFilePermit
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.serialization.json.Json
import app.bililisten.widget.WidgetState

@UnstableApi
class ListenService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private data class Write(val snapshot: PlaybackSnapshot? = null, val history: LocalHistoryEntry? = null, val done: SettableFuture<SessionResult>? = null, val clearAccount: String? = null, val liveHistory: LiveHistoryEntry? = null, val deleteHistoryAccount: String? = null)
    private val writes = Channel<Write>(Channel.UNLIMITED)
    private val heardIds = mutableSetOf<String>()
    private val heardAt = mutableMapOf<String, Long>()
    private var liveRoom: Pair<String, Long>? = null
    private data class LiveResolution(val owner:String,val generation:Long,val room:LiveRoom,val stream:LiveStream)
    @Volatile private var liveResolution:LiveResolution?=null
    private var liveExperience=LiveExperience()
    private var liveEpoch=0L
    private var liveRequest:Job?=null
    private var liveMonitor:Job?=null
    private var liveConsent=false
    private var liveReconnectAttempt=0
    private var liveStableAt=0L
    private var liveVodSaved=false
    private var liveReconnectNotice=""
    private val sleepDeadline = SleepDeadline()
    private var sleepJob: Job? = null
    private var timerRemaining = 0L
    private lateinit var queue: PlaybackQueue
    private lateinit var app: ListenApplication
    private var presentationJob:Job?=null
    private var presentationKey:String?=null
    private var notificationControlsKey:String?=null
    private var favoriteJob:Job?=null
    private val favoriteCommand=SessionCommand(NotificationFavoriteTickets.ACTION,Bundle.EMPTY)
    private fun refreshNotificationControls() {
        if(!::session.isInitialized)return
        val item=player.currentMediaItem
        val entry=item?.queueEntry()
        val stamp=app.accounts.session.value.stamp
        val vod=entry!=null && item?.mediaMetadata?.extras?.getBoolean("live")!=true
        val previous=vod && (player.hasPreviousMediaItem() && session.player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) || session.player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
        val next=vod && player.hasNextMediaItem() && session.player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        val favorite=vod && Bvid.parse(entry!!.bvid)!=null
        val key="${stamp.account}/${stamp.generation}/${item?.mediaId}/${item?.mediaMetadata?.extras?.getLong("epoch")}/$previous/$next/$favorite"
        if(notificationControlsKey==key)return
        notificationControlsKey=key
        val target=Bundle().apply {
            item?.let {putString("id",it.mediaId);putLong("epoch",it.mediaMetadata.extras?.getLong("epoch") ?: 0)}
            putString("account",stamp.account);putLong("generation",stamp.generation)
        }
        session.setMediaButtonPreferences(NotificationControls.buttons(target,previous,next,favorite))
    }
    private fun refreshPresentation() {
        if(!::session.isInitialized)return
        refreshNotificationControls()
        val item=player.currentMediaItem
        val entry=item?.queueEntry()
        val stamp=app.accounts.session.value.stamp
        val key=item?.let{"${stamp.account}/${stamp.generation}/${it.mediaId}/${it.mediaMetadata.extras?.getLong("epoch")}/${entry?.cid}"}
        if(key==presentationKey)return
        presentationKey=key;presentationJob?.cancel()
        if(item==null || entry==null || entry.cid<=0)return
        presentationJob=scope.launch {
            try {
                val video=app.content.video(entry.bvid)
                ensureActive();app.accounts.requireCurrent(stamp)
                if(presentationKey!=key || player.currentMediaItem?.mediaId!=item.mediaId)return@launch
                val index=player.currentMediaItemIndex
                player.replaceMediaItem(index,item.withNotificationVideo(video))
                val cover=app.covers.load(video.cover)
                val bytes=withContext(Dispatchers.IO) {cover?.let { bitmap->
                    val scale=minOf(1f,384f/maxOf(bitmap.width,bitmap.height))
                    val scaled=android.graphics.Bitmap.createScaledBitmap(bitmap,(bitmap.width*scale).toInt().coerceAtLeast(1),(bitmap.height*scale).toInt().coerceAtLeast(1),true)
                    try {java.io.ByteArrayOutputStream().use{out->scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG,75,out);out.toByteArray().takeIf{it.size<=128*1024}}}
                    finally{if(scaled!==bitmap)scaled.recycle()}
                }}
                ensureActive();app.accounts.requireCurrent(stamp)
                if(presentationKey==key && player.currentMediaItem?.mediaId==item.mediaId)
                    player.replaceMediaItem(player.currentMediaItemIndex,player.currentMediaItem!!.withNotificationVideo(video,bytes))
            }catch(e:CancellationException){throw e}catch(_:Exception){ /* Keep playback usable if metadata or the cover is unavailable. */ }
        }
    }
    private fun notificationFavorite(target:Bundle):ListenableFuture<SessionResult> {
        val entry=player.currentMediaItem?.queueEntry() ?: return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        if(favoriteJob?.isActive==true)return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        val stamp=app.accounts.session.value.stamp
        val epoch=player.currentMediaItem?.mediaMetadata?.extras?.getLong("epoch")
        if(target.getString("id")!=entry.id || target.getLong("epoch",-1)!=epoch || target.getString("account")!=stamp.account || target.getLong("generation",-1)!=stamp.generation)
            return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        fun current()=player.currentMediaItem?.mediaId==entry.id && player.currentMediaItem?.mediaMetadata?.extras?.getLong("epoch")==epoch && app.accounts.session.value.stamp==stamp
        val done=SettableFuture.create<SessionResult>()
        favoriteJob=scope.launch {
            try {
                val result=NotificationFavorite(app.accounts,app.settings,app.content,app.favorites).toggle(entry.bvid,::current)
                if(result.chooseFolder && current()) {
                    val token=NotificationFavoriteTickets.issue(NotificationFavoriteTarget(entry.id,entry.bvid,stamp.account,stamp.generation))
                    val pending=PendingIntent.getActivity(this@ListenService,41,Intent(this@ListenService,MainActivity::class.java).setAction(NotificationFavoriteTickets.ACTION).putExtra("favoriteTicket",token),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    val options=if(android.os.Build.VERSION.SDK_INT>=34)android.app.ActivityOptions.makeBasic().apply{setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)}.toBundle() else null
                    pending.send(this@ListenService,0,null,null,null,null,options)
                } else android.widget.Toast.makeText(this@ListenService,result.message,android.widget.Toast.LENGTH_SHORT).show()
                done.set(SessionResult(SessionResult.RESULT_SUCCESS))
            }catch(e:CancellationException){done.cancel(false);throw e}catch(e:Exception){
                android.widget.Toast.makeText(this@ListenService,(e as? PlatformFailure)?.category ?: "收藏未确认，请稍后核对",android.widget.Toast.LENGTH_SHORT).show()
                done.set(SessionResult(SessionError.ERROR_UNKNOWN))
            }
        }
        return done
    }
    private var pauseReason = PauseReason.UNKNOWN
    private var explicitPause: PauseReason? = null
    private var issue: PlaybackIssue? = null
    private var publishedState: String? = null
    private lateinit var network: PlaybackNetwork
    private val checkpointPolicy = CheckpointPolicy()
    private var playbackMaintenance: Job? = null
    private lateinit var outputs: AudioOutputMonitor
    private var outputConfigured = false
    private lateinit var effects:EffectsController
    private var effectsChanging=false
    private fun refreshEffects(force:Boolean=false,preference:EffectsSettings=app.settings.settings.value.effects) {
        if(!::effects.isInitialized||!::player.isInitialized||!outputConfigured)return
        val output=outputs.snapshot()
        effects.sync(preference,EffectsContext(player.audioSessionId,output.routedId,output.devices.firstOrNull{it.id==output.routedId}?.type),output.routedName,force)
    }
    private var pauseRevision = 0L
    private var audioState = AudioExperience()
    private var qualityJob: Job? = null
    private var widgetJob: Job? = null
    @Volatile private var qualityRevision = 0L
    private data class Resolved(val key: String, val generation: Long, val revision: Long, val track: AudioTrack, val options: List<AudioOption>, val restriction: AudioRestriction?, val at: Long, val cacheEligible:Boolean=false)
    @Volatile private var resolved: Resolved? = null
    private val failedTracks = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun contentKey() = player.currentMediaItem?.queueEntry()?.let { "${it.bvid}/${it.cid}" }.orEmpty()
    private fun routeAllowed() = outputConfigured && outputs.allowed()
    private fun refreshOutput() {
        if (!::player.isInitialized) return
        player.setPreferredAudioDevice(outputs.configure(app.settings.settings.value))
        refreshEffects()
        if (outputConfigured && !outputs.allowed() && (player.playWhenReady || player.isLoading)) pauseWithReason(PauseReason.NOISY,true)
        publishState()
    }

    override fun onCreate() {
        super.onCreate()
        app = application as ListenApplication
        setMediaNotificationProvider(DefaultMediaNotificationProvider(this).apply{setSmallIcon(R.drawable.ic_notification)})
        effects=EffectsController(AndroidEffectsFactory()){publishState()}
        outputs = AudioOutputMonitor(this, { refreshOutput() }, { if (::player.isInitialized) pauseWithReason(PauseReason.NOISY,true) })
        network = PlaybackNetwork(this, { app.settings.settings.value }) { scope.launch { enforceNetwork() } }
        // This client deliberately has no cookies, auth interceptor or disk cache.
        val mediaHttp = OkHttpClient.Builder().cache(null).followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                network.check()
                val url = chain.request().url
                if (url.scheme != "https" || !isMediaHost(url.host)) throw IOException("媒体地址不在已验证域名范围")
                chain.proceed(chain.request())
            }.build()
        val upstream = OkHttpDataSource.Factory(mediaHttp)
            .setUserAgent("BiliListen-M0/0.0.1")
            .setDefaultRequestProperties(mapOf("Referer" to "https://www.bilibili.com/"))
        val cachedUpstream=app.automaticAudio.wrap(upstream)
        val storageUpstream=androidx.media3.datasource.DataSource.Factory { StorageDataSource(app,cachedUpstream) }
        val resolver = ResolvingDataSource.Factory(storageUpstream) { spec ->
            val uri = spec.uri
            if(uri.scheme=="bililisten"&&uri.host=="offline") {
                val id=uri.lastPathSegment ?: throw PlaybackNetworkException(PlaybackIssue.LOCAL_FILE)
                val file=try{app.downloads.playable(id)}catch(_:Exception){throw PlaybackNetworkException(PlaybackIssue.LOCAL_FILE)}
                return@Factory spec.buildUpon().setUri(android.net.Uri.fromFile(file)).setCustomData(OfflineFilePermit(id)).build()
            }
            network.check()
            if(uri.scheme=="https" && liveResolution?.let{it.owner==app.accountKey && it.generation==app.vault.generation}==true) {
                if(!isMediaHost(uri.host.orEmpty()) || uri.port !in setOf(-1,443) || uri.userInfo!=null)throw IOException("直播分段地址需要验证")
                return@Factory spec.buildUpon().setHttpRequestHeaders(spec.httpRequestHeaders+("Referer" to "https://live.bilibili.com/")).build()
            }
            if (uri.scheme == "bililisten" && uri.host == "live") {
                val room = uri.lastPathSegment?.toLongOrNull() ?: throw IOException("房间身份无效")
                val stream = liveResolution?.takeIf{it.room.roomId==room && it.owner==app.accountKey && it.generation==app.vault.generation}?.stream
                    ?: throw IOException("直播地址已失效，请重新连接当前房间")
                val target = android.net.Uri.parse(stream.url)
                if (target.scheme != "https" || !isMediaHost(target.host.orEmpty())) throw IOException("直播媒体域名需要验证")
                return@Factory spec.buildUpon().setUri(target).setHttpRequestHeaders(spec.httpRequestHeaders+("Referer" to "https://live.bilibili.com/")).build()
            }
            if (uri.scheme != "bililisten" || uri.host != "video") throw IOException("不支持的内容地址")
            val parts = uri.pathSegments
            if (parts.size != 2 || Bvid.parse(parts[0]) == null) throw IOException("视频身份无效")
            val generation = app.vault.generation
            val revision = qualityRevision
            val owner = app.accountKey
            val originalCid=parts[1].toLongOrNull()?.takeIf{it>=0} ?: throw IOException("分 P 无效")
            val lazyVideo=if(originalCid==0L)try {runBlocking(Dispatchers.IO){app.content.video(parts[0])}}
                catch(e:PlatformFailure){throw PlaybackNetworkException(when(e.kind()){FailureKind.SESSION_EXPIRED->PlaybackIssue.SESSION_EXPIRED;FailureKind.PLATFORM_BLOCKED->PlaybackIssue.PLATFORM_LIMITED;FailureKind.MISSING,FailureKind.ACCESS_DENIED->PlaybackIssue.CONTENT_UNAVAILABLE;else->PlaybackIssue.NETWORK_ERROR})}
                catch(_:Exception){throw PlaybackNetworkException(PlaybackIssue.NETWORK_ERROR)} else null
            if(lazyVideo!=null && (lazyVideo.bvid!=parts[0]||!lazyVideo.hasCreator(uri.getQueryParameter("owner")?.toLongOrNull() ?: 0)))throw PlaybackNetworkException(PlaybackIssue.CONTENT_UNAVAILABLE)
            if(app.vault.generation!=generation||app.accountKey!=owner||qualityRevision!=revision)throw IOException("视频请求已过期")
            val cid=lazyVideo?.parts?.firstOrNull{it.number==1}?.cid ?: originalCid
            if(cid<=0)throw IOException("没有可播放的 P1")
            if(lazyVideo!=null)scope.launch {
                if(app.vault.generation==generation&&app.accountKey==owner&&qualityRevision==revision) {
                    try {if(queue.resolveVideo(lazyVideo)){checkpoint();publishState()}}
                    catch(_:Exception){pauseWithReason(PauseReason.ERROR,true)}
                }
            }
            val key = "${parts[0]}/$cid"
            val selection = try { runBlocking(Dispatchers.IO) {
                val cached = resolved?.takeIf { it.key == key && it.generation == generation && it.revision == revision && android.os.SystemClock.elapsedRealtime()-it.at < 30000 }
                cached ?: run {
                    val probe = app.entitlements.probe(parts[0],cid,true)
                    if (app.vault.generation != generation || app.accountKey != owner || qualityRevision != revision) throw IOException("音轨请求已过期")
                    val settings = app.settings.current()
                    val supports: (AudioTrack) -> Boolean = { DeviceAudioSupport.supports(it) && "$key:${it.id}:${it.codec.lowercase()}" !in failedTracks }
                    val choice = AudioExperienceRules.choose(probe.tracks,settings.audioChoice,supports)
                    val track = choice.track ?: throw PlatformFailure(if (choice.restriction == AudioRestriction.DEVICE_UNSUPPORTED) "没有设备支持的独立音轨" else "未返回独立音轨")
                    val cacheEligible=if(settings.storage.automaticAudio)try {
                        val video=app.content.video(parts[0]);val part=video.parts.firstOrNull{it.cid==cid}
                        part!=null&&OfflineAudioRules.admitted(video,VideoRef(parts[0],cid,part.number),probe,track)
                    }catch(e:CancellationException){throw e}catch(_:Exception){false} else false
                    Resolved(key,generation,revision,track,AudioExperienceRules.options(probe.tracks,supports),choice.restriction,android.os.SystemClock.elapsedRealtime(),cacheEligible).also { resolved=it }
                }
            } }
                catch (failure: PlatformFailure) { throw PlaybackNetworkException(if (failure.category == "没有设备支持的独立音轨") PlaybackIssue.UNSUPPORTED else when(failure.kind()) {
                    FailureKind.SESSION_EXPIRED -> PlaybackIssue.SESSION_EXPIRED
                    FailureKind.PLATFORM_BLOCKED -> PlaybackIssue.PLATFORM_LIMITED
                    FailureKind.MISSING, FailureKind.ACCESS_DENIED -> PlaybackIssue.CONTENT_UNAVAILABLE
                    else -> PlaybackIssue.NETWORK_ERROR
                },if(failure.category=="没有设备支持的独立音轨")AudioRestriction.DEVICE_UNSUPPORTED else AudioExperienceRules.failure(failure)) }
                catch (_: Exception) { throw IOException("获取独立音轨失败，请检查网络、登录或平台限制") }
            if (app.vault.generation != generation || app.accountKey != owner || qualityRevision != revision) throw IOException("音轨请求已过期")
            scope.launch {
                if (contentKey() == key && app.vault.generation == generation && qualityRevision == revision) {
                    val selected=AudioChoice(selection.track.id,selection.track.codec.lowercase())
                    audioState = if(audioState.contentKey==key && audioState.selected==selected) audioState.copy(options=selection.options,restriction=selection.restriction,checking=false)
                        else AudioExperience(key,selection.options,selected,restriction=selection.restriction)
                    publishState()
                }
            }
            val target = android.net.Uri.parse(selection.track.url)
            if (target.scheme != "https" || !isMediaHost(target.host.orEmpty())) throw IOException("媒体域名或协议需要验证")
            if(selection.cacheEligible)spec.buildUpon().setUri(target).setKey(app.automaticAudio.key(owner,parts[0],cid,selection.track)).setCustomData(app.automaticAudio.permit()).build()
            else spec.withUri(target)
        }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context).setEnableFloatOutput(false).setEnableAudioTrackPlaybackParams(false)
                    .setAudioProcessorChain(MusicTempoChain())
                    .setAudioTrackProvider { config, attributes, sessionId, audioContext ->
                        DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config,attributes,sessionId,audioContext).also { outputs.attach(it,config.sampleRate) }
                    }.build()
        }
        player = ExoPlayer.Builder(this,renderers)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(5000, 15000, 1000, 2000).setBackBuffer(0, false).setTargetBufferBytes(2 * 1024 * 1024).build())
            .setUseLazyPreparation(true)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolver).setLoadErrorHandlingPolicy(BoundedMediaRetry { network.blocked() == null })).build().apply {
            configureListeningAudioFocus(this) { pauseWithReason(PauseReason.FOCUS_LOSS) }
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
        }
        queue = PlaybackQueue(player)
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // All controllers, including the system card, reconnect a paused live stream.
        val sessionPlayer = object : ForwardingPlayer(player) {
            private fun live() = player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("live") == true
            override fun getAvailableCommands(): Player.Commands {
                val commands = super.getAvailableCommands().buildUpon()
                if (live()) commands.removeAll(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SET_SPEED_AND_PITCH)
                if(live() && !liveWindow())commands.removeAll(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,Player.COMMAND_SEEK_BACK,Player.COMMAND_SEEK_FORWARD)
                return commands.build()
            }
            override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
            override fun seekTo(positionMs: Long) { if (!live() || liveWindow()) super.seekTo(positionMs) }
            override fun seekTo(mediaItemIndex: Int, positionMs: Long) { if (!live() || liveWindow()) super.seekTo(mediaItemIndex, positionMs) }
            override fun seekToNextMediaItem() { if (!live()) super.seekToNextMediaItem() }
            override fun seekToPreviousMediaItem() { if (!live()) super.seekToPreviousMediaItem() }
            override fun play() = setPlayWhenReady(true)
            override fun pause() = setPlayWhenReady(false)
            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (!playWhenReady) { pauseWithReason(PauseReason.USER); return }
                if (!routeAllowed()) { pauseWithReason(PauseReason.NOISY,true); return }
                if (enforceNetwork(onPlayRequest = true)) return
                issue = null
                if (playWhenReady) pauseReason = PauseReason.UNKNOWN
                if (playWhenReady && !player.playWhenReady && player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("live") == true) {
                    reconnectLive(true);return
                }
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                player.playWhenReady = playWhenReady
                publishState()
            }
        }
        session = MediaSession.Builder(this, sessionPlayer).setSessionActivity(pending)
            .setMediaButtonPreferences(NotificationControls.buttons(Bundle.EMPTY,false,false,false))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    val own = controller.packageName == packageName
                    if (!own && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    if(own || session.isMediaNotificationController(controller)) {
                        commands.add(favoriteCommand)
                        commands.add(SessionCommand(NotificationControls.PREVIOUS,Bundle.EMPTY))
                        commands.add(SessionCommand(NotificationControls.NEXT,Bundle.EMPTY))
                    }
                    if (own) PlaybackCommands.all.forEach { commands.add(PlaybackCommands.command(it)) }
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands.build())
                        .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                            .remove(Player.COMMAND_SET_SHUFFLE_MODE).remove(Player.COMMAND_SET_REPEAT_MODE)
                            .remove(Player.COMMAND_SET_SPEED_AND_PITCH).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM).build())
                        .build()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if(customCommand.customAction in setOf(NotificationControls.PREVIOUS,NotificationControls.NEXT)) {
                        if(controller.packageName!=packageName && !session.isMediaNotificationController(controller))return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                        val stamp=app.accounts.session.value.stamp
                        val target=if(customCommand.customExtras.containsKey("id"))customCommand.customExtras else args
                        val applied=NotificationControls.skip(session.player,customCommand.customAction,target,stamp.account,stamp.generation)
                        return Futures.immediateFuture(SessionResult(if(applied)SessionResult.RESULT_SUCCESS else SessionError.ERROR_INVALID_STATE))
                    }
                    if(customCommand.customAction==NotificationFavoriteTickets.ACTION) {
                        if(controller.packageName!=packageName && !session.isMediaNotificationController(controller))return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                        return notificationFavorite(if(customCommand.customExtras.containsKey("id"))customCommand.customExtras else args)
                    }
                    if (controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                    if (customCommand.customAction == PlaybackCommands.AUDIO_QUALITY) return changeAudio(args)
                    if (customCommand.customAction == PlaybackCommands.AUDIO_OUTPUT) return changeOutput(args)
                    if (customCommand.customAction == PlaybackCommands.AUDIO_EFFECTS) return changeEffects(args)
                    if (customCommand.customAction == PlaybackCommands.REPLACE_QUEUE) return replaceQueue(args)
                    if (customCommand.customAction == PlaybackCommands.PLAY_LIVE) return playLive(args)
                    if (customCommand.customAction == PlaybackCommands.WIDGET_CONTROL) return widgetControl(args)
                    try {
                        when (customCommand.customAction) {
                            PlaybackCommands.SET_SPEED -> { require(liveRoom == null); queue.speed(args.getFloat("speed")); applySpeed(); checkpoint() }
                            PlaybackCommands.SLEEP_TIMER -> setSleepTimer(args.getInt("seconds"))
                            PlaybackCommands.EXIT_LISTENING -> {
                                pauseWithReason(PauseReason.USER); setSleepTimer(0)
                                val owner = queue.snapshot()?.account ?: liveRoom?.first ?: app.accountKey
                                if (app.settings.settings.value.historyDeleteOnExit) {
                                    heardIds.clear(); heardAt.clear()
                                    writes.trySend(Write(deleteHistoryAccount = owner))
                                }
                                cancelLive();queue.clear(); liveRoom = null; stopSelf()
                            }
                            PlaybackCommands.PAUSE_REASON -> pauseWithReason(PauseReason.valueOf(requireNotNull(args.getString("reason"))))
                            PlaybackCommands.EDIT_QUEUE -> {
                                require(player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("live") != true)
                                val account = requireNotNull(queue.snapshot()).account
                                require(account == app.accountKey)
                                checkpoint()
                                if (queue.edit(Json.decodeFromString<QueueEdit>(requireNotNull(args.getString("edit"))), args.getLong("version"))) {
                                    heardIds.clear(); writes.trySend(Write(clearAccount = account)); pauseReason = PauseReason.USER; stopSelf()
                                }
                                checkpoint()
                            }
                            PlaybackCommands.LIVE_EDGE -> {
                                require(liveRoom!=null)
                                if(liveWindow())player.seekToDefaultPosition() else reconnectLive(true)
                            }
                            PlaybackCommands.SET_MODE -> {
                                require(player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("live") != true)
                                checkpoint()
                                queue.changeMode(PlayMode.valueOf(requireNotNull(args.getString("mode"))))
                                checkpoint()
                            }
                            PlaybackCommands.CLEAR -> {
                                qualityJob?.cancel();qualityRevision++;resolved=null;audioState=AudioExperience()
                                checkpoint();cancelLive(); queue.clear(); heardIds.clear(); heardAt.clear(); liveRoom = null; setSleepTimer(0); stopSelf()
                            }
                            PlaybackCommands.FORGET_HISTORY -> {
                                // Paused checkpoints must not resurrect history deleted by the user.
                                require(!player.playWhenReady)
                                val bv = args.getString("bvid")
                                if (bv == null) { heardIds.clear(); heardAt.clear() } else {
                                    val cid = args.getLong("cid")
                                    (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).queueEntry() }
                                        .filter { it.bvid == bv && it.cid == cid }.forEach { heardIds.remove(it.id); heardAt.remove(it.id) }
                                }
                            }
                            PlaybackCommands.FLUSH -> checkpoint()
                            else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                        }
                        publishState()
                        val done = SettableFuture.create<SessionResult>()
                        if (writes.trySend(Write(done = done)).isFailure) done.set(SessionResult(SessionError.ERROR_UNKNOWN))
                        return done
                    } catch (_: IllegalArgumentException) {
                        return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
                    }
                }
            }).build()
        CoroutineScope(Dispatchers.IO).launch {
            // A single writer preserves order, including the final checkpoint after service teardown.
            for (write in writes) {
                try {
                    write.deleteHistoryAccount?.let { app.stores.delete(it); app.persistenceError = false }
                    write.liveHistory?.let { heard ->
                        app.stores.checkpointLive(heard)
                        app.syncHistory(heard)
                        app.stores.prune(heard.account, app.settings.current().historyPolicy(), app.clock.nowMs())
                        app.persistenceError = false
                    }
                    write.clearAccount?.let { app.database.listenDao().clearPlayback(it); app.persistenceError = false }
                    write.snapshot?.let { snapshot ->
                        app.stores.checkpoint(snapshot, write.history)
                        write.history?.let{app.syncHistory(it)}
                        write.history?.let { heard -> heard.source?.takeIf { it.kind in setOf(SourceKind.UP_COLLECTION,SourceKind.PUBLIC_FAVORITES,SourceKind.UP_UPLOADS) }?.let { source ->
                            app.stores.markHeard(heard.account, source, heard.video.bvid)
                        } }
                        val settings = app.settings.current()
                        app.stores.prune(snapshot.queue.account, settings.historyPolicy(), app.clock.nowMs())
                        app.persistenceError = false
                    }
                    write.done?.set(SessionResult(if (app.persistenceError) SessionError.ERROR_UNKNOWN else SessionResult.RESULT_SUCCESS))
                } catch (_: Exception) { app.persistenceError = true; app.diagnostics.record(DiagnosticEvent.STORAGE_FAILED, FailureKind.STORAGE); write.done?.set(SessionResult(SessionError.ERROR_UNKNOWN)) }
            }
        }
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { refreshEffects();publishState() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updatePlaybackMaintenance()
                if (queue.changing) return
                if (!playWhenReady) pauseRevision++
                if (!playWhenReady) pauseReason = when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> PauseReason.FOCUS_LOSS
                    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> PauseReason.NOISY
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> explicitPause ?: pauseReason.takeIf { queue.changing } ?: PauseReason.USER
                    else -> pauseReason
                }
                if (!playWhenReady) explicitPause = null
                publishState()
            }
            override fun onPlayerError(error: PlaybackException) {
                if(liveRoom!=null){liveError(error);return}
                issue = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<PlaybackNetworkException>().firstOrNull()?.issue
                    ?: network.blocked() ?: when(error.errorCode) {
                        PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> PlaybackIssue.UNSUPPORTED
                        else -> PlaybackIssue.NETWORK_ERROR
                    }
                pauseWithReason(if (issue in setOf(PlaybackIssue.OFFLINE, PlaybackIssue.NETWORK_ERROR)) PauseReason.NETWORK else PauseReason.ERROR)
                audioState = audioState.copy(checking=false,restriction=generateSequence<Throwable>(error){it.cause}.filterIsInstance<PlaybackNetworkException>().firstOrNull()?.audioRestriction ?: when(issue) {
                    PlaybackIssue.UNSUPPORTED -> AudioRestriction.DEVICE_UNSUPPORTED
                    PlaybackIssue.SESSION_EXPIRED -> AudioRestriction.ACCESS_REQUIRED
                    PlaybackIssue.PLATFORM_LIMITED -> AudioRestriction.PLATFORM_LIMITED
                    PlaybackIssue.NETWORK_ERROR,PlaybackIssue.OFFLINE -> AudioRestriction.NETWORK
                    else -> AudioRestriction.UNKNOWN
                })
                val chosen = audioState.selected
                if (issue == PlaybackIssue.UNSUPPORTED && chosen != null && chosen.codec != "mp4a.40.2" &&
                    failedTracks.add("${contentKey()}:${chosen.id}:${chosen.codec}")) {
                    // One bounded fallback per failing track. Recovery stays paused after a decoder error.
                    resolved=null
                    changeAudio(Bundle().apply { putString("choice",Json.encodeToString(app.settings.settings.value.audioChoice)) })
                }
                publishState()
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (queue.changing) return
                refreshPresentation()
                if (audioState.contentKey != contentKey()) audioState=AudioExperience(contentKey())
                applySpeed()
                refreshEffects()
                val began = player.isPlaying && player.currentMediaItem?.mediaId?.let(heardIds::add) == true
                if (player.isPlaying) player.currentMediaItem?.mediaId?.let { heardAt[it] = app.clock.nowMs() }
                if(liveRoom!=null && player.isPlaying) {
                    if(liveExperience.phase!=LivePhase.PLAYING)liveStableAt=android.os.SystemClock.elapsedRealtime()
                    liveExperience=liveExperience.copy(phase=LivePhase.PLAYING,notice="",attempt=liveReconnectAttempt)
                    issue=null
                }
                if (player.playbackState == Player.STATE_ENDED) {
                    if(liveRoom!=null && player.playWhenReady) scheduleLiveRetry(LiveFailure.NETWORK,"直播流已中断，正在检查房间")
                    else if(liveRoom==null) {
                        pauseReason = PauseReason.ENDED
                        if (player.playWhenReady) pauseWithReason(PauseReason.ENDED)
                    }
                }
                if (began || events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                    events.contains(Player.EVENT_POSITION_DISCONTINUITY) || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState in setOf(Player.STATE_ENDED, Player.STATE_IDLE)) checkpoint()
                publishState()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (queue.changing) return
                val oldItem = oldPosition.mediaItem?.takeIf(queue::accepts) ?: return
                val old = oldItem.queueEntry() ?: return
                if (old.id in heardIds) checkpoint(old to oldPosition.positionMs.coerceAtLeast(0))
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                if (audioState.contentKey == contentKey()) {
                    val bits=if(format.pcmEncoding==C.ENCODING_PCM_24BIT)24 else if(format.pcmEncoding==C.ENCODING_PCM_32BIT)32 else null
                    val detail=listOfNotNull(format.sampleMimeType,format.sampleRate.takeIf{it>0}?.let{"$it Hz"},format.channelCount.takeIf{it>0}?.let{"$it 声道"},bits?.let{"$it bit"}).joinToString(" · ")
                    audioState=audioState.copy(decodedLabel=detail);publishState()
                }
            }
            override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                if (audioState.contentKey==contentKey()) { audioState=audioState.copy(decoded=true);publishState() }
            }
        })
        outputs.start()
        network.start()
        scope.launch {
            try { app.settings.current(); outputConfigured=true; app.settings.settings.collect { refreshOutput(); app.automaticAudio.refreshLimit(); enforceNetwork() } }
            catch(e:CancellationException){throw e}
            catch(_:Exception){outputConfigured=false;pauseWithReason(PauseReason.ERROR,true);publishState()}
        }
        scope.launch {
            app.accounts.session.collect { state ->
                refreshPresentation()
                val live=liveResolution
                if(live!=null && (live.owner!=state.stamp.account || live.generation!=app.vault.generation)) {
                    pauseWithReason(PauseReason.ERROR,true);cancelLive();liveRoom=null;queue.clear();publishState()
                }
            }
        }
    }

    private fun liveWindow()=liveRoom!=null && player.isCurrentMediaItemLive && player.isCurrentMediaItemSeekable && player.duration>0
    private fun cancelLiveRequests() {
        liveEpoch++;liveRequest?.cancel();liveRequest=null;liveMonitor?.cancel();liveMonitor=null
    }
    private fun cancelLive() {
        cancelLiveRequests();liveResolution=null;liveExperience=LiveExperience();liveConsent=false;liveReconnectAttempt=0;liveVodSaved=false;liveReconnectNotice=""
    }
    private suspend fun resolveLive(room:Long,consent:Boolean):LiveResolution = withContext(Dispatchers.IO) {
        withTimeout(20000) {
            val owner=app.accountKey;val generation=app.vault.generation
            val info=app.live.room(room)
            if(info.roomId!=room)throw PlatformFailure("直播房间身份不匹配")
            if(info.state!=LiveRoomStatus.LIVE)throw PlatformFailure(if(info.state==LiveRoomStatus.UNKNOWN)"直播状态未知，已停止连接" else "房间当前${info.state.label}",if(info.state==LiveRoomStatus.UNKNOWN)null else 10001)
            val selected=LiveStreams.choose(app.live.streams(room),consent)
            require(app.accountKey==owner && app.vault.generation==generation)
            LiveResolution(owner,generation,info,selected)
        }
    }
    private fun installLive(resolution:LiveResolution) {
        val room=resolution.room;val stream=resolution.stream
        liveResolution=resolution;liveRoom=resolution.owner to room.roomId
        liveExperience=LiveExperience(room.roomId,room.title,room.anchor,room.cover,LivePhase.CONNECTING,stream.kind,liveReconnectAttempt,notice=liveReconnectNotice,canReturnVod=liveVodSaved)
        issue=null;pauseReason=PauseReason.UNKNOWN;liveStableAt=0
        player.repeatMode=Player.REPEAT_MODE_OFF;player.shuffleModeEnabled=false
        player.setPlaybackSpeed(1f)
        player.setMediaItem(MediaItem.Builder().setMediaId("live:${room.roomId}")
            .setUri("bililisten://live/${room.roomId}").setMimeType(stream.mime)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(room.title).setArtist(room.anchor)
                .setArtworkUri(room.cover.takeIf{it.isNotBlank()}?.let(android.net.Uri::parse))
                .setExtras(Bundle().apply{putBoolean("live",true);putString("streamKind",stream.kind.name)}).build()).build())
        if(routeAllowed() && !enforceNetwork(true)) {player.prepare();player.play()}
        else pauseWithReason(if(!routeAllowed())PauseReason.NOISY else PauseReason.NETWORK,true)
        startLiveMonitor();publishState()
    }
    private fun playLive(args:Bundle):ListenableFuture<SessionResult> {
        val done=SettableFuture.create<SessionResult>()
        val room=args.getLong("room");val consent=args.getBoolean("mixedConsent")
        if(room<=0 || args.containsKey("generation") && args.getLong("generation")!=app.vault.generation)return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        cancelLiveRequests();val ticket=liveEpoch;val owner=app.accountKey;val generation=app.vault.generation;val pauseToken=pauseRevision
        liveRequest=scope.launch {
            try {
                val resolution=resolveLive(room,consent)
                require(ticket==liveEpoch && app.accountKey==owner && app.vault.generation==generation && pauseRevision==pauseToken)
                // Do not replace the current player until its durable checkpoint has succeeded.
                checkpoint();val saved=SettableFuture.create<SessionResult>()
                check(writes.trySend(Write(done=saved)).isSuccess)
                check(withContext(Dispatchers.IO){saved.get(10,TimeUnit.SECONDS)}.resultCode==SessionResult.RESULT_SUCCESS)
                ensureActive();require(ticket==liveEpoch && app.accountKey==owner && app.vault.generation==generation && pauseRevision==pauseToken)
                liveVodSaved=liveVodSaved || queue.snapshot()?.entries?.isNotEmpty()==true || withContext(Dispatchers.IO){app.stores.load(owner)!=null}
                ensureActive();require(ticket==liveEpoch && app.accountKey==owner && app.vault.generation==generation && pauseRevision==pauseToken)
                queue.clear();heardIds.clear();heardAt.clear();qualityJob?.cancel();qualityRevision++;resolved=null;audioState=AudioExperience()
                liveConsent=consent;liveReconnectAttempt=0;liveReconnectNotice="";installLive(resolution)
                done.set(SessionResult(SessionResult.RESULT_SUCCESS))
            }catch(e:TimeoutCancellationException){done.set(SessionResult(SessionError.ERROR_BAD_VALUE,Bundle().apply{putString("notice","直播请求超时，原点播状态保留")}))}
            catch(e:CancellationException){done.set(SessionResult(SessionError.ERROR_INVALID_STATE));throw e}
            catch(e:Exception){done.set(SessionResult(SessionError.ERROR_BAD_VALUE,Bundle().apply{putString("notice",(e as? PlatformFailure)?.category ?: "直播连接未完成，原点播状态保留")}))}
            finally{if(ticket==liveEpoch)liveRequest=null}
        }
        return done
    }
    private fun reconnectLive(manual:Boolean,waitMs:Long=0) {
        val room=liveRoom ?: return
        if(room.first!=app.accountKey || !routeAllowed() || network.blocked()!=null) {issue=network.blocked();pauseWithReason(PauseReason.NETWORK,true);return}
        cancelLiveRequests();val ticket=liveEpoch;val generation=app.vault.generation
        if(manual){liveReconnectAttempt=0;liveReconnectNotice=""}
        liveExperience=liveExperience.copy(phase=if(manual)LivePhase.CONNECTING else LivePhase.RECONNECTING,attempt=liveReconnectAttempt,
            notice=if(manual)"正在重新检查房间" else liveReconnectNotice.ifBlank{"连接中断，正在重连（$liveReconnectAttempt / 3）"})
        player.stop();player.playWhenReady=true;publishState()
        liveRequest=scope.launch {
            try {
                if(waitMs>0)delay(waitMs)
                val resolution=resolveLive(room.second,liveConsent)
                ensureActive();require(ticket==liveEpoch && room==liveRoom && app.accountKey==room.first && app.vault.generation==generation && player.playWhenReady)
                installLive(resolution)
            }catch(e:TimeoutCancellationException){if(ticket==liveEpoch && room==liveRoom)scheduleLiveRetry(LiveFailure.NETWORK,"直播请求超时")}
            catch(e:CancellationException){throw e}
            catch(e:Exception){if(ticket==liveEpoch && room==liveRoom) {
                val failure=when((e as? PlatformFailure)?.code){10001->LiveFailure.OFFLINE_ROOM;-101->LiveFailure.SESSION;-403,-412,-352,403,412,429->LiveFailure.PLATFORM;else->if(e is kotlinx.coroutines.TimeoutCancellationException || (e as? PlatformFailure)?.category?.contains("网络")==true)LiveFailure.NETWORK else LiveFailure.UNSUPPORTED}
                if(failure==LiveFailure.OFFLINE_ROOM)endLive("房间已停止直播，等待你选择")
                else scheduleLiveRetry(failure,(e as? PlatformFailure)?.category)
            }}finally{if(ticket==liveEpoch)liveRequest=null}
        }
    }
    private fun endLive(notice:String) {
        liveExperience=liveExperience.copy(phase=LivePhase.ENDED,notice=notice)
        issue=null;pauseWithReason(PauseReason.ENDED,true)
    }
    private fun scheduleLiveRetry(failure:LiveFailure,notice:String?=null) {
        val wanted=player.playWhenReady
        val next=liveReconnectAttempt+1
        val wait=LiveReconnect.delayMs(next,failure,wanted,network.blocked()==null && routeAllowed())
        if(wait!=null) {liveReconnectAttempt=next;liveReconnectNotice=notice.orEmpty();reconnectLive(false,wait);return}
        issue=when(failure){LiveFailure.PLATFORM->PlaybackIssue.PLATFORM_LIMITED;LiveFailure.SESSION->PlaybackIssue.SESSION_EXPIRED;LiveFailure.UNSUPPORTED->PlaybackIssue.UNSUPPORTED;else->network.blocked() ?: PlaybackIssue.NETWORK_ERROR}
        liveExperience=liveExperience.copy(phase=LivePhase.FAILED,notice=notice ?: issue!!.message,attempt=liveReconnectAttempt)
        pauseWithReason(PauseReason.ERROR,true)
    }
    private fun liveError(error:PlaybackException) {
        if(!player.playWhenReady)return
        val response=generateSequence<Throwable>(error){it.cause}.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        val failure=when {
            error.errorCode==PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW->LiveFailure.WINDOW_EXPIRED
            error.errorCode in setOf(PlaybackException.ERROR_CODE_DECODING_FAILED,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)->LiveFailure.UNSUPPORTED
            response in setOf(401,403,410)->LiveFailure.EXPIRED_URL
            response in setOf(412,429)->LiveFailure.PLATFORM
            else->LiveFailure.NETWORK
        }
        scheduleLiveRetry(failure,when(failure){
            LiveFailure.WINDOW_EXPIRED->"回退窗口已过期，重新连接当前直播"
            LiveFailure.EXPIRED_URL->"直播地址已失效或访问受限，重新检查当前房间"
            else->null
        })
    }
    private fun startLiveMonitor() {
        liveMonitor?.cancel()
        if(!player.playWhenReady)return
        val room=liveRoom ?: return;val generation=app.vault.generation
        liveMonitor=scope.launch {
            while(isActive && liveRoom==room && player.playWhenReady) {
                delay(30000)
                if(app.accountKey!=room.first || app.vault.generation!=generation){pauseWithReason(PauseReason.ERROR,true);return@launch}
                if(liveStableAt>0 && android.os.SystemClock.elapsedRealtime()-liveStableAt>=30000)liveReconnectAttempt=0
                try {
                    val current=withContext(Dispatchers.IO){withTimeout(15000){app.live.room(room.second)}}
                    if(current.roomId!=room.second)throw PlatformFailure("房间身份变化")
                    if(current.state==LiveRoomStatus.UNKNOWN){scheduleLiveRetry(LiveFailure.UNSUPPORTED,"房间状态未知，已停止等待选择");return@launch}
                    if(current.state!=LiveRoomStatus.LIVE){endLive("房间已${current.state.label}，等待你选择");return@launch}
                }catch(_:TimeoutCancellationException){ /* A metadata timeout does not interrupt a healthy stream. */ }
                catch(e:CancellationException){throw e}catch(e:PlatformFailure){if(e.code in setOf(-101,-403,-412,-352,403,412,429)){scheduleLiveRetry(if(e.code==-101)LiveFailure.SESSION else LiveFailure.PLATFORM,e.category);return@launch}}
                catch(_:Exception){ /* Playback errors own the bounded media reconnect budget. */ }
            }
        }
    }

    private fun replaceQueue(args:Bundle):ListenableFuture<SessionResult> {
        val done=SettableFuture.create<SessionResult>()
        val generation=app.vault.generation;val owner=app.accountKey
        val queueVersion=queue.snapshot()?.queueVersion;val pauseToken=pauseRevision
        scope.launch {
            try {
                val incoming=withContext(Dispatchers.IO) {
                    args.getString("queueFile")?.let {name->
                        require(args.getLong("generation",-1)==generation)
                        QueueHandoff(java.io.File(cacheDir,"queue-handoff")).read(name)
                    } ?: SnapshotCodec.decode(requireNotNull(args.getString("snapshot")))
                }
                ensureActive()
                require(app.vault.generation==generation&&app.accountKey==owner&&incoming.account==owner)
                require(queue.snapshot()?.queueVersion==queueVersion&&pauseRevision==pauseToken)
                incoming.entries.filter{it.offline}.forEach { e->require(app.downloads.records.value.any{it.id==e.id&&it.account==incoming.account&&it.video==VideoRef(e.bvid,e.cid,e.part)&&it.phase==DownloadPhase.COMPLETE}) }
                checkpoint();heardIds.clear();heardAt.clear()
                qualityRevision++;resolved=null;failedTracks.clear();audioState=AudioExperience()
                val local=incoming.entries.first{it.id==incoming.currentId}.offline
                issue=network.blocked().takeIf{args.getBoolean("play")&&!local}
                pauseReason=if(issue!=null)PauseReason.NETWORK else if(args.getBoolean("play"))PauseReason.UNKNOWN else PauseReason.RESTORED
                cancelLive();liveRoom=null
                queue.replace(incoming,args.getBoolean("play")&&issue==null&&routeAllowed());applySpeed();checkpoint();publishState()
                if(writes.trySend(Write(done=done)).isFailure)done.set(SessionResult(SessionError.ERROR_UNKNOWN))
            }catch(e:CancellationException){done.set(SessionResult(SessionError.ERROR_INVALID_STATE));throw e}
            catch(_:Exception){done.set(SessionResult(SessionError.ERROR_BAD_VALUE))}
        }
        return done
    }
    private fun widgetState(): WidgetState {
        val saved = queue.snapshot()
        val entry = player.currentMediaItem?.queueEntry()
        val live = liveRoom != null
        return WidgetState(account = saved?.account ?: liveRoom?.first ?: app.accountKey,
            mediaId = player.currentMediaItem?.mediaId.orEmpty(), version = saved?.queueVersion ?: 0,
            title = player.mediaMetadata.title?.toString() ?: "尚未播放", part = entry?.part ?: 0, bvid = entry?.takeUnless { it.offline }?.bvid.orEmpty(),
            live = live, requested = player.playWhenReady, buffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0), durationMs = player.duration.coerceAtLeast(0),
            canPrevious = !live && player.mediaItemCount > 0, canNext = !live && player.hasNextMediaItem(),
            canSeek = !live && player.isCurrentMediaItemSeekable, issue = issue)
    }
    private fun widgetControl(args: Bundle): ListenableFuture<SessionResult> {
        if (widgetJob?.isActive == true) return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        val done = SettableFuture.create<SessionResult>()
        val token = args.getString("token")
        val owner = args.getString("owner")
        val id = args.getString("mediaId").orEmpty()
        val version = args.getLong("version", -1)
        val action = args.getString("control").orEmpty()
        val pauseToken = pauseRevision
        widgetJob = scope.launch {
            try {
                withTimeout(6500) {
                    require(token == app.widgets.token && owner == app.accounts.session.value.stamp.account)
                    if (player.mediaItemCount == 0) {
                        require(action == "play" && id.isNotBlank())
                        val saved = withContext(Dispatchers.IO) { app.stores.load(requireNotNull(owner)) } ?: error("没有续听记录")
                        require(saved.queue.currentId == id && saved.queue.queueVersion == version)
                        val incoming = saved.queue.checked()
                        val entry = incoming.entries.first { it.id == id }
                        if (!entry.offline && app.accounts.session.value.status == SessionStatus.UNVERIFIED) {
                            withContext(Dispatchers.IO) { app.accounts.verify() }
                        }
                        require(token == app.widgets.token && owner == app.accounts.session.value.stamp.account && player.mediaItemCount == 0 && pauseRevision == pauseToken)
                        require(entry.offline || app.accounts.session.value.status != SessionStatus.UNVERIFIED)
                        app.accountKey = requireNotNull(owner)
                        if (entry.offline) {
                            withContext(Dispatchers.IO) { require(app.downloads.playable(entry.id).isFile) }
                        } else {
                            val video = withContext(Dispatchers.IO) { app.content.video(entry.bvid) }
                            require(video.parts.any { it.cid == entry.cid && it.number == entry.part } || entry.cid == 0L && video.hasCreator(entry.source?.owner ?: 0))
                        }
                        require(token == app.widgets.token && owner == app.accounts.session.value.stamp.account && player.mediaItemCount == 0 && pauseRevision == pauseToken)
                        liveRoom = null; pauseReason = PauseReason.RESTORED
                        queue.replace(incoming, false); applySpeed(); checkpoint()
                    } else require(widgetState().matches(requireNotNull(owner), id, version))
                    val stamp = app.vault.generation
                    val acceptedVersion = queue.snapshot()?.queueVersion
                    app.settings.current()
                    while (!outputConfigured) { delay(25) }
                    require(token == app.widgets.token && owner == app.accounts.session.value.stamp.account && stamp == app.vault.generation && pauseRevision == pauseToken)
                    require(queue.snapshot()?.queueVersion == acceptedVersion)
                    val state = widgetState()
                    require(!done.isCancelled)
                    require(state.mediaId == id && state.account == owner && state.allows(action))
                    // Play/pause are absolute commands: a double tap cannot silently toggle back.
                    when (action) {
                        "play" -> session.player.play()
                        "pause" -> session.player.pause()
                        "previous" -> if (player.hasPreviousMediaItem()) session.player.seekToPreviousMediaItem() else session.player.seekTo(0)
                        "next" -> session.player.seekToNextMediaItem()
                        "back", "forward" -> session.player.seekTo((player.currentPosition + if (action == "back") -15000 else 15000).coerceIn(0, player.duration.coerceAtLeast(0)))
                    }
                    checkpoint(); publishState()
                }
                done.set(SessionResult(SessionResult.RESULT_SUCCESS))
            } catch (e: CancellationException) {
                done.set(SessionResult(SessionError.ERROR_INVALID_STATE))
            } catch (_: Exception) {
                done.set(SessionResult(SessionError.ERROR_BAD_VALUE))
            } finally { app.widgets.publish(widgetState(), true) }
        }
        done.addListener({ if(done.isCancelled)widgetJob?.cancel() }, androidx.core.content.ContextCompat.getMainExecutor(this))
        return done
    }
    private fun changeEffects(args:Bundle):ListenableFuture<SessionResult> {
        if(effectsChanging)return Futures.immediateFuture(SessionResult(SessionError.ERROR_INVALID_STATE))
        val requested=try {args.getString("effects")?.let{require(it.length<=8192);Json.decodeFromString<EffectsSettings>(it).checked()}}
            catch(_:Exception){return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))}
        val done=SettableFuture.create<SessionResult>();effectsChanging=true
        scope.launch {
            try {
                if(requested!=null)app.settings.update(app.settings.current().copy(effects=requested))
                refreshEffects(force=requested==null,preference=requested ?: app.settings.current().effects)
                publishState();done.set(SessionResult(SessionResult.RESULT_SUCCESS))
            }catch(e:CancellationException){done.set(SessionResult(SessionError.ERROR_INVALID_STATE));throw e}
            catch(_:Exception){done.set(SessionResult(SessionError.ERROR_IO))}
            finally{effectsChanging=false}
        }
        return done
    }

    private fun changeAudio(args: Bundle): ListenableFuture<SessionResult> {
        if(player.currentMediaItem?.queueEntry()?.offline==true)return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        if (qualityJob?.isActive == true || liveRoom != null || contentKey().isEmpty() || player.currentMediaItem?.queueEntry()?.cid==0L) return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
        val choice = try { args.getString("choice")?.let { Json.decodeFromString<AudioChoice>(it).checked() } }
            catch (_: Exception) { return Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE)) }
        val entry = requireNotNull(player.currentMediaItem?.queueEntry())
        val key=contentKey(); val owner=app.accountKey; val generation=app.vault.generation; val version=queue.snapshot()?.queueVersion
        val wasRequested=player.playWhenReady; val pauseToken=pauseRevision
        val done=SettableFuture.create<SessionResult>()
        audioState=audioState.copy(contentKey=key,checking=true);publishState()
        qualityJob=scope.launch {
            try {
                val blocked=network.blocked()
                if(blocked!=null) throw PlatformFailure(if(blocked==PlaybackIssue.OFFLINE) "网络请求失败" else "当前网络不允许取流")
                val probe=withContext(Dispatchers.IO){app.entitlements.probe(entry.bvid,entry.cid,true)}
                ensureActive()
                if (contentKey()!=key || app.accountKey!=owner || app.vault.generation!=generation || queue.snapshot()?.queueVersion!=version) throw CancellationException("音质目标已变化")
                val settings=app.settings.current()
                val supports:(AudioTrack)->Boolean={DeviceAudioSupport.supports(it)&&"$key:${it.id}:${it.codec.lowercase()}" !in failedTracks}
                val decision=AudioExperienceRules.choose(probe.tracks,choice ?: settings.audioChoice,supports)
                val options=AudioExperienceRules.options(probe.tracks,supports)
                if (choice==null) {
                    audioState=audioState.copy(contentKey=key,options=options,checking=false,restriction=decision.restriction,
                        notice="已重新检查实际音轨；播放保持当前状态，会员标签不代表音轨权益")
                } else {
                    val selected=decision.track
                    if(selected==null) {
                        audioState=audioState.copy(options=options,checking=false,restriction=decision.restriction)
                        done.set(SessionResult(SessionError.ERROR_NOT_SUPPORTED));publishState();return@launch
                    }
                    // Read and merge the latest settings; changing quality must not undo other preferences.
                    app.settings.update(app.settings.current().copy(audioChoice=choice))
                    if(contentKey()!=key || app.accountKey!=owner || app.vault.generation!=generation || queue.snapshot()?.queueVersion!=version) throw CancellationException("音质目标已变化")
                    val resume=AudioExperienceRules.safeToResume(wasRequested,true,pauseToken==pauseRevision,routeAllowed(),network.blocked()==null)
                    val retainedPauseReason=pauseReason
                    val position=player.currentPosition.coerceAtLeast(0);val index=player.currentMediaItemIndex
                    checkpoint();qualityRevision++
                    resolved=Resolved(key,generation,qualityRevision,selected,options,decision.restriction,android.os.SystemClock.elapsedRealtime())
                    player.pause();player.stop();player.seekTo(index,position)
                    pauseReason=if(resume)PauseReason.UNKNOWN else retainedPauseReason
                    audioState=AudioExperience(key,options,AudioChoice(selected.id,selected.codec.lowercase()),restriction=decision.restriction,
                        notice=if(resume)"已保留分 P 和进度，正在切换音质" else "已保留分 P 和进度，保持暂停；请手动播放")
                    issue=null
                    if(resume) { player.prepare();player.play() }
                    checkpoint()
                }
                publishState();done.set(SessionResult(SessionResult.RESULT_SUCCESS))
            } catch(e: CancellationException) {
                if(contentKey()==key) { audioState=audioState.copy(checking=false);publishState() }
                done.set(SessionResult(SessionError.ERROR_INVALID_STATE))
            } catch(e: Exception) {
                if(contentKey()==key && app.accountKey==owner && app.vault.generation==generation) {
                    audioState=audioState.copy(checking=false,restriction=if(e is PlatformFailure)AudioExperienceRules.failure(e) else AudioRestriction.UNKNOWN,
                        notice="检查未完成，原播放和进度保留；可稍后重试")
                    publishState()
                }
                done.set(SessionResult(SessionError.ERROR_UNKNOWN))
            }
        }
        return done
    }

    private fun changeOutput(args: Bundle): ListenableFuture<SessionResult> {
        val done=SettableFuture.create<SessionResult>()
        try {
            val id=if(args.containsKey("device"))args.getInt("device") else null
            val device=outputs.select(id)
            pauseWithReason(PauseReason.USER,true)
            scope.launch {
                try { app.settings.update(app.settings.current().copy(preferredOutputType=device?.type));refreshOutput();done.set(SessionResult(SessionResult.RESULT_SUCCESS)) }
                catch(_:Exception){done.set(SessionResult(SessionError.ERROR_UNKNOWN))}
            }
        } catch(_:IllegalArgumentException){done.set(SessionResult(SessionError.ERROR_BAD_VALUE))}
        return done
    }

    private fun updatePlaybackMaintenance() {
        if (!player.playWhenReady) {
            playbackMaintenance?.cancel(); playbackMaintenance = null
            return
        }
        if (playbackMaintenance != null) return
        playbackMaintenance = scope.launch {
            while (isActive) {
                delay(1000)
                // Local connectivity check only: no API polling. Covers delayed vendor callbacks.
                if (player.playWhenReady) enforceNetwork()
                if (checkpointPolicy.periodic(android.os.SystemClock.elapsedRealtime(), player.isPlaying)) {
                    player.currentMediaItem?.mediaId?.let { heardAt[it] = app.clock.nowMs() }
                    checkpoint()
                    app.widgets.publish(widgetState())
                }
            }
        }
    }

    private fun applySpeed() {
        val speed = LongListening.speed(queue.snapshot(), liveRoom != null)
        if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
    }
    private fun setSleepTimer(seconds: Int) {
        sleepDeadline.set(seconds, android.os.SystemClock.elapsedRealtime())
        sleepJob?.cancel(); sleepJob = null
        timerRemaining = sleepDeadline.remaining(android.os.SystemClock.elapsedRealtime())
        if (seconds > 0) sleepJob = scope.launch {
            while (isActive) {
                delay(1000)
                val now = android.os.SystemClock.elapsedRealtime()
                timerRemaining = sleepDeadline.remaining(now)
                if (sleepDeadline.consumeExpired(now)) {
                    pauseWithReason(PauseReason.TIMER, stopLoading = true)
                    break
                }
                publishState()
            }
        }
        publishState()
    }
    private fun pauseWithReason(reason: PauseReason, stopLoading: Boolean = false) {
        cancelLiveRequests()
        if(liveRoom!=null && liveExperience.phase !in setOf(LivePhase.ENDED,LivePhase.FAILED))liveExperience=liveExperience.copy(phase=LivePhase.PAUSED,notice=if(reason==PauseReason.EXTERNAL_VIDEO)"已暂停，返回不会自动播放" else "")
        pauseRevision++
        explicitPause = reason; pauseReason = reason
        player.pause()
        if (stopLoading || liveRoom!=null) player.stop()
        checkpoint(); publishState()
    }
    private fun enforceNetwork(onPlayRequest: Boolean = false): Boolean {
        val offline=player.currentMediaItem?.queueEntry()?.takeIf{it.offline}
        if(offline!=null) {
            if(queue.snapshot()?.account!=app.accounts.session.value.stamp.account) {
                issue=PlaybackIssue.LOCAL_FILE;pauseWithReason(PauseReason.ERROR,true);return true
            }
            return false
        }
        // Silent queue restoration and appearance changes must not ask for playback consent.
        if (!onPlayRequest && !player.playWhenReady && !player.isLoading) return false
        val blocked = network.blocked() ?: return false
        if (player.mediaItemCount == 0) return true
        issue = blocked
        if (player.playWhenReady || player.isLoading) pauseWithReason(PauseReason.NETWORK, stopLoading = true)
        else publishState()
        return true
    }
    private fun publishState() {
        if (!::queue.isInitialized) return
        if (!queue.changing) app.widgets.publish(widgetState())
        val snapshot = queue.snapshot()
        val output=if(::outputs.isInitialized)outputs.snapshot().let { if(outputConfigured)it else it.copy(notice="正在读取输出设置，保持暂停；若持续未就绪，请检查本机存储后重开") } else OutputExperience()
        val actualAudio=audioState.takeIf { it.contentKey==contentKey() } ?: AudioExperience(contentKey())
        val audioJson=Json.encodeToString(actualAudio);val outputJson=Json.encodeToString(output);val effectsJson=Json.encodeToString(if(::effects.isInitialized)effects.state else EffectsView())
        if(liveRoom!=null)liveExperience=liveExperience.copy(canSeekWindow=liveWindow(),windowMs=if(liveWindow())player.duration else 0,
            windowPositionMs=if(liveWindow())player.currentPosition.coerceAtLeast(0) else 0)
        val liveJson=Json.encodeToString(liveExperience)
        val fingerprint = "${snapshot?.mode}:${snapshot?.queueVersion}:$pauseReason:$issue:${player.audioSessionId}:${player.playbackParameters.speed}:$timerRemaining:$audioJson:$outputJson:$effectsJson:$liveJson"
        if (fingerprint == publishedState) return
        publishedState = fingerprint
        val metadata = MediaMetadata.Builder().setExtras(Bundle().apply {
            putString("mode", snapshot?.mode?.name); putLong("queueVersion", snapshot?.queueVersion ?: 0)
            putString("pauseReason", pauseReason.name); putString("issue", issue?.name)
            putInt("audioSessionId", player.audioSessionId)
            putFloat("speed", player.playbackParameters.speed); putLong("timerRemainingMs", timerRemaining)
            putString("audio",audioJson);putString("output",outputJson)
            putString("effects",effectsJson)
            putString("liveExperience",liveJson)
        }).build()
        // MediaMetadata equality does not provide an extras-change event contract.
        // Session extras explicitly notify every controller, including changes with the same title.
        if (::session.isInitialized) session.setSessionExtras(requireNotNull(metadata.extras))
    }

    private fun checkpoint(departed: Pair<QueueEntry, Long>? = null) {
        if (queue.changing) return
        val live = liveRoom
        if (live != null) {
            val id = "live:${live.second}"
            if (id in heardIds && (app.settings.settings.value.historyEnabled||app.historySyncEnabled(live.first))) heardAt[id]?.let { at ->
                writes.trySend(Write(liveHistory = LiveHistoryEntry(live.first,live.second,player.mediaMetadata.title?.toString() ?: "直播",at)))
            }
            return
        }
        val snapshot = queue.snapshot() ?: return
        val entry = departed?.first ?: snapshot.entries.first { it.id == snapshot.currentId }
        val position = departed?.second ?: snapshot.positionMs
        val history = if (entry.cid>0 && entry.id in heardIds && (app.settings.settings.value.historyEnabled||app.historySyncEnabled(snapshot.account))) LocalHistoryEntry(snapshot.account, VideoRef(entry.bvid, entry.cid, entry.part), entry.title, entry.resolvedSource(snapshot.account), position, heardAt[entry.id] ?: app.clock.nowMs(), entry.resolvedOrigin()) else null
        writes.trySend(Write(PlaybackSnapshot(snapshot, app.clock.nowMs(), pauseReason), history))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session
    override fun onTaskRemoved(rootIntent: Intent?) {
        checkpoint()
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) stopSelf()
    }
    override fun onDestroy() {
        checkpoint()
        app.widgets.publish(widgetState()); app.widgets.serviceStopped()
        network.close()
        outputs.close()
        effects.close()
        player.release(); session.release()
        writes.close()
        scope.cancel()
        super.onDestroy()
    }
}

internal fun isMediaHost(host: String): Boolean = listOf("bilivideo.com", "bilivideo.cn", "akamaized.net").any { host == it || host.endsWith(".$it") }
