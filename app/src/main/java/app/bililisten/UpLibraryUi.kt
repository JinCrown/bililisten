package app.bililisten

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bililisten.shared.*

@Composable internal fun UpCard(profile:UpProfile,open:()->Unit) {
    PanelCard {Row(Modifier.fillMaxWidth().semantics{contentDescription="查看 ${profile.name} 的投稿"}.clickable(onClick=open).padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
        Cover(profile.avatar,Modifier.size(58.dp).clip(CircleShape),label="UP 主头像")
        Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
            Text(profile.name,fontSize=16.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("UID ${profile.mid}",fontSize=11.sp,color=muted())
            Text("${profile.videos} 个投稿"+(profile.fans?.let{" · ${countLabel(it)} 粉丝"} ?: ""),fontSize=12.sp,color=muted())
        }
        Glyph(Mark.CHEVRON,Modifier.size(17.dp),muted())
    }}
}
internal fun LazyListScope.upSearchItems(s:ScreenState,search:(String)->Unit,open:(UpProfile)->Unit,more:()->Unit,cancel:()->Unit,retry:()->Unit) {
    item {
        var input by rememberSaveable {mutableStateOf(s.upSearchKeyword)}
        val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
        fun submit(){search(input);focus.clearFocus();keyboard?.hide()}
        OutlinedTextField(input,{input=it.take(100);cancel()},Modifier.fillMaxWidth(),label={Text("UP 主名字、UID 或主页链接")},singleLine=true,
            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={submit()}))
        Button({submit()},Modifier.fillMaxWidth().padding(top=10.dp).semantics{contentDescription="提交 UP 主搜索"}){Glyph(Mark.SEARCH,Modifier.size(18.dp));Text("搜索 UP 主",Modifier.padding(start=8.dp))}
        Text("找到喜欢的 UP 主，把全部投稿作为歌单收听。",Modifier.padding(top=10.dp),fontSize=12.sp,color=muted())
    }
    if(s.upSearchBusy)item{Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(18.dp));Text("正在搜索 UP 主",Modifier.weight(1f).padding(10.dp));TextButton(cancel){Text("取消")}}}
    s.upSearchError?.let{error->item{Text(error,fontSize=13.sp,color=muted());TextButton(retry){Text("重新搜索")}}}
    items(s.upSearch?.items.orEmpty(),key={it.mid}){UpCard(it){open(it)}}
    if(s.upSearch?.items?.isEmpty()==true&&!s.upSearchBusy&&s.upSearchError==null)item{EmptyState("没有找到 UP 主","试试完整名字，或粘贴主页链接")}
    if(s.upSearch?.hasMore==true)item{TextButton(more,enabled=!s.upSearchBusy){Text("加载更多 UP 主")}}
}
internal fun LazyListScope.upUploadsItems(s:ScreenState,playAll:(Boolean)->Unit,bookmark:(Boolean)->Unit,order:(UploadOrder)->Unit,query:(String)->Unit,
    play:(FavoriteItem)->Unit,inspect:(FavoriteItem)->Unit,more:()->Unit,reload:()->Unit,cancelRead:()->Unit,cancelQueue:()->Unit) {
    val profile=s.upProfile ?: return
    val source=s.sourceContent
    val saved=s.bookmarks.any{it.source==profile.source}
    item {
        PanelCard {
            Row(verticalAlignment=Alignment.CenterVertically){Cover(profile.avatar,Modifier.size(70.dp).clip(CircleShape),label="UP 主头像");Column(Modifier.weight(1f).padding(start=14.dp)) {
                Text(profile.name,fontSize=21.sp,lineHeight=28.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text("UID ${profile.mid}",fontSize=11.sp,color=muted())
                Text("${profile.videos} 个投稿",fontSize=13.sp,color=muted())
            }}
            if(profile.signature.isNotBlank())Text(profile.signature,Modifier.padding(top=10.dp),fontSize=12.sp,color=muted(),maxLines=3,overflow=TextOverflow.Ellipsis)
            TextButton({bookmark(!saved)},enabled=!s.busy){Glyph(Mark.STAR,Modifier.size(18.dp));Text(if(saved)"已加入 UP 收藏 · 点击移除" else "加入 UP 收藏",Modifier.padding(start=7.dp))}
        }
        Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button({playAll(false)},Modifier.weight(1f),enabled=!s.busy&&source?.items?.isNotEmpty()==true){Text("播放全部")}
            OutlinedButton({playAll(true)},Modifier.weight(1f),enabled=!s.busy&&source?.items?.isNotEmpty()==true){Text("随机播放")}
        }
        Text(if(s.upQuery.isBlank())"全部投稿 · 各视频 P1 · 音频按播放读取" else "当前搜索结果 · 各视频 P1 · 音频按播放读取",fontSize=11.sp,color=muted())
        if(s.collectionPreparing)Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(16.dp));Text(s.collectionProgress,Modifier.weight(1f).padding(8.dp),fontSize=12.sp);TextButton(cancelQueue){Text("取消")}}
        if(s.upLoading)Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(16.dp));Text(s.upProgress,Modifier.weight(1f).padding(8.dp),fontSize=12.sp);TextButton(cancelRead){Text("取消读取")}}
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            UploadOrder.entries.forEach{choice->FilterChip(s.upOrder==choice,{order(choice)},enabled=!s.busy,label={Text(choice.label)})}
        }
        var input by rememberSaveable(profile.mid,s.upQuery){mutableStateOf(s.upQuery)}
        val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
        fun submit(){query(input);focus.clearFocus();keyboard?.hide()}
        OutlinedTextField(input,{input=it.take(100)},Modifier.fillMaxWidth(),label={Text("搜索该 UP 主的投稿")},singleLine=true,enabled=!s.busy,
            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={submit()}),
            trailingIcon={TextButton({submit()},enabled=!s.busy){Text("查找")}})
        Row(verticalAlignment=Alignment.CenterVertically){Text("已加载 ${source?.items?.size ?: 0} 项"+(if(s.sourceCached)" · 本机保存的列表" else if(source?.hasMore==true)" · 可继续加载" else if(source!=null)" · 已完整" else ""),Modifier.weight(1f),fontSize=11.sp,color=muted());TextButton(reload,enabled=!s.busy){Text("刷新投稿")}}
    }
    items(source?.items.orEmpty(),key={it.bvid}){row->Column {
        VideoRowCard(row.title,row.cover,row.author,row.duration,onClick={play(row)},onMore={inspect(row)})
        if(row.bvid in s.sourceHeard)Text("已听",fontSize=11.sp,color=ListenPink)
    }}
    if(source?.items?.isEmpty()==true&&!s.busy)item{EmptyState(if(s.upQuery.isBlank())"暂无投稿" else "没有匹配的投稿","可更换关键词或刷新查看")}
    if(source==null&&!s.busy)item{EmptyState("投稿暂未读取",s.error ?: "点击刷新投稿再试一次","重新读取投稿",reload)}
    if(source?.hasMore==true&&!s.sourceCached)item{TextButton(more,enabled=!s.busy){Text("加载更多投稿 · 已显示 ${source.items.size} 项")}}
}
