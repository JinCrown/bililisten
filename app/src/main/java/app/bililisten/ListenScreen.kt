package app.bililisten

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import app.bililisten.platform.WebLoginActivity
import app.bililisten.platform.OfficialAccountLinks
import app.bililisten.platform.OfficialAccountPage
import app.bililisten.shared.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.*

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi @Composable
internal fun ListenScreen(vm:MainViewModel,widgetOpen:Int=0,notificationFavorite:app.bililisten.playback.NotificationFavoriteTarget?=null) {
    val s by vm.state.collectAsStateWithLifecycle()
    ListenTheme(s.settings.theme) { ListenContent(vm,s,widgetOpen,notificationFavorite) }
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi @Composable
internal fun ListenContent(vm:MainViewModel,s:ScreenState,widgetOpen:Int=0,notificationFavorite:app.bililisten.playback.NotificationFavoriteTarget?=null) {
    val context=LocalContext.current
    val folderColumns=if(LocalConfiguration.current.screenWidthDp<360 || LocalDensity.current.fontScale>1.3f)2 else 3
    val view=LocalView.current
    val colors=MaterialTheme.colorScheme
    val app=context.applicationContext as ListenApplication
    val organizerVm: OrganizerViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val transferVm:TransferViewModel=androidx.lifecycle.viewmodel.compose.viewModel()
    val sharedInput by vm.sharedInput.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    val favoriteSnack=remember{SnackbarHostState()}
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    var tab by rememberSaveable{mutableIntStateOf(0)}
    var page by rememberSaveable{mutableStateOf("root")}
    var subtitleFraction by remember { mutableFloatStateOf(if(page=="subtitles")1f else 0f) }
    val playerPages=page in setOf("player","subtitles","videoDetails")
    val readerFraction=if(playerPages)subtitleFraction else if(page=="lyrics")1f else 0f
    val chromeColor=lerp(colors.background,Color(0xFF42344F),readerFraction)
    LaunchedEffect(playerPages) {if(!playerPages)subtitleFraction=0f}
    SideEffect {
        (context as? Activity)?.window?.let { window ->
            window.statusBarColor=chromeColor.toArgb();window.navigationBarColor=chromeColor.toArgb()
            val controller=WindowCompat.getInsetsController(window,view)
            controller.isAppearanceLightStatusBars=chromeColor.luminance()>.5f
            controller.isAppearanceLightNavigationBars=chromeColor.luminance()>.5f
        }
    }
    DisposableEffect(page=="subtitles") {val opened=page=="subtitles";if(opened)vm.openSubtitles();onDispose{if(opened)vm.closeSubtitles()}}
    DisposableEffect(page=="lyrics") {val opened=page=="lyrics";if(opened)vm.openLyrics();onDispose{if(opened)vm.closeLyrics()}}
    DisposableEffect(page=="messages") {val opened=page=="messages";if(opened)vm.openMessages();onDispose{if(opened)vm.closeMessages()}}
    DisposableEffect(page in setOf("subtitles","lyrics")) {
        vm.timedTextVisible(page in setOf("subtitles","lyrics"))
        onDispose { vm.timedTextVisible(false) }
    }
    DisposableEffect(page=="videoDetails") {onDispose {vm.closeVideoDetails()}}
    LaunchedEffect(page,activeDetailKey(s),s.account?.id,s.accountChecked,s.busy) {
        if(page=="videoDetails"&&!s.busy&&!s.isLive)activeDetailKey(s)?.let(vm::openVideoDetails)
    }
    var playerOrigin by rememberSaveable{mutableStateOf("root")}
    var upOrigin by rememberSaveable{mutableStateOf("root")}
    var storageOrigin by rememberSaveable{mutableStateOf("root")}
    var sourceTab by key("favorites-tabs-v2"){rememberSaveable{mutableIntStateOf(0)}}
    var query by rememberSaveable{mutableStateOf("")}
    var searchEditing by rememberSaveable{mutableStateOf(true)}
    LaunchedEffect(page,query,searchEditing){vm.searchHints(page=="search" && (searchEditing || query.isBlank()),query)}
    DisposableEffect(vm){onDispose{vm.searchHints(false,"")}}
    var input by rememberSaveable{mutableStateOf("")}
    val category=s.recommendationCategory
    var sheet by rememberSaveable{mutableStateOf<String?>(null)}
    var newFolderOpen by rememberSaveable(s.account?.id){mutableStateOf(false)}
    var newFolderName by rememberSaveable(s.account?.id){mutableStateOf("")}
    var newFolderDefault by rememberSaveable(s.account?.id){mutableStateOf(false)}
    var newFolderPrivate by rememberSaveable(s.account?.id){mutableStateOf(false)}
    var notice by remember{mutableStateOf<String?>(null)}
    var confirmTitle by remember{mutableStateOf<String?>(null)}
    var confirmBody by remember{mutableStateOf("")}
    var confirmAction by remember{mutableStateOf<()->Unit>({})}
    var historySyncConfirmOwner by remember{mutableStateOf<String?>(null)}
    var historySyncConfirmWord by remember{mutableStateOf("")}
    LaunchedEffect(s.remoteHistory.account){if(historySyncConfirmOwner!=s.remoteHistory.account){historySyncConfirmOwner=null;historySyncConfirmWord=""}}
    var storageUsage by remember{mutableStateOf(StorageUsage())}
    suspend fun refreshStorage() {
        val records=withContext(Dispatchers.IO){listOf(app.getDatabasePath("listening.db"),app.getDatabasePath("listening.db-wal"),java.io.File(app.filesDir,"datastore"),java.io.File(app.noBackupFilesDir,"organizer"),java.io.File(app.cacheDir,"home-v1"),java.io.File(app.cacheDir,"home-ranking-v2")).sumOf{f->if(f.isDirectory)f.walkTopDown().filter{it.isFile}.sumOf{it.length()} else f.length()}}
        storageUsage=StorageUsage(records,app.covers.bytes(),app.automaticAudio.bytes(),app.downloads.bytes())
    }
    fun clearStorage(pictures:Boolean,automatic:Boolean) {scope.launch {try {if(pictures)app.covers.clear();if(automatic)app.automaticAudio.clear();refreshStorage();notice="缓存已清理，主动下载和收听记录保留"}catch(_:Exception){notice="部分缓存未能清理，请稍后再试"}}}
    var liveInput by rememberSaveable{mutableStateOf("")}
    var jumpFallback by remember{mutableStateOf(false)}
    var historyQuery by rememberSaveable { mutableStateOf("") }
    var historyType by rememberSaveable { mutableStateOf(HistoryType.ALL) }
    var historyOrigin by rememberSaveable { mutableStateOf<HistoryOrigin?>(null) }
    val recentHistory=remember(s.history,s.liveHistory){HistorySearch.filter(s.history,s.liveHistory)}
    val filteredHistory=remember(s.history,s.liveHistory,historyQuery,historyType,historyOrigin){HistorySearch.filter(s.history,s.liveHistory,historyQuery,historyType,historyOrigin)}
    var selectedHistory by remember{mutableStateOf<LocalHistoryEntry?>(null)}
    val saved=rememberSaveableStateHolder()
    val active=s.queue.firstOrNull{it.id==s.currentId} ?: s.resume?.let{q->q.entries.firstOrNull{it.id==q.currentId}}
    val current=s.metadata[active?.bvid]
    val hasSession=s.queue.isNotEmpty() || s.resume!=null || s.isLive
    LaunchedEffect(widgetOpen) {
        if(widgetOpen>0) {
            val originPage=page;val originTab=tab
            val ready=vm.state.first{it.queue.isNotEmpty()||it.resume!=null||it.isLive||it.startupReady&&!it.busy}
            if(page!=originPage||tab!=originTab)return@LaunchedEffect
            sheet=null
            playerOrigin="root"
            page=if(ready.queue.isNotEmpty()||ready.resume!=null||ready.isLive)"player" else "root"
        }
    }
    LaunchedEffect(notificationFavorite) {
        val target=notificationFavorite ?: return@LaunchedEffect
        val ready=vm.state.first{it.accountChecked && it.connected && !it.busy}
        if(app.accounts.session.value.stamp!=SessionStamp(target.account,target.generation) || ready.currentId!=target.id || ready.queue.none{it.id==target.id && it.bvid==target.bvid})return@LaunchedEffect
        sheet=null;playerOrigin="root";page="player"
        vm.toggleDefaultFavorite(target.bvid){message->scope.launch{favoriteSnack.showSnackbar(message)}}
    }
    val position=if(s.queue.isEmpty())s.resume?.positionMs ?: 0 else s.positionMs
    // The view endpoint reports the whole video's duration, not the current part.
    val duration=if(s.durationMs>0)s.durationMs else (current?.takeIf{it.parts.size==1}?.duration ?: 0)*1000
    val login=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){if(it.resultCode==Activity.RESULT_OK)vm.loginFinished() else vm.loginCancelled()}
    val startLogin={vm.prepareLogin();login.launch(Intent(context,WebLoginActivity::class.java).putExtra("darkUi",colors.background.luminance()<.5f))}
    val openPlayer={if(hasSession){playerOrigin=page;page="player"}else{notice="先选择一个视频，再开始收听。"}}
    val toggle={if(s.queue.isEmpty()&&!s.isLive)vm.continueListening() else vm.toggle()}
    fun openSearch(){searchEditing=true;page="search"}
    fun editSearch(value:String){query=value.take(100);searchEditing=true;vm.cancelSearch()}
    fun submitSearch(value:String){
        query=value.take(100);page="search"
        searchEditing=query.isBlank()
        vm.searchVideos(query)
        if(!searchEditing){focus.clearFocus();keyboard?.hide()}
    }
    fun confirm(title:String,body:String,action:()->Unit){confirmTitle=title;confirmBody=body;confirmAction=action}
    fun browse(url:String){try{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}catch(_:Exception){notice="没有找到可打开链接的应用，可复制链接后再试。"}}
    fun officialAccount(page:OfficialAccountPage){
        if(!OfficialAccountLinks.open(context,page))notice="无法打开 B 站客户端，请安装或更新本机 B 站 App 后再试。"
    }
    fun copy(text:String){context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("视频链接",text));notice="已复制链接"}
    fun openUp(profile:UpProfile){if(s.busy)return;upOrigin=page;vm.openUp(profile);page="uploads"}
    fun back(){focus.clearFocus();keyboard?.hide();if(page=="manage")vm.cancelLibraryManagement();if(page=="upSearch")vm.cancelUpSearch();if(page=="uploads")vm.cancelUpLoading();val target=when(page){"subtitles","lyrics","videoDetails"->"player";"player"->playerOrigin;"uploads"->upOrigin;"storage"->storageOrigin;"appearance","privacy","audio","about","features","transfer","widgets"->"settings";"settings","history","account","messages","downloads"->"root";else->"root"};if(page=="uploads"&&target=="player")upOrigin="root";page=target}
    fun inspect(bvid:String,folder:Long?=null,source:SourceRef?=null,origin:HistoryOrigin=HistoryOrigin.LINK){vm.inspect(bvid,folder,source,origin);sheet="parts"}
    fun openLiveRoom(room:Long){liveInput=room.toString();page="live";vm.inspectLive(liveInput)}
    fun startLive(){
        fun start(consent:Boolean){vm.playLiveWithConsent(consent){playerOrigin="live";page="player"}}
        if(s.liveStreams.any{it.kind==StreamKind.AUDIO_ONLY})start(false)
        else confirm("使用含视频数据的直播来源？","当前没有独立音频来源。只输出声音，但混流仍含视频数据，会增加流量；不录制、不下载。取消后可选择 B站查看。"){start(true)}
    }
    fun liveOfficial(room:Long){vm.openLiveOfficial(room,{url->Intent(Intent.ACTION_VIEW,Uri.parse(url)).resolveActivity(context.packageManager)!=null},{url->browse(url)})}
    fun jump(browser:Boolean=false){
        if(s.queue.isEmpty()&&!s.isLive){notice="请先继续收听，再打开当前视频。";return}
        vm.openOfficialChecked({url->Intent(Intent.ACTION_VIEW,Uri.parse(url)).apply{if(!browser)setPackage("tv.danmaku.bili")}.resolveActivity(context.packageManager)!=null},{url->
            try{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).apply{if(!browser)setPackage("tv.danmaku.bili")})}catch(_:Exception){jumpFallback=true}
        })
        val url=if(s.isLive)s.liveExperience.roomId.takeIf{it>0}?.let(LiveInput::official) else active?.let{VideoJump.url(it,position)}
        if(url!=null && Intent(Intent.ACTION_VIEW,Uri.parse(url)).apply{if(!browser)setPackage("tv.danmaku.bili")}.resolveActivity(context.packageManager)==null)jumpFallback=true
    }
    fun officialRights(browser:Boolean=false) {
        fun target(url:String)=Intent(Intent.ACTION_VIEW,Uri.parse(url)).apply {if(!browser)setPackage("tv.danmaku.bili")}
        if(!browser && target("https://account.bilibili.com/account/big").resolveActivity(context.packageManager)==null) {
            confirm("使用浏览器查看账户权益？","未找到可打开此页面的 B 站 App。浏览器登录与本软件登录独立，可能需要单独登录；返回后仍按实际音轨检查结果显示。"){officialRights(true)}
            return
        }
        vm.openOfficialRights({url->target(url).resolveActivity(context.packageManager)!=null},{url->context.startActivity(target(url))})
    }
    fun dismissSheet(){if(sheet=="sourceLink")vm.cancelCollectionLink();sheet=null}
    BackHandler(page!="root" || sheet!=null){if(sheet!=null)dismissSheet() else if(page=="messages"&&s.messages.selected!=null)vm.backMessages() else back()}
    LaunchedEffect(active?.bvid,s.account?.id){active?.takeUnless{it.offline}?.bvid?.let(vm::loadMetadata)}
    LaunchedEffect(s.currentId,s.queue){s.queue.getOrNull(s.queue.indexOfFirst{it.id==s.currentId}+1)?.takeUnless{it.offline}?.bvid?.let(vm::loadMetadata)}
    LaunchedEffect(page,tab){focus.clearFocus();keyboard?.hide();withFrameNanos{};focus.clearFocus();keyboard?.hide()}
    LaunchedEffect(page,s.downloads,s.settings.storage){if(page=="storage")refreshStorage()}
    LaunchedEffect(page){if(page=="uploads"){vm.state.first{!it.busy};vm.refreshUpLocal()}}
    LaunchedEffect(page,tab,sourceTab,s.account?.id,s.accountChecked){if(s.accountChecked&&page=="root"){vm.state.first{!it.busy};if(tab==0){vm.loadPopularMusic();vm.loadHomeUps();vm.preloadRecommendationCategories()};if(tab==1){when(sourceTab){0->if(s.account!=null)vm.loadFolders();2->vm.loadBookmarks();else->if(s.account!=null)vm.loadFollowedSources()}};if(tab==2&&s.account!=null)vm.loadProfile()}}
    LaunchedEffect(s.account?.id,s.accountChecked){if(s.account!=null && s.accountChecked){vm.state.first{!it.busy};vm.ensureDefaultFavoriteFolder()}}
    LaunchedEffect(active?.bvid,s.account?.id,s.settings.defaultFavoriteFolders,s.busy){if(!s.busy&&!s.isLive)active?.let{vm.readCurrentFavorite(it.bvid)}}
    fun favoriteCurrent(){active?.let{vm.toggleDefaultFavorite(it.bvid){message->scope.launch{favoriteSnack.showSnackbar(message)}}}}
    fun chooseCurrentFavorite(){active?.let{vm.chooseFavorite(it.bvid)}}
    val root=page=="root"
    Surface(Modifier.fillMaxSize(),color=chromeColor) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(if(playerPages||page=="lyrics")listOf(chromeColor,lerp(colors.background,Color(0xFF1C1A29),readerFraction)) else listOf(blush().copy(alpha=if(root&&tab in 0..1).3f else .55f),MaterialTheme.colorScheme.background),endY=760f))) {
            Scaffold(containerColor=Color.Transparent,contentWindowInsets=WindowInsets.safeDrawing,snackbarHost={SnackbarHost(favoriteSnack)},
                bottomBar={if(page!in setOf("player","subtitles","videoDetails","lyrics"))Column(Modifier.navigationBarsPadding().background(MaterialTheme.colorScheme.background)){
                    if(s.isLive&&s.resume!=null)TextButton(vm::restoreVod,enabled=!s.busy){Text("返回保留的点播队列（暂停）")}
                    if(s.timerRemainingMs>0)Text("定时停止 · 剩余 ${timeLabel(s.timerRemainingMs)}",Modifier.fillMaxWidth().clickable{sheet="listening"}.padding(horizontal=PageSideInset),fontSize=11.sp,color=ListenPink)
                    if(hasSession)MiniPlayer(s,current,active,position,openPlayer,toggle,vm::next,{sheet="queue"},::favoriteCurrent,::chooseCurrentFavorite)
                    if(root)Row(Modifier.fillMaxWidth().padding(horizontal=18.dp),horizontalArrangement=Arrangement.SpaceAround,verticalAlignment=Alignment.CenterVertically){
                        listOf("首页","收藏","我的").forEachIndexed{index,title->val selected=tab==index
                            Column(Modifier.weight(1f).semantics{this.selected=selected;role=Role.Tab}.clickable(interactionSource=remember{MutableInteractionSource()},indication=null){tab=index;focus.clearFocus()}.heightIn(min=50.dp).padding(vertical=2.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){Box(Modifier.size(54.dp,28.dp).background(if(selected)blush() else Color.Transparent,CircleShape),contentAlignment=Alignment.Center){Glyph(listOf(Mark.HOME,Mark.STAR,Mark.TV)[index],Modifier.size(if(index==2)23.dp else 21.dp),if(selected)MaterialTheme.colorScheme.primary else muted(),selected)};Text(title,Modifier.padding(top=2.dp),fontSize=11.sp,lineHeight=14.sp,fontWeight=if(selected)FontWeight.Bold else FontWeight.Normal,color=if(selected)MaterialTheme.colorScheme.primary else muted(),maxLines=1)}
                        }
                    }
                }}
            ){padding->
                if(page in setOf("player","subtitles","videoDetails"))PlayerWorkspace(s,current,active,page,{page=it},Modifier.padding(padding).consumeWindowInsets(padding),back={page=playerOrigin},creator={current?.takeIf{it.owner>0}?.let{openUp(UpProfile(it.owner,it.author,it.avatar))}},timer={sheet="timer"},lyrics={page="lyrics"},audio={sheet="audio"},player={
                    PlayerPage(s,current,active,position,duration,Modifier,::back,toggle,vm::seekTo,vm::previous,vm::next,{sheet="queue"},{vm.changeMode(PlayMode.entries[(s.mode.ordinal+1)%PlayMode.entries.size])},
                        ::favoriteCurrent, {page="subtitles"},{jump()}, {notice=it}, {sheet="timer"}, vm::seekBy,{sheet="audio"},{page="lyrics"},{confirm("下载当前分 P 的音频？","只保存完整普通音轨，不下载视频画面，不开始播放。下载范围会先核对，进度可在下载管理查看。"){vm.downloadCurrent();page="downloads"}},speed={sheet="speed"},creator={current?.takeIf{it.owner>0}?.let{openUp(UpProfile(it.owner,it.author,it.avatar))}},chooseFavorite=::chooseCurrentFavorite,liveEdge=vm::returnToLive,restoreVod=vm::restoreVod)
                },subtitles={
                    SubtitlePage(s,current,Modifier,{page="player"},toggle,vm::seekTo,vm::previous,vm::next,{sheet="queue"},vm::retrySubtitles,vm::selectSubtitle,vm::subtitleMode,startLogin)
                },details={
                    VideoDetailsPage(s,current,active,position,duration,Modifier,{page="player"},toggle,vm::seekTo,vm::previous,vm::next,{sheet="queue"},
                        {active?.bvid?.let(vm::openVideoDetails)},startLogin,::chooseCurrentFavorite,
                        vm::engageVideo,vm::reconcileFavorite,{current?.takeIf{it.owner>0}?.let{openUp(UpProfile(it.owner,it.author,it.avatar))}},{jump()},::copy,favorite=::favoriteCurrent)
                },chrome={subtitleFraction=it})
                else if(page=="lyrics")LyricsPage(s,current,Modifier.padding(padding).consumeWindowInsets(padding).imePadding(),{page="player"},toggle,vm::seekTo,vm::previous,vm::next,{sheet="queue"},vm::searchLyrics,vm::selectLyrics,vm::offsetLyrics,vm::confirmLyrics)
                else if(page=="messages")MessagesPage(s.messages,Modifier.padding(padding).consumeWindowInsets(padding),::back,vm::backMessages,vm::selectMessages,vm::openConversation,vm::refreshMessages,vm::refreshMessagesNow,vm::moreMessages,startLogin,{officialAccount(OfficialAccountPage.MESSAGES)})
                else if(page=="manage")LibraryManagementPage(s,Modifier.padding(padding).consumeWindowInsets(padding),::back,{section->sourceTab=section.ordinal;vm.loadLibraryManagement(section)},vm::hideLibrarySource,vm::moveLibrarySource,vm::resetLibraryLayout,vm::cancelLibraryManagement)
                else if(page=="organize")Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                    Row(verticalAlignment=Alignment.CenterVertically){ActionIcon(Mark.BACK,"返回收藏",{page="root";tab=1;vm.loadFolders()});Text("收藏整理与查找",fontSize=20.sp,fontWeight=FontWeight.Bold)}
                    OrganizerPage(organizerVm,{bvid,folder,origin->vm.playVideo(bvid,folder,origin=origin)},startLogin,Modifier.weight(1f))
                }
                else Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
                    if(!root)Row(Modifier.fillMaxWidth().heightIn(min=66.dp).padding(horizontal=PageSideInset),verticalAlignment=Alignment.CenterVertically){ActionIcon(Mark.BACK,"返回",::back);Text(when(page){"upSearch"->"搜索 UP 主";"uploads"->"UP 主投稿";"search"->"搜索";"detail"->s.folder?.title ?: s.sourceContent?.source?.title ?: "来源详情";"history"->"最近收听";"settings"->"设置";"storage"->"缓存管理";"appearance"->"外观";"privacy"->"隐私与本机历史";"audio"->"音质与播放";"account"->"账号";"link"->"打开链接";"live"->"直播收听";"downloads"->"下载管理";"messages"->"消息";"transfer"->"数据转移";"about"->"关于哔哩听视频";else->"更多功能"},Modifier.weight(1f).padding(start=6.dp),fontWeight=FontWeight.Bold,fontSize=22.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}
                    if(s.busy&&!(root&&tab in 1..2))LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp),color=ListenPink)
                    s.error?.let{error->Row(Modifier.fillMaxWidth().background(blush()).padding(horizontal=16.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically){Text(error,Modifier.weight(1f),fontSize=12.sp);ActionIcon(Mark.CLOSE,"关闭提示",vm::dismissError)}}
                    if(!s.accountChecked&&!s.busy)TextButton(vm::checkAccount){Text("重试账号检查")}
                    if(s.mutationPending!=null)TextButton(vm::reconcileFavorite,enabled=!s.busy){Text("上次操作结果待核对 · 点击核对")}
                    saved.SaveableStateProvider("$page/$tab/$sourceTab") {
                        val list=rememberLazyListState()
                        UserRefreshBox(s.busy,{
                            if(page=="uploads")vm.loadUp() else vm.foregrounded(true)
                            vm.state.first{!it.busy}
                            if(root&&tab==0)vm.refreshHome()
                        },Modifier.weight(1f)) {
                            LazyColumn(Modifier.fillMaxSize().imePadding(),state=list,contentPadding=PaddingValues(start=PageSideInset,end=PageSideInset,bottom=26.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                if(root&&tab==0){
                                    item{HomeSearchBar(query,::openSearch){page="link"}}
                                    item{HomeRecommendationHeader(category,vm::selectRecommendationCategory)}
                                    val recommendations=s.recommendations
                                    item{if(category==HomeCategory.LIVE)HomeLiveRanking(s,::openLiveRoom){vm.loadLiveRanking(true)} else if(recommendations.isEmpty())EmptyState(if(s.recommendationBusy)"正在读取推荐" else if(s.recommendationError!=null)"推荐暂时不可用" else "暂无可显示的推荐",s.recommendationError ?: if(category.rid==null)"近 30 天暂无可显示的相关投稿" else "下拉刷新，或直接搜索想听的视频","搜索视频",::openSearch) else HeroCarousel(recommendations,category){vm.playVideo(it,origin=HistoryOrigin.RECOMMENDATION)}}
                                    item{HomePopularMusic(s,{vm.loadMorePopularMusic()},{vm.loadMorePopularMusic(true)}) { row->
                                        row.collection?.let{vm.openSource(it.ref);page="detail"}
                                            ?: vm.playVideo(row.bvid,origin=HistoryOrigin.RECOMMENDATION)
                                    }}
                                    item{HomeSectionHeading("推荐 UP","更多",{page="upSearch"},if(s.settings.musicRecommendations)"音乐热榜创作者" else "B站首页推荐创作者")}
                                    item{HomeCreators(s,::openUp)}
                                    item{HomeSectionHeading("最近","查看全部",{page="history"})}
                                    if(recentHistory.isEmpty())item{EmptyState("从一段好声音开始",if(s.account==null)"无需登录，收听后会保存在本机" else "收听过的视频会出现在这里")}
                                    if(recentHistory.isNotEmpty())item {
                                        LazyRow(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                            items(recentHistory.take(6),key={"home-${it.key}"}){item->
                                                item.video?.let{row->HomeRecentCard(vm,s,row,{vm.playHistory(row);playerOrigin="root";page="player"},{selectedHistory=row;sheet="historyActions"})}
                                                item.live?.let{row->HomeRecentLiveCard(row,{openLiveRoom(row.roomId)},{confirm("删除这条直播历史？",if(s.remoteHistory.enabled)"将同步删除 B站的这条直播历史。" else "只删除本机记录。"){vm.deleteLiveHistory(row)}})}
                                            }
                                        }
                                    }
                                    item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){TextButton(::openSearch){Text("发现更多",fontSize=12.sp,color=muted())};TextButton({page="live"}){Text("直播收听",fontSize=12.sp,color=muted())}}}
                                } else if(root&&tab==1){
                                    item{FavoritesHeader(s.accountChecked&&!s.busy){page="manage";vm.loadLibraryManagement(if(s.account==null)LibrarySection.UP else LibrarySection.entries[sourceTab])}}
                                    item { FavoriteSourceTabs(sourceTab){sourceTab=it;vm.selectSourceTab()} }
                                    if(sourceTab==2) {
                                        item{SectionTitle("UP 收藏",null);Text("把喜欢的 UP 主投稿作为歌单收听",fontSize=12.sp,color=muted());Button({page="upSearch"},Modifier.fillMaxWidth().padding(top=12.dp)){Glyph(Mark.SEARCH,Modifier.size(18.dp));Text("搜索 / 添加 UP 主",Modifier.padding(start=8.dp))}}
                                        val ups=s.library.apply(LibrarySection.UP,s.bookmarks.filter{it.source.kind==SourceKind.UP_UPLOADS}){it.source}
                                        if(ups.isEmpty()&&!s.busy)item{EmptyState(if(s.bookmarks.any{it.source.kind==SourceKind.UP_UPLOADS})"UP 收藏已隐藏" else "还没有 UP 收藏",if(s.bookmarks.any{it.source.kind==SourceKind.UP_UPLOADS})"在管理中可恢复显示" else "搜索 UP 主，或在播放器点击作者名，进入投稿页即可收藏")}
                                        items(ups,key={it.source.owner}){row->UpCard(UpProfile(row.source.owner,row.ownerName.ifBlank{row.title},row.cover,videos=row.total)){if(!s.busy){upOrigin="root";vm.openSavedUp(row);page="uploads"}}}
                                    } else if(s.account==null)item{EmptyState("请登录账户","登录后收听自己的收藏和追更的合集、收藏夹","登录",startLogin)}
                                    else {
                                        if(sourceTab==0){
                                            val folders=s.library.apply(LibrarySection.MINE,s.folders){SourceRef(SourceKind.OWN_FAVORITES,it.id,s.account.id)}
                                            item{LibrarySectionHeader("我的收藏夹","${folders.size} 个","新建收藏夹",!s.busy){newFolderName="";newFolderPrivate=false;newFolderDefault=false;newFolderOpen=true;vm.inspectFolderCreation()}}
                                            if(folders.isEmpty()&&!s.busy&&s.foldersLoaded)item{EmptyState(if(s.folders.isNotEmpty())"收藏夹已隐藏" else "暂无收藏夹",if(s.folders.isNotEmpty())"在管理中可恢复显示" else "在 B 站收藏的视频会出现在这里")}
                                            items(folders.chunked(folderColumns)){group->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                                group.forEach{folder->LaunchedEffect(folder.id){vm.loadFolderPreview(folder)};FolderCard(folder,s.folderPreviews[folder.id]?.firstOrNull()?.cover.orEmpty(),Modifier.weight(1f)){vm.openFolder(folder);page="detail"}}
                                                repeat(folderColumns-group.size){Spacer(Modifier.weight(1f))}
                                            }}
                                            val recent=folders.flatMap{f->s.folderPreviews[f.id].orEmpty().map{f to it}}.take(6)
                                            if(recent.isNotEmpty())item{SectionTitle("最近收藏到",Mark.CLOCK);Text("已加载收藏夹的最近条目",fontSize=11.sp,color=muted())}
                                            items(recent){(folder,item)->VideoRowCard(item.title,item.cover,item.author,item.duration,folder=folder.title,onClick={vm.playVideo(item.bvid,folder.id,SourceRef(SourceKind.OWN_FAVORITES,folder.id,s.account.id))},onMore={inspect(item.bvid,folder.id,SourceRef(SourceKind.OWN_FAVORITES,folder.id,s.account.id))})}
                                        }else{
                                            val allRows=followedLibraryRows(s)
                                            val rows=s.library.apply(LibrarySection.FOLLOWED,allRows){it.source.ref}
                                            item{LibrarySectionHeader("追更来源",null,"新增合集",!s.busy){input="";sheet="sourceLink"};Text("追更的合集与别人收藏夹，更新后可继续收听",fontSize=12.sp,color=muted());TextButton({vm.loadFollowedSources()},enabled=!s.busy){Text("刷新追更")}}
                                            items(rows,key={"followed-${it.source.ref}"}){row->SourceCard(row.source,row.statusLabel){vm.openSource(row.source.ref);page="detail"}}
                                            if(s.sourceListOwner==null&&s.sources?.hasMore==true)item{TextButton({vm.loadFollowedSources(true)},enabled=!s.busy){Text("加载更多追更")}}
                                            if(rows.any{!it.followed})item{Text("本机保存的入口仅保存在本软件，可随数据迁移",fontSize=11.sp,color=muted())}
                                            if(rows.isEmpty()&&!s.busy)item{EmptyState(if(s.error!=null)"追更暂未读取" else if(allRows.isNotEmpty())"追更来源已隐藏" else "暂无追更来源",s.error ?: if(allRows.isNotEmpty())"在管理中可恢复显示，更多来源可继续加载" else "在 B 站收藏的合集与别人收藏夹会显示在这里","刷新追更",{vm.loadFollowedSources()})}
                                        }
                                    }
                                } else if(root&&tab==2){
                                    item{MineHeader({page="messages"},{page="settings"})}
                                    item{ProfileCard(s,{if(s.account==null)startLogin() else page="account"},{if(s.account!=null)officialAccount(OfficialAccountPage.EDIT_PROFILE) else startLogin()})}
                                    item{SectionTitle("我的收听",null,"查看全部"){page="history"}}
                                    item{Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){MineTile("最近收听","继续上次的声音之旅",Mark.CLOCK,ListenPink,Modifier.weight(1f)){page="history"};MineTile("下载管理","独立音频与离线收听",Mark.DOWNLOAD,Color(0xFF218BFF),Modifier.weight(1f)){page="downloads"}}}
                                    item{Row(horizontalArrangement=Arrangement.spacedBy(9.dp)){MineTile("缓存管理","分类空间与网络选项",Mark.CACHE,Color(0xFF04BA98),Modifier.weight(1f)){storageOrigin="root";page="storage"};MineTile("播放历史","最近 ${s.history.size+s.liveHistory.size} 条记录",Mark.HISTORY,Color(0xFF8C55FF),Modifier.weight(1f)){page="history"}}}
                                } else when(page){
                                    "search"->{
                                        item{SearchPill(query,::editSearch,{submitSearch(query)},{page="link"},onFocus={searchEditing=true},requestFocus=searchEditing);Text("搜索 B 站视频 · 可输入标题或作者关键词",Modifier.padding(vertical=9.dp),color=muted(),fontSize=12.sp)}
                                        item{TextButton({vm.cancelSearch();page="upSearch"}){Glyph(Mark.SEARCH,Modifier.size(17.dp));Text("搜索 UP 主",Modifier.padding(start=7.dp))}}
                                        if(searchEditing || query.isBlank())searchAssistItems(s.searchAssist.let{
                                            if(it.query==query.trim())it else it.copy(query=query.trim(),suggestions=emptyList(),suggestionsBusy=query.isNotBlank(),suggestionsError=null)
                                        },::submitSearch,vm::retrySearchHot)
                                        if(s.searchBusy)item{Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(20.dp));Text("正在搜索",Modifier.weight(1f).padding(10.dp));TextButton(vm::cancelSearch){Text("取消搜索")}}}
                                        if(!searchEditing && query.isNotBlank()) {
                                            if(s.search?.items?.isEmpty()==true&&!s.searchBusy)item{EmptyState("没有找到相关视频","换个关键词试试")}
                                            items(s.search?.items.orEmpty(),key={it.bvid}){row->VideoRowCard(row.title,row.cover,row.author,row.duration,onClick={vm.playVideo(row.bvid,origin=HistoryOrigin.SEARCH)},onMore={inspect(row.bvid,origin=HistoryOrigin.SEARCH)})}
                                            if(s.search?.hasMore==true)item{TextButton({vm.searchVideos(s.searchKeyword,true)},enabled=!s.searchBusy){Text("加载下一页 · 已显示 ${s.search.items.size} 条")}}
                                        }
                                    }
                                    "upSearch"->upSearchItems(s,{vm.searchUps(it)},::openUp,{vm.searchUps(s.upSearchKeyword,true)},vm::cancelUpSearch,vm::retryUpSearch)
                                    "uploads"->upUploadsItems(s,{shuffle->vm.playCollection(shuffle){if(upOrigin=="player")upOrigin="root";playerOrigin="uploads";page="player"}},vm::bookmarkUp,
                                        {vm.filterUp(order=it)},{vm.filterUp(query=it)},
                                        {row->s.upProfile?.let{if(upOrigin=="player")upOrigin="root";vm.playVideo(row.bvid,source=it.source);playerOrigin="uploads";page="player"}},
                                        {row->inspect(row.bvid,source=s.upProfile?.source)},
                                        {vm.loadUp(true)},vm::retryUp,vm::cancelUpLoading,vm::cancelCollectionPlayback)
                                    "detail"->{
                                        val source=s.sourceContent?.source;val folder=s.folder
                                        if(source==null&&folder==null&&s.sources!=null){
                                            item{Text("该 UP 主的公开收藏夹",fontSize=16.sp,fontWeight=FontWeight.Bold)}
                                            items(s.sources.sources){row->SourceCard(row,"他人公开收藏"){vm.openSource(row.ref)}}
                                            if(s.sources.hasMore)item{TextButton(vm::moreSources,enabled=!s.busy){Text("加载更多收藏夹")}}
                                        }
                                        item{Text(folder?.description?.takeIf{it.isNotBlank()} ?: source?.ownerName?.let{"${it}的${if(source.ref.kind==SourceKind.UP_COLLECTION)"合集" else "收藏夹"}"} ?: "我的收藏夹",color=muted(),fontSize=13.sp)
                                            Text(if(s.sourceCached)"本机保存的内容 · 下拉获取最新状态" else "已加载 ${(source?.let{s.sourceContent.items} ?: s.favorites).size} 项${if(s.hasMore||s.sourceContent?.hasMore==true)" · 尚未完整" else ""}",fontSize=11.sp,color=muted())
                                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({vm.playCollection{playerOrigin="detail";page="player"}},enabled=!s.busy){Glyph(Mark.PLAY,Modifier.size(17.dp));Text("播放全部")};OutlinedButton({vm.playCollection(true){playerOrigin="detail";page="player"}},enabled=!s.busy){Text("随机播放")}}
                                            Text("播放收藏内全部视频的 P1 · 音频按播放读取",fontSize=11.sp,color=muted())
                                            if(s.collectionPreparing)Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(16.dp));Text(s.collectionProgress,Modifier.weight(1f).padding(start=8.dp),fontSize=12.sp,color=muted());TextButton(vm::cancelCollectionPlayback){Text("取消")}}
                                            if(folder!=null){TextButton({page="organize";organizerVm.filters(folder=folder.id);organizerVm.refresh()}){Text("远端查找 / 批量整理 / 本地别名")}}
                                            OutlinedTextField(s.collectionFilter,vm::setCollectionFilter,Modifier.fillMaxWidth(),label={Text("查找已加载的视频")},singleLine=true,shape=CircleShape)
                                        }
                                        if(source!=null)item{Row{TextButton({vm.bookmarkSource(s.bookmarks.none{it.source==source.ref})},enabled=!s.busy){Text(if(s.bookmarks.any{it.source==source.ref})"移除本机入口" else "保存本机入口")};if(source.ref.collectionKind==CollectionKind.SEASON)TextButton({if(s.sourceFollowed==null)vm.inspectFollow() else confirm(if(s.sourceFollowed==true)"取消追更？" else "追更此合集？",source.title){vm.followSource(s.sourceFollowed!=true)}},enabled=!s.busy){Text(when(s.sourceFollowed){null->"查看追更状态";true->"已追更";false->"追更"})}}}
                                        val rows=(s.sourceContent?.items ?: s.favorites).filter{it.title.contains(s.collectionFilter,true)}
                                        if(rows.isEmpty()&&!s.busy)item{EmptyState(if(s.collectionFilter.isBlank())"当前没有可显示的内容" else "已加载范围内未找到",if(s.sourceCached)"当前显示本机镜像" else "可下拉刷新或检查访问权限")}
                                        items(rows,key={it.bvid}){row->Column{
                                            VideoRowCard(row.title,row.cover,row.author,row.duration,onClick={vm.playVideo(row.bvid,folder?.id,source?.ref ?: folder?.let{SourceRef(SourceKind.OWN_FAVORITES,it.id,s.account!!.id)})},onMore={inspect(row.bvid,folder?.id,source?.ref)})
                                            if(row.bvid in s.sourceAdded || row.bvid in s.sourceHeard)Text(if(row.bvid in s.sourceAdded)"新增内容" else "已听",color=ListenPink,fontSize=11.sp)
                                        }}
                                        if(if(s.sourceContent!=null)s.sourceContent.hasMore else s.hasMore)item{TextButton({if(folder!=null)vm.openFolder(folder,true) else source?.let{vm.openSource(it.ref,true)}},enabled=!s.busy&&!s.sourceCached){Text("加载更多 · 查找仅限已加载内容")}}
                                    }
                                    "history"->{
                                        val rows=filteredHistory
                                        item{HistoryFilters(historyQuery,{historyQuery=it},historyType,{historyType=it;historyOrigin=null},historyOrigin,{historyOrigin=it})}
                                        item{Row(verticalAlignment=Alignment.CenterVertically){Text("${if(s.remoteHistory.enabled)"B站同步记录" else "本机记录"} ${rows.size} 条 · 最近收听优先",Modifier.weight(1f),fontSize=12.sp,color=muted());TextButton({confirm(if(s.remoteHistory.enabled)"清空 B站历史？" else "清空本机历史？",if(s.remoteHistory.enabled)"将删除当前账号在 B站的全部观看历史（包括本软件未显示的其他内容），并同步清空这里的最近记录。此操作不可撤销。" else "清空当前身份的全部视频和直播历史，保留续听快照、下载、收藏和推荐偏好。",vm::deleteLocalHistory)}){Text("清空")}}}
                                        if(rows.isEmpty())item{EmptyState("没有符合条件的记录","仅查找当前身份保存在本机的收听历史")}
                                        items(rows,key={it.key}){item->
                                            item.video?.let{row->Column { HistoryRow(vm,s,row,{vm.playHistory(row);playerOrigin="history";page="player"},{selectedHistory=row;sheet="historyActions"});Text("${item.origin?.label} · ${historyDate(item.playedAt)}",fontSize=11.sp,color=muted()) }}
                                            item.live?.let{row->LiveHistoryCard(row,{openLiveRoom(row.roomId)},{confirm("删除这条直播历史？",if(s.remoteHistory.enabled)"将同步删除 B站的这条直播历史；续听快照保留。" else "只删除本机记录，点播续听状态保留。"){vm.deleteLiveHistory(row)}})}
                                        }
                                    }
                                    "settings"->{
                                        item{PageIntro("让收听更合心意","播放、外观和本机数据，都在这里",Mark.SETTINGS)}
                                        item{PanelCard{SettingToggle("音乐推荐",if(s.settings.musicRecommendations)"200 万+ 音乐视频 · 音乐热榜 UP" else if(s.account==null)"B站手机首页推荐 · 当前为游客" else "当前账号的 B站手机首页推荐",s.settings.musicRecommendations,enabled=!s.busy,description="音乐推荐开关"){on->vm.updateSettings(s.settings.copy(musicRecommendations=on))}}}
                                        item{PanelCard{SettingToggle("实时同步B站最近记录",if(s.remoteHistory.enabled)"已开启 · 最近记录与当前 B站账号同步" else if(s.account==null)"登录 B站账号后可开启 · 默认关闭" else "默认关闭 · 最近记录只在本机保存",s.remoteHistory.enabled,enabled=!s.busy&&(s.remoteHistory.enabled||s.account!=null&&s.accountChecked)){on->if(on){historySyncConfirmWord="";historySyncConfirmOwner=s.remoteHistory.account}else vm.setHistorySync(false,s.remoteHistory.account)}
                                            if(s.remoteHistory.enabled){Text(if(s.remoteHistory.refreshing)"正在同步" else s.remoteHistory.error ?: "${if(s.remoteHistory.lastSync>0)"上次同步 ${historyDate(s.remoteHistory.lastSync)}" else "等待同步"} · 待同步 ${s.remoteHistory.pending} 条",fontSize=12.sp,color=muted());if(s.remoteHistory.unsupported>0)Text("${s.remoteHistory.unsupported} 条其他类型或不可播放的 B站记录未显示",fontSize=12.sp,color=muted());TextButton(vm::refreshHistorySync,enabled=!s.busy&&!s.remoteHistory.refreshing){Text("刷新同步记录")};Text("前台每 30 秒检查更新；实际收听进度定期上报。断网时保留已读记录，联网后继续同步。关闭后从新的本机记录开始。",fontSize=12.sp,color=muted())}
                                        }}
                                        item{SettingsRow("音质与播放",AudioExperienceRules.preference(s.settings.audioChoice),Mark.QUEUE){page="audio"};SettingsRow("默认收藏夹",s.settings.defaultFavoriteFolders[s.account?.id?.toString()]?.title ?: "登录后设置 · 点击直接收藏，长按选择",Mark.STAR){if(s.account==null)startLogin() else vm.openDefaultFavoriteFolder()};SettingsRow("外观","浅色 / 深色 / 跟随系统",Mark.TV){page="appearance"};SettingsRow("隐私与本机历史","本机保存与清理",Mark.HISTORY){page="privacy"};SettingsRow("数据转移","Wi-Fi 迁移 / 文件导出与导入",Mark.SHARE){page="transfer"};SettingsRow("缓存管理","分类清理与音频缓存",Mark.CACHE){storageOrigin="settings";page="storage"}}
                                        item{SettingsRow("定时停止",if(s.timerRemainingMs>0)"剩余 ${timeLabel(s.timerRemainingMs)}" else "未开启",Mark.CLOCK){sheet="listening"};SettingsRow("均衡器与音效",if(s.settings.effects.enabled)"已保存启用 · 实际状态见面板" else "默认关闭 · 预设与自定义调节",Mark.QUEUE){sheet="effects"};SectionTitle("更多功能");SettingsRow("桌面小组件","播放条",Mark.TV){page="widgets"};SettingsRow("稍后再听","",Mark.INFO){notice="稍后再听尚未开放，将在后续功能阶段接入。"};SettingsRow("关于哔哩听视频",BuildConfig.VERSION_NAME+" · 内测版",Mark.INFO){page="about"}}
                                    }
                                    "widgets"->item{PlaybackWidgetSettings(s){notice=it}}
                                    "appearance"->item{PageIntro("你的界面，你来选择","外观立即生效，收听状态保持不变",Mark.TV);Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Theme.entries.forEach{theme->Surface(onClick={vm.updateSettings(s.settings.copy(theme=theme))},shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(if(s.settings.theme==theme)2.dp else 1.dp,if(s.settings.theme==theme)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)){Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){ThemeSwatch(theme);Column(Modifier.weight(1f).padding(horizontal=14.dp)){Text(when(theme){Theme.SYSTEM->"跟随系统";Theme.LIGHT->"浅色";Theme.DARK->"深色"},fontWeight=FontWeight.SemiBold);Text(when(theme){Theme.SYSTEM->"随设备自动切换";Theme.LIGHT->"轻盈柔和 · 明亮清晰";Theme.DARK->"低亮舒适 · 夜间陪伴"},fontSize=12.sp,color=muted())};RadioButton(s.settings.theme==theme,{vm.updateSettings(s.settings.copy(theme=theme))})}}}}}
                                    "privacy"->item{if(s.remoteHistory.enabled)InfoCard("当前使用 B站同步记录。本机历史的保存与清理规则暂停应用；请在设置关闭实时同步B站最近记录后管理本机历史。")else HistoryPrivacy(s.settings,{next->if(!next.historyKeepAll && (s.settings.historyKeepAll || next.historyDays<s.settings.historyDays))confirm("应用历史保留规则？","将立即清理当前身份超出保留范围的记录，保留收藏、下载和续听快照。"){vm.updateSettings(next)}else vm.updateSettings(next)}, {confirm("清空本机历史？","只删除当前身份的本机视频和直播历史，保留收藏、下载、推荐偏好和续听快照。",vm::deleteLocalHistory)}, {vm.updateSettings(s.settings.copy(musicBoost=true,musicRecommendations=true));notice="推荐偏好已恢复默认，历史保留。"})}
                                    "audio"->item{SettingsRow("实际音质与输出","查看当前音轨、切换音质和选择输出设备",Mark.QUEUE){sheet="audio"};SwitchRow("连续播放分 P","默认开启；打开视频接着上次分 P 和进度听，无记录从 P1 开始；选择分 P 从该首开头连播，已建立的队列不变",s.settings.continuousParts){vm.updateSettings(s.settings.copy(continuousParts=it))};SettingsRow("倍速与定时停止","快进快退固定 15 秒",Mark.CLOCK){sheet="listening"};Text("音质偏好："+AudioExperienceRules.preference(s.settings.audioChoice),Modifier.padding(vertical=16.dp),fontWeight=FontWeight.Bold);Text("根据视频实际音源、当前身份和设备解码能力选择；会员身份不代表每条视频都有无损音轨。",fontSize=13.sp,color=muted());SwitchRow("允许移动数据收听","在线播放不自动保存音频文件",s.settings.mobilePlayback==true){vm.updateSettings(s.settings.copy(mobilePlayback=it))}}
                                    "storage"->item{StorageControls(s,storageUsage,vm::updateSettings,::confirm,{clearStorage(true,false)},{clearStorage(false,true)},{clearStorage(true,true)},{page="audio"})}
                                    "account"->item{ProfileCard(s,startLogin,{if(s.account!=null)officialAccount(OfficialAccountPage.EDIT_PROFILE)});TextButton(vm::checkAccount){Text("重新检查账号")};if(s.account!=null)TextButton({confirm("退出登录？",if(s.settings.historyDeleteOnExit)"将删除当前账号本机历史并使用游客模式；保留续听快照。" else "本机历史按账号保留并隔离，退出后使用游客模式。"){vm.logout();page="root"}}){Text("退出登录")}}
                                    "link"->item{PageIntro("把喜欢的内容带过来","视频链接、分享文字或 BV 号都可以",Mark.LINK);PanelCard{OutlinedTextField(input,{input=it},Modifier.fillMaxWidth(),label={Text("视频链接、分享文字或 BV 号")},minLines=2);Row{TextButton({input=context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()}){Text("粘贴链接")};Button({vm.playVideo(input);focus.clearFocus()},enabled=!s.busy){Text("打开视频")}};Text("支持 B 站视频链接及 b23.tv 短链接。不会自动读取剪贴板。",fontSize=12.sp,color=muted())}}
                                    "live"->item{LiveRoomContent(s,liveInput,{liveInput=it.take(2048)},{vm.inspectLive(liveInput)},::startLive,::liveOfficial,vm::toggleLiveBookmark,::openLiveRoom)}
                                    "downloads"->downloadItems(s,vm,::confirm)
                                    "transfer"->item{TransferPage(transferVm,vm::localDataTransferred)}
                                    "about"->item{PanelCard(accent=true){Column(Modifier.fillMaxWidth().padding(vertical=20.dp),horizontalAlignment=Alignment.CenterHorizontally){Tv(Modifier.size(110.dp));Text("哔哩听视频",fontSize=26.sp,fontWeight=FontWeight.Bold);Text("把喜欢的视频，听起来",Modifier.padding(vertical=10.dp),color=muted());StatusTag(BuildConfig.VERSION_NAME+" · 内测版")}};Spacer(Modifier.height(16.dp));PanelCard{Text("让好内容陪在耳边",fontWeight=FontWeight.Bold,fontSize=17.sp);Text("搜索与推荐、收藏与 UP 投稿、本机续听、字幕与歌词、音效、普通音频下载与桌面播放条。",fontSize=13.sp,color=muted());HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant);Text("本软件不是 B 站官方客户端。音质与内容可用性取决于平台授权和设备能力。",fontSize=12.sp,color=muted());Text("稍后再听尚未开放。",fontSize=12.sp,color=muted());TempoLicenseNotice()}}
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if(sharedInput!=null)ShareSheet(sharedInput!!,app,vm,s){room->vm.dismissShare();openLiveRoom(room)}
    if(sheet=="queue")ModalBottomSheet(onDismissRequest=::dismissSheet,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface){
        QueuePanel(s,vm::selectQueueEntry,vm::editQueue,{vm.changeMode(PlayMode.entries[(s.mode.ordinal+1)%PlayMode.entries.size])},{confirm("清空播放队列？","这只清空本机队列，不删除历史或远端收藏。"){vm.editQueue(QueueEdit.Clear);sheet=null}},vm::loadMetadata)
    }
    if(sheet!=null&&sheet!="queue")ModalBottomSheet(onDismissRequest=::dismissSheet,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=sheet in setOf("listening","audio","effects","speed","timer")),containerColor=MaterialTheme.colorScheme.surface){
        LazyColumn(Modifier.fillMaxWidth().imePadding().heightIn(max=600.dp),contentPadding=PaddingValues(horizontal=PageSideInset,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            when(sheet){
                "effects"->item{AudioEffectsPanel(s,vm::chooseEffects,vm::retryEffects)}
                "audio"->item{AudioExperiencePanel(s,vm::refreshAudio,vm::chooseAudio,{officialRights()},vm::chooseOutput){vm.updateSettings(s.settings.copy(externalOutputOnly=it))}}
                "speed"->item{PlaybackSpeedPanel(s,vm::setSpeed)}
                "timer"->item{SleepTimerPanel(s,vm::setSleepTimer)}
                "listening"->item{TextButton({sheet="effects"}){Text("均衡器与音效")};LongListeningPanel(s,vm::setSpeed,vm::setSleepTimer){vm.updateSettings(s.settings.copy(continuousParts=it))}}
                "parts"->{item{Text(s.video?.title ?: if(s.busy)"正在读取视频" else "未能读取视频",fontWeight=FontWeight.Bold,fontSize=20.sp);s.error?.let{Text(it,color=MaterialTheme.colorScheme.error)};if(s.busy)LinearProgressIndicator();if(s.video!=null)Row{TextButton({vm.requestFavorite()}){Glyph(Mark.STAR,Modifier.size(19.dp));Text("收藏到…")};TextButton({vm.playPart(null,true);sheet=null;playerOrigin=page;page="player"},enabled=!s.busy){Text("播放全部分 P")}}}
                    items(s.video?.parts.orEmpty(),key={it.cid}){part->PanelCard{Row(verticalAlignment=Alignment.CenterVertically){TextButton({vm.playPart(part);sheet=null;playerOrigin=page;page="player"},Modifier.weight(1f),enabled=!s.busy){Text("P${part.number} · ${part.title}",Modifier.fillMaxWidth())};ActionIcon(Mark.ADD,"加入队列",{vm.enqueuePart(part,false)},enabled=!s.busy)}}}}
                "sourceLink"->item{Text("新增合集",fontSize=20.sp,fontWeight=FontWeight.Bold);Text("粘贴 B 站合集链接，核对内容后确认追更到账号",fontSize=12.sp,color=muted());OutlinedTextField(input,{input=it.take(2048)},Modifier.fillMaxWidth(),label={Text("B站合集链接")},enabled=!s.busy);TextButton({input=context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().take(2048)},enabled=!s.busy){Text("粘贴链接")};s.error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)};Button({vm.openCollectionLink(input){if(sheet=="sourceLink"){sheet=null;page="detail";focus.clearFocus()}}},enabled=!s.busy&&input.isNotBlank()){Text(if(s.busy)"正在读取" else "读取合集")}}
                "historyActions"->{val row=selectedHistory;item{Text(row?.title.orEmpty(),fontWeight=FontWeight.Bold);if(row!=null){SettingsRow("继续收听","从 ${timeLabel(row.positionMs)} 开始",Mark.PLAY){vm.playHistory(row);sheet=null;playerOrigin=page;page="player"};SettingsRow("去 B 站看","保留本机收听位置",Mark.TV){sheet=null;vm.openHistoryOfficial(row,{url->Intent(Intent.ACTION_VIEW,Uri.parse(url)).resolveActivity(context.packageManager)!=null},{browse(it)})};SettingsRow("删除这条历史","保留收藏及续听快照",Mark.CLOSE){confirm(if(s.remoteHistory.enabled)"删除 B站记录？" else "删除本机记录？",if(s.remoteHistory.enabled)"将同步删除 B站的视频历史：${row.title}" else row.title){vm.deleteLocalHistory(row);sheet=null}}}}}
            }
        }
    }
    if(s.pendingFavorite&&!s.defaultFolderPrompt&&!newFolderOpen)ModalBottomSheet(onDismissRequest=vm::dismissFavorite,containerColor=MaterialTheme.colorScheme.surface){LazyColumn(Modifier.fillMaxWidth().imePadding(),contentPadding=PaddingValues(horizontal=PageSideInset,vertical=18.dp)){
        item{Text("收藏到",fontSize=22.sp,fontWeight=FontWeight.Bold);Text((s.favoriteTarget ?: s.video)?.title.orEmpty(),fontSize=13.sp,color=muted());if(s.busy)LinearProgressIndicator();if(s.mutationStatus.isNotBlank())Text(s.mutationStatus,color=ListenPink)}
        if(s.account==null)item{EmptyState("请登录账户","登录后选择收藏夹，不会自动添加","登录",startLogin)}
        else{items(s.folders,key={it.id}){folder->Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Glyph(Mark.FOLDER,color=ListenPink);Column(Modifier.weight(1f).padding(12.dp)){Text(folder.title,fontWeight=FontWeight.Medium);Text(if(folder.contains==true)"已收藏到此夹" else if(folder.contains==false)"未收藏" else "收藏关系待核对",fontSize=11.sp,color=muted())};TextButton({val add=folder.contains!=true;confirm(if(add)"收藏到「${folder.title}」？" else "从「${folder.title}」移除？",(s.favoriteTarget ?: s.video)?.title.orEmpty()){vm.modifyFavorite(folder,add)}},enabled=!s.busy&&folder.contains!=null&&s.mutationPending==null,modifier=Modifier.semantics{contentDescription="收藏到文件夹：${folder.title}"}){Text(if(folder.contains==true)"移除" else "收藏")}}};item{TextButton(vm::loadFolders,enabled=!s.busy){Text("刷新收藏位置")};if(s.mutationPending!=null)TextButton(vm::reconcileFavorite,enabled=!s.busy){Text("核对上次收藏操作")}}}
    }}
    if(confirmTitle!=null)AlertDialog(onDismissRequest={confirmTitle=null},title={Text(confirmTitle!!)},text={Text(confirmBody)},confirmButton={TextButton({confirmTitle=null;confirmAction()}){Text("确认")}},dismissButton={TextButton({confirmTitle=null}){Text("取消")}})
    historySyncConfirmOwner?.let{owner->AlertDialog(onDismissRequest={historySyncConfirmOwner=null;historySyncConfirmWord=""},title={Text("开启实时同步B站最近记录？")},text={Column(verticalArrangement=Arrangement.spacedBy(14.dp)){Text("实时同步B站最近记录会覆盖删除本机的全部最近浏览记录，是否继续");OutlinedTextField(historySyncConfirmWord,{historySyncConfirmWord=it},label={Text("输入“是”才能开启")},singleLine=true,modifier=Modifier.fillMaxWidth());Text("仅替换当前账号的本机最近记录，收藏和下载保留。",fontSize=12.sp,color=muted())}},confirmButton={TextButton({val word=historySyncConfirmWord;historySyncConfirmOwner=null;historySyncConfirmWord="";focus.clearFocus();keyboard?.hide();vm.setHistorySync(true,owner,word)},enabled=historySyncConfirmWord.trim()=="是"&&!s.busy){Text("开启同步")}},dismissButton={TextButton({historySyncConfirmOwner=null;historySyncConfirmWord="";focus.clearFocus();keyboard?.hide()}){Text("取消")}})}
    if(s.defaultFolderPrompt&&!newFolderOpen)ModalBottomSheet(onDismissRequest=vm::dismissDefaultFavoriteFolder,containerColor=MaterialTheme.colorScheme.surface) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=600.dp),contentPadding=PaddingValues(horizontal=PageSideInset,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item{Text("默认收藏夹",fontSize=22.sp,fontWeight=FontWeight.Bold);Text("点击收藏直接加入这里，长按收藏可选择其他收藏夹",fontSize=12.sp,color=muted())}
            if(s.busy)item{LinearProgressIndicator(Modifier.fillMaxWidth())}
            s.error?.let{error->item{Text(error,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}}
            items(s.folders,key={it.id}){folder->Surface(onClick={vm.selectDefaultFavoriteFolder(folder)},enabled=!s.busy,shape=RoundedCornerShape(16.dp),color=colors.surfaceVariant,modifier=Modifier.fillMaxWidth().semantics{contentDescription="默认收藏夹候选：${folder.title}"}) {
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Glyph(Mark.FOLDER,color=colors.primary);Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(folder.title,fontWeight=FontWeight.Medium);Text(if(folder.attr?.and(1)==1)"仅自己可见" else "公开收藏夹",fontSize=11.sp,color=muted())};if(s.settings.defaultFavoriteFolders[s.account?.id?.toString()]?.id==folder.id)Glyph(Mark.CHECK,color=colors.primary)}
            }}
            item{TextButton({newFolderName="";newFolderPrivate=false;newFolderDefault=true;newFolderOpen=true;vm.inspectFolderCreation()},enabled=!s.busy){Glyph(Mark.ADD);Text("新建并设为默认")};TextButton(vm::loadFolders,enabled=!s.busy){Text("刷新收藏夹")};TextButton(vm::dismissDefaultFavoriteFolder){Text("稍后设置")}}
        }
    }
    if(newFolderOpen)AlertDialog(onDismissRequest={if(!s.busy)newFolderOpen=false},title={Text(if(newFolderDefault)"新建默认收藏夹" else "新建收藏夹")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        OutlinedTextField(newFolderName,{newFolderName=it.take(20)},Modifier.fillMaxWidth(),label={Text("收藏夹名称")},supportingText={Text("${newFolderName.length}/20")},singleLine=true,enabled=!s.busy)
        Row(verticalAlignment=Alignment.CenterVertically){Text("私密收藏夹",Modifier.weight(1f));Switch(newFolderPrivate,{newFolderPrivate=it},enabled=!s.busy,modifier=Modifier.semantics{contentDescription="私密收藏夹开关"})}
        Text(if(newFolderPrivate)"仅自己可见" else "公开，其他人也可查看",fontSize=12.sp,color=muted())
        Text("创建后同步到 B 站账号，可在官方 App 查看",fontSize=12.sp,color=muted())
        if(s.folderCreationNote.isNotBlank())Text(s.folderCreationNote,fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
        s.error?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
        if(s.folderCreationPending)TextButton(vm::reconcileFolderCreation,enabled=!s.busy){Text("核对上次收藏夹操作")}
        if(s.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    }},confirmButton={TextButton({vm.createFolder(newFolderName,newFolderPrivate,newFolderDefault){note->newFolderOpen=false;notice=note}},enabled=!s.busy&&newFolderName.trim().isNotEmpty()&&!s.folderCreationPending ){Text(if(newFolderDefault)"创建并设为默认" else "创建并同步到 B站")}},dismissButton={TextButton({newFolderOpen=false},enabled=!s.busy){Text("取消")}})
    notice?.let{AlertDialog(onDismissRequest={notice=null},text={Text(it)},confirmButton={TextButton({notice=null}){Text("知道了")}})}
    if(jumpFallback)AlertDialog(onDismissRequest={jumpFallback=false},title={Text("暂时无法打开 B 站")},text={Text("可以使用浏览器、复制链接，或继续在这里收听。")},confirmButton={TextButton({jumpFallback=false;jump(true)}){Text("浏览器打开")}},dismissButton={Row{TextButton({if(s.isLive)s.liveExperience.roomId.takeIf{it>0}?.let{copy(LiveInput.official(it))} else active?.let{copy(VideoJump.url(it,position))};jumpFallback=false}){Text("复制链接")};TextButton({jumpFallback=false;if(!s.playRequested)toggle()}){Text("继续收听")}}})
    var mobileDismissed by remember(s.playbackIssue){mutableStateOf(false)}
    if(s.playbackIssue==PlaybackIssue.MOBILE_CHOICE&&!mobileDismissed)AlertDialog(onDismissRequest={mobileDismissed=true},title={Text("允许移动数据收听？")},text={Text("选择会保存；允许后请再点击播放。")},confirmButton={TextButton({mobileDismissed=true;vm.updateSettings(s.settings.copy(mobilePlayback=true))}){Text("允许")}},dismissButton={TextButton({mobileDismissed=true;vm.updateSettings(s.settings.copy(mobilePlayback=false))}){Text("仅 Wi-Fi")}})
}

