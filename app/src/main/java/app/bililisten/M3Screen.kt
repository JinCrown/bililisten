package app.bililisten

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import app.bililisten.platform.WebLoginActivity
import app.bililisten.shared.*

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi @Composable
fun M3Screen(vm: MainViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sourceTab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    var sourceLink by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf(false) }
    var player by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var clearHistory by remember { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf<Boolean?>(null) }
    var favorite by remember { mutableStateOf<Pair<FavoriteFolder,Boolean>?>(null) }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if(it.resultCode == Activity.RESULT_OK) vm.loginFinished() else vm.loginCancelled()
    }
    val startLogin = { vm.prepareLogin(); login.launch(Intent(context,WebLoginActivity::class.java)) }
    if (advanced) {
        Column { TextButton(onClick={advanced=false}) { Text("返回设置") }; Box(Modifier.weight(1f)) { PrototypeScreen(vm) } }
        return
    }
    if(logout) Confirm("退出登录？", "当前账号的本机历史保留并隔离，返回游客模式。", {logout=false}) { logout=false;vm.logout() }
    if(clearHistory) Confirm("清空本机历史？", "仅删除当前身份历史，保留续听快照和远端收藏。", {clearHistory=false}) {clearHistory=false;vm.deleteLocalHistory()}
    favorite?.let { (folder,add) -> Confirm(if(add) "收藏到 ${folder.title}？" else "从 ${folder.title} 移除？", s.video?.title.orEmpty(), {favorite=null}) {favorite=null;vm.modifyFavorite(folder,add)} }
    follow?.let { value -> Confirm(if(value) "追更此合集？" else "取消追更此合集？", s.sourceContent?.source?.title.orEmpty(), {follow=null}) {follow=null;vm.followSource(value)} }
    if(s.pendingFavorite) ModalBottomSheet(onDismissRequest=vm::dismissFavorite) {
        LazyColumn(Modifier.fillMaxWidth().padding(20.dp)) {
            item { Text("收藏到",style=MaterialTheme.typography.titleLarge);Text(s.video?.title.orEmpty()) }
            if(s.account==null) item { Text("请登录账户"); Button(onClick=startLogin) {Text("登录") } }
            else {
                item { Text(s.mutationStatus); if(s.busy) LinearProgressIndicator(); TextButton(onClick=vm::loadFolders,enabled=!s.busy) {Text("刷新收藏位置")} }
                items(s.folders,key={it.id}) { folder ->
                    ListItem(headlineContent={Text(folder.title)},supportingContent={Text(if(folder.contains==true) "已收藏到此夹" else if(folder.contains==false) "未收藏到此夹" else "关系待核对")},trailingContent={
                        TextButton(onClick={favorite=folder to (folder.contains!=true)},enabled=!s.busy && folder.contains!=null && s.mutationPending==null) {Text(if(folder.contains==true) "移除" else "收藏")}
                    })
                }
                item { if(s.mutationPending!=null) TextButton(onClick=vm::reconcileFavorite,enabled=!s.busy) {Text("核对待处理操作")} }
            }
        }
    }
    if(details && s.video!=null) ModalBottomSheet(onDismissRequest={details=false}) {
        LazyColumn(Modifier.fillMaxWidth().padding(20.dp)) {
            item { Text(s.video!!.title,style=MaterialTheme.typography.titleLarge)
                Row { Button(onClick=vm::requestFavorite,enabled=!s.busy){Text("收藏到…")};TextButton(onClick=vm::probeAudio,enabled=!s.busy){Text("检查可用音质")} }
                Text(s.audioSummary)
                Button(onClick={vm.playPart(null,true);details=false},enabled=!s.busy){Text("播放全部分 P")}
            }
            items(s.video!!.parts,key={it.cid}) { part ->
                TextButton(onClick={vm.playPart(part);details=false},enabled=!s.busy){Text("P${part.number} · ${part.title}")}
                Row {TextButton(onClick={vm.enqueuePart(part,false)},enabled=!s.busy){Text("加入队列")};TextButton(onClick={vm.enqueuePart(part,true)},enabled=!s.busy){Text("下一条播放")}}
            }
        }
    }
    if(player) ModalBottomSheet(onDismissRequest={player=false}) {
        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(s.playingTitle,style=MaterialTheme.typography.titleLarge)
            Slider(value=s.positionMs.coerceIn(0,s.durationMs.coerceAtLeast(1)).toFloat(),onValueChange={vm.seekTo(it.toLong())},valueRange=0f..s.durationMs.coerceAtLeast(1).toFloat(),enabled=s.canSeek)
            Text("${s.positionMs/1000} / ${s.durationMs/1000} 秒")
            Row {TextButton(onClick=vm::previous,enabled=s.canPrevious){Text("上一条")};Button(onClick=vm::toggle){Text(if(s.playRequested) "暂停" else "播放")};TextButton(onClick=vm::next,enabled=s.canNext){Text("下一条")}}
            Row { PlayMode.entries.forEach { mode -> TextButton(onClick={vm.changeMode(mode)},enabled=!s.busy && !s.isLive){Text(when(mode){PlayMode.SEQUENTIAL->"顺序";PlayMode.REPEAT_ALL->"列表循环";PlayMode.REPEAT_ONE->"单曲循环";PlayMode.SHUFFLE->"随机"})} } }
            TextButton(onClick={vm.openOfficial { context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(it))) }}){Text("去 B 站看视频")}
            s.playbackIssue?.let{Text(it.message,color=MaterialTheme.colorScheme.error)}
        }
    }
    if(settings) ModalBottomSheet(onDismissRequest={settings=false}) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("设置",style=MaterialTheme.typography.titleLarge)
            Text("音质：自动选择当前最高可用")
            Row { Text("允许移动数据收听",Modifier.weight(1f));Switch(s.settings.mobilePlayback==true,{vm.updateSettings(s.settings.copy(mobilePlayback=it))},enabled=!s.busy) }
            TextButton(onClick={clearHistory=true}){Text("清空当前身份历史")}
            TextButton(onClick={advanced=true;settings=false}){Text("播放器与诊断工具")}
            Text("0.0.8 · 账号与收藏功能版")
        }
    }
    var mobileDismissed by remember(s.playbackIssue){mutableStateOf(false)}
    if(s.playbackIssue==PlaybackIssue.MOBILE_CHOICE && !mobileDismissed) AlertDialog(onDismissRequest={mobileDismissed=true},title={Text("允许移动数据收听？")},text={Text("选择会保存；允许后请再点击播放。")},confirmButton={TextButton(onClick={mobileDismissed=true;vm.updateSettings(s.settings.copy(mobilePlayback=true))}){Text("允许")}},dismissButton={TextButton(onClick={mobileDismissed=true;vm.updateSettings(s.settings.copy(mobilePlayback=false))}){Text("仅 Wi-Fi")}})
    Scaffold(bottomBar={ Column {
        if(s.queue.isNotEmpty() || s.isLive) Surface(tonalElevation=3.dp) { Row(Modifier.fillMaxWidth().padding(8.dp)) {
            TextButton(onClick={player=true},modifier=Modifier.weight(1f)){Text(s.playingTitle,maxLines=1)}
            TextButton(onClick=vm::toggle){Text(if(s.playRequested) "暂停" else "播放")}
        } }
        NavigationBar { listOf("首页","收藏","我的").forEachIndexed { index,label -> NavigationBarItem(selected=tab==index,onClick={tab=index;if(index==1 && s.account!=null) {if(sourceTab==0) vm.loadFolders() else vm.loadFollowedSources()}},icon={Text(listOf("⌂","☆","◉")[index])},label={Text(label)}) } }
    } }) { padding ->
        PullToRefreshBox(isRefreshing=s.busy,onRefresh={vm.foregrounded(true)},modifier=Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal=18.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=16.dp)) {
                item {Text(listOf("哔哩听视频","收藏","我的")[tab],style=MaterialTheme.typography.headlineLarge)}
                item {Text(s.message,style=MaterialTheme.typography.bodySmall);if(s.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(!s.accountChecked && !s.busy) TextButton(onClick=vm::checkAccount){Text("重试账号检查")}
                    if(s.mutationPending!=null) TextButton(onClick=vm::reconcileFavorite,enabled=!s.busy){Text("有待核对操作，点击核对")}
                }
                if(tab==0) {
                    item {OutlinedTextField(query,{query=it},label={Text("搜索 B 站视频")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        Row {Button(onClick={vm.searchVideos(query)},enabled=s.accountChecked){Text("搜索")};if(s.searchBusy)TextButton(onClick=vm::cancelSearch){Text("取消搜索")}}
                        if(s.searchBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
                        OutlinedTextField(link,{link=it},label={Text("视频链接或 BV 号")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        TextButton(onClick={vm.inspect(link);details=true},enabled=!s.busy && s.accountChecked){Text("打开视频")}
                    }
                    items(s.search?.items.orEmpty(),key={"search-${it.bvid}"}) { row -> VideoRow(row.title){vm.inspect(row.bvid);details=true} }
                    if(s.search?.hasMore==true) item {TextButton(onClick={vm.searchVideos(s.searchKeyword,true)},enabled=!s.searchBusy){Text("搜索下一页")}}
                    item {Text("推荐",style=MaterialTheme.typography.titleLarge);TextButton(onClick=vm::loadRecommendations,enabled=!s.busy && s.accountChecked){Text("刷新推荐")}}
                    items(s.recommendations,key={"rec-${it.bvid}"}) { row -> VideoRow(row.title){vm.inspect(row.bvid);details=true} }
                    item {Text("最近收听",style=MaterialTheme.typography.titleLarge);if(s.account==null)Text("本机游客历史")
                        s.resume?.let {Text("继续：${it.entries.first { e->e.id==it.currentId }.title} · ${it.positionMs/1000} 秒");TextButton(onClick=vm::continueListening,enabled=!s.busy && s.accountChecked){Text("继续收听")}}
                        if(s.history.isEmpty())Text("暂无收听记录")
                    }
                    items(s.history,key={"history-${it.video.bvid}-${it.video.cid}"}) { row ->
                        VideoRow("${row.title} · P${row.video.part} · ${row.positionMs/1000} 秒"){vm.playHistory(row)}
                        Text("来源：${row.source?.let { "${it.kind} · ${it.owner} / ${it.id}" } ?: "搜索或视频链接"}",style=MaterialTheme.typography.bodySmall)
                        TextButton(onClick={vm.deleteLocalHistory(row)},enabled=!s.busy){Text("删除此条历史")}
                    }
                } else if(tab==1) {
                    if(s.account==null) item {Text("请登录账户");Button(onClick=startLogin){Text("登录")}}
                    else {
                        item { Row {listOf("我的收藏","追更合集").forEachIndexed {index,label->TextButton(onClick={sourceTab=index;vm.selectSourceTab();if(index==0)vm.loadFolders() else vm.loadFollowedSources()},enabled=!s.busy){Text(if(sourceTab==index) "• $label" else label)}}} }
                        if(sourceTab==0) items(s.folders,key={"folder-${it.id}"}) { folder->VideoRow("${folder.title} · ${folder.count} 项 · ${if(folder.attr?.and(1)==1) "私有" else "公开"}"){vm.openFolder(folder)} }
                        else {
                            item {OutlinedTextField(sourceLink,{sourceLink=it},label={Text("来源分享链接或 UP 主页链接")},modifier=Modifier.fillMaxWidth());TextButton(onClick={vm.openSourceLink(sourceLink)},enabled=!s.busy){Text("打开来源")}}
                            items(followedLibraryRows(s),key={"source-${it.source.ref}"}) { row->VideoRow("${row.source.title} · ${row.source.ownerName} · ${row.statusLabel}"){vm.openSource(row.source.ref)} }
                            if(s.sources?.hasMore==true)item{TextButton(onClick=vm::moreSources,enabled=!s.busy){Text("更多来源")}}
                        }
                        val content=s.sourceContent
                        if(s.folder!=null || content!=null) {
                            val rows=content?.items ?: s.favorites
                            item {HorizontalDivider();Text(content?.source?.title ?: s.folder!!.title,style=MaterialTheme.typography.titleLarge)
                                content?.let{Text("所有者：${it.source.ownerName}")}
                                Text("已加载 ${rows.size} 项 · ${if(s.sourceCached) "本机镜像，刷新后再加载更多" else if(content?.hasMore ?: s.hasMore) "尚未完整" else "已完整"}")
                                OutlinedTextField(s.collectionFilter,vm::setCollectionFilter,label={Text("仅查找已加载的 ${rows.size} 项")},modifier=Modifier.fillMaxWidth())
                                Row{TextButton(onClick={if(content!=null)vm.openSource(content.source.ref) else vm.openFolder(s.folder!!)},enabled=!s.busy){Text("刷新来源")};TextButton(onClick={vm.playCollection(false)},enabled=!s.busy){Text("播放来源")};TextButton(onClick={vm.playCollection(true)},enabled=!s.busy){Text("随机播放")}}
                                Text("播放来源内全部视频的 P1 · 音频按播放读取")
                                if(s.collectionPreparing)Row{Text(s.collectionProgress,Modifier.weight(1f));TextButton(vm::cancelCollectionPlayback){Text("取消")}}
                                if(content!=null) {
                                    TextButton(onClick={vm.bookmarkSource(s.bookmarks.none { it.source==content.source.ref })},enabled=!s.busy){Text(if(s.bookmarks.any{it.source==content.source.ref})"移除本机入口" else "保存本机入口")}
                                    if(content.source.ref.collectionKind==CollectionKind.SEASON) Row {TextButton(onClick=vm::inspectFollow,enabled=!s.busy){Text("检查追更关系")};TextButton(onClick={follow=s.sourceFollowed!=true},enabled=!s.busy && s.mutationPending==null && s.sourceFollowed!=null){Text(if(s.sourceFollowed==true)"取消追更" else "追更合集")}}
                                }
                                if(rows.none{it.title.contains(s.collectionFilter,true)})Text("已加载范围没有匹配项，不代表整个来源无结果")
                            }
                            items(rows.filter{it.title.contains(s.collectionFilter,true)},key={"content-${it.bvid}"}) { row->
                                VideoRow(row.title + if(row.bvid in s.sourceAdded) " · 新增" else if(row.bvid in s.sourceHeard) " · 已听" else "") {vm.inspect(row.bvid,s.folder?.id,content?.source?.ref);details=true}
                            }
                            if(content?.hasMore ?: s.hasMore)item{TextButton(onClick={if(content!=null)vm.openSource(content.source.ref,true) else vm.openFolder(s.folder!!,true)},enabled=!s.busy && !s.sourceCached){Text("来源下一页")}}
                        }
                    }
                } else {
                    item {Card(Modifier.fillMaxWidth()){Column(Modifier.padding(20.dp)){
                        Text(s.account?.name ?: "未登录",style=MaterialTheme.typography.titleLarge)
                        Text(when(s.account?.membership){Membership.VIP->"大会员";Membership.ORDINARY->"普通账号";Membership.UNKNOWN->"会员状态待确认";null->"登录后管理收藏与合集"})
                        if(s.account==null)Button(onClick=startLogin){Text("点击登录")}
                        else {TextButton(onClick=vm::checkAccount,enabled=!s.busy){Text("刷新账号状态")};TextButton(onClick={logout=true}){Text("退出登录")}}
                        if(s.loginPhase==LoginPhase.WAITING)Text("等待官方登录验证")
                    }};TextButton(onClick={tab=0}){Text("最近收听与本机历史")};TextButton(onClick={settings=true}){Text("设置")}}
                }
            }
        }
    }
}

@Composable private fun VideoRow(title: String, onClick: () -> Unit) { Card(Modifier.fillMaxWidth().clickable(onClick=onClick)) {Text(title,Modifier.padding(14.dp))} }
@Composable private fun Confirm(title:String,body:String,dismiss:()->Unit,confirm:()->Unit) {AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={Text(body)},confirmButton={TextButton(onClick=confirm){Text("确认")}},dismissButton={TextButton(onClick=dismiss){Text("取消")}})}
