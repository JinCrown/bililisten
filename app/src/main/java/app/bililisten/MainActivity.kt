package app.bililisten

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.media3.common.util.UnstableApi
import app.bililisten.platform.WebLoginActivity
import app.bililisten.shared.PlayMode
import app.bililisten.shared.QueueEdit
import app.bililisten.shared.PlaybackIssue

@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var playbackVm: MainViewModel
    private var widgetOpen by mutableIntStateOf(0)
    private var notificationFavorite by mutableStateOf<app.bililisten.playback.NotificationFavoriteTarget?>(null)
    private fun acceptNotificationFavorite(intent:Intent) {
        if(intent.action==app.bililisten.playback.NotificationFavoriteTickets.ACTION)notificationFavorite=app.bililisten.playback.NotificationFavoriteTickets.take(intent.getStringExtra("favoriteTicket"))
    }
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep persistent bottom chrome anchored to the screen; content handles IME insets.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(application) as T
        }
        playbackVm = ViewModelProvider(this, factory)[MainViewModel::class.java]
        val deadline=android.os.SystemClock.elapsedRealtime()+1800
        val content=findViewById<android.view.View>(android.R.id.content)
        // Ensure the deadline can produce a draw even if lifecycle-aware Compose collection is paused.
        content.postDelayed({ content.invalidate() },1800)
        content.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean {
                val ready=playbackVm.state.value.startupReady || android.os.SystemClock.elapsedRealtime()>=deadline
                if(ready)content.viewTreeObserver.removeOnPreDrawListener(this)
                return ready
            }
        })
        if(savedInstanceState == null) acceptShare(intent) else playbackVm.receiveShare(savedInstanceState.getString("pendingShare"))
        if (intent.action == app.bililisten.widget.PlaybackWidget.OPEN_PLAYER) widgetOpen++
        acceptNotificationFavorite(intent)
        setContent { ListenScreen(playbackVm, widgetOpen,notificationFavorite) }
    }
    private fun acceptShare(intent: Intent) {
        val text = when(intent.action) {
            Intent.ACTION_SEND -> if(intent.type=="text/plain") intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() else null
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if(text!=null) playbackVm.receiveShare(text)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);setIntent(intent);acceptShare(intent);acceptNotificationFavorite(intent);if(intent.action==app.bililisten.widget.PlaybackWidget.OPEN_PLAYER)widgetOpen++ }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("pendingShare",playbackVm.sharedInput.value);super.onSaveInstanceState(outState) }
    override fun onStart() {
        super.onStart(); playbackVm.foregrounded()
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { runOnUiThread { playbackVm.networkAvailable() } }
        }
        networkCallback = callback
        getSystemService(android.net.ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
    }
    override fun onStop() {
        networkCallback?.let { getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(it) }; networkCallback = null
        playbackVm.backgrounded(); super.onStop()
    }
}