private fun Modifier.selectableTab(selected:Boolean,onClick:()->Unit)=this.semantics{this.selected=selected;role=Role.Tab}.clickable(onClick=onClick).heightIn(min=50.dp).padding(vertical=2.dp)

@Composable private fun FavoritesHeader(enabled:Boolean,manage:()->Unit){
    Row(Modifier.fillMaxWidth().padding(top=12.dp,bottom=4.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
        Text("收藏",Modifier.weight(1f).semantics{heading();contentDescription="收藏页标题"},fontSize=28.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold)
        TextButton(manage,enabled=enabled,contentPadding=PaddingValues(horizontal=12.dp)){Text("管理",fontSize=14.sp,color=if(enabled)MaterialTheme.colorScheme.primary else muted())}
    }
}
@Composable private fun LibrarySectionHeader(title:String,count:String?,addLabel:String,enabled:Boolean,add:()->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=44.dp).padding(top=8.dp),verticalAlignment=Alignment.CenterVertically){
        Text(title,Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.Bold)
        if(count!=null)Text(count,Modifier.padding(end=8.dp),fontSize=12.sp,color=muted())
        ActionIcon(Mark.ADD,addLabel,add,Modifier.size(44.dp),MaterialTheme.colorScheme.primary,enabled)
    }
}
@Composable internal fun FavoriteSourceTabs(selected:Int,onSelect:(Int)->Unit){
    Column(Modifier.fillMaxWidth()){
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())){
            listOf("我的收藏","追更合集","UP收藏").forEachIndexed{index,label->
                val active=selected==index
                Column(Modifier.widthIn(min=76.dp).padding(horizontal=8.dp).selectableTab(active){onSelect(index)},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
                    Text(label,Modifier.padding(top=5.dp,bottom=8.dp),fontSize=14.sp,lineHeight=20.sp,fontWeight=if(active)FontWeight.SemiBold else FontWeight.Normal,color=if(active)MaterialTheme.colorScheme.primary else muted(),maxLines=1,overflow=TextOverflow.Ellipsis)
                    Box(Modifier.padding(bottom=5.dp).size(24.dp,3.dp).background(if(active)MaterialTheme.colorScheme.primary else Color.Transparent,CircleShape))
                }
            }
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.65f))
    }
}
@Composable private fun HistoryRow(vm:MainViewModel,s:ScreenState,row:LocalHistoryEntry,onClick:()->Unit,onMore:()->Unit){
    LaunchedEffect(row.video.bvid){vm.loadMetadata(row.video.bvid)}
    val info=s.metadata[row.video.bvid]
    VideoRowCard(row.title,info?.cover.orEmpty(),"${info?.author.orEmpty()} · P${row.video.part}",info?.duration ?: 0,row.positionMs,onClick=onClick,onMore=onMore)
}
@Composable private fun FolderCard(folder:FavoriteFolder,fallback:String,modifier:Modifier,onClick:()->Unit){
    Surface(onClick,modifier=modifier,shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f))){Column{
        Box(Modifier.fillMaxWidth().aspectRatio(1.15f)){
            Cover(folder.cover.ifBlank{fallback},Modifier.fillMaxSize());Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.22f)))))
            Text("${folder.count} 个视频",Modifier.align(Alignment.BottomEnd).padding(7.dp).background(Color.Black.copy(alpha=.45f),CircleShape).padding(horizontal=6.dp,vertical=3.dp),color=Color.White,fontSize=9.sp)
        }
        Column(Modifier.padding(10.dp)){Text(folder.title,fontSize=13.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis);Text(if(folder.attr?.and(1)==1)"仅自己可见" else "公开收藏夹",Modifier.padding(top=4.dp),fontSize=10.sp,color=muted())}
    }}
}
@Composable private fun SourceCard(source:ContentSource,subtitle:String,onClick:()->Unit){
    Surface(onClick,shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
        Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){
            val type=if(source.ref.kind==SourceKind.UP_COLLECTION)"合集" else "收藏夹"
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(14.dp))) {
                Cover(source.cover,Modifier.matchParentSize(),label="${type}封面")
                Surface(Modifier.align(Alignment.TopEnd),color=Color.Black.copy(alpha=.58f),shape=RoundedCornerShape(bottomStart=4.dp)) {
                    Text(type,Modifier.padding(horizontal=4.dp,vertical=2.dp),fontSize=8.sp,color=Color.White)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(source.title,fontWeight=FontWeight.SemiBold,fontSize=15.sp);Text("${source.ownerName} · ${source.count} 项",Modifier.padding(top=4.dp),fontSize=12.sp,color=muted());Text(subtitle,Modifier.padding(top=5.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.primary)};Glyph(Mark.CHEVRON,Modifier.size(17.dp),muted())}
    }
}
@Composable private fun MineHeader(messages:()->Unit,settings:()->Unit){
    Row(Modifier.fillMaxWidth().padding(top=16.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.weight(1f)){Text("我的",fontWeight=FontWeight.Bold,fontSize=28.sp);Text("收藏喜欢，记录每次陪伴",Modifier.padding(top=4.dp),fontSize=12.sp,color=muted())}
        ActionIcon(Mark.MESSAGE,"消息",messages,Modifier.background(MaterialTheme.colorScheme.surface,CircleShape));Spacer(Modifier.width(8.dp));ActionIcon(Mark.SETTINGS,"设置",settings,Modifier.background(MaterialTheme.colorScheme.surface,CircleShape))
    }
}
@Composable private fun ProfileCard(s:ScreenState,onAccount:()->Unit,onEdit:()->Unit){
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(blush(),MaterialTheme.colorScheme.surface,MaterialTheme.colorScheme.surface))).border(1.dp,MaterialTheme.colorScheme.surface,RoundedCornerShape(26.dp)).padding(20.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){if(s.account==null)Tv(Modifier.size(68.dp)) else Cover(s.account.avatar,Modifier.size(68.dp).clip(CircleShape));Column(Modifier.weight(1f).padding(horizontal=10.dp).clickable(onClick=onAccount)){
            Text(s.account?.name ?: "未登录",fontSize=17.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(s.account?.let{"B站 UID：${it.id}"} ?: "点击登录",fontSize=11.sp,color=muted())
            Spacer(Modifier.height(6.dp));StatusTag(s.account?.let{when(it.membership){Membership.VIP->"大会员";Membership.ORDINARY->"已登录";Membership.UNKNOWN->"会员状态待确认"}+ (it.level?.let{" · Lv$it"} ?: "")} ?: "游客也可以听视频")
        };TextButton(onEdit,contentPadding=PaddingValues(5.dp)){Text(if(s.account==null)"登录 ›" else "编辑资料 ›",fontSize=11.sp)}}
        Row(Modifier.fillMaxWidth().padding(top=18.dp),horizontalArrangement=Arrangement.SpaceEvenly){listOf((s.followingCount?.toString() ?: "—") to "关注内容",(if(s.account==null || !s.foldersLoaded)"—" else s.folders.size.toString()) to "收藏夹").forEach{(value,label)->Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){Text(value,fontWeight=FontWeight.Bold,fontSize=19.sp);Text(label,fontSize=10.sp,color=muted())}}}
    }
}
@Composable private fun MineTile(title:String,subtitle:String,mark:Mark,color:Color,modifier:Modifier,onClick:()->Unit){
    Surface(onClick,modifier=modifier,shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.7f))){
        Column(Modifier.padding(16.dp).heightIn(min=110.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){IconBadge(mark,color,38.dp);Spacer(Modifier.weight(1f));Glyph(Mark.CHEVRON,Modifier.size(15.dp),muted())}
            Text(title,fontSize=15.sp,fontWeight=FontWeight.Bold);Text(subtitle,fontSize=11.sp,color=muted(),maxLines=2)
        }
    }
}
@Composable private fun SettingsRow(title:String,subtitle:String,mark:Mark,onClick:()->Unit){
    Surface(onClick,Modifier.fillMaxWidth().padding(vertical=4.dp),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f))){
        Row(Modifier.padding(14.dp).heightIn(min=40.dp),verticalAlignment=Alignment.CenterVertically){IconBadge(mark,size=36.dp);Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(title,fontSize=14.sp,fontWeight=FontWeight.SemiBold);if(subtitle.isNotBlank())Text(subtitle,Modifier.padding(top=3.dp),fontSize=11.sp,color=muted())};Glyph(Mark.CHEVRON,Modifier.size(16.dp),muted())}
    }
}
@Composable private fun SwitchRow(title:String,subtitle:String,value:Boolean,onChange:(Boolean)->Unit){PanelCard(Modifier.padding(vertical=4.dp)){SettingToggle(title,subtitle,value,change=onChange)}}
@Composable private fun ThemeSwatch(theme:Theme){
    val dark=theme==Theme.DARK
    Box(Modifier.size(52.dp,62.dp).clip(RoundedCornerShape(12.dp)).background(if(dark)Color(0xFF24222E) else Color(0xFFF8F7FA)).border(1.dp,MaterialTheme.colorScheme.outlineVariant,RoundedCornerShape(12.dp)).padding(7.dp)){
        Column(verticalArrangement=Arrangement.spacedBy(5.dp)){Box(Modifier.fillMaxWidth().height(13.dp).background(if(dark)Color(0xFF402435) else Color(0xFFFFE7F0),RoundedCornerShape(4.dp)));repeat(2){Box(Modifier.fillMaxWidth().height(7.dp).background(if(dark)Color(0xFF5F5668) else Color.White,RoundedCornerShape(3.dp)))};Box(Modifier.width(20.dp).height(4.dp).background(ListenPink,CircleShape))}
        if(theme==Theme.SYSTEM)Box(Modifier.align(Alignment.CenterEnd).width(12.dp).fillMaxHeight().background(Color(0xFF302D3B).copy(alpha=.8f)))
    }
}
@Composable internal fun MiniPlayer(s:ScreenState,video:Video?,entry:QueueEntry?,position:Long,open:()->Unit,toggle:()->Unit,next:()->Unit,queue:()->Unit,favorite:()->Unit,chooseFavorite:()->Unit){
    Surface(Modifier.fillMaxWidth().padding(horizontal=PageSideInset,vertical=6.dp),shape=RoundedCornerShape(22.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shadowElevation=5.dp){
        Column{
            Row(Modifier.padding(start=10.dp,end=4.dp,top=9.dp,bottom=7.dp),verticalAlignment=Alignment.CenterVertically){
                Cover(if(s.isLive)s.liveExperience.cover else video?.cover.orEmpty(),Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).clickable(onClick=open));Column(Modifier.weight(1f).semantics{contentDescription="展开播放页"}.clickable(interactionSource=remember{MutableInteractionSource()},indication=null,onClick=open).padding(horizontal=10.dp)){Text(if(s.isLive)s.playingTitle else entry?.title ?: s.playingTitle,fontSize=13.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(if(s.isLive)"LIVE · ${s.liveExperience.anchor}" else video?.author?.takeIf{it.isNotBlank()} ?: "${timeLabel(position)} · 点击展开",Modifier.padding(top=3.dp),fontSize=11.sp,color=muted(),maxLines=1)}
                FavoriteButton("播放条收藏",s.currentFavoriteBvid==entry?.bvid && s.currentFavoriteFolder==s.settings.defaultFavoriteFolders[s.account?.id?.toString()]?.id && s.currentFavoritePresent==true,entry!=null&&!s.isLive&&!s.busy,favorite,chooseFavorite)
                FilledIconButton(toggle,Modifier.size(40.dp).semantics{contentDescription=if(s.playRequested)"暂停" else "播放"},colors=IconButtonDefaults.filledIconButtonColors(containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary)){Glyph(if(s.playRequested)Mark.PAUSE else Mark.PLAY,Modifier.size(21.dp))}
                ActionIcon(Mark.NEXT,"下一条",next,Modifier.size(40.dp),muted(),s.canNext);ActionIcon(Mark.QUEUE,"播放队列",queue,Modifier.size(40.dp),muted())
            }
            if(!s.isLive)LinearProgressIndicator(progress={if(s.durationMs>0)(position/s.durationMs.toFloat()).coerceIn(0f,1f) else 0f},modifier=Modifier.fillMaxWidth().height(2.dp),color=MaterialTheme.colorScheme.primary,trackColor=blush(),drawStopIndicator={})
        }
    }
}

private fun activeDetailKey(s:ScreenState):String?=s.queue.firstOrNull{it.id==s.currentId}?.bvid
