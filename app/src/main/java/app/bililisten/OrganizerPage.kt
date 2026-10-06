package app.bililisten

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bililisten.shared.*

@Composable
fun OrganizerPage(vm: OrganizerViewModel, open: (String, Long?, HistoryOrigin) -> Unit, login: () -> Unit, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<FolderDraft?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    var privateFolder by rememberSaveable { mutableStateOf(true) }
    var deleteStage by remember { mutableIntStateOf(0) }
    var deleting by remember { mutableStateOf<FavoriteFolder?>(null) }
    var target by remember { mutableStateOf<Long?>(null) }
    var action by remember { mutableStateOf<OrganizeAction?>(null) }
    var confirmBatch by remember { mutableStateOf(false) }
    var aliasItem by remember { mutableStateOf<FavoriteItem?>(null) }
    var alias by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(s.account, s.authenticated) { if (s.authenticated && s.folders.isEmpty()) vm.refresh() }
    val folder = s.folders.firstOrNull { it.id == s.folder }
    fun editing(draft: FolderDraft) { name = draft.title; privateFolder = draft.private; edit = draft }
    LazyColumn(modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(horizontal=PageSideInset,vertical=20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item{PageIntro("让收藏更有条理","跨夹查找、整理和本机别名",Mark.FOLDER)}
        if (!s.authenticated) item { EmptyState("请登录并验证账户后整理收藏","选择自己的收藏夹，继续整理","登录账户",login) }
        else {
            item {PanelCard{
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row { TextButton(vm::refresh, enabled = !s.busy) { Text("刷新收藏夹") }; TextButton({ editing(FolderDraft(FolderAction.CREATE)) }, enabled = !s.busy) { Text("新建收藏夹") } }
                OrganizerChoice("收藏夹", s.folders.map { it.id to "${it.title} · ${it.count} 项${if(it.attr==null)" · 权限待核对" else if(it.attr?.and(1)==1)" · 私有" else " · 公开"}" }, s.folder, !s.busy) { vm.filters(folder = it) }
                if (folder != null) Row {
                    TextButton({ editing(FolderDraft(FolderAction.EDIT, folder.id, folder.title, folder.attr?.and(1) == 1)) }, enabled = !s.busy && folder.attr != null) { Text("改名 / 权限") }
                    TextButton({ deleting = folder; deleteStage = 1 }, enabled = !s.busy && folder.attr?.and(2) == 2) { Text("删除整个夹") }
                }
                OrganizerChoice("查找范围", listOf(0 to "当前收藏夹", 1 to "我的全部收藏夹", 2 to "B站在线视频"), s.scope, !s.busy) { vm.filters(scope = it, sort = 0) }
                OutlinedTextField(s.query, { vm.filters(query = it) }, Modifier.fillMaxWidth(), enabled = !s.busy, singleLine = true, label = { Text("远端查找，不限本机已加载范围") })
                OrganizerChoice("视频分区", listOf(0 to "全部视频", 3 to "音乐", 1 to "动画", 4 to "游戏", 36 to "知识"), s.tid, !s.busy) { vm.filters(tid = it) }
                OrganizerChoice("排序", (if(s.scope==2)listOf("平台综合", "最新投稿", "最多播放") else listOf("最近收藏", "最新投稿", "最多播放") + if(s.scope==0)listOf("本机固定顺序") else emptyList()).mapIndexed { i, label -> i to label }, s.sort, !s.busy) { vm.filters(sort = it) }
                Button({ vm.search() }, enabled = !s.busy && s.folders.isNotEmpty()) { Text("查找 / 载入") }
                Text("查找整个所选范围，可按视频分区和时间排序。")
                if (s.sort == 3) Text("本机固定顺序仅在此整理页生效；先载入更多再调整。未固定的视频按最近收藏追加。")
                if (s.scope == 0 && s.selected.isNotEmpty()) {
                    Text("已选 ${s.selected.size} 项（每批最多 50 项）")
                    Row(Modifier.horizontalScroll(rememberScrollState())) { listOf(OrganizeAction.COPY to "复制到…", OrganizeAction.MOVE to "移动到…", OrganizeAction.REMOVE to "移出当前夹").forEach { (type, label) ->
                        TextButton({ action = type; target = s.folders.firstOrNull { it.id != s.folder }?.id; confirmBatch = type == OrganizeAction.REMOVE }, enabled = !s.busy) { Text(label) }
                    } }
                }
                if (s.data.pending != null || s.data.batch?.rows?.any { it.outcome in setOf(RowOutcome.UNKNOWN, RowOutcome.RUNNING, RowOutcome.READY) } == true)
                    Button(vm::reconcile, enabled = !s.busy) { Text("核对上次整理结果（不重试写入）") }
                if (s.data.result.isNotBlank()) Text(s.data.result)
            }}
            s.data.batch?.rows?.let { results -> items(results, key = { "result-${it.bvid}" }) { row ->
                Text("${row.title}：${row.detail}", color = if(row.outcome in setOf(RowOutcome.PARTIAL,RowOutcome.UNKNOWN,RowOutcome.FAILED)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            } }
            if (s.searched && s.rows.isEmpty() && !s.busy) item { Text("平台当前未返回匹配视频") }
            itemsIndexed(s.rows, key = { _, row -> row.bvid }) { index, row ->
                PanelCard { Column {
                    Row(verticalAlignment=Alignment.CenterVertically) { if(s.scope==0) Checkbox(row.bvid in s.selected, { vm.select(row.bvid) }, enabled = !s.busy)
                        Text("选择此条目",Modifier.weight(1f),fontSize=12.sp,color=muted()) }
                    if(s.data.aliases.containsKey(row.bvid)) Text("原题：${row.title}")
                    VideoRowCard(s.data.aliases[row.bvid] ?: row.title,row.cover,row.author,row.duration,onClick={open(row.bvid, if(s.scope==0)s.folder else null, if(s.scope==2)HistoryOrigin.SEARCH else HistoryOrigin.FAVORITES)},onMore={vm.memberships(row.bvid)})
                    Row(Modifier.horizontalScroll(rememberScrollState())) { TextButton({vm.memberships(row.bvid)},enabled=!s.busy){Text("收藏归属")};TextButton({aliasItem=row;alias=s.data.aliases[row.bvid].orEmpty()},enabled=!s.busy){Text("本地别名")}
                        if(s.sort==3&&s.scope==0){ TextButton({vm.moveLocal(row.bvid,-1)},enabled=index>0&&!s.busy){Text("上移")};TextButton({vm.moveLocal(row.bvid,1)},enabled=index<s.rows.lastIndex&&!s.busy){Text("下移")}} }
                } }
            }
            if(s.hasMore) item { TextButton({vm.search(true)},enabled=!s.busy){Text("加载下一页 · 已显示 ${s.rows.size} 条") } }
        }
    }
    edit?.let { draft -> AlertDialog(onDismissRequest={if(!s.busy)edit=null},title={Text(if(draft.action==FolderAction.CREATE)"新建收藏夹" else "修改收藏夹")},text={Column {
        OutlinedTextField(name,{name=it.take(20)},label={Text("名称（最多20字）")},singleLine=true)
        Row { Checkbox(privateFolder,{privateFolder=it});Text(if(privateFolder)"私有：仅本人可见" else "公开：所有人可见") }
        Text("将写入当前 B 站账户。改名保留原简介和封面。")
    }},confirmButton={TextButton({vm.folder(draft.copy(title=name.trim(),private=privateFolder));edit=null},enabled=name.isNotBlank()&&!s.busy){Text("保存到B站")}},dismissButton={TextButton({edit=null}){Text("取消")}}) }
    deleting?.let { f -> if(deleteStage>0) AlertDialog(onDismissRequest={deleteStage=0;deleting=null},title={Text(if(deleteStage==1)"删除「${f.title}」？" else "再次确认永久删除")},text={Text("收藏夹：${f.title}\n当前记录 ${f.count} 项。删除整个远端收藏夹及其中的收藏关系；不会删除视频或其他夹中的收藏。此操作不能在本软件撤销。")},confirmButton={TextButton({if(deleteStage==1)deleteStage=2 else {vm.folder(FolderDraft(FolderAction.DELETE,f.id,confirmedTitle=f.title,confirmedCount=f.count));deleteStage=0;deleting=null}},enabled=!s.busy){Text(if(deleteStage==1)"继续确认" else "确认删除整个收藏夹")}},dismissButton={TextButton({deleteStage=0;deleting=null}){Text("取消")}}) }
    action?.let { type -> AlertDialog(onDismissRequest={action=null;confirmBatch=false},title={Text(if(confirmBatch)"确认整理 ${s.selected.size} 项？" else "选择目标收藏夹")},text={Column {
        Text("来源：${folder?.title}")
        if(type!=OrganizeAction.REMOVE) OrganizerChoice("目标",s.folders.filter{it.id!=s.folder}.map{it.id to it.title},target,true){target=it}
        Text(when(type){OrganizeAction.MOVE->"逐条先收藏到目标，再从来源移除。多重收藏的其他归属保留。";OrganizeAction.COPY->"仅添加到目标收藏夹，保留原收藏。";OrganizeAction.REMOVE->"仅移除当前夹中的收藏关系，其他夹不变。"})
        Text("部分条目可能失败；不会自动回滚或重复提交。")
    }},confirmButton={TextButton({if(!confirmBatch)confirmBatch=true else {vm.batch(target?:0,type);action=null;confirmBatch=false}},enabled=!s.busy&&(type==OrganizeAction.REMOVE||target!=null)){Text(if(confirmBatch)"开始整理" else "下一步")}},dismissButton={TextButton({action=null;confirmBatch=false}){Text("取消")}}) }
    aliasItem?.let { row -> AlertDialog(onDismissRequest={aliasItem=null},title={Text("本机别名")},text={Column{Text("原题：${row.title}");OutlinedTextField(alias,{alias=it.take(80)},label={Text("留空恢复原题")});Text("按当前账户保存在本机，不修改 B 站原视频标题。")}},confirmButton={TextButton({vm.alias(row.bvid,alias);aliasItem=null}){Text("保存")}},dismissButton={TextButton({aliasItem=null}){Text("取消")}}) }
    s.membership?.let { AlertDialog(onDismissRequest=vm::dismissMembership,title={Text("收藏归属")},text={Text(it)},confirmButton={TextButton(vm::dismissMembership){Text("关闭")}}) }
}

@Composable private fun <T> OrganizerChoice(label:String,options:List<Pair<T,String>>,value:T?,enabled:Boolean,onSelect:(T)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton({expanded=true},modifier=Modifier.fillMaxWidth(),enabled=enabled){Text("$label：${options.firstOrNull{it.first==value}?.second ?: "请选择"}")}
        DropdownMenu(expanded,{expanded=false}){options.forEach{(key,title)->DropdownMenuItem(text={Text(title)},onClick={expanded=false;onSelect(key)})}}
    }
}
