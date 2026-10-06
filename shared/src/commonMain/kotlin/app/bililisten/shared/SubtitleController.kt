package app.bililisten.shared

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Independent of playback commands. A subtitle failure cannot pause, replace, or prepare audio. */
class SubtitleController(private val scope: CoroutineScope, private val repository: SubtitleRepository,
    private val accounts: AccountRepository, private val settings: SettingsRepository, private val clock: Clock) {
    private val mutable=MutableStateFlow(SubtitleView())
    val state=mutable.asStateFlow()
    private var target: VideoRef?=null
    private var stamp: SessionStamp?=null
    private var job: Job?=null
    private var revision=0L
    private var loadedTracks=emptyList<SubtitleTrack>()
    private var tracksAt=0L
    private var visible=false
    fun show(video: VideoRef?, identity: SessionStamp, force: Boolean=false) {
        val changed=target!=video || stamp!=identity
        visible=true
        if(changed || force || mutable.value.status==SubtitleStatus.IDLE) {
            target=video;stamp=identity
            refresh()
        }
    }
    fun hide() { visible=false; revision++;job?.cancel();job=null;loadedTracks=emptyList();mutable.value=SubtitleView() }
    fun identityChanged(identity: SessionStamp) { if(stamp!=null && stamp!=identity) { hide();target=null;stamp=identity } }
    fun retry() { if(visible)refresh() }
    private fun current(ticket: Long, video: VideoRef, identity: SessionStamp) =
        visible && revision==ticket && target==video && stamp==identity && accounts.session.value.stamp==identity
    private fun refresh(requested: String?=null) {
        job?.cancel();val ticket=++revision
        loadedTracks=emptyList()
        val video=target;val identity=stamp
        if(video==null || identity==null) { mutable.value=SubtitleView(status=SubtitleStatus.UNSUPPORTED,message="当前没有可读取字幕的点播分 P");return }
        mutable.value=SubtitleView(video,SubtitleStatus.LOADING,message="正在读取当前分 P 的字幕轨道")
        job=scope.launch {
            try {
                val result=repository.tracks(video)
                ensureActive();accounts.requireCurrent(identity)
                if(!current(ticket,video,identity))return@launch
                if(result.video!=video)throw SubtitleFailure(SubtitleStatus.FAILED,"字幕分 P 身份不匹配")
                loadedTracks=result.tracks;tracksAt=clock.nowMs()
                val options=result.tracks.map { it.option }
                if(options.isEmpty()) {
                    mutable.value=SubtitleView(video,if(result.needsLogin)SubtitleStatus.LOGIN_REQUIRED else SubtitleStatus.EMPTY,
                        message=if(result.needsLogin)"平台要求登录后读取此分 P 字幕，收听仍可继续" else "当前分 P 暂无独立字幕；画面内文字不等于独立字幕")
                    return@launch
                }
                val preference=settings.current().subtitleLanguage
                val selected=result.tracks.firstOrNull { it.option.id==requested }
                    ?: result.tracks.firstOrNull { it.option.language==preference }
                    ?: result.tracks.firstOrNull { it.option.language=="zh-CN" }
                    ?: result.tracks.first()
                if(requested!=null && selected.option.id==requested)settings.update(settings.current().copy(subtitleLanguage=selected.option.language))
                val notice=if(preference.isNotBlank() && options.none { it.language==preference }) "当前分 P 未提供偏好语言，已使用可用字幕" else "按平台原始句子时间同步，不提供逐字精度"
                mutable.value=SubtitleView(video,SubtitleStatus.LOADING,options,selected.option,message="正在读取${selected.option.label} · ${selected.option.kind.label}")
                read(selected,ticket,video,identity,notice)
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { fail(e,ticket,video,identity) }
        }
    }
    fun select(id: String) {
        val track=loadedTracks.firstOrNull { it.option.id==id } ?: return
        val video=target ?: return;val identity=stamp ?: return
        if(clock.nowMs()-tracksAt !in 0..300_000) {refresh(id);return}
        job?.cancel();val ticket=++revision
        mutable.value=mutable.value.copy(status=SubtitleStatus.LOADING,selected=track.option,cues=emptyList(),message="正在切换字幕语言")
        job=scope.launch {
            try {
                accounts.requireCurrent(identity)
                settings.update(settings.current().copy(subtitleLanguage=track.option.language))
                ensureActive()
                if(current(ticket,video,identity))read(track,ticket,video,identity,"按平台原始句子时间同步，不提供逐字精度")
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {fail(e,ticket,video,identity)}
        }
    }
    private suspend fun read(track: SubtitleTrack,ticket: Long,video: VideoRef,identity: SessionStamp,notice: String) {
        val cues=repository.cues(track)
        currentCoroutineContext().ensureActive();accounts.requireCurrent(identity)
        if(current(ticket,video,identity))mutable.value=mutable.value.copy(status=if(cues.isEmpty())SubtitleStatus.EMPTY else SubtitleStatus.READY,
            cues=cues,message=if(cues.isEmpty())"该字幕轨道暂无可显示句子，收听仍可继续" else notice,checkedAt=clock.nowMs())
    }
    private fun fail(e: Exception,ticket: Long,video: VideoRef,identity: SessionStamp) {
        if(!current(ticket,video,identity))return
        val status=when(e) { is SubtitleFailure -> e.status; is PlatformFailure -> when(e.kind()) {
            FailureKind.SESSION_EXPIRED -> SubtitleStatus.LOGIN_REQUIRED
            FailureKind.ACCESS_DENIED -> SubtitleStatus.RESTRICTED
            FailureKind.PLATFORM_BLOCKED -> SubtitleStatus.RESTRICTED
            else -> SubtitleStatus.FAILED
        };else -> SubtitleStatus.FAILED }
        val detail=when(e) {is SubtitleFailure -> e.detail;is PlatformFailure -> e.category;else -> "字幕读取失败"}
        mutable.value=mutable.value.copy(status=status,cues=emptyList(),message="$detail；收听与播放控制不受影响")
    }
}
