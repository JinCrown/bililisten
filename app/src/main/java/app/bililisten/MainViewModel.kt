package app.bililisten

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

enum class LoginPhase { GUEST, CHECKING, WAITING, AUTHENTICATED, EXPIRED, FAILED, CANCELLED }

data class ScreenState(
    val startupReady: Boolean = true, val recommendationsCached: Boolean = false,
    val metadata: Map<String, Video> = emptyMap(),
    val folderPreviews: Map<Long, List<FavoriteItem>> = emptyMap(),
    val followingCount: Long? = null,
    val foldersLoaded: Boolean = false,
    val error: String? = null,
    val loginPhase: LoginPhase = LoginPhase.CHECKING,
    val searchBusy: Boolean = false,
    val pendingFavorite: Boolean = false,
    val favoriteTarget:Video?=null,
    val defaultFolderPrompt:Boolean=false,
    val currentFavoriteBvid:String?=null,val currentFavoriteFolder:Long?=null,val currentFavoritePresent:Boolean?=null,
    val mutationPending: MutationRecord? = null,
    val mutationStatus: String = "",
    val bookmarks: List<CollectionSnapshot> = emptyList(),
    val sourceFollowed: Boolean? = null,
    val sourceAdded: Set<String> = emptySet(),
    val sourceHeard: Set<String> = emptySet(),
    val collectionFilter: String = "",
    val sourceCached: Boolean = false,
    val refreshedAt: Long = 0,
    val entitlement: EntitlementSnapshot? = null,
    val settings: UserSettings = UserSettings(),
    val downloads: List<AudioDownload> = emptyList(),
    val diagnosticPreview: String? = null,
    val durationMs: Long = 0,
    val queueVersion: Long = 0,
    val canSeek: Boolean = false,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
    val canEditQueue: Boolean = false,
    val playbackIssue: PlaybackIssue? = null,
    val busy: Boolean = false,
    val collectionPreparing: Boolean = false,
    val collectionProgress: String = "",
    val message: String = "",
    val account: Account? = null,
    val accountChecked: Boolean = false,
    val folders: List<FavoriteFolder> = emptyList(),
    val folder: FavoriteFolder? = null,
    val favorites: List<FavoriteItem> = emptyList(),
    val hasMore: Boolean = false,
    val video: Video? = null,
    val history: List<LocalHistoryEntry> = emptyList(),
    val liveHistory: List<LiveHistoryEntry> = emptyList(),
    val speed: Float = 1f, val timerRemainingMs: Long = 0, val pauseReason: PauseReason = PauseReason.UNKNOWN,
    val resume: ResumeSnapshot? = null,
    val playingTitle: String = "尚未播放",
    val positionMs: Long = 0,
    val playing: Boolean = false,
    val playRequested: Boolean = false,
    val buffering: Boolean = false,
    val connected: Boolean = false,
    val queue: List<QueueEntry> = emptyList(),
    val mode: PlayMode = PlayMode.SEQUENTIAL,
    val currentId: String? = null,
    val search: SearchPage? = null,
    val searchKeyword: String = "",
    val searchAssist: SearchAssistView = SearchAssistView(),
    val recommendations: List<Recommendation> = emptyList(),
    val recommendationCategory: HomeCategory = HomeCategory.ALL,
    val recommendationBusy: Boolean = false,
    val recommendationError: String? = null,
    val homeUps: List<UpProfile> = emptyList(), val homeUpsBusy: Boolean = false,
    val homeUpsLoaded: Boolean = false, val homeUpsError: String? = null,
    val popularMusic: List<PopularMusic> = emptyList(), val popularMusicBusy: Boolean = false,
    val popularMusicLoaded: Boolean = false, val popularMusicError: String? = null,
    val popularMusicMoreBusy:Boolean=false,val popularMusicHasMore:Boolean=true,val popularMusicMoreError:String?=null,
    val liveRoom: LiveRoom? = null,
    val liveStreams:List<LiveStream> = emptyList(),
    val liveRankings:List<LiveRanking> = emptyList(),val liveRankingBusy:Boolean=false,val liveRankingError:String?=null,
    val liveExperience:LiveExperience=LiveExperience(),
    val liveSummary: String = "",
    val audioSummary: String = "",
    val audio: AudioExperience = AudioExperience(), val output: OutputExperience = OutputExperience(),
    val subtitles: SubtitleView = SubtitleView(),
    val effects:EffectsView=EffectsView(),
    val lyrics: LyricsView = LyricsView(),
    val isLive: Boolean = false,
    val sources: SourceListPage? = null,
    val sourceContent: SourceContentPage? = null,
    val sourceListOwner: Long? = null,
    val upSearch:UpSearchPage?=null, val upSearchKeyword:String="",val upSearchBusy:Boolean=false,val upSearchError:String?=null,
    val upProfile:UpProfile?=null,val upOrder:UploadOrder=UploadOrder.NEWEST,val upQuery:String="",
    val upLoading:Boolean=false,val upProgress:String="",
    val library:LibraryPreferences=LibraryPreferences(),val managementSection:LibrarySection=LibrarySection.MINE,
    val managementSources:List<ContentSource> = emptyList(),val managementLoading:Boolean=false,val managementReady:Boolean=false,
    val folderCreationPending:Boolean=false,val folderCreationNote:String="",
    val collectionLinkReading:Boolean=false,
    val remoteHistory:RemoteHistoryView=RemoteHistoryView(),
    val videoDetails:VideoDetailsView=VideoDetailsView(),
    val messages:MessagesView=MessagesView(),
)


