package app.bililisten

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.bililisten.shared.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun VideoDetailsPage(s:ScreenState,current:Video?,entry:QueueEntry?,position:Long,duration:Long,modifier:Modifier,
    back:()->Unit,toggle:()->Unit,seek:(Long)->Unit,previous:()->Unit,next:()->Unit,queue:()->Unit,
    retry:()->Unit,login:()->Unit,chooseFavorite:()->Unit,
    engage:(String,String,EngagementAction,Int)->Unit,reconcile:()->Unit,creator:()->Unit,official:()->Unit,copy:(String)->Unit,favorite:()->Unit=chooseFavorite) {
    val detail=s.videoDetails.takeIf{it.bvid==entry?.bvid} ?: VideoDetailsView(entry?.bvid,video=current)
    val video=detail.video ?: current?.takeIf{it.bvid==entry?.bvid}
    val relations=detail.relations
    val owner=s.account?.id?.toString()
    val pending=s.mutationPending!=null
    val ready=video!=null&&!s.busy&&!detail.loading&&relations!=null&&!pending
    val coinLimit=when(video?.copyright){1->2;2->1;else->0}
    val remaining=(coinLimit-(relations?.coins ?: 0)).coerceAtLeast(0)
    val ownVideo=owner!=null&&video?.owner?.toString()==owner
    val defaultFavoritePresent=video!=null&&s.currentFavoriteBvid==video.bvid&&s.currentFavoriteFolder==s.settings.defaultFavoriteFolders[owner]?.id&&s.currentFavoritePresent==true
    var dialog by rememberSaveable(entry?.bvid,owner){mutableStateOf<String?>(null)}
    var amount by rememberSaveable(entry?.bvid,owner){mutableIntStateOf(1)}
    var holdProgress by remember { mutableFloatStateOf(0f) }
    var expanded by rememberSaveable(entry?.bvid){mutableStateOf(false)}
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(start=PageSideInset,end=PageSideInset,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                if(detail.loading)LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                detail.error?.let{InfoCard(it);TextButton(retry,enabled=!s.busy){Text("重新读取详情")}}
            }
            if(video!=null) {
                item {PlayerIdentity(s,video,entry,creator,details=true)}
                item {
                    Text(listOfNotNull(video.published?.let{DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(it))},video.zoneName.takeIf{it.isNotBlank()},when(video.copyright){1->"原创";2->"转载";else->null}).joinToString(" · "),Modifier.padding(top=7.dp),fontSize=12.sp,color=muted())
                }
                item {PanelCard {
                    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(18.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                        listOf("播放" to video.views,"弹幕" to video.danmaku,"评论" to video.replies,"分享" to video.shares).forEach{(label,value)->Text("${countLabel(value)} $label",fontSize=12.sp,color=muted())}
                    }
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                        TripleLikeTile(if(relations?.liked==true)"已点赞" else "点赞",countLabel(video.likes),relations?.liked==true,owner==null||ready,
                            video.bvid+"/"+owner,{if(owner==null)login()else engage(video.bvid,owner,EngagementAction.LIKE,0)},
                            {if(owner==null)login()else engage(video.bvid,owner,EngagementAction.TRIPLE,0)},{holdProgress=it})
                        EngagementTile(Mark.COIN,if((relations?.coins ?: 0)>0)"已投 ${relations!!.coins} 枚" else "投币",countLabel(video.coins),(relations?.coins ?: 0)>0,
                            owner==null||ready&&remaining>0&&!ownVideo,{if(owner==null)login()else{amount=1;dialog="coin"}},progress=holdProgress)
                        EngagementTile(Mark.STAR,if(defaultFavoritePresent)"取消收藏" else "收藏",countLabel(video.favorites),defaultFavoritePresent,!s.busy&&!pending,
                            {if(owner==null)login()else favorite()},chooseFavorite,progress=holdProgress)
                    }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom) {
                        Text(if(holdProgress>0f)"继续按住，完成三连…" else "长按点赞完成三连 · 收藏到默认收藏夹",Modifier.weight(1f).padding(end=8.dp),fontSize=11.sp,color=muted())
                        if(owner!=null)Text(when {
                            detail.coinBalanceLoading->"余额读取中…"
                            detail.coinBalance!=null->"剩余硬币 ${java.math.BigDecimal.valueOf(detail.coinBalance!!).stripTrailingZeros().toPlainString()}"
                            else->"余额暂不可用"
                        },Modifier.widthIn(max=150.dp).semantics{contentDescription="账户硬币余额"},fontSize=11.sp,color=muted())
                    }
                    if(owner==null)Text("登录 B站账号后，互动会同步到 B站",fontSize=12.sp,color=muted())
                    else if(ownVideo)Text("自己的投稿不能投币，仍可点赞和收藏",fontSize=12.sp,color=muted())
                    detail.relationError?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error);TextButton(retry){Text("重新读取互动状态")}}
                    if(detail.message.isNotBlank())Text(detail.message,Modifier.semantics{contentDescription="视频互动结果"},fontSize=13.sp,color=MaterialTheme.colorScheme.primary)
                    if(pending)TextButton(reconcile,enabled=!s.busy){Text("核对上次操作结果")}
                }}
                item {PanelCard {
                    Text("简介",fontSize=17.sp,fontWeight=FontWeight.Bold)
                    Text(video.description.ifBlank{"UP 主暂未填写简介"},Modifier.semantics{contentDescription="视频简介"},fontSize=14.sp,lineHeight=23.sp,maxLines=if(expanded)Int.MAX_VALUE else 5,overflow=TextOverflow.Ellipsis)
                    if(video.description.length>120||video.description.count{it=='\n'}>4)TextButton({expanded=!expanded},contentPadding=PaddingValues(0.dp)){Text(if(expanded)"收起简介" else "展开全部简介")}
                    if(detail.tags.isNotEmpty())FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {detail.tags.forEach{tag->Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surfaceVariant){Text(tag,Modifier.padding(horizontal=10.dp,vertical=6.dp),fontSize=11.sp,color=muted())}}}
                }}
                item {PanelCard {
                    Row(Modifier.fillMaxWidth().clickable{copy(video.bvid)},verticalAlignment=Alignment.CenterVertically){Text(video.bvid,Modifier.weight(1f),fontSize=12.sp,color=muted());Text("复制 BV",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)}
                    Text("共 ${video.parts.size} 个分 P · ${timeLabel(video.duration*1000)}",fontSize=12.sp,color=muted())
                    TextButton(official,contentPadding=PaddingValues(0.dp)){Glyph(Mark.TV,Modifier.size(20.dp));Text("去 B站查看视频",Modifier.padding(start=8.dp))}
                }}
            } else if(!detail.loading) item {EmptyState("详情暂不可用","可以继续收听，或重新读取视频详情","重新读取",retry)}
        }
        Surface(shadowElevation=6.dp,color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal=PageSideInset)) {
                PlaybackSeek(position,duration,s.canSeek&&!s.busy,seek)
                Row(Modifier.fillMaxWidth().padding(bottom=5.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                    ActionIcon(Mark.PREVIOUS,"上一条",previous,enabled=s.canPrevious)
                    FilledIconButton(toggle,Modifier.size(52.dp).semantics{contentDescription=if(s.playRequested)"暂停" else "播放"}){Glyph(if(s.playRequested)Mark.PAUSE else Mark.PLAY)}
                    ActionIcon(Mark.NEXT,"下一条",next,enabled=s.canNext)
                    ActionIcon(Mark.QUEUE,"播放队列",queue)
                }
            }
        }
    }
    if(dialog=="coin")AlertDialog(onDismissRequest={dialog=null},title={Text("为 UP 主投币")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(video?.title.orEmpty(),maxLines=2,overflow=TextOverflow.Ellipsis)
        Text("请选择投币枚数，确认后无法撤回")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){(1..minOf(2,remaining)).forEach{value->FilterChip(amount==value,{amount=value},label={Text("$value 枚")})}}
    }},confirmButton={TextButton({dialog=null;if(video!=null&&owner!=null)engage(video.bvid,owner,EngagementAction.COIN,amount)},enabled=ready&&amount in 1..remaining){Text("确认投币")}},dismissButton={TextButton({dialog=null}){Text("取消")}})

}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun RowScope.EngagementTile(mark:Mark,label:String,count:String,selected:Boolean,enabled:Boolean,click:()->Unit,longClick:()->Unit=click,progress:Float=0f) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).combinedClickable(enabled=enabled,onClick=click,onLongClick=longClick)
        .semantics{contentDescription=label;stateDescription=if(selected)"已完成" else "未完成"}.padding(vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp),contentAlignment=Alignment.Center){if(progress>0f)CircularProgressIndicator(progress={progress},modifier=Modifier.size(40.dp),strokeWidth=2.dp);Glyph(mark,Modifier.size(28.dp),if(selected)MaterialTheme.colorScheme.primary else muted().copy(alpha=if(enabled)1f else .4f),selected)}
        Text(label,Modifier.padding(top=5.dp),fontSize=12.sp,color=if(selected)MaterialTheme.colorScheme.primary else muted())
        Text(count,Modifier.padding(top=2.dp),fontSize=11.sp,color=muted())
    }
}