@UnstableApi
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PrototypeScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var modeMenu by remember { mutableStateOf(false) }
    var keyword by rememberSaveable { mutableStateOf("") }
    var liveId by rememberSaveable { mutableStateOf("6") }
    var sourceLink by rememberSaveable { mutableStateOf("") }
    var favoriteAction by remember { mutableStateOf<Pair<app.bililisten.shared.FavoriteFolder, Boolean>?>(null) }
    var clearHistory by remember { mutableStateOf(false) }
    var liveConsent by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var clearQueue by remember { mutableStateOf(false) }
    var mobileDismissed by remember(state.playbackIssue) { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var exportPreview by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) (context as ComponentActivity).lifecycleScope.launch(Dispatchers.IO) {
            val success = runCatching { requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(exportPreview) } }.isSuccess
            withContext(Dispatchers.Main) { android.widget.Toast.makeText(context, if (success) "诊断记录已导出" else "导出失败，请重新选择位置", android.widget.Toast.LENGTH_SHORT).show() }
        }
    }
    state.diagnosticPreview?.let { preview -> AlertDialog(onDismissRequest = vm::closeDiagnostics,
        title = { Text("诊断日志预览") }, text = { Text(preview.ifBlank { "暂无记录" }, modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton(onClick = { exportPreview = preview; export.launch("bililisten-diagnostics.txt") }) { Text("导出所示记录") } },
        dismissButton = { TextButton(onClick = vm::closeDiagnostics) { Text("关闭") } }) }
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == android.app.Activity.RESULT_OK) vm.loginFinished() }
    if (liveConsent) AlertDialog(onDismissRequest = { liveConsent = false }, title = { Text("使用混流收听直播？") },
        text = { Text("当前来源是包含视频的直播流，会比独立音频消耗更多流量。只输出声音，不保存直播文件，不提供时移；恢复时重新连接当前直播。") },
        confirmButton = { TextButton(onClick = { liveConsent = false; vm.playLiveWithConsent() }) { Text("同意混流收听") } },
        dismissButton = { TextButton(onClick = { liveConsent = false }) { Text("取消") } })
    favoriteAction?.let { (folder, add) -> AlertDialog(onDismissRequest = { favoriteAction = null },
        title = { Text(if (add) "添加到真实收藏夹" else "移出真实收藏夹") },
        text = { Text("${state.video?.title}\n收藏夹：${folder.title}\n将实际修改 B 站账号，仅操作此收藏夹中的这个视频。") },
        confirmButton = { TextButton(onClick = { favoriteAction = null; vm.modifyFavorite(folder, add) }) { Text("确认${if (add) "添加" else "移除"}") } },
        dismissButton = { TextButton(onClick = { favoriteAction = null }) { Text("取消") } }) }
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text("清空本机历史") },
        text = { Text("仅清空当前账号的本机收听历史；保留独立续听快照。") },
        confirmButton = { TextButton(onClick = { clearHistory = false; vm.deleteLocalHistory() }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { clearHistory = false }) { Text("取消") } })
    if (state.playbackIssue == PlaybackIssue.MOBILE_CHOICE && !mobileDismissed) AlertDialog(onDismissRequest = { mobileDismissed = true },
        title = { Text("允许移动数据收听？") }, text = { Text("会使用当前计费网络，只在线播放，不自动缓存音频。选择会保存；允许后请再点击播放。") },
        confirmButton = { TextButton(onClick = { mobileDismissed = true; vm.updateSettings(state.settings.copy(mobilePlayback = true)) }) { Text("允许并记住") } },
        dismissButton = { TextButton(onClick = { mobileDismissed = true; vm.updateSettings(state.settings.copy(mobilePlayback = false)) }) { Text("仅非计费网络") } })
    if (clearQueue) AlertDialog(onDismissRequest = { clearQueue = false }, title = { Text("清空本机播放队列？") },
        text = { Text("同时清除这份队列的续听快照，保留历史和所有远端收藏。") },
        confirmButton = { TextButton(onClick = { clearQueue = false; vm.editQueue(QueueEdit.Clear) }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { clearQueue = false }) { Text("取消") } })
    if (expanded) ModalBottomSheet(onDismissRequest = { expanded = false }) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.playingTitle, style = MaterialTheme.typography.titleLarge)
            PlaybackSeek(state, vm)
            Row { TextButton(onClick = vm::previous, enabled = state.canPrevious) { Text("上一条") }
                Button(onClick = vm::toggle) { Text(if (state.playRequested) "暂停" else "播放") }
                TextButton(onClick = vm::next, enabled = state.canNext) { Text("下一条") } }
            state.playbackIssue?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
            Text(if (state.isLive) "直播没有固定进度和点播队列" else "${state.queue.size} 条 · ${state.mode.label()}")
        }
    }
    Scaffold(bottomBar = {
        if (state.queue.isNotEmpty() || state.isLive) Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp)) {
                TextButton(onClick = { expanded = true }, modifier = Modifier.weight(1f)) { Text(state.playingTitle, maxLines = 1) }
                TextButton(onClick = vm::toggle) { Text(if (state.playRequested) "暂停" else "播放") }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 20.dp)) {
            item {
                Text("哔哩听视频", style = MaterialTheme.typography.headlineMedium)
                Text("M2 播放验证版 · 独立第三方应用", style = MaterialTheme.typography.labelLarge)
                Text("先验证真实链路，尚未完成正式界面与全机型测试。", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.playingTitle, style = MaterialTheme.typography.titleMedium)
                    Text(if (state.isLive) "直播 · 无时移窗口" else "${state.positionMs / 60000}:${((state.positionMs / 1000) % 60).toString().padStart(2, '0')}")
                    PlaybackSeek(state, vm)
                    state.playbackIssue?.let { Text(it.message, color = MaterialTheme.colorScheme.error) }
                    if (state.buffering) Text(if (state.playRequested) "正在缓冲…" else "已暂停，正在缓冲", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::previous, enabled = state.canPrevious) { Text("上一条") }
                        Button(onClick = vm::toggle, enabled = state.connected && state.playingTitle != "尚未播放") { Text(if (state.playRequested) "暂停" else "播放") }
                        OutlinedButton(onClick = vm::next, enabled = state.canNext) { Text("下一条") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.seekBy(-15000) }, enabled = state.canSeek) { Text("后退 15 秒") }
                        TextButton(onClick = { vm.seekBy(15000) }, enabled = state.canSeek) { Text("前进 15 秒") }
                    }
                    Box {
                        TextButton(onClick = { modeMenu = true }, enabled = state.queue.isNotEmpty() && !state.busy) { Text("播放模式：${state.mode.label()}") }
                        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                            PlayMode.entries.forEach { mode -> DropdownMenuItem(text = { Text(mode.label()) }, onClick = { modeMenu = false; vm.changeMode(mode) }) }
                        }
                    }
                    TextButton(onClick = { vm.openOfficial { url ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } }, enabled = !state.busy && state.queue.isNotEmpty()) { Text("去 B 站看当前视频（带分 P / 时间）") }
                } }
            }
            item {
                Text(state.message, color = MaterialTheme.colorScheme.primary)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!state.busy && !state.accountChecked) TextButton(onClick = vm::checkAccount) { Text("重试账号状态检查") }
            }
            item {
                OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("BV 号或完整视频链接") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.inspect(input) }, enabled = !state.busy && state.accountChecked) { Text("读取视频与分 P") }
            }
            state.video?.let { video ->
                item { Text(video.title, style = MaterialTheme.typography.titleMedium); Button(onClick = { vm.playPart(null, all = true) }, enabled = !state.busy && state.connected) { Text("播放全部 ${video.parts.size} P") } }
                item { TextButton(onClick = vm::probeAudio, enabled = !state.busy) { Text("检查实际音轨") }; Text(state.audioSummary) }
                items(video.parts, key = { "part-${it.cid}" }) { part ->
                    OutlinedButton(onClick = { vm.playPart(part) }, enabled = !state.busy && state.connected, modifier = Modifier.fillMaxWidth()) { Text(if (part.title.isBlank()) "P${part.number}" else "P${part.number} · ${part.title}") }
                    Row { TextButton(onClick = { vm.enqueuePart(part, false) }, enabled = !state.busy && !state.isLive && state.accountChecked) { Text("加入队列") }
                        TextButton(onClick = { vm.enqueuePart(part, true) }, enabled = !state.busy && !state.isLive && state.accountChecked) { Text("下一条播放") } }
                }
            }
            if (state.queue.isNotEmpty()) {
                item { Text("当前队列 · ${state.queue.size} 条", style = MaterialTheme.typography.titleMedium) }
                items(state.queue, key = { "queue-${it.id}" }) { entry ->
                    Text((if (entry.id == state.currentId) "正在收听 · " else "") + entry.title, style = MaterialTheme.typography.bodySmall)
                    val index = state.queue.indexOf(entry)
                    Row {
                        TextButton(onClick = { vm.editQueue(QueueEdit.Move(entry.id, index - 1)) }, enabled = index > 0 && !state.busy) { Text("上移") }
                        TextButton(onClick = { vm.editQueue(QueueEdit.Move(entry.id, index + 1)) }, enabled = index < state.queue.lastIndex && !state.busy) { Text("下移") }
                        TextButton(onClick = { vm.editQueue(QueueEdit.Remove(entry.id)) }, enabled = !state.busy) { Text("移出队列") }
                    }
                }
                item { TextButton(onClick = { clearQueue = true }, enabled = !state.busy) { Text("清空播放队列") } }
            }
            item {
                HorizontalDivider(); Text("账号与真实收藏", style = MaterialTheme.typography.titleLarge)
                Text(state.account?.name ?: "未登录")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.account == null) Button(onClick = { vm.prepareLogin(); login.launch(Intent(context, WebLoginActivity::class.java)) }, enabled = !state.busy) { Text("打开 B 站网页登录") }
                    else {
                        Button(onClick = vm::loadFolders, enabled = !state.busy) { Text("读取收藏夹") }
                        TextButton(onClick = vm::logout, enabled = !state.busy) { Text("退出登录") }
                    }
                }
                Text("单手机网页登录候选路线；写入仅操作你确认的收藏夹，写后回读，结果不确定时不自动重试。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = vm::reconcileFavorite, enabled = !state.busy && state.account != null) { Text("核对上次收藏操作") }
            }
            items(state.folders, key = { "folder-${it.id}" }) { folder ->
                OutlinedButton(onClick = { vm.openFolder(folder) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("${folder.title} · ${folder.count} 项") }
                if (state.video != null) Row { TextButton(onClick = { favoriteAction = folder to true }, enabled = !state.busy) { Text("将当前视频添加到此夹") }; TextButton(onClick = { favoriteAction = folder to false }, enabled = !state.busy) { Text("从此夹移除") } }
            }
            state.folder?.let { folder ->
                item { Text("${folder.title} · 已载入 ${state.favorites.size} 项") }
                items(state.favorites) { item -> TextButton(onClick = { vm.inspect(item.bvid, folder.id) }, enabled = !state.busy) { Text(item.title) } }
                if (state.hasMore) item { OutlinedButton(onClick = { vm.openFolder(folder, true) }, enabled = !state.busy) { Text("下一页") } }
            }
            item {
                HorizontalDivider(); Text("站内搜索", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = keyword, onValueChange = { keyword = it }, label = { Text("搜索 B 站视频") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = { vm.searchVideos(keyword) }, enabled = !state.busy) { Text("在线搜索") }
            }
            item {
                HorizontalDivider(); Text("追更合集与收藏夹验证", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(value = sourceLink, onValueChange = { sourceLink = it }, label = { Text("收藏夹、合集或 UP 主页完整链接") }, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { vm.openSourceLink(sourceLink) }, enabled = !state.busy) { Text("读取来源") }
                    TextButton(onClick = { vm.loadFollowedSources() }, enabled = !state.busy && state.account != null) { Text("我的追更") }
                }
            }
            items(state.sources?.sources.orEmpty()) { source ->
                TextButton(onClick = { vm.openSource(source.ref) }, enabled = !state.busy) { Text("${source.title} · ${source.ownerName} · ${source.count} 条") }
            }
            if (state.sources?.hasMore == true) item { TextButton(onClick = vm::moreSources, enabled = !state.busy) { Text("更多来源") } }
            state.sourceContent?.let { content ->
                item { Text("来源：${content.source.title} · ${content.source.ownerName}") }
                items(content.items) { row -> TextButton(onClick = { vm.inspect(row.bvid, source = content.source.ref) }, enabled = !state.busy) { Text(row.title) } }
                if (content.hasMore) item { TextButton(onClick = { vm.openSource(content.source.ref, true) }, enabled = !state.busy) { Text("来源下一页") } }
            }
            items(state.search?.items.orEmpty(), key = { "search-${it.bvid}" }) { item -> TextButton(onClick = { vm.inspect(item.bvid) }, enabled = !state.busy) { Text(item.title) } }
            if (state.search?.hasMore == true) item { TextButton(onClick = { vm.searchVideos(state.searchKeyword, true) }, enabled = !state.busy) { Text("搜索下一页") } }
            item {
                HorizontalDivider(); Text("发现与直播验证", style = MaterialTheme.typography.titleLarge)
                Button(onClick = vm::loadRecommendations, enabled = !state.busy) { Text("读取一组推荐") }
            }
            items(state.recommendations, key = { "recommend-${it.bvid}" }) { item -> TextButton(onClick = { vm.inspect(item.bvid) }, enabled = !state.busy) { Text(item.title) } }
            item {
                OutlinedTextField(value = liveId, onValueChange = { liveId = it }, label = { Text("直播房间号") })
                Button(onClick = { vm.inspectLive(liveId) }, enabled = !state.busy) { Text("检查直播状态与流") }
                Text(state.liveSummary)
                if (state.liveRoom?.status == 1) Button(onClick = { liveConsent = true }, enabled = !state.busy) { Text("选择混流收听直播") }
            }
            item {
                HorizontalDivider(); Text("本机继续收听", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { clearHistory = true }, enabled = !state.busy) { Text("清空本机历史") }
                TextButton(onClick = vm::refreshResume, enabled = !state.busy) { Text("刷新本机快照") }
                state.resume?.let { snapshot ->
                    Text(snapshot.entries.first { it.id == snapshot.currentId }.title)
                    Text("${snapshot.positionMs / 1000} 秒 · ${snapshot.entries.size} 条队列")
                    Button(onClick = vm::continueListening, enabled = !state.busy && state.connected) { Text("继续收听") }
                } ?: Text("暂无可恢复记录；启动不会自动外放。")
            }
            items(state.history, key = { "history-${it.video.bvid}-${it.video.cid}" }) { row -> Column { TextButton(onClick = { vm.playHistory(row) }, enabled = !state.busy && state.connected) { Text("${row.title} · ${row.positionMs / 1000} 秒") }; TextButton(onClick = { vm.deleteLocalHistory(row) }, enabled = !state.busy) { Text("删除此条本机历史") } } }
            item {
                HorizontalDivider(); Text("设置与诊断", style = MaterialTheme.typography.titleLarge)
                Text("保留 ${state.settings.historyDays} 天，最多 ${state.settings.historyLimit} 条；续听快照独立保存。")
                Row { Text("移动数据在线播放", Modifier.weight(1f)); Switch(state.settings.mobilePlayback == true, { vm.updateSettings(state.settings.copy(mobilePlayback = it)) }, enabled = !state.busy) }
                Text(if (state.settings.mobilePlayback == null) "尚未选择，首次计费网络播放时询问。" else "选择已保存；音频自动缓存始终关闭。")
                TextButton(onClick = vm::previewDiagnostics) { Text("预览诊断日志") }
            }
        }
    }
}

@Composable
private fun PlaybackSeek(state: ScreenState, vm: MainViewModel) {
    if (state.isLive) return
    var scrub by remember(state.currentId) { mutableStateOf<Float?>(null) }
    val end = state.durationMs.coerceAtLeast(1).toFloat()
    Slider(value = (scrub ?: state.positionMs.toFloat()).coerceIn(0f, end), onValueChange = { scrub = it },
        onValueChangeFinished = { scrub?.let { vm.seekTo(it.toLong()) }; scrub = null }, valueRange = 0f..end, enabled = state.canSeek && state.durationMs > 0)
    if (state.durationMs > 0) Text("${state.positionMs / 1000} / ${state.durationMs / 1000} 秒", style = MaterialTheme.typography.bodySmall)
}

private fun PlayMode.label(): String = when (this) {
    PlayMode.SEQUENTIAL -> "顺序播放"
    PlayMode.REPEAT_ALL -> "列表循环"
    PlayMode.REPEAT_ONE -> "单条循环"
    PlayMode.SHUFFLE -> "随机播放"
}