class MainViewModel(private val deps: AppDependencies) : ViewModel() {
    @androidx.media3.common.util.UnstableApi
    constructor(application: Application) : this((application as ListenApplication).dependencies())
    private val mutable = MutableStateFlow(ScreenState(startupReady=deps.home==null, recommendationBusy=deps.home!=null))
    val state = mutable.asStateFlow()
    private val accountKey get() = deps.accounts.session.value.stamp.account
    private var coinBalanceJob:Job?=null
    private var detailsJob:Job?=null
    private var detailsEpoch=0L
    private var detailsStamp:SessionStamp?=null
    fun closeVideoDetails(){detailsJob?.cancel();coinBalanceJob?.cancel();detailsEpoch++}
    private fun refreshCoinBalance(bvid:String) {
        coinBalanceJob?.cancel()
        val stamp=deps.accounts.session.value.stamp
        val authenticated=deps.accounts.session.value.status==SessionStatus.AUTHENTICATED
        if(state.value.videoDetails.bvid!=bvid)return
        mutable.value=mutable.value.copy(videoDetails=state.value.videoDetails.copy(coinBalance=null,coinBalanceLoading=authenticated))
        if(!authenticated)return
        val ticket=detailsEpoch
        coinBalanceJob=viewModelScope.launch {
            fun current()=ticket==detailsEpoch&&deps.accounts.session.value.stamp==stamp&&state.value.videoDetails.bvid==bvid
            try {
                val balance=deps.engagement?.coinBalance(stamp)
                if(current())mutable.value=mutable.value.copy(videoDetails=state.value.videoDetails.copy(coinBalance=balance,coinBalanceLoading=false))
            }catch(e:CancellationException){throw e}catch(_:Exception){
                if(current())mutable.value=mutable.value.copy(videoDetails=state.value.videoDetails.copy(coinBalance=null,coinBalanceLoading=false))
            }
        }
    }
    fun openVideoDetails(bvid:String) {
        if(Bvid.parse(bvid)!=bvid||state.value.isLive)return
        detailsJob?.cancel();val ticket=++detailsEpoch;val stamp=deps.accounts.session.value.stamp
        detailsStamp=stamp
        val prior=state.value.videoDetails.takeIf{it.bvid==bvid} ?: VideoDetailsView(bvid,video=state.value.metadata[bvid])
        mutable.value=mutable.value.copy(videoDetails=prior.copy(loading=true,error=null,relationError=null))
        refreshCoinBalance(bvid)
        detailsJob=viewModelScope.launch {
            fun current()=ticket==detailsEpoch&&deps.accounts.session.value.stamp==stamp
            try {
                val video=deps.content.video(bvid)
                if(video.bvid!=bvid)throw PlatformFailure("视频详情返回了其他稿件")
                deps.accounts.requireCurrent(stamp)
                if(!current())return@launch
                mutable.value=mutable.value.copy(metadata=state.value.metadata+(bvid to video),videoDetails=state.value.videoDetails.copy(video=video))
                val repository=deps.engagement
                val tags=try{repository?.tags(bvid).orEmpty()}catch(e:CancellationException){throw e}catch(_:Exception){prior.tags}
                if(!current())return@launch
                mutable.value=mutable.value.copy(videoDetails=state.value.videoDetails.copy(tags=tags))
                if(deps.accounts.session.value.status==SessionStatus.AUTHENTICATED) {
                    try {
                        val relations=repository?.relations(bvid)
                        deps.accounts.requireCurrent(stamp)
                        if(current())mutable.value=mutable.value.copy(videoDetails=state.value.videoDetails.copy(relations=relations,relationError=if(repository==null)"互动功能暂不可用" else null))
                    }catch(e:CancellationException){throw e}catch(e:Exception){if(current())mutable.value=mutable.value.copy(videoDetails=state.value.videoDetails.copy(relations=null,relationError=(e as? PlatformFailure)?.category ?: "互动状态读取失败"))}
                }else if(current())mutable.value=mutable.value.copy(videoDetails=state.value.videoDetails.copy(relations=null))
            }catch(e:CancellationException){throw e}catch(e:Exception){if(current())mutable.value=mutable.value.copy(videoDetails=state.value.videoDetails.copy(error=(e as? PlatformFailure)?.category ?: "详情读取失败，请重试"))}
            finally{if(current())mutable.value=mutable.value.copy(videoDetails=state.value.videoDetails.copy(loading=false))}
        }
    }
    private suspend fun handleUnavailableDefaultFolder(folder:Long?) {
        if(folder==null)return
        val preferences=deps.settings.current()
        if(preferences.defaultFavoriteFolders[accountKey]?.id!=folder)return
        val next=preferences.copy(defaultFavoriteFolders=preferences.defaultFavoriteFolders-accountKey)
        deps.settings.update(next)
        update{copy(settings=next,defaultFolderPrompt=true,currentFavoritePresent=null)}
        refreshFoldersNow()
    }
    fun engageVideo(bvid:String,owner:String,action:EngagementAction,amount:Int=0)=operation {
        if(owner!=accountKey||state.value.isLive||state.value.queue.firstOrNull{it.id==state.value.currentId}?.bvid!=bvid)throw PlatformFailure("账号或播放内容已变化，请重新打开详情")
        val target=state.value.videoDetails.takeIf{it.bvid==bvid}?.video ?: throw PlatformFailure("请先读取视频详情")
        val repository=deps.engagement ?: throw PlatformFailure("互动功能暂不可用")
        val default=if(action==EngagementAction.TRIPLE)deps.settings.current().defaultFavoriteFolders[owner] else null
        if(action==EngagementAction.TRIPLE&&default==null){
            update{copy(defaultFolderPrompt=true,videoDetails=videoDetails.copy(message="请先设置默认收藏夹，再次长按点赞完成三连"))}
            refreshFoldersNow();return@operation
        }
        try {
            val result=repository.change(operationStamp(),target,action,amount,default?.id)
            handleUnavailableDefaultFolder(result.unavailableFolder)
            val pending=deps.collections.mutation(owner)
            update{copy(videoDetails=videoDetails.copy(message=result.message),mutationPending=pending,currentFavoriteBvid=null,currentFavoritePresent=null)}
        }catch(e:CancellationException){throw e}catch(e:Exception){
            val pending=deps.collections.mutation(owner)
            update{copy(videoDetails=videoDetails.copy(message=(e as? PlatformFailure)?.category ?: "操作结果待核对"),mutationPending=pending)};throw e
        }finally{if(action!=EngagementAction.LIKE)refreshCoinBalance(bvid)}
    }
    fun reconcileEngagement()=operation {
        val repository=deps.engagement ?: throw PlatformFailure("互动功能暂不可用")
        val result=repository.reconcile(operationStamp())
        handleUnavailableDefaultFolder(result.unavailableFolder)
        val pending=deps.collections.mutation(accountKey)
        update{copy(videoDetails=videoDetails.copy(message=result.message),mutationPending=pending)}
        state.value.videoDetails.bvid?.let(::refreshCoinBalance)
    }
    private var startupLocalReady = false
    private var liveRankJob:Job?=null
    private var liveRankStamp:SessionStamp?=null
    private var liveRoomStamp:SessionStamp?=null
    private var liveVisibleOwner:String?=null
    private var liveReturnRoom:Long?=null
    private var liveRankEpoch=0L
    private var startupVisualReady = false
    private var startupEpoch = 0L
    private var historyJob: Job? = null
    private var historyPollJob:Job?=null
    private fun pollHistory() {
        if(!foreground||!state.value.remoteHistory.enabled||historyPollJob?.isActive==true||deps.accounts.session.value.status!=SessionStatus.AUTHENTICATED)return
        val sync=deps.historySync ?: return
        val stamp=deps.accounts.session.value.stamp
        historyPollJob=viewModelScope.launch {
            while(foreground&&deps.accounts.session.value.stamp==stamp) {
                try{sync.refresh(stamp)}catch(e:CancellationException){throw e}catch(_:Exception){}
                delay(30000)
            }
        }
    }
    fun refreshHistorySync()=operation { deps.retryAccount();deps.historySync?.refresh(operationStamp()) }
    fun setHistorySync(enabled:Boolean,expectedAccount:String=accountKey,confirmation:String?=null)=operation {
        if(accountKey!=expectedAccount)throw PlatformFailure("账号已变化，请重新确认同步设置")
        if(enabled&&confirmation?.trim()!="是")throw PlatformFailure("请输入“是”确认开启同步")
        val sync=deps.historySync ?: throw PlatformFailure("同步功能不可用")
        deps.playback.pause();deps.playback.flush();deps.playback.forget(null)
        sync.setEnabled(operationStamp(),enabled)
        val view=sync.observe(accountKey).first()
        if(enabled)update{copy(remoteHistory=view,message="已切换为 B站同步记录，本机旧历史已清空")}
        else {
            if(deps.accounts.session.value.stamp!=operationStamp())throw PlatformFailure("账号会话已变化")
            mutable.value=mutable.value.copy(remoteHistory=view,message="已切换为本机记录，之后的收听按本机保存设置记录")
        }
        if(!enabled)historyPollJob?.cancel() else pollHistory()
    }
    private class Op(val id: Long, val stamp: SessionStamp?) : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<Op> }
    private var operationJob: Job? = null
    private var epoch = 0L
    private var searchJob: Job? = null
    private var upSearchJob:Job?=null
    private var upSearchEpoch=0L
    private var recommendationJob: Job? = null
    private var recommendationEpoch = 0L
    private var homeUpJob: Job? = null
    private var homeUpEpoch = 0L
    private var popularMusicJob: Job? = null
    private var popularMusicMoreJob: Job? = null
    private var popularMusicEpoch = 0L
    private val recommendationPages = mutableMapOf<HomeCategory,List<Recommendation>>()
    private val recommendationReads = mutableMapOf<HomeCategory,Deferred<List<Recommendation>>>()
    private val recommendationLimit = Semaphore(2)
    private var recommendationPreloadJob:Job?=null
    private var recommendationsPreloaded=false
    private var searchEpoch = 0L
    private val searchAssistController=deps.searchAssist?.let{SearchAssistController(viewModelScope,it,deps.clock)}
    private val messagesController=deps.messages?.let{MessagesController(viewModelScope,it,deps.accounts)}
    fun openMessages(){messagesController?.show()}
    fun closeMessages(){messagesController?.hide()}
    fun selectMessages(category:MessageCategory){messagesController?.select(category)}
    fun openConversation(session:MessageSession){messagesController?.open(session)}
    fun backMessages(){messagesController?.back()}
    fun moreMessages(){messagesController?.more()}
    fun refreshMessages(){viewModelScope.launch{refreshMessagesNow()}}
    suspend fun refreshMessagesNow(){deps.retryAccount();messagesController?.refresh();messagesController?.state?.first{!it.loading}}
    fun searchHints(visible:Boolean,query:String){if(visible)searchAssistController?.show(query) else searchAssistController?.hide()}
    fun retrySearchHot(){viewModelScope.launch{deps.retryAccount();searchAssistController?.retryHot()}}
    private var foreground = false
    private var readerVisible = false
    private var precisePositionApplied = false
    private fun syncPositionSampling() {
        val enabled = foreground && readerVisible
        if (enabled != precisePositionApplied) {
            precisePositionApplied = enabled
            deps.playback.precisePosition(enabled)
        }
    }
    fun timedTextVisible(visible: Boolean) { readerVisible = visible; syncPositionSampling() }
    private var refreshRequested = false
    private var rightsReturnPending = false
    private var subtitlesVisible = false
    private var lyricsVisible = false
    private val lyricsController=deps.lyrics?.let { LyricsController(viewModelScope,it,deps.accounts) }
    private val subtitleController = deps.subtitles?.let { SubtitleController(viewModelScope,it,deps.accounts,deps.settings,deps.clock) }
    private fun syncSubtitles() {
        val p=deps.playback.state.value
        val current=p.queue.firstOrNull { it.id==p.currentId }
        if(subtitlesVisible)subtitleController?.show(current?.takeUnless { p.live || it.cid<=0 }?.let { VideoRef(it.bvid,it.cid,it.part) },deps.accounts.session.value.stamp)
        if(lyricsVisible)lyricsController?.show(current?.takeUnless { p.live || it.cid<=0 }?.let { VideoRef(it.bvid,it.cid,it.part) },deps.accounts.session.value.stamp)
    }
    fun openSubtitles() {subtitlesVisible=true;syncSubtitles()}
    fun closeSubtitles() {subtitlesVisible=false;subtitleController?.hide()}
    fun retrySubtitles() {viewModelScope.launch {deps.retryAccount();subtitleController?.retry()}}
    fun selectSubtitle(id: String) {subtitleController?.select(id)}
    fun subtitleMode(line: Boolean) {viewModelScope.launch {deps.settings.update(deps.settings.current().copy(subtitleLineMode=line))}}
    fun openLyrics(){lyricsVisible=true;syncSubtitles()}
    fun closeLyrics(){lyricsVisible=false;lyricsController?.hide()}
    fun searchLyrics(name:String,artist:String){lyricsController?.search(name,artist)}
    fun selectLyrics(id:Long){lyricsController?.select(id)}
    fun offsetLyrics(value:Long){lyricsController?.offset(value)}
    fun confirmLyrics(){lyricsController?.confirm()}
    private var lastRefresh = Long.MIN_VALUE
    private suspend fun operationStamp(): SessionStamp = currentCoroutineContext()[Op]?.stamp ?: deps.accounts.session.value.stamp
    private var favoritePage = 0
    private var sourceFolder: Long? = null
    private var selectedSource: SourceRef? = null
    private var selectedOrigin = HistoryOrigin.LINK
    private val metadataPending = mutableSetOf<String>()
    private val metadataLimit = kotlinx.coroutines.sync.Semaphore(3)
    private val previewPending = mutableSetOf<Long>()
    fun loadFolderPreview(folder:FavoriteFolder) {
        if(state.value.folderPreviews.containsKey(folder.id) || !previewPending.add(folder.id)) return
        val stamp=deps.accounts.session.value.stamp
        viewModelScope.launch {
            metadataLimit.acquire()
            try { val page=deps.favorites.page(folder.id,1);deps.accounts.requireCurrent(stamp)
                mutable.value=mutable.value.copy(folderPreviews=mutable.value.folderPreviews+(folder.id to page.items.take(3)))
            } catch(e:CancellationException){throw e} catch(_:Exception){} finally{metadataLimit.release();previewPending.remove(folder.id)}
        }
    }
    fun loadMetadata(bvid: String) {
        if(Bvid.parse(bvid) != bvid || state.value.metadata.containsKey(bvid) || !metadataPending.add(bvid)) return
        val stamp = deps.accounts.session.value.stamp
        viewModelScope.launch {
            metadataLimit.acquire()
            try {
                val video = deps.content.video(bvid); deps.accounts.requireCurrent(stamp)
                mutable.value = mutable.value.copy(metadata = (mutable.value.metadata + (bvid to video)).entries.toList().takeLast(100).associate { it.toPair() })
                deps.home?.metadata(stamp.account,video)
            } catch(e: CancellationException) { throw e } catch(_: Exception) { /* A missing cover must not stop listening. */ }
            finally { metadataLimit.release(); metadataPending.remove(bvid) }
        }
    }
    fun loadProfile() = operation {
        if(state.value.account != null) {
            refreshFoldersNow()
            val count = deps.content.followingCount()
            update { copy(followingCount = count) }
        }
    }
    fun dismissError() { mutable.value = mutable.value.copy(error = null) }
    init {
        messagesController?.let{controller->viewModelScope.launch{controller.state.collect{mutable.value=mutable.value.copy(messages=it)}}}
        deps.historySync?.let{sync->viewModelScope.launch {
            deps.accounts.session.collectLatest{session->
                historyPollJob?.cancel();historyPollJob=null
                mutable.value=mutable.value.copy(remoteHistory=RemoteHistoryView())
                sync.observe(session.stamp.account).collect{view->
                    if(deps.accounts.session.value.stamp==session.stamp){mutable.value=mutable.value.copy(remoteHistory=view);if(view.enabled)pollHistory() else historyPollJob?.cancel()}
                }
            }
        }}
        searchAssistController?.let{controller->viewModelScope.launch { controller.state.collect {mutable.value=mutable.value.copy(searchAssist=it)} }}
        viewModelScope.launch { deps.playback.state.collect { p ->
            mutable.value = mutable.value.copy(playingTitle = p.title, positionMs = p.positionMs, playing = p.playing,
                playRequested = p.requested, buffering = p.buffering, connected = p.connected, queue = p.queue,
                mode = p.mode, currentId = p.currentId, isLive = p.live, durationMs = p.durationMs, queueVersion = p.queueVersion,
                canSeek = p.capabilities.seekable, canNext = p.canNext, canPrevious = p.canPrevious, canEditQueue = p.canEditQueue, playbackIssue = p.issue, speed = p.speed, timerRemainingMs = p.timerRemainingMs, pauseReason = p.pauseReason,
                audio=p.audio,output=p.output,effects=p.effects,liveExperience=p.liveExperience)
            syncSubtitles()
            if (p.error != null) { deps.diagnostics.record(DiagnosticEvent.PLAYBACK_FAILED); mutable.value = mutable.value.copy(message = "播放失败（${p.error}）") }
        } }
        subtitleController?.let { controller -> viewModelScope.launch { controller.state.collect { mutable.value=mutable.value.copy(subtitles=it) } } }
        lyricsController?.let { controller -> viewModelScope.launch { controller.state.collect { mutable.value=mutable.value.copy(lyrics=it) } } }
        viewModelScope.launch { deps.accounts.session.collect { session ->
            val liveOwnerChanged=liveVisibleOwner!=null && liveVisibleOwner!=session.stamp.account
            if(liveOwnerChanged || liveRankStamp!=null && liveRankStamp!=session.stamp || liveRoomStamp!=null && liveRoomStamp!=session.stamp) {
                val ownerChanged=liveOwnerChanged
                liveRankJob?.cancel();liveRankEpoch++;liveRankStamp=null
                liveRoomStamp=null
                if(ownerChanged){liveReturnRoom=null;liveVisibleOwner=null}
                mutable.value=mutable.value.copy(liveRoom=if(ownerChanged)null else state.value.liveRoom,
                    liveSummary=if(ownerChanged)"" else state.value.liveSummary,
                    liveStreams=if(ownerChanged)emptyList() else state.value.liveStreams,
                    liveRankings=if(ownerChanged)emptyList() else state.value.liveRankings,liveRankingBusy=false,liveRankingError=null)
            }
            if(detailsStamp!=null&&detailsStamp!=session.stamp){closeVideoDetails();detailsStamp=null;mutable.value=mutable.value.copy(videoDetails=VideoDetailsView())}
            subtitleController?.identityChanged(session.stamp);lyricsController?.identityChanged(session.stamp);syncSubtitles() } }
        viewModelScope.launch { deps.settings.settings.collect { applySettings(it) } }
        deps.downloads?.let { files -> viewModelScope.launch { combine(files.records,deps.accounts.session){rows,session->rows.filter{it.account==session.stamp.account}}
            .collect{mutable.value=mutable.value.copy(downloads=it)} } }
        checkAccount()
        if(deps.home!=null) prepareHome()
    }
    private fun revealHome() {
        if(startupLocalReady && startupVisualReady) mutable.value=mutable.value.copy(startupReady=true)
    }
    private fun applySettings(settings:UserSettings) {
        val changed=state.value.settings.musicRecommendations!=settings.musicRecommendations
        mutable.value=mutable.value.copy(settings=settings)
        if(!changed)return
        popularMusicJob?.cancel();popularMusicMoreJob?.cancel();popularMusicEpoch++
        homeUpJob?.cancel();homeUpEpoch++
        mutable.value=mutable.value.copy(popularMusic=emptyList(),popularMusicBusy=false,popularMusicLoaded=false,
            popularMusicError=null,popularMusicMoreBusy=false,popularMusicHasMore=true,popularMusicMoreError=null,
            homeUps=emptyList(),homeUpsBusy=false,homeUpsLoaded=false,homeUpsError=null)
        if(state.value.accountChecked){loadPopularMusic();loadHomeUps()}
    }
    private fun prepareHome() {
        val owner=accountKey; val ticket=epoch;startupEpoch=ticket
        // This deadline is independent of any network or image coroutine.
        viewModelScope.launch { delay(1800);mutable.value=mutable.value.copy(startupReady=true) }
        viewModelScope.launch {
            try {
                val settings=deps.settings.current()
                applySettings(settings)
                deps.history.prune(owner,settings.historyPolicy(),deps.clock.nowMs())
                val rows=deps.history.observe(owner).first()
                val lives=deps.history.observeLive(owner).first()
                val saved=deps.snapshots.load(owner)?.queue
                val cache=try { deps.home?.read(owner) } catch(e:CancellationException){throw e}catch(_:Exception){null}
                if(accountKey!=owner || epoch!=ticket)return@launch
                val existing=state.value
                mutable.value=existing.copy(settings=settings,history=rows,liveHistory=lives,resume=existing.resume ?: saved,
                    metadata=cache?.metadata.orEmpty().filterKeys{key->rows.any{it.video.bvid==key}}+existing.metadata,
                    recommendations=existing.recommendations.ifEmpty { cache?.recommendations.orEmpty().take(5) },
                    popularMusic=existing.popularMusic.ifEmpty { if(settings.musicRecommendations)PopularMusic.valid(cache?.popularMusic.orEmpty()).take(12) else emptyList() },
                    recommendationsCached=existing.recommendations.isEmpty() && !cache?.recommendations.isNullOrEmpty())
                if(!cache?.recommendations.isNullOrEmpty()) {
                    // Cached content is usable even while its cover is still downloading.
                    startupVisualReady=true
                }
                startupLocalReady=true;revealHome()
                if(!cache?.recommendations.isNullOrEmpty())warmHome()
            }catch(e:CancellationException){throw e}
            catch(_:Exception){startupLocalReady=true;revealHome()}
        }
    }
    private suspend fun warmHome() {
        val s=state.value
        val recent=s.history.take(3).mapNotNull{s.metadata[it.video.bvid]?.cover}
        deps.home?.warm(listOfNotNull(s.recommendations.firstOrNull()?.cover)+recent)
    }
    private suspend fun update(block: ScreenState.() -> ScreenState) {
        val op = currentCoroutineContext()[Op]
        if (op != null && op.id != epoch) throw CancellationException("Superseded operation")
        op?.stamp?.let(deps.accounts::requireCurrent)
        mutable.value = mutable.value.block()
    }
    private fun operation(sessionChange: Boolean = false, action: suspend () -> Unit) {
        if (state.value.busy && !sessionChange) return
        if(sessionChange){popularMusicMoreJob?.cancel();mutable.value=mutable.value.copy(popularMusicMoreBusy=false,popularMusicHasMore=true,popularMusicMoreError=null)}
        if (sessionChange) { closeVideoDetails();mutable.value=mutable.value.copy(videoDetails=VideoDetailsView());operationJob?.cancel(); searchJob?.cancel();favoriteReadJob?.cancel();favoriteReadEpoch++; upSearchJob?.cancel();upSearchEpoch++; recommendationJob?.cancel(); recommendationEpoch++;recommendationPages.clear();recommendationPreloadJob?.cancel();recommendationsPreloaded=false;recommendationReads.values.toList().forEach{it.cancel()};recommendationReads.clear();homeUpJob?.cancel();homeUpEpoch++;popularMusicJob?.cancel();popularMusicEpoch++;mutable.value=mutable.value.copy(popularMusic=emptyList(),popularMusicBusy=false,popularMusicLoaded=false,popularMusicError=null,recommendationBusy=false,upSearchBusy=false,homeUps=emptyList(),homeUpsBusy=false,homeUpsLoaded=false,homeUpsError=null,currentFavoriteBvid=null,currentFavoriteFolder=null,currentFavoritePresent=null); searchEpoch++; historyJob?.cancel() }
        val id = ++epoch
        val stamp = if (sessionChange) null else deps.accounts.session.value.stamp
        mutable.value = mutable.value.copy(busy = true, error = null, collectionPreparing = false, collectionProgress = "",upLoading=false,upProgress="")
        operationJob = viewModelScope.launch(Op(id, stamp)) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: PlatformFailure) {
                deps.diagnostics.record(DiagnosticEvent.READ_FAILED, failure.kind())
                if (id == epoch) mutable.value = mutable.value.copy(message = failure.category, error = failure.category)
            }
            catch (_: Exception) { if (id == epoch) mutable.value = mutable.value.copy(message = "操作未完成，请检查网络或本机存储。", error = "操作未完成，请检查网络或本机存储。") }
            finally { if (id == epoch) { mutable.value = mutable.value.copy(busy = false, collectionPreparing = false, collectionProgress = "",upLoading=false,upProgress="",collectionLinkReading=false); if (refreshRequested && foreground) { refreshRequested = false; foregrounded() } } }
        }
    }
    fun checkAccount() = operation(sessionChange = true) {
        update { copy(accountChecked = false, loginPhase = LoginPhase.CHECKING, searchBusy = false, recommendationBusy = deps.home!=null) }
        deps.retryAccount()
        try {
            val account = deps.accounts.verify()
            deps.onAccountConfirmed(accountKey)
            update { copy(account = account, accountChecked = true, loginPhase = if (account == null) LoginPhase.GUEST else LoginPhase.AUTHENTICATED) }
            lastRefresh=deps.clock.nowMs()
            if(deps.home!=null)loadRecommendations()
            loadLocal()
            if (deps.favorites.pending(accountKey) != null) update { copy(message = "上次收藏操作尚待核对，不会自动重试写入。") }
        } catch (e: PlatformFailure) {
            if (e.code == -101) {
                deps.playback.clear(); deps.onAccountConfirmed("guest")
                selectedSource = null; sourceFolder = null
                update { ScreenState(startupReady = startupReady, settings = settings, accountChecked = true, loginPhase = LoginPhase.EXPIRED, busy = true, connected = deps.playback.state.value.connected, message = "登录已失效，可继续游客收听") }
                loadLocal()
            } else { update { copy(loginPhase = LoginPhase.FAILED,recommendationBusy=false,recommendationError="暂时无法刷新，联网后可重试") }; loadLocal() }
            throw e
        }
    }
    /** A tap on a content row is an explicit play gesture. More actions retain the part picker. */
    fun playVideo(input: String, folder: Long? = null, source: SourceRef? = null, origin: HistoryOrigin = HistoryOrigin.LINK) = operation {
        if (!state.value.accountChecked) throw PlatformFailure("请先确认账号状态")
        val bvid = deps.content.resolve(input)
        val video = deps.content.video(bvid)
        val resolvedSource = source ?: folder?.let { SourceRef(SourceKind.OWN_FAVORITES,it,state.value.account?.id ?: throw PlatformFailure("请登录账户")) }
        resolvedSource?.let { deps.sources.content(it,state.value.account?.id) }
        deps.accounts.requireCurrent(operationStamp())
        // Flush the departing item before reading durable history; the service serializes writes.
        deps.playback.flush()
        val settings=deps.settings.current()
        val cloud=deps.historySync?.enabled(accountKey)==true
        val prior=if(settings.historyEnabled||cloud)deps.history.latestVideo(accountKey,bvid) else null
        val saved=if(prior!=null&&!cloud)deps.snapshots.load(accountKey)?.queue else null
        val savedEntry=saved?.entries?.firstOrNull{it.id==saved.currentId}
        val position=if(saved?.account==accountKey && savedEntry?.bvid==bvid && savedEntry.cid==prior?.video?.cid) saved.positionMs else prior?.positionMs ?: 0
        val start=VideoContinuation.prepare(video,prior?.takeIf{it.account==accountKey}?.video,position,settings.continuousParts,finished=prior?.finished==true)
        deps.accounts.requireCurrent(operationStamp())
        val entries = start.parts.map { QueueEntry(UUID.randomUUID().toString(),bvid,it.cid,it.number,
            if(video.parts.size > 1) "${video.title} · ${it.title}" else video.title,folder,resolvedSource,origin) }
        sourceFolder=folder;selectedSource=resolvedSource;selectedOrigin=origin
        deps.playback.replace(ResumeSnapshot(account=accountKey,entries=entries,order=entries.map { it.id },currentId=entries[start.currentIndex].id,positionMs=start.positionMs),true)
        update { copy(video=video,metadata=metadata+(bvid to video),message=when {
            start.missingPart->"上次的分 P 已不可用，已从 P1 开始播放"
            start.resumed->"已接着上次 P${start.parts[start.currentIndex].number} 的进度继续收听"
            else->"已开始播放；更多操作可选择分 P、收藏或加入队列"
        }) }
    }
    fun inspect(input: String, folder: Long? = null, source: SourceRef? = null, origin: HistoryOrigin = HistoryOrigin.LINK) {
        operation {
            update { copy(video = null, audioSummary = "", entitlement = null) }
            val bvid = deps.content.resolve(input)
            val result = deps.content.video(bvid)
            deps.accounts.requireCurrent(operationStamp())
            sourceFolder = folder; selectedSource = source; selectedOrigin = origin
            update { copy(video = result, metadata = metadata + (bvid to result), message = "已读取 ${result.parts.size} 个分 P。请选择播放范围。") }
        }
    }
    fun playPart(part: VideoPart?, all: Boolean = false) {
        val video = state.value.video ?: return
        val selected = if (all) video.parts else if (state.value.settings.continuousParts && part != null) video.parts.dropWhile { it.number != part.number } else listOfNotNull(part)
        val entries = selected.map { QueueEntry(UUID.randomUUID().toString(), video.bvid, it.cid, it.number,
            if (it.title.isBlank()) video.title else "${video.title} · ${it.title}", sourceFolder, selectedSource, selectedOrigin) }
        if (entries.isNotEmpty()) play(entries, 0)
    }
    private fun play(entries: List<QueueEntry>, position: Long) {
        if (!state.value.accountChecked || entries.isEmpty()) return
        playSnapshot(ResumeSnapshot(account = accountKey, entries = entries, order = entries.map { it.id }, currentId = entries.first().id, positionMs = position))
    }
    private fun playSnapshot(snapshot: ResumeSnapshot) = operation {
        if (!state.value.accountChecked || snapshot.account != accountKey) throw PlatformFailure("请先确认账号状态")
        snapshot.entries.mapNotNull { it.resolvedSource(snapshot.account) }.distinct().forEach { deps.sources.content(it, state.value.account?.id) }
        deps.accounts.requireCurrent(operationStamp())
        deps.playback.replace(snapshot, true)
        update { copy(message = "已更新播放队列。在线播放未启用持久音频缓存。") }
    }
    fun continueListening() = operation {
        val context = ResumeCoordinator(deps.snapshots, deps.accounts, deps.sources, deps.clock).prepare() ?: return@operation
        deps.accounts.requireCurrent(context.session)
        // Explicit user gesture is the only reason to turn a silent restore into playback.
        deps.playback.replace(context.snapshot.queue, true)
    }
    fun playHistory(row: LocalHistoryEntry) = operation {
        requirePlaybackIdentity()
        require(row.account == accountKey)
        row.source?.let { deps.sources.content(it, state.value.account?.id) }
        var position=row.positionMs
        val entries = if (state.value.settings.continuousParts||row.finished) {
            val video = deps.content.video(row.video.bvid)
            if (video.parts.none { it.cid == row.video.cid }) throw PlatformFailure("原分 P 已不可用；历史记录保留")
            val plan=VideoContinuation.prepare(video,row.video,row.positionMs,state.value.settings.continuousParts,row.finished)
            position=plan.positionMs
            plan.parts.drop(plan.currentIndex).map { part -> row.entry(UUID.randomUUID().toString()).copy(cid=part.cid,part=part.number,title="${video.title} · ${part.title}") }
        } else listOf(row.entry(UUID.randomUUID().toString()))
        deps.accounts.requireCurrent(operationStamp())
        deps.playback.replace(ResumeSnapshot(account=accountKey,entries=entries,order=entries.map { it.id },currentId=entries.first().id,positionMs=position),true)
    }
    private fun requirePlaybackIdentity() {
        if (!state.value.accountChecked || deps.accounts.session.value.status == SessionStatus.UNVERIFIED)
            throw PlatformFailure("当前账号尚未联网验证；本机历史和进度保留，请联网后重试账号检查")
    }
    fun setSpeed(value: Float) = operation { deps.playback.speed(value) }
    fun refreshAudio() = operation { requirePlaybackIdentity(); deps.playback.audioQuality() }
    fun chooseAudio(choice: AudioChoice) = operation { requirePlaybackIdentity(); deps.playback.audioQuality(choice.checked()) }
    fun chooseOutput(id: Int?) = operation { deps.playback.audioOutput(id) }
    fun chooseEffects(value:EffectsSettings)=operation {deps.playback.audioEffects(value.checked())}
    fun retryEffects()=operation {deps.playback.audioEffects()}
    fun openOfficialRights(canLaunch:(String)->Boolean,launch:(String)->Unit) = operation {
        val url="https://account.bilibili.com/account/big"
        if(!canLaunch(url)) throw PlatformFailure("没有可打开官方权益页的应用，可使用浏览器访问 B 站账户中心")
        deps.playback.pause(PauseReason.EXTERNAL_VIDEO);deps.playback.flush()
        launch(url);rightsReturnPending=true
        update { copy(message="返回后重新检查账户和实际音轨；打开官方页面不代表权益已变化") }
    }
    fun setSleepTimer(minutes: Int) = operation { require(minutes in 0..180); deps.playback.sleepTimer(minutes * 60) }
    fun exitListening(done: () -> Unit) = operation { deps.playback.exitListening(); done() }
    fun playLiveHistory(row: LiveHistoryEntry) = operation {
        requirePlaybackIdentity()
        require(row.account == accountKey)
        liveRoomStamp=operationStamp()
        liveVisibleOwner=liveRoomStamp?.account
        val room = deps.live.room(row.roomId)
        deps.accounts.requireCurrent(operationStamp())
        val streams=if(room.state==LiveRoomStatus.LIVE)deps.live.streams(room.roomId).filter(LiveStreams::supported) else emptyList()
        update { copy(liveRoom = room,liveStreams=streams,message="已检查房间${room.state.label}，尚未播放") }
    }
    fun deleteLiveHistory(row: LiveHistoryEntry) = operation {
        require(row.account == accountKey)
        deps.playback.pause(); deps.playback.flush(); deps.playback.forget(null)
        if(deps.historySync?.enabled(accountKey)==true)deps.historySync.delete(operationStamp(),"live_${row.roomId}") else deps.history.deleteLive(accountKey, row.roomId)
    }
    fun changeMode(mode: PlayMode) = operation { deps.playback.mode(mode) }
    fun toggle() = deps.playback.toggle()
    fun next() = deps.playback.next()
    fun previous() = deps.playback.previous()
    fun seekBy(delta: Long) = deps.playback.seekBy(delta)
    fun seekTo(positionMs: Long) = deps.playback.seekTo(positionMs)
    fun backgrounded() { foreground = false; historyPollJob?.cancel();historyPollJob=null;syncPositionSampling(); viewModelScope.launch { try { deps.playback.flush() } catch (e: CancellationException) { throw e } catch (_: Exception) { deps.diagnostics.record(DiagnosticEvent.STORAGE_FAILED) } } }
    fun foregrounded(force: Boolean = false) {
        foreground = true
        favoriteReadJob?.cancel();favoriteReadEpoch++
        mutable.value=mutable.value.copy(currentFavoritePresent=null)
        state.value.currentFavoriteBvid?.let(::readCurrentFavorite)
        pollHistory()
        syncPositionSampling()
        liveReturnRoom?.let{room->
            liveReturnRoom=null
            val owner=deps.accounts.session.value.stamp.account
            viewModelScope.launch {
                yield()
                val stamp=deps.accounts.session.first{it.status!=SessionStatus.UNVERIFIED}.stamp
                if(stamp.account!=owner)return@launch
                try {
                    val info=deps.live.room(room);deps.accounts.requireCurrent(stamp)
                    liveRoomStamp=stamp
                    liveVisibleOwner=stamp.account
                    mutable.value=mutable.value.copy(liveRoom=info,message="房间${info.state.label}，保持暂停；请手动继续收听")
                }catch(e:CancellationException){throw e}catch(_:Exception){if(deps.accounts.session.value.stamp==stamp)mutable.value=mutable.value.copy(message="暂时无法检查直播房间，保持暂停")}
            }
        }
        if(rightsReturnPending) {
            if(state.value.busy) {refreshRequested=true;return}
            rightsReturnPending=false
            operation(sessionChange=true) {
                val old=accountKey
                val account=try { deps.accounts.verify() } catch(e:PlatformFailure) {
                    if(e.code == -101) {
                        deps.playback.clear();deps.onAccountConfirmed("guest")
                        update { copy(account=null,accountChecked=true,loginPhase=LoginPhase.EXPIRED,entitlement=null,message="登录已失效，原记录保留，可继续游客使用") }
                        loadLocal()
                    }
                    throw e
                }
                deps.onAccountConfirmed(accountKey)
                if(old!=accountKey) { deps.playback.clear();loadLocal() }
                update { copy(account=account,accountChecked=true,loginPhase=if(account==null)LoginPhase.GUEST else LoginPhase.AUTHENTICATED,entitlement=null) }
                if(old==accountKey && state.value.queue.isNotEmpty() && !state.value.isLive) deps.playback.audioQuality()
                update { copy(message="已重新检查账户和实际音轨，保持暂停；请手动继续收听") }
            }
            return
        }
        if (!state.value.accountChecked || state.value.loginPhase == LoginPhase.WAITING) return
        if (!force && lastRefresh != Long.MIN_VALUE && deps.clock.nowMs() - lastRefresh < 30000) return
        if (state.value.busy) { refreshRequested = true; return }
        lastRefresh = deps.clock.nowMs()
        operation(sessionChange = true) {
            val old = accountKey
            try {
                val account = deps.accounts.verify()
                if (old != accountKey) { deps.playback.clear(); selectedSource = null; sourceFolder = null; update { ScreenState(startupReady = startupReady, settings = settings, busy = true, connected = deps.playback.state.value.connected) } }
                deps.onAccountConfirmed(accountKey)
                update { copy(account = account, accountChecked = true, loginPhase = if(account == null) LoginPhase.GUEST else LoginPhase.AUTHENTICATED, entitlement = null) }
                loadLocal()
                if (account != null) {
                    refreshFoldersNow()
                    if(state.value.sources != null && state.value.sourceListOwner == null) {
                        val followed = deps.sources.followed(account.id)
                        update { copy(sources = followed) }
                    }
                    state.value.folder?.let { openFolderNow(it, false) }
                    state.value.sourceContent?.source?.ref?.let { openSourceNow(it, false) }
                }
            } catch (e: PlatformFailure) {
                if (e.code == -101) {
                    deps.playback.clear(); deps.onAccountConfirmed("guest")
                    update { ScreenState(startupReady = startupReady, settings = settings, busy = true, accountChecked = true, loginPhase = LoginPhase.EXPIRED, message = "登录已失效，可继续游客使用") }
                    loadLocal()
                } else { update { copy(accountChecked = deps.accounts.session.value.status != SessionStatus.UNVERIFIED, loginPhase = LoginPhase.FAILED) }; loadLocal() }
                throw e
            }
        }
    }
    fun networkAvailable() { if (foreground) foregrounded() }
    fun editQueue(edit: QueueEdit) = operation {
        deps.playback.edit(edit, state.value.queueVersion); loadLocal()
    }
    fun enqueuePart(part: VideoPart, next: Boolean) = operation {
        val video = state.value.video ?: return@operation
        val entry = QueueEntry(UUID.randomUUID().toString(), video.bvid, part.cid, part.number, "${video.title} · ${part.title}", sourceFolder, selectedSource, selectedOrigin)
        entry.resolvedSource(accountKey)?.let { deps.sources.content(it, state.value.account?.id) }
        deps.accounts.requireCurrent(operationStamp())
        if (state.value.queue.isEmpty() && !state.value.isLive) deps.playback.replace(ResumeSnapshot(account = accountKey, entries = listOf(entry), order = listOf(entry.id), currentId = entry.id, positionMs = 0), false)
        else deps.playback.edit(QueueEdit.Add(listOf(entry), next), state.value.queueVersion)
        update { copy(message = "已更新本机队列，远端收藏保持不变。") }
    }
    fun refreshResume() = operation { deps.playback.flush(); loadLocal() }
    private val sharedInputMutable = MutableStateFlow<String?>(null)
    val sharedInput = sharedInputMutable.asStateFlow()
    fun receiveShare(input: String?) { sharedInputMutable.value = input?.take(8193) }
    fun dismissShare() { sharedInputMutable.value = null }
    fun sharedVideo(video: Video, part: VideoPart, action: Int) {
        if (state.value.busy || !state.value.accountChecked) return
        require(part in video.parts && action in 0..2)
        sourceFolder = null; selectedSource = null; selectedOrigin = HistoryOrigin.SHARE
        mutable.value = mutable.value.copy(video=video,metadata=state.value.metadata+(video.bvid to video))
        when(action){0->playPart(part);1->enqueuePart(part,true);else->enqueuePart(part,false)}
        dismissShare()
    }
    fun sharedLive(room: LiveRoom) {
        if(state.value.busy || !state.value.accountChecked) return
        mutable.value=mutable.value.copy(liveRoom=room)
        inspectLive(room.roomId.toString());dismissShare()
    }
    fun restoreVod() = operation {
        val context = ResumeCoordinator(deps.snapshots,deps.accounts,deps.sources,deps.clock).prepare() ?: throw PlatformFailure("没有保留的点播队列")
        deps.accounts.requireCurrent(context.session)
        deps.playback.replace(context.snapshot.queue,false);loadLocal()
        update{copy(message="已返回原点播队列并保持暂停")}
    }
    fun prepareLogin() {
        deps.playback.pause(); operationJob?.cancel(); searchJob?.cancel(); epoch++; searchEpoch++
        mutable.value = mutable.value.copy(busy = false, searchBusy = false, loginPhase = LoginPhase.WAITING, message = "请在官方页面完成登录")
    }
    fun loginCancelled() { mutable.value = mutable.value.copy(loginPhase = LoginPhase.CANCELLED, message = "已取消登录，可继续当前页面") }
    private var promptedFavoriteAccount:String?=null
    private var favoriteReadJob:Job?=null
    private var favoriteReadEpoch=0L
    fun ensureDefaultFavoriteFolder() {
        val owner=state.value.account?.id?.toString() ?: return
        if(!state.value.accountChecked || promptedFavoriteAccount==owner)return
        operation {
            promptedFavoriteAccount=owner
            if(deps.settings.current().defaultFavoriteFolders[owner]==null) {
                update{copy(defaultFolderPrompt=true)};refreshFoldersNow()
            }
        }
    }
    fun openDefaultFavoriteFolder()=operation {
        if(state.value.account==null)throw PlatformFailure("请先登录账户")
        update{copy(defaultFolderPrompt=true)};refreshFoldersNow()
    }
    fun dismissDefaultFavoriteFolder(){mutable.value=mutable.value.copy(defaultFolderPrompt=false)}
    private suspend fun saveDefaultFavoriteFolder(folder:FavoriteFolder) {
        val stamp=operationStamp();val preferences=deps.settings.current()
        deps.accounts.requireCurrent(stamp)
        val next=preferences.copy(defaultFavoriteFolders=preferences.defaultFavoriteFolders+(stamp.account to DefaultFavoriteFolder(folder.id,folder.title)))
        deps.settings.update(next)
        update{copy(settings=next,defaultFolderPrompt=false,currentFavoriteBvid=null,currentFavoriteFolder=null,currentFavoritePresent=null,message="默认收藏夹已设为「${folder.title}」")}
    }
    fun selectDefaultFavoriteFolder(folder:FavoriteFolder)=operation {
        val account=state.value.account ?: throw PlatformFailure("请先登录账户")
        val verified=deps.favorites.folders(account.id).firstOrNull{it.id==folder.id} ?: throw PlatformFailure("收藏夹已不可用，请刷新后重新选择")
        saveDefaultFavoriteFolder(verified)
    }
    private suspend fun favoriteVideo(bvid:String):Video {
        val video=state.value.metadata[bvid] ?: state.value.video?.takeIf{it.bvid==bvid} ?: deps.content.video(bvid)
        if(video.bvid!=bvid || video.aid<=0)throw PlatformFailure("未能核对当前视频，请稍后再试")
        deps.accounts.requireCurrent(operationStamp());return video
    }
    fun chooseFavorite(bvid:String)=operation {
        val target=favoriteVideo(bvid)
        update{copy(favoriteTarget=target,pendingFavorite=true,mutationStatus="")}
        if(state.value.account!=null)refreshFoldersNow()
    }
    fun toggleDefaultFavorite(bvid:String,report:(String)->Unit={})=operation {
        try {
            val account=state.value.account
            if(account==null){val target=favoriteVideo(bvid);update{copy(favoriteTarget=target,pendingFavorite=true)};return@operation}
            val selected=deps.settings.current().defaultFavoriteFolders[account.id.toString()]
            if(selected==null){update{copy(defaultFolderPrompt=true)};refreshFoldersNow();return@operation}
            favoriteReadJob?.cancel();favoriteReadEpoch++
            val target=favoriteVideo(bvid)
            val folders=deps.favorites.folders(account.id,target.aid)
            deps.accounts.requireCurrent(operationStamp())
            val folder=folders.firstOrNull{it.id==selected.id}
            if(folder==null) {
                val preferences=deps.settings.current();val next=preferences.copy(defaultFavoriteFolders=preferences.defaultFavoriteFolders-account.id.toString())
                deps.settings.update(next);update{copy(settings=next,folders=folders,foldersLoaded=true,defaultFolderPrompt=true,currentFavoritePresent=null)}
                report("默认收藏夹已不可用，请重新选择");return@operation
            }
            if(folder.contains==null)throw PlatformFailure("暂时无法核对收藏关系，请稍后再试")
            if(deps.favorites.pending(accountKey)!=null)throw PlatformFailure("上次收藏操作尚待核对，请先核对")
            deps.accounts.requireCurrent(operationStamp())
            if(deps.settings.current().defaultFavoriteFolders[account.id.toString()]?.id!=selected.id)throw PlatformFailure("默认收藏夹已改变，操作未提交")
            val add=folder.contains!=true
            val result=deps.favorites.change(account.id,target.aid,folder.id,add)
            val pending=deps.favorites.pending(accountKey)
            update{copy(mutationPending=pending,currentFavoriteBvid=bvid,currentFavoriteFolder=folder.id,currentFavoritePresent=if(result==MutationOutcome.UNKNOWN)null else add)}
            report(when(result){MutationOutcome.CONFIRMED->if(add)"已收藏到「${folder.title}」" else "已从「${folder.title}」取消收藏";MutationOutcome.UNCHANGED->if(add)"已在「${folder.title}」中" else "已不在「${folder.title}」中";MutationOutcome.UNKNOWN->"收藏操作结果待核对，不会重复提交"})
            if(result!=MutationOutcome.UNKNOWN && state.value.videoDetails.bvid==bvid)openVideoDetails(bvid)
        }catch(e:CancellationException){throw e}catch(e:Exception){
            val pending=deps.favorites.pending(accountKey);update{copy(mutationPending=pending)}
            report((e as? PlatformFailure)?.category ?: "收藏未确认，请核对后再试");throw e
        }
    }
    fun readCurrentFavorite(bvid:String) {
        val account=state.value.account ?: return
        val selected=state.value.settings.defaultFavoriteFolders[account.id.toString()] ?: return
        if(state.value.busy)return
        if(state.value.currentFavoriteBvid==bvid && state.value.currentFavoriteFolder==selected.id && state.value.currentFavoritePresent!=null)return
        favoriteReadJob?.cancel();val ticket=++favoriteReadEpoch;val stamp=deps.accounts.session.value.stamp
        mutable.value=mutable.value.copy(currentFavoriteBvid=bvid,currentFavoriteFolder=selected.id,currentFavoritePresent=null)
        favoriteReadJob=viewModelScope.launch {
            try {
                val video=state.value.metadata[bvid] ?: deps.content.video(bvid)
                if(video.bvid!=bvid || video.aid<=0)return@launch
                val folder=deps.favorites.folders(account.id,video.aid).firstOrNull{it.id==selected.id}
                deps.accounts.requireCurrent(stamp)
                if(ticket==favoriteReadEpoch)mutable.value=mutable.value.copy(currentFavoritePresent=folder?.contains)
            }catch(e:CancellationException){throw e}catch(_:Exception){ /* Unknown remains an outline, never a claimed success. */ }
        }
    }
    fun requestFavorite() {state.value.video?.let{chooseFavorite(it.bvid)}}
    fun dismissFavorite(){mutable.value=mutable.value.copy(pendingFavorite=false,favoriteTarget=null)}
    private suspend fun loadLocal() {
        val account = accountKey
        loadLibraryNow()
        deps.history.prune(account, deps.settings.current().historyPolicy(), deps.clock.nowMs())
        val snapshot = try { deps.snapshots.load(account)?.queue } catch (e: CancellationException) { throw e } catch (_: Exception) { deps.diagnostics.record(DiagnosticEvent.STORAGE_FAILED, FailureKind.STORAGE); null }
        update { copy(resume = snapshot) }
        if(snapshot!=null && state.value.accountChecked && deps.playback.state.value.queue.isEmpty() && !deps.playback.state.value.live && deps.accounts.session.value.status!=SessionStatus.UNVERIFIED) {
            deps.playback.replace(snapshot,false)
        }
        val pending = deps.favorites.pending(account)
        update { copy(mutationPending = pending) }
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            kotlinx.coroutines.flow.combine(deps.history.observe(account), deps.history.observeLive(account)) { videos,lives -> videos to lives }
                .catch { deps.diagnostics.record(DiagnosticEvent.STORAGE_FAILED, FailureKind.STORAGE) }.collect { (videos,lives) ->
                    if (account == accountKey) {
                        mutable.value = mutable.value.copy(history = videos, liveHistory = lives)
                        if(deps.home!=null && state.value.accountChecked) videos.take(3).forEach{loadMetadata(it.video.bvid)}
                    }
                }
        }
        if(deps.home!=null && state.value.accountChecked && state.value.recommendations.isEmpty())loadRecommendations()
    }
    fun loginFinished() = operation(sessionChange = true) {
        val pending = state.value.pendingFavorite
        val video = state.value.video
        val favoriteTarget=state.value.favoriteTarget
        val search = state.value.search
        val query = state.value.searchKeyword
        val priorAccount = accountKey
        deps.playback.pause(); deps.playback.flush(); deps.playback.forget(null)
        deps.playback.clear()
        val account = deps.accounts.verify()
        if (priorAccount != accountKey && state.value.settings.historyDeleteOnExit) deps.history.delete(priorAccount)
        deps.onAccountConfirmed(accountKey); selectedSource = null; sourceFolder = null
        update { ScreenState(startupReady = startupReady, settings = settings, account = account, accountChecked = true, loginPhase = if(account == null) LoginPhase.GUEST else LoginPhase.AUTHENTICATED,
            video = video, favoriteTarget=favoriteTarget, search = search, searchKeyword = query, pendingFavorite = pending, busy = true, connected = deps.playback.state.value.connected, message = "登录成功，请设置默认收藏夹") }
        loadLocal()
        if (account != null) {refreshFoldersNow();if(deps.settings.current().defaultFavoriteFolders[account.id.toString()]==null)update{copy(defaultFolderPrompt=true)}}
    }
    fun logout() = operation(sessionChange = true) {
        val oldAccount = accountKey
        deps.playback.pause(); deps.playback.flush(); deps.playback.forget(null)
        if (state.value.settings.historyDeleteOnExit) deps.history.delete(oldAccount)
        deps.playback.clear(); deps.accounts.logout(); deps.onAccountConfirmed("guest")
        selectedSource = null; sourceFolder = null
        update { ScreenState(startupReady = startupReady, settings = settings, accountChecked = true, loginPhase = LoginPhase.GUEST, busy = true, connected = deps.playback.state.value.connected, message = if(settings.historyDeleteOnExit) "已退出并删除原账号本机历史；续听快照保留。" else "已退出；原账号本机历史保持隔离。") }
        loadLocal()
    }
    fun modifyFavorite(folder: FavoriteFolder, add: Boolean) = operation {
        val account = state.value.account ?: throw PlatformFailure("请先登录")
        val video = state.value.favoriteTarget ?: state.value.video ?: throw PlatformFailure("请先选择视频")
        if(state.value.folders.none{it.id==folder.id})throw PlatformFailure("收藏夹已不可用，请刷新")
        favoriteReadJob?.cancel();favoriteReadEpoch++
        update { copy(mutationStatus = "处理中") }
        val result = try { deps.favorites.change(account.id, video.aid, folder.id, add) }
            catch (e: Exception) { val pending = deps.favorites.pending(accountKey); update { copy(mutationStatus = "操作未确认，请核对后再试", mutationPending = pending) }; throw e }
        val pending = deps.favorites.pending(accountKey)
        update { copy(mutationPending = pending, mutationStatus = if(result == MutationOutcome.UNKNOWN) "结果待核对" else "已确认") }
        update { copy(message = when(result) {
            MutationOutcome.CONFIRMED -> "已回读确认：${if (add) "添加到" else "移出"}「${folder.title}」。"
            MutationOutcome.UNCHANGED -> "远端已是目标状态，没有重复写入。"
            MutationOutcome.UNKNOWN -> "结果待核对；没有重试写入。"
        }) }
        if (result != MutationOutcome.UNKNOWN) {
            refreshFoldersNow()
            if(folder.id==state.value.settings.defaultFavoriteFolders[account.id.toString()]?.id)update{copy(currentFavoriteBvid=video.bvid,currentFavoriteFolder=folder.id,currentFavoritePresent=add)}
            state.value.folder?.let { openFolderNow(it, false) }
        }
    }
    fun reconcileFavorite() = operation {
        if(state.value.mutationPending?.engagement!=null) {
            val result=(deps.engagement ?: throw PlatformFailure("互动核对暂不可用")).reconcile(operationStamp())
            handleUnavailableDefaultFolder(result.unavailableFolder)
            val pending=deps.collections.mutation(accountKey)
            update{copy(mutationPending=pending,message=result.message,videoDetails=videoDetails.copy(message=result.message))};state.value.videoDetails.bvid?.let(::refreshCoinBalance);return@operation
        }
        val account = state.value.account ?: throw PlatformFailure("请先登录原账号")
        if (deps.favorites.pending(accountKey)?.followSource != null) {
            val followed = deps.sources.reconcileFollow(account.id)
            update { copy(mutationPending = null, mutationStatus = "已核对", sourceFollowed = followed, message = "已回读追更关系，没有重试写入") }; return@operation
        }
        val result = deps.favorites.reconcile(account.id)
        update { copy(message = if (result == null) "没有待核对收藏操作。" else "已回读当前关系：${if(result.present == true) "在" else "不在"}该收藏夹中；没有重试写入。") }
        update { copy(mutationPending = null, mutationStatus = "已核对") }; refreshFoldersNow()
    }
    fun deleteLocalHistory(row: LocalHistoryEntry? = null) = operation {
        require(row == null || row.account == accountKey)
        deps.playback.pause(); deps.playback.flush(); deps.playback.forget(row?.video)
        if(deps.historySync?.enabled(accountKey)==true)deps.historySync.delete(operationStamp(),row?.let{"archive_${it.video.bvid}"}) else deps.history.delete(accountKey, row?.video)
        try { deps.home?.clearMetadata(accountKey) } catch(e:CancellationException){throw e}catch(_:Exception){}
        update { copy(message = if(remoteHistory.enabled)"已删除 B站历史并更新最近记录；续听快照保留" else "已删除本机历史；续听快照单独保留。") }
    }
    fun openOfficial(launch: (String) -> Unit) = openOfficialChecked({ true }, launch)
    fun openOfficialChecked(canLaunch: (String) -> Boolean, launch: (String) -> Unit) = operation {
        val player = deps.playback.state.value
        if(player.live) {
            val room=player.liveExperience.roomId.takeIf{it>0} ?: player.currentId?.removePrefix("live:")?.toLongOrNull() ?: throw PlatformFailure("直播房间身份不可用")
            val url=LiveInput.official(room)
            if(!canLaunch(url))throw PlatformFailure("无法打开目标，可复制链接或使用浏览器")
            deps.playback.pause(PauseReason.EXTERNAL_VIDEO);deps.playback.flush();liveReturnRoom=room;launch(url)
            update{copy(message="已暂停直播；返回不会自动播放")};return@operation
        }
        val entry = player.queue.firstOrNull { it.id == player.currentId } ?: throw PlatformFailure("请先播放视频")
        val confirmed=resolveQueueMetadata(entry)
        deps.accounts.requireCurrent(operationStamp())
        val url = deps.jump.url(JumpSnapshot(VideoRef(confirmed.bvid, confirmed.cid, confirmed.part), player.positionMs, state.value.resume))
        if(!canLaunch(url)) throw PlatformFailure("无法打开目标，可复制链接或使用浏览器")
        deps.playback.pause(PauseReason.EXTERNAL_VIDEO); deps.playback.flush()
        launch(url)
        update { copy(message = "已保存状态并暂停；返回不会自动外放。") }
    }
    fun selectQueueEntry(id: String) = operation {
        deps.playback.flush()
        val saved = deps.snapshots.load(accountKey)?.queue ?: throw PlatformFailure("没有可恢复的队列")
        require(saved.entries.any { it.id == id })
        deps.playback.replace(saved.copy(currentId = id, positionMs = if(id == saved.currentId) saved.positionMs else 0), true)
    }
    fun openHistoryOfficial(row:LocalHistoryEntry,canLaunch:(String)->Boolean,launch:(String)->Unit)=operation {
        require(row.account==accountKey)
        val url=deps.jump.url(JumpSnapshot(row.video,row.positionMs,null))
        if(!canLaunch(url))throw PlatformFailure("没有找到可打开链接的应用")
        deps.playback.pause(PauseReason.EXTERNAL_VIDEO);deps.playback.flush();launch(url)
    }
    fun updateSettings(settings: UserSettings) = operation {
        if (state.value.settings.historyEnabled && !settings.historyEnabled) {
            deps.playback.pause(); deps.playback.flush(); deps.playback.forget(null)
        }
        val policyChanged = state.value.settings.historyPolicy() != settings.historyPolicy()
        deps.settings.update(settings)
        applySettings(settings)
        if (policyChanged) deps.history.prune(accountKey, settings.historyPolicy(), deps.clock.nowMs())
    }
    fun downloadCurrent()=operation {
        if(state.value.isLive)throw PlatformFailure("直播不保存为离线音频")
        val entry=state.value.queue.firstOrNull{it.id==state.value.currentId} ?: state.value.resume?.let{q->q.entries.firstOrNull{it.id==q.currentId}} ?: throw PlatformFailure("请先选择点播内容")
        val confirmed=resolveQueueMetadata(entry);deps.accounts.requireCurrent(operationStamp())
        requireNotNull(deps.downloads).enqueue(listOf(confirmed))
        update{copy(message="已提交当前分 P 的音频下载，请在下载管理查看结果")}
    }
    private suspend fun resolveQueueMetadata(entry:QueueEntry):QueueEntry {
        if(entry.cid>0)return entry
        val video=deps.content.video(entry.bvid);currentCoroutineContext().ensureActive();deps.accounts.requireCurrent(operationStamp())
        if(entry.source?.kind!=SourceKind.UP_UPLOADS||video.bvid!=entry.bvid||!video.hasCreator(entry.source?.owner ?: 0))throw PlatformFailure("投稿作者身份不匹配")
        val part=video.parts.firstOrNull{it.number==1} ?: throw PlatformFailure("没有可播放的 P1")
        return entry.copy(cid=part.cid,title=video.title)
    }
    fun downloadQueue()=operation {
        if(state.value.queue.size>500)throw PlatformFailure("主动下载每批最多 500 项，请选择需要下载的歌曲")
        val entries=state.value.queue.map{resolveQueueMetadata(it)}
        requireNotNull(deps.downloads).enqueue(entries);update{copy(message="已提交队列中的音频任务")}
    }
    fun pauseDownload(id:String)=operation{requireNotNull(deps.downloads).pause(id)}
    fun resumeDownload(id:String)=operation{requireNotNull(deps.downloads).resume(id)}
    fun removeDownload(id:String)=operation {
        if(state.value.queue.firstOrNull{it.id==state.value.currentId}?.let{it.id==id&&it.offline}==true)deps.playback.pause()
        requireNotNull(deps.downloads).remove(id)
    }
    fun playDownload(id:String)=operation {
        val files=requireNotNull(deps.downloads)
        withContext(Dispatchers.IO){files.playable(id)}
        val row=files.records.value.first{it.id==id&&it.account==accountKey}
        val entry=row.entry()
        deps.onAccountConfirmed(accountKey)
        deps.playback.replace(ResumeSnapshot(account=accountKey,entries=listOf(entry),order=listOf(id),currentId=id,positionMs=0),true)
    }
    fun previewDiagnostics() { mutable.value = mutable.value.copy(diagnosticPreview = deps.diagnostics.preview()) }
    fun closeDiagnostics() { mutable.value = mutable.value.copy(diagnosticPreview = null) }
    override fun onCleared() { deps.playback.close(); super.onCleared() }
    fun searchUps(input:String,more:Boolean=false) {
        val query=input.trim()
        if(query.isEmpty()||query.length>100){mutable.value=mutable.value.copy(upSearchError="请输入 UP 主名字、UID 或主页链接");return}
        upSearchJob?.cancel();val ticket=++upSearchEpoch
        val stamp=deps.accounts.session.value.stamp
        val previous=state.value.upSearch.takeIf{state.value.upSearchKeyword==query}
        if(more&&previous?.hasMore!=true)return
        val page=if(more)previous!!.page+1 else 1
        mutable.value=mutable.value.copy(upSearchKeyword=query,upSearchBusy=true,upSearchError=null,upSearch=if(more)previous else null)
        upSearchJob=viewModelScope.launch {
            try {
                val repo=requireNotNull(deps.upLibrary)
                val mid=UpLinks.mid(query)
                val result=if(mid!=null) {
                    val profile=try{repo.profile(mid)}catch(e:CancellationException){throw e}catch(e:PlatformFailure){
                        if(e.kind()==FailureKind.MISSING)throw e
                        // A known UID can still open uploads if optional profile fields are restricted.
                        UpProfile(mid,"UP $mid")
                    }
                    UpSearchPage(listOf(profile),1,1,false)
                } else repo.search(query,page)
                ensureActive();deps.accounts.requireCurrent(stamp)
                if(ticket!=upSearchEpoch)return@launch
                if(result.page!=page)throw PlatformFailure("平台返回页码不匹配")
                if(more&&result.hasMore&&result.items.none{row->previous!!.items.none{it.mid==row.mid}})throw PlatformFailure("UP 主搜索分页没有推进")
                mutable.value=mutable.value.copy(upSearch=result.copy(items=if(more)(previous!!.items+result.items).distinctBy{it.mid} else result.items))
            }catch(e:CancellationException){throw e}
            catch(e:PlatformFailure){if(ticket==upSearchEpoch)mutable.value=mutable.value.copy(upSearchError=e.category)}
            catch(_:Exception){if(ticket==upSearchEpoch)mutable.value=mutable.value.copy(upSearchError="UP 主搜索未完成，请重试")}
            finally{if(ticket==upSearchEpoch)mutable.value=mutable.value.copy(upSearchBusy=false)}
        }
    }
    fun cancelUpSearch(){upSearchJob?.cancel();upSearchEpoch++;mutable.value=mutable.value.copy(upSearchBusy=false,upSearch=null,upSearchError=null)}
    fun retryUpSearch(){viewModelScope.launch{deps.retryAccount();searchUps(state.value.upSearchKeyword)}}
    fun openUp(profile:UpProfile)=operation {
        update{copy(folder=null,sourceContent=null,favorites=emptyList(),sources=null,upProfile=profile,upOrder=UploadOrder.NEWEST,upQuery="",sourceCached=false,sourceAdded=emptySet(),sourceHeard=emptySet())}
        deps.collections.collection(accountKey,profile.source)?.let { cached->
            val info=if(profile.name.startsWith("UP ")&&cached.ownerName.isNotBlank())profile.copy(name=cached.ownerName,avatar=cached.cover,videos=cached.total) else profile
            update{copy(upProfile=info,sourceContent=SourceContentPage(ContentSource(profile.source,cached.title,cached.ownerName,cached.total,cached.cover),cached.items.map{FavoriteItem(it.bvid,it.title)},1,!cached.complete),sourceCached=true)}
        }
        loadUpNow(false)
    }
    fun openUp(mid:Long)=openUp(UpProfile(mid,"UP $mid"))
    fun openSavedUp(saved:CollectionSnapshot)=openUp(UpProfile(saved.source.owner,saved.ownerName.ifBlank{saved.title},saved.cover,videos=saved.total))
    fun loadUp(more:Boolean=false)=operation{loadUpNow(more)}
    fun filterUp(order:UploadOrder=state.value.upOrder,query:String=state.value.upQuery)=operation {
        require(query.length<=100)
        update{copy(upOrder=order,upQuery=query.trim(),sourceContent=null,sourceCached=false)}
        loadUpNow(false)
    }
    fun retryUp(){viewModelScope.launch{deps.retryAccount();loadUp()}}
    fun cancelUpLoading(){if(state.value.upLoading)operationJob?.cancel()}
    private suspend fun loadUpNow(more:Boolean) {
        val profile=state.value.upProfile ?: throw PlatformFailure("请先选择 UP 主")
        val previous=state.value.sourceContent?.takeIf{it.source.ref==profile.source}
        if(more&&(previous?.hasMore!=true||state.value.sourceCached))throw PlatformFailure("请先刷新投稿第一页")
        val repo=requireNotNull(deps.upLibrary)
        val order=state.value.upOrder;val query=state.value.upQuery
        var page=if(more)previous!!.page+1 else 1
        update{copy(upLoading=true,upProgress="正在读取投稿…")}
        val rows=linkedMapOf<String,FavoriteItem>()
        if(more)previous!!.items.forEach{rows[it.bvid]=it}
        var result:SourceContentPage
        do {
            result=repo.uploads(profile.mid,page,order,query)
            currentCoroutineContext().ensureActive();deps.accounts.requireCurrent(operationStamp())
            if(result.source.ref!=profile.source||result.page!=page)throw PlatformFailure("投稿来源或页码不匹配")
            val before=rows.size;result.items.forEach{rows.putIfAbsent(it.bvid,it)}
            if(result.hasMore&&rows.size==before)throw PlatformFailure("投稿分页没有推进")
            update{copy(upProgress="已读取 ${rows.size} 项${if(order==UploadOrder.OLDEST&&result.hasMore) " · 正在准备最早发布顺序" else ""}")}
            page++
        }while(order==UploadOrder.OLDEST&&result.hasMore)
        val name=profile.name.takeUnless{it.startsWith("UP ")} ?: result.source.ownerName.ifBlank{profile.name}
        val info=profile.copy(name=name,videos=if(query.isBlank())result.source.count else profile.videos)
        val combined=result.copy(source=result.source.copy(title="${name}的投稿",ownerName=name,cover=info.avatar),items=rows.values.toList().let{if(order==UploadOrder.OLDEST)it.reversed() else it})
        if(query.isBlank()) {
            CollectionCoordinator(deps.collections,deps.accounts,deps.clock).save(combined,operationStamp())
            loadBookmarksNow()
        }
        val checkpoint=deps.collections.updateCheckpoint(accountKey,profile.source)
        update{copy(upProfile=info,sourceContent=combined,sourceCached=false,sourceHeard=checkpoint?.heard.orEmpty(),message="已加载 ${combined.items.size} 项${if(combined.hasMore) " · 可继续加载" else " · 已完整"}")}
    }
    fun bookmarkUp(saved:Boolean)=operation {
        val profile=state.value.upProfile ?: throw PlatformFailure("请先选择 UP 主")
        deps.collections.bookmark(accountKey,ContentSource(profile.source,"${profile.name}的投稿",profile.name,profile.videos,profile.avatar),saved)
        loadBookmarksNow()
        update{copy(message=if(saved)"已加入 UP 收藏" else "已移除 UP 收藏")}
    }
    fun refreshUpLocal() {
        val profile=state.value.upProfile ?: return
        val stamp=deps.accounts.session.value.stamp
        viewModelScope.launch {
            try {
                val bookmarks=deps.collections.bookmarks(stamp.account)
                val heard=deps.collections.updateCheckpoint(stamp.account,profile.source)?.heard.orEmpty()
                ensureActive();deps.accounts.requireCurrent(stamp)
                if(state.value.upProfile?.mid==profile.mid)mutable.value=mutable.value.copy(bookmarks=bookmarks,sourceHeard=heard)
            }catch(e:CancellationException){throw e}catch(_:Exception){/* Keep the last local state. */}
        }
    }
    fun loadFollowedSources(more: Boolean = false) = operation {
        val account = state.value.account ?: throw PlatformFailure("请先登录")
        loadBookmarksNow()
        val layout=state.value.library.layout(LibrarySection.FOLLOWED)
        if(!more&&layout!=null&&(layout.order.isNotEmpty()||layout.hidden.isNotEmpty())) {
            val result=readAllFollowed(account.id)
            update{copy(sources=result,sourceListOwner=null,message="已按本机管理设置读取追更来源")};return@operation
        }
        val prior = state.value.sources.takeIf{state.value.sourceListOwner==null}
        if(more && prior?.hasMore != true) return@operation
        val page = if (more) (prior?.page ?: 0) + 1 else 1
        val result = deps.sources.followed(account.id, page)
        if(result.page != page) throw PlatformFailure("平台返回页码不匹配")
        if(more && result.hasMore && result.sources.none { row -> prior?.sources.orEmpty().none { it.ref == row.ref } }) throw PlatformFailure("来源分页没有推进，保留已加载内容")
        val sources = (if (page == 1) result.sources else prior?.sources.orEmpty() + result.sources).distinctBy { it.ref }
        update { copy(sources = result.copy(sources = sources), sourceListOwner = null, message = "已读取追更来源第 $page 页。${result.unavailableSeasonIds.size} 个失效来源不可打开。") }
    }
    fun openSourceLink(text: String) = operation {
        val link = SourceLinks.parse(text) ?: throw PlatformFailure("请输入 B 站收藏夹、合集或 UP 主页的完整链接")
        update { copy(folder=null,sourceContent=null,favorites=emptyList(),sources=null,sourceListOwner=null,hasMore=false) }
        if (link.id == null) {
            val sources = deps.sources.publicFolders(requireNotNull(link.owner))
            update { copy(sources = sources, sourceListOwner = link.owner, message = "已读取该 UP 的公开收藏夹") }
        } else {
            val result = deps.sources.resolve(link, state.value.account?.id)
            saveSourcePage(result)
        }
    }
    fun openSource(source: SourceRef, more: Boolean = false) = operation { openSourceNow(source, more) }
    private suspend fun openSourceNow(source: SourceRef, more: Boolean) {
        val previous = state.value.sourceContent?.takeIf { it.source.ref == source }
        if (!more) {
            deps.collections.collection(accountKey, source)?.let { cached ->
                update { copy(folder = null, sourceContent = SourceContentPage(ContentSource(source,cached.title,cached.ownerName,cached.total),cached.items.map { FavoriteItem(it.bvid,it.title) },1,!cached.complete), sourceCached = true, refreshedAt = cached.refreshedAt, sourceFollowed = null, sourceAdded = emptySet(), sourceHeard = emptySet()) }
            }
        } else if (previous == null || !previous.hasMore || state.value.sourceCached) throw PlatformFailure("请先刷新来源第一页")
        val page = if (more) (previous?.page ?: 0) + 1 else 1
        val result = deps.sources.content(source, state.value.account?.id, page)
        if(result.page != page || result.source.ref != source) throw PlatformFailure("来源身份或页码不匹配")
        if (more && result.items.isNotEmpty() && result.items.all { row -> previous?.items?.any { it.bvid == row.bvid } == true }) throw PlatformFailure("分页内容重复，已停止追加")
        val combined = result.copy(items = if (more) (previous?.items.orEmpty() + result.items).distinctBy { it.bvid } else result.items)
        saveSourcePage(combined)
    }
    private suspend fun saveSourcePage(combined: SourceContentPage) {
        val prior = deps.collections.updateCheckpoint(accountKey, combined.source.ref)
        val changes = SourceUpdates.compare(prior,accountKey,combined.source.ref,combined.items.map { it.bvid },!combined.hasMore)
        CollectionCoordinator(deps.collections, deps.accounts, deps.clock).save(combined, operationStamp())
        update { copy(folder = null, sourceContent = combined, sourceCached = false, sourceFollowed = null, sourceAdded = changes.added, sourceHeard = prior?.heard.orEmpty(), refreshedAt = deps.clock.nowMs(), collectionFilter = "",
            message = "${combined.source.title} · 已加载 ${combined.items.size} 项${if(combined.hasMore) "，尚未完整" else "，已完整"} · 新增 ${changes.added.size} 项") }
    }
    fun moreSources() {
        val owner = state.value.sourceListOwner
        if (owner == null) { loadFollowedSources(true); return }
        operation {
            val previous = state.value.sources ?: return@operation
            if (!previous.hasMore) return@operation
            val result = deps.sources.publicFolders(owner, previous.page + 1)
            if(result.page != previous.page + 1) throw PlatformFailure("平台返回页码不匹配")
            if(result.hasMore && result.sources.none { row -> previous.sources.none { it.ref == row.ref } }) throw PlatformFailure("来源分页没有推进，保留已加载内容")
            update { copy(sources = result.copy(sources = (previous.sources + result.sources).distinctBy { it.ref })) }
        }
    }
    fun searchVideos(keyword: String, more: Boolean = false) {
        val query = keyword.trim()
        if (query.isEmpty() || query.length > 100) { mutable.value = mutable.value.copy(message = "请输入 1—100 字关键词"); return }
        searchJob?.cancel()
        val ticket = ++searchEpoch
        val stamp = deps.accounts.session.value.stamp
        val previous = state.value.search.takeIf { state.value.searchKeyword == query }
        val page = if (more) (previous?.page ?: 0) + 1 else 1
        if (more && previous?.hasMore != true) return
        mutable.value = mutable.value.copy(searchBusy = true, searchKeyword = query, search = if(more) previous else null, error=null)
        searchJob = viewModelScope.launch {
            try {
                if(!more)deps.retryAccount()
                deps.accounts.requireCurrent(stamp)
                if(ticket!=searchEpoch)return@launch
                val found = deps.content.search(query, page)
                deps.accounts.requireCurrent(stamp)
                if (ticket != searchEpoch) return@launch
                if (found.page != page) throw PlatformFailure("平台返回页码不匹配")
                val items = if (more) (previous?.items.orEmpty() + found.items).distinctBy { it.bvid } else found.items
                mutable.value = mutable.value.copy(search = found.copy(items = items), message = if(items.isEmpty()) "没有找到相关视频" else "站内搜索 · 已显示 ${items.size} 条")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if(ticket == searchEpoch && deps.accounts.session.value.stamp == stamp) { val error=(e as? PlatformFailure)?.category ?: "搜索失败，已保留成功加载的内容"; mutable.value = mutable.value.copy(message = error,error=error) } }
            finally { if(ticket == searchEpoch) mutable.value = mutable.value.copy(searchBusy = false) }
        }
    }
    fun cancelSearch() { searchJob?.cancel(); searchEpoch++; mutable.value = mutable.value.copy(searchBusy = false) }
    fun probeAudio() = operation {
        val video = state.value.video ?: throw PlatformFailure("请先读取视频")
        val part = video.parts.first()
        val result = deps.entitlements.inspect(VideoRef(video.bvid,part.cid,part.number))
        update { copy(entitlement = result, audioSummary = "默认自动最高可用 · " + result.observedTracks.joinToString { "${it.codec} ${it.bitrate / 1000}kbps" }, message = "只按实际返回音轨判断，不以会员标志推导音质。") }
    }
    fun selectRecommendationCategory(category:HomeCategory){
        if(state.value.recommendationCategory==category)return
        recommendationJob?.cancel();recommendationEpoch++
        val old=state.value
        if(!old.recommendationBusy&&old.recommendationError==null&&old.recommendations.isNotEmpty())recommendationPages[old.recommendationCategory]=old.recommendations
        val cached=recommendationPages[category].orEmpty()
        mutable.value=old.copy(recommendationCategory=category,recommendations=cached,recommendationsCached=cached.isNotEmpty(),recommendationBusy=false,recommendationError=null)
        if(category==HomeCategory.LIVE){loadLiveRanking();return}
        if(category !in recommendationPages)loadRecommendations()
    }
    fun retryRecommendations() {
        if(recommendationJob?.isActive==true)return
        viewModelScope.launch {deps.retryAccount();loadRecommendations()}
    }
    fun loadRecommendations() {
        if(state.value.recommendationCategory==HomeCategory.LIVE){loadLiveRanking(true);return}
        if(state.value.loginPhase==LoginPhase.CHECKING)return
        if(recommendationJob?.isActive==true)return
        val stamp=deps.accounts.session.value.stamp
        val category=state.value.recommendationCategory;val ticket=++recommendationEpoch
        mutable.value=mutable.value.copy(recommendationBusy=true,recommendationError=null)
        recommendationJob=viewModelScope.launch {
            try { val result=readRecommendation(category).await();deps.accounts.requireCurrent(stamp)
                if(ticket!=recommendationEpoch)return@launch
                recommendationPages[category]=result
                mutable.value=mutable.value.copy(recommendations=result,recommendationsCached=false)
                if(deps.home!=null&&category==HomeCategory.ALL) {
                    try { deps.home.recommendations(stamp.account,result);warmHome() }
                    catch(e:CancellationException){throw e}catch(_:Exception){ /* Display cache is optional. */ }
                    if(startupEpoch==epoch){startupVisualReady=true;revealHome()}
                }
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(ticket==recommendationEpoch&&deps.accounts.session.value.stamp==stamp)mutable.value=mutable.value.copy(recommendationError=(e as? PlatformFailure)?.category ?: "推荐读取失败")}
            finally{if(ticket==recommendationEpoch&&deps.accounts.session.value.stamp==stamp){mutable.value=mutable.value.copy(recommendationBusy=false);if(startupEpoch==epoch){startupVisualReady=true;revealHome()}}}
        }
    }
    /** Shared in-flight reads let a category tap join its preload without a second request. */
    private fun readRecommendation(category:HomeCategory):Deferred<List<Recommendation>> {
        recommendationReads[category]?.let{return it}
        val stamp=deps.accounts.session.value.stamp
        val task=viewModelScope.async(start=CoroutineStart.LAZY) {
            val rows=recommendationLimit.withPermit{deps.recommendations.candidates(category).distinctBy{it.bvid}.take(5)}
            currentCoroutineContext().ensureActive();deps.accounts.requireCurrent(stamp)
            recommendationPages[category]=rows
            rows
        }
        recommendationReads[category]=task
        task.invokeOnCompletion{if(recommendationReads[category]===task)recommendationReads.remove(category)}
        task.start();return task
    }
    fun preloadRecommendationCategories(force:Boolean=false) {
        if(!state.value.accountChecked || state.value.loginPhase==LoginPhase.CHECKING)return
        if(recommendationPreloadJob?.isActive==true || (!force && recommendationsPreloaded))return
        loadLiveRanking(force)
        val stamp=deps.accounts.session.value.stamp
        recommendationPreloadJob=viewModelScope.launch {
            val reads=HomeCategory.entries.filter{it!=HomeCategory.LIVE && (force || it!=HomeCategory.ALL) && (force || it !in recommendationPages)}.map{readRecommendation(it)}
            for(read in reads) {
                try {
                    val rows=read.await();deps.accounts.requireCurrent(stamp)
                    try{deps.home?.warm(rows.map{it.cover})}catch(e:CancellationException){throw e}catch(_:Exception){}
                }catch(e:CancellationException){throw e}catch(_:Exception){ /* One unavailable category does not erase another. */ }
            }
            if(deps.accounts.session.value.stamp==stamp)recommendationsPreloaded=true
        }
    }
    /** Called only by a user's pull, after the account refresh has completed. */
    suspend fun refreshHome() {
        if(!state.value.accountChecked || state.value.loginPhase==LoginPhase.CHECKING)return
        deps.retryAccount()
        loadPopularMusic(true);loadRecommendations();loadHomeUps(true);preloadRecommendationCategories(true)
        listOfNotNull(recommendationJob,homeUpJob,recommendationPreloadJob,popularMusicJob,liveRankJob).forEach{it.join()}
    }
    fun retryHomeUps() {
        if(homeUpJob?.isActive==true)return
        viewModelScope.launch { deps.retryAccount();loadHomeUps(true) }
    }
    fun loadHomeUps(force:Boolean=false) {
        if(!state.value.accountChecked || state.value.loginPhase==LoginPhase.CHECKING)return
        if(!force && (homeUpJob?.isActive==true || state.value.homeUpsLoaded))return
        if(force)homeUpJob?.cancel()
        val stamp=deps.accounts.session.value.stamp;val ticket=++homeUpEpoch
        val previous=state.value.homeUps.map{it.mid}.toSet()
        val musicOnly=state.value.settings.musicRecommendations
        mutable.value=mutable.value.copy(homeUpsBusy=true,homeUpsError=null)
        homeUpJob=viewModelScope.launch {
            try {
                val rows=(if(musicOnly)deps.recommendations.creators(previous) else deps.recommendations.accountCreators(previous))
                    .filter{it.mid>0&&it.name.isNotBlank()}.distinctBy{it.mid}.take(10)
                deps.accounts.requireCurrent(stamp)
                if(ticket!=homeUpEpoch)return@launch
                if(rows.isNotEmpty() || previous.isEmpty())mutable.value=mutable.value.copy(homeUps=rows)
                else mutable.value=mutable.value.copy(homeUpsError="暂时没有新的推荐 UP，已保留当前推荐")
            } catch(e:CancellationException){throw e}
            catch(e:Exception){if(ticket==homeUpEpoch && deps.accounts.session.value.stamp==stamp)
                mutable.value=mutable.value.copy(homeUpsError=(e as? PlatformFailure)?.category ?: "推荐 UP 读取失败")}
            finally {if(ticket==homeUpEpoch && deps.accounts.session.value.stamp==stamp)
                mutable.value=mutable.value.copy(homeUpsBusy=false,homeUpsLoaded=true)}
        }
    }
    fun loadPopularMusic(force:Boolean=false) {
        if(!state.value.accountChecked || state.value.loginPhase==LoginPhase.CHECKING)return
        if(!force && (popularMusicJob?.isActive==true || state.value.popularMusicLoaded))return
        if(force){popularMusicJob?.cancel();popularMusicMoreJob?.cancel()}
        val stamp=deps.accounts.session.value.stamp;val ticket=++popularMusicEpoch
        val previous=state.value.popularMusic.map{it.key}.toSet()
        val musicOnly=state.value.settings.musicRecommendations
        mutable.value=mutable.value.copy(popularMusicBusy=true,popularMusicError=null,popularMusicMoreBusy=false,popularMusicHasMore=true,popularMusicMoreError=null)
        popularMusicJob=viewModelScope.launch {
            try {
                val ready:suspend (List<PopularMusic>)->Unit = { partial->
                    deps.accounts.requireCurrent(stamp)
                    // Keep an existing batch intact while its replacement is still being filled.
                    if(ticket==popularMusicEpoch && previous.isEmpty() && partial.isNotEmpty())mutable.value=mutable.value.copy(popularMusic=PopularMusic.valid(partial,musicOnly).take(PopularMusic.BATCH_SIZE))
                }
                val rows=PopularMusic.valid(if(musicOnly)deps.recommendations.popularMusic(previous,ready) else deps.recommendations.accountHome(previous,ready),musicOnly).take(PopularMusic.BATCH_SIZE)
                deps.accounts.requireCurrent(stamp)
                if(ticket!=popularMusicEpoch)return@launch
                if(rows.isNotEmpty()) {
                    mutable.value=mutable.value.copy(popularMusic=rows)
                    if(musicOnly)try{deps.home?.popularMusic(stamp.account,rows)}catch(e:CancellationException){throw e}catch(_:Exception){}
                } else if(previous.isNotEmpty())mutable.value=mutable.value.copy(popularMusicError="暂时没有更多符合条件的视频，已保留当前推荐")
            } catch(e:CancellationException){throw e}
            catch(e:Exception){if(ticket==popularMusicEpoch && deps.accounts.session.value.stamp==stamp)
                mutable.value=mutable.value.copy(popularMusicError=(e as? PlatformFailure)?.category ?: "推荐视频读取失败")}
            finally {if(ticket==popularMusicEpoch && deps.accounts.session.value.stamp==stamp)
                mutable.value=mutable.value.copy(popularMusicBusy=false,popularMusicLoaded=true)}
        }
    }
    fun loadMorePopularMusic(retry:Boolean=false) {
        val visible=state.value
        if(!visible.accountChecked || visible.popularMusicBusy || !visible.popularMusicLoaded || visible.popularMusic.isEmpty() ||
            visible.popularMusicMoreBusy || !visible.popularMusicHasMore || !retry && visible.popularMusicMoreError!=null)return
        val stamp=deps.accounts.session.value.stamp;val ticket=popularMusicEpoch
        val base=visible.popularMusic
        val musicOnly=visible.settings.musicRecommendations
        val excluded=base.flatMap{listOf(it.key,it.bvid)}.toSet()
        mutable.value=visible.copy(popularMusicMoreBusy=true,popularMusicMoreError=null)
        popularMusicMoreJob=viewModelScope.launch {
            fun current()=ticket==popularMusicEpoch && deps.accounts.session.value.stamp==stamp
            fun append(rows:List<PopularMusic>):List<PopularMusic> = base+PopularMusic.valid(rows,musicOnly)
                .filter{it.key !in excluded && it.bvid !in excluded}.take(PopularMusic.BATCH_SIZE)
            try {
                val ready:suspend (List<PopularMusic>)->Unit = {partial->
                    deps.accounts.requireCurrent(stamp)
                    if(current())mutable.value=mutable.value.copy(popularMusic=append(partial))
                }
                val rows=if(musicOnly)deps.recommendations.popularMusic(excluded,ready) else deps.recommendations.accountHome(excluded,ready)
                deps.accounts.requireCurrent(stamp)
                if(current()) {
                    val merged=append(rows)
                    mutable.value=mutable.value.copy(popularMusic=merged,popularMusicHasMore=merged.size>base.size)
                    val covers=merged.drop(base.size).map{it.cover}.take(3)
                    viewModelScope.launch {
                        if(!current())return@launch
                        try{deps.home?.warm(covers)}catch(e:CancellationException){throw e}catch(_:Exception){}
                    }
                }
            }catch(e:CancellationException){throw e}
            catch(e:Exception){if(current())mutable.value=mutable.value.copy(popularMusicMoreError=(e as? PlatformFailure)?.category ?: "更多推荐视频读取失败")}
            finally{if(current())mutable.value=mutable.value.copy(popularMusicMoreBusy=false)}
        }
    }
    fun loadLiveRanking(force:Boolean=false) {
        if(liveRankJob?.isActive==true || !force && liveRankStamp==deps.accounts.session.value.stamp && state.value.liveRankings.isNotEmpty())return
        val stamp=deps.accounts.session.value.stamp;val ticket=++liveRankEpoch;liveRankStamp=stamp
        liveVisibleOwner=stamp.account
        mutable.value=mutable.value.copy(liveRankingBusy=true,liveRankingError=null)
        liveRankJob=viewModelScope.launch {
            fun current()=ticket==liveRankEpoch && deps.accounts.session.value.stamp==stamp
            try {
                val rows=deps.live.ranking().filter{it.roomId>0}.distinctBy{it.roomId}.take(5)
                deps.accounts.requireCurrent(stamp)
                if(current())mutable.value=mutable.value.copy(liveRankings=rows)
                try{deps.home?.warm(rows.map{it.cover})}catch(e:CancellationException){throw e}catch(_:Exception){}
            }catch(e:CancellationException){throw e}catch(e:Exception){if(current())mutable.value=mutable.value.copy(liveRankingError=(e as? PlatformFailure)?.category ?: "直播排行读取失败，已保留原榜单")}
            finally{if(current())mutable.value=mutable.value.copy(liveRankingBusy=false)}
        }
    }
    fun inspectLive(input: String) = operation {
        liveRoomStamp=operationStamp()
        liveVisibleOwner=liveRoomStamp?.account
        update{copy(liveRoom=null,liveStreams=emptyList(),liveSummary="")}
        val room = deps.live.resolve(input)
        update{copy(liveRoom=room)}
        val streams = if (room.status == 1) deps.live.streams(room.roomId) else emptyList()
        update { copy(liveRoom = room,liveStreams=streams.filter(LiveStreams::supported), liveSummary = "${room.anchor} · 房间 ${room.roomId} · ${room.state.label}",
            message = if(room.state!=LiveRoomStatus.LIVE)room.state.label else if (streams.none(LiveStreams::supported)) "当前未取得可用直播流，请在 B站查看" else "已读取房间，尚未播放") }
    }
    fun playLiveWithConsent(mixedConsent:Boolean=false,ready:()->Unit={}) = operation {
        val room = state.value.liveRoom ?: throw PlatformFailure("请先检查房间")
        if (room.status != 1) throw PlatformFailure("房间当前未开播")
        // Persist the VOD queue before switching sources. A storage failure must leave it intact.
        deps.playback.flush()
        deps.accounts.requireCurrent(operationStamp())
        deps.playback.live(room, mixedConsent)
        loadLocal()
        update { copy(message = "正在连接直播；暂停后恢复会重新检查房间并接近当前直播") }
        ready()
    }
    fun toggleLiveBookmark()=operation {
        val stamp=operationStamp();val owner=stamp.account
        val room=state.value.liveRoom ?: throw PlatformFailure("请先读取房间")
        val saved=deps.settings.current();deps.accounts.requireCurrent(stamp);val rows=saved.localLiveRooms[owner].orEmpty()
        val next=if(rows.any{it.roomId==room.roomId})rows.filterNot{it.roomId==room.roomId} else {
            if(rows.size>=100)throw PlatformFailure("本机常听最多保存 100 个房间，请先移除部分房间")
            rows+LiveBookmark(room.roomId,room.title.take(500),room.anchor.take(200),room.cover.take(2048))
        }
        val settings=saved.copy(localLiveRooms=saved.localLiveRooms+(owner to next))
        deps.settings.update(settings);update{copy(settings=settings,message=if(next.size<rows.size)"已移出本机常听" else "已加入本机常听")}
    }
    fun openLiveOfficial(room:Long,canLaunch:(String)->Boolean,launch:(String)->Unit)=operation {
        val url=LiveInput.official(room)
        if(!canLaunch(url))throw PlatformFailure("未找到可打开此房间的应用")
        deps.playback.pause(PauseReason.EXTERNAL_VIDEO);deps.playback.flush();liveReturnRoom=room;launch(url)
    }
    fun returnToLive(){deps.playback.liveEdge()}
    private suspend fun refreshFoldersNow() {
        val account = state.value.account ?: throw PlatformFailure("请登录账户")
        val folders = deps.favorites.folders(account.id, (if(state.value.pendingFavorite)state.value.favoriteTarget else state.value.video)?.aid)
        update { copy(folders = folders, foldersLoaded=true, refreshedAt = deps.clock.nowMs()) }
    }
    fun loadFolders() = operation { refreshFoldersNow(); loadBookmarksNow() }
    fun inspectFolderCreation()=operation {
        val result=requireNotNull(deps.folderCreation).inspect(operationStamp())
        update{copy(folderCreationPending=result.pending,folderCreationNote=result.message)}
    }
    fun createFolder(title:String,private:Boolean,makeDefault:Boolean=false,submitted:(String)->Unit={})=operation {
        if(state.value.account==null)throw PlatformFailure("请先登录账户")
        val name=title.trim();if(name.isEmpty()||name.length>20)throw PlatformFailure("收藏夹名称需要 1 至 20 个字")
        if(state.value.folderCreationPending)throw PlatformFailure("请先核对上次收藏夹创建结果")
        val before=if(makeDefault)deps.favorites.folders(requireNotNull(state.value.account).id).map{it.id}.toSet() else emptySet()
        val result=requireNotNull(deps.folderCreation).create(operationStamp(),name,private)
        update{copy(folderCreationPending=result.pending,folderCreationNote=result.message,message=result.message)}
        refreshFoldersNow()
        if(makeDefault && !result.pending) {
            val created=state.value.folders.filter{it.id !in before && it.title==name && (it.attr?.and(1)==1)==private}.singleOrNull()
            if(created!=null)saveDefaultFavoriteFolder(created)
        }
        submitted(if(makeDefault && !state.value.defaultFolderPrompt)"已创建并设为默认收藏夹" else result.message)
    }
    fun reconcileFolderCreation()=operation {
        val result=requireNotNull(deps.folderCreation).reconcile(operationStamp())
        update{copy(folderCreationPending=result.pending,folderCreationNote=result.message,message=result.message)}
        refreshFoldersNow()
    }
    fun openCollectionLink(text:String,ready:()->Unit={})=operation {
        update{copy(collectionLinkReading=true)}
        val account=state.value.account?.id ?: throw PlatformFailure("请先登录账户")
        val link=deps.sources.collectionLink(text)
        val result=deps.sources.resolve(link,account)
        if(result.source.ref.kind!=SourceKind.UP_COLLECTION||result.source.ref.collectionKind!=CollectionKind.SEASON||result.source.ref.id!=link.id||result.source.ref.owner!=link.owner)throw PlatformFailure("合集身份不匹配")
        saveSourcePage(result)
        val followed=try{deps.sources.isFollowed(account,result.source.ref)}catch(e:CancellationException){throw e}catch(_:Exception){null}
        update{copy(sourceFollowed=followed,message=if(followed==true)"此合集已在 B 站追更" else "核对合集内容后，可在详情确认追更到 B 站")}
        ready()
    }
    fun cancelCollectionLink(){if(state.value.collectionLinkReading){operationJob?.cancel();epoch++;mutable.value=mutable.value.copy(busy=false,collectionLinkReading=false)}}
    fun openFolder(folder: FavoriteFolder, more: Boolean = false) = operation { openFolderNow(folder, more) }
    private suspend fun openFolderNow(folder: FavoriteFolder, more: Boolean) {
        val owner = state.value.account?.id ?: throw PlatformFailure("请登录账户")
        val source = SourceRef(SourceKind.OWN_FAVORITES, folder.id, owner)
        val filter = if(state.value.folder?.id==folder.id)state.value.collectionFilter else ""
        if (!more) {
            val saved = deps.collections.collection(accountKey, source)
            if (saved != null) update { copy(folder = folder, favorites = saved.items.map { FavoriteItem(it.bvid,it.title) }, hasMore = !saved.complete, sourceCached = true, refreshedAt = saved.refreshedAt) }
        }
        val page = if (more && state.value.folder?.id == folder.id) favoritePage + 1 else 1
        if (more && (!state.value.hasMore || state.value.sourceCached)) throw PlatformFailure("请先刷新第一页，再继续加载")
        val result = deps.sources.content(source, owner, page)
        if (result.source.ref != source || result.page != page) throw PlatformFailure("收藏来源身份或页码不匹配")
        val before = if(more) state.value.favorites else emptyList()
        if (more && result.hasMore && result.items.none { item -> before.none { it.bvid == item.bvid } }) throw PlatformFailure("分页没有推进，保留已加载内容")
        val items = (before + result.items).distinctBy { it.bvid }
        CollectionCoordinator(deps.collections, deps.accounts, deps.clock).save(result.copy(items = items), operationStamp())
        favoritePage = page
        update { copy(folder = folder, favorites = items, hasMore = result.hasMore, sourceContent = null, sourceCached = false, refreshedAt = deps.clock.nowMs(), collectionFilter = filter,
            message = "${folder.title} · 已加载 ${items.size} 项${if(result.hasMore) "，尚未完整" else "，已完整"}") }
    }
    fun setCollectionFilter(text: String) { mutable.value = mutable.value.copy(collectionFilter = text) }
    fun selectSourceTab() { mutable.value = mutable.value.copy(folder = null, sourceContent = null, sources = null, sourceListOwner = null, favorites = emptyList(), sourceFollowed = null, collectionFilter = "",upProfile=null) }
    private suspend fun loadLibraryNow() {val preferences=deps.library?.read(operationStamp()) ?: LibraryPreferences();update{copy(library=preferences)}}
    private suspend fun loadBookmarksNow() { loadLibraryNow();val result = deps.collections.bookmarks(accountKey); update { copy(bookmarks = result) } }
    fun loadBookmarks() = operation { loadBookmarksNow() }
    fun localDataTransferred()=operation { loadBookmarksNow() }
    fun loadLibraryManagement(section:LibrarySection)=operation {
        update{copy(managementSection=section,managementSources=emptyList(),managementLoading=true,managementReady=false)}
        try {
            loadBookmarksNow()
            val rows=when(section) {
                LibrarySection.MINE->{refreshFoldersNow();val owner=requireNotNull(state.value.account).id
                    state.value.folders.map{ContentSource(SourceRef(SourceKind.OWN_FAVORITES,it.id,owner),it.title,"我的收藏夹",it.count,state.value.folderPreviews[it.id]?.firstOrNull()?.cover.orEmpty())}}
                LibrarySection.UP->state.value.bookmarks.filter{it.source.kind==SourceKind.UP_UPLOADS}.map{ContentSource(it.source,it.title,it.ownerName,it.total,it.cover)}
                LibrarySection.FOLLOWED->{
                    val account=state.value.account ?: throw PlatformFailure("请先登录账户")
                    val result=readAllFollowed(account.id)
                    update{copy(sources=result,sourceListOwner=null)}
                    followedLibraryRows(state.value).map{it.source}
                }
            }
            update{copy(managementSources=rows.distinctBy{it.ref},managementReady=true)}
        }finally{update{copy(managementLoading=false)}}
    }
    private suspend fun readAllFollowed(account:Long):SourceListPage {
        val all=linkedMapOf<SourceRef,ContentSource>();val unsupported=mutableSetOf<Int>();val unavailable=mutableSetOf<Long>();var page=1
        while(true) {
            currentCoroutineContext().ensureActive();val result=deps.sources.followed(account,page)
            if(result.page!=page)throw PlatformFailure("追更分页身份不匹配")
            val before=all.size;result.sources.forEach{all.putIfAbsent(it.ref,it)}
            if(result.hasMore&&all.size==before)throw PlatformFailure("追更分页没有推进，未更改本机管理数据")
            unsupported+=result.unsupportedTypes;unavailable+=result.unavailableSeasonIds
            update{copy(message="正在读取管理来源 · ${all.size} 个")}
            if(!result.hasMore)return result.copy(sources=all.values.toList(),unsupportedTypes=unsupported,unavailableSeasonIds=unavailable)
            page++
        }
    }
    fun cancelLibraryManagement(){if(state.value.managementLoading){operationJob?.cancel();epoch++;mutable.value=mutable.value.copy(busy=false,managementLoading=false,managementReady=false)}}
    fun hideLibrarySource(source:SourceRef,hidden:Boolean)=operation {
        require(state.value.managementReady&&state.value.managementSources.any{it.ref==source})
        val section=state.value.managementSection
        val preferences=requireNotNull(deps.library).update(operationStamp()){old->old.edit(section,deps.clock.nowMs()){it.copy(hidden=if(hidden)it.hidden+source else it.hidden-source)}}
        update{copy(library=preferences,message=if(hidden)"已在本机隐藏，可在管理中恢复" else "已恢复显示")}
    }
    fun moveLibrarySource(source:SourceRef,target:Int)=operation {
        require(state.value.managementReady)
        val section=state.value.managementSection
        val refs=state.value.library.apply(section,state.value.managementSources,true){it.ref}.map{it.ref}.toMutableList()
        val from=refs.indexOf(source);require(from>=0&&target in refs.indices)
        refs.add(target,refs.removeAt(from))
        val preferences=requireNotNull(deps.library).update(operationStamp()){old->old.edit(section,deps.clock.nowMs()){it.copy(order=refs+it.order.filterNot{ref->ref in refs})}}
        update{copy(library=preferences,message="本机显示顺序已保存")}
    }
    fun resetLibraryLayout(order:Boolean)=operation {
        require(state.value.managementReady);val section=state.value.managementSection
        val preferences=requireNotNull(deps.library).update(operationStamp()){old->old.edit(section,deps.clock.nowMs()){if(order)it.copy(order=emptyList()) else it.copy(hidden=emptySet())}}
        update{copy(library=preferences,message=if(order)"已恢复默认顺序" else "已恢复全部显示")}
    }
    fun bookmarkSource(saved: Boolean) = operation {
        if(state.value.account == null && state.value.sourceContent?.source?.ref?.kind!=SourceKind.UP_UPLOADS) throw PlatformFailure("请登录账户")
        val source = state.value.sourceContent?.source ?: return@operation
        deps.collections.bookmark(accountKey, source, saved); loadBookmarksNow()
        update { copy(message = if(saved) "已保存本机来源入口，不改变远端关系" else "已移除本机入口，不改变远端关系") }
    }
    fun inspectFollow() = operation {
        val owner = state.value.account?.id ?: throw PlatformFailure("请登录账户")
        val source = state.value.sourceContent?.source?.ref ?: return@operation
        val followed = deps.sources.isFollowed(owner, source)
        update { copy(sourceFollowed = followed) }
    }
    fun followSource(follow: Boolean) = operation {
        val owner = state.value.account?.id ?: throw PlatformFailure("请登录账户")
        val source = state.value.sourceContent?.source?.ref ?: return@operation
        update { copy(mutationStatus = "处理中") }
        try {
            val result = deps.sources.follow(owner, source, follow)
            val pending = deps.favorites.pending(accountKey)
            update { copy(mutationPending = pending, sourceFollowed = if(result == MutationOutcome.UNKNOWN) null else follow,
                mutationStatus = if(result == MutationOutcome.UNKNOWN) "结果待核对" else "已确认", message = if(result == MutationOutcome.UNKNOWN) "追更结果待核对，未重试写入" else "已回读确认追更关系") }
            if(result != MutationOutcome.UNKNOWN) {
                val sources = deps.sources.followed(owner)
                update { copy(sources = sources, sourceListOwner = null) }
            }
        } catch(e: Exception) {
            val pending = deps.favorites.pending(accountKey)
            update { copy(mutationPending = pending, mutationStatus = "请核对操作结果") }; throw e
        }
    }
    fun cancelCollectionPlayback() {
        if (!state.value.collectionPreparing) return
        operationJob?.cancel()
        mutable.value = mutable.value.copy(message = "已取消读取收藏，原播放队列保留")
    }
    fun playCollection(shuffle: Boolean = false, onReady: () -> Unit = {}) = operation {
        val source = state.value.sourceContent?.source?.ref ?: state.value.folder?.let { SourceRef(SourceKind.OWN_FAVORITES,it.id,state.value.account!!.id) }
            ?: throw PlatformFailure("请先打开来源")
        val stamp = operationStamp()
        val owner = state.value.account?.id
        val uploadOrder=state.value.upOrder;val uploadQuery=state.value.upQuery
        suspend fun checkCurrent() { currentCoroutineContext().ensureActive(); deps.accounts.requireCurrent(stamp) }
        update { copy(collectionPreparing = true, collectionProgress = "正在读取收藏列表…") }
        val items = linkedMapOf<String,FavoriteItem>()
        var page = 1
        while (true) {
            checkCurrent()
            val result = if(source.kind==SourceKind.UP_UPLOADS)requireNotNull(deps.upLibrary).uploads(source.owner,page,uploadOrder,uploadQuery) else deps.sources.content(source, owner, page)
            checkCurrent()
            if(result.page != page || result.source.ref != source) throw PlatformFailure("分页身份不匹配")
            val previousCount = items.size
            result.items.forEach { items.putIfAbsent(it.bvid,it) }
            if(result.hasMore && items.size == previousCount) throw PlatformFailure("分页没有推进，原播放队列保留")
            update { copy(collectionProgress = "已读取 ${items.size} 项 · ${if(result.hasMore) "正在读取下一页" else "正在准备队列"}") }
            if (!result.hasMore) break
            page++
        }
        val entries = mutableListOf<QueueEntry>(); var missing = 0
        if(source.kind==SourceKind.UP_UPLOADS) {
            val rows=items.values.toList().let{if(uploadOrder==UploadOrder.OLDEST)it.reversed() else it}.let{if(shuffle)it.shuffled() else it}
            rows.forEach{item->entries+=QueueEntry(UUID.randomUUID().toString(),item.bvid,0,1,item.title,source=source)}
            // Only the starting song needs P1 details before playback; the rest resolve on demand.
            while(entries.isNotEmpty()) {
                checkCurrent()
                try {
                    val first=entries.first();val video=deps.content.video(first.bvid);checkCurrent()
                    if(video.bvid!=first.bvid||!video.hasCreator(source.owner))throw PlatformFailure("投稿作者身份不匹配")
                    val part=video.parts.firstOrNull{it.number==1} ?: throw PlatformFailure("平台响应格式变化")
                    entries[0]=first.copy(cid=part.cid,title=video.title);break
                }catch(e:PlatformFailure){checkCurrent();if(e.kind() in setOf(FailureKind.MISSING,FailureKind.ACCESS_DENIED)){entries.removeAt(0);missing++} else throw e}
            }
        }
        // Resolve P1 metadata only. Audio URLs and bytes remain lazy in the playback service.
        for((index,item) in (if(source.kind==SourceKind.UP_UPLOADS)emptyList() else items.values.toList()).withIndex()) {
            checkCurrent()
            try {
                val video = deps.content.video(item.bvid)
                checkCurrent()
                if(video.bvid != item.bvid) throw PlatformFailure("视频身份不匹配")
                val part = video.parts.firstOrNull { it.number == 1 } ?: throw PlatformFailure("平台响应格式变化")
                entries += QueueEntry(UUID.randomUUID().toString(),video.bvid,part.cid,part.number,video.title,source=source)
            } catch(e: PlatformFailure) { checkCurrent(); if(e.kind() in setOf(FailureKind.MISSING,FailureKind.ACCESS_DENIED)) missing++ else throw e }
            update { copy(collectionProgress = "正在准备队列 ${index+1}/${items.size}${if(missing>0) " · 跳过 $missing 项" else ""}") }
        }
        if(entries.isEmpty()) throw PlatformFailure("收藏中没有可播放内容")
        checkCurrent()
        val order = entries.map { it.id }.let { if(shuffle&&source.kind!=SourceKind.UP_UPLOADS) it.shuffled() else it }
        val snapshot = ResumeSnapshot(account=stamp.account,entries=entries,order=order,currentId=order.first(),positionMs=0,mode=if(shuffle) PlayMode.SHUFFLE else PlayMode.SEQUENTIAL).checked()
        // Cancellation applies to reading; hide it before the single service commit begins.
        update { copy(collectionPreparing = false, collectionProgress = "") }
        deps.playback.replace(snapshot,true)
        update { copy(message = "已加入全部 ${entries.size} 个视频的 P1${if(missing>0) "；跳过 $missing 个不可访问视频" else ""}。后续更新不会插入当前队列") }
        onReady()
    }
}
