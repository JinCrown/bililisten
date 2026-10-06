package app.bililisten

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.*
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.RequestDisallowInterceptTouchEvent
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.bililisten.shared.*
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Preview reordering locally; release sends one stable-ID move to the service. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable internal fun QueuePanel(s:ScreenState,select:(String)->Unit,edit:(QueueEdit)->Unit,mode:()->Unit,clear:()->Unit,loadMetadata:(String)->Unit={}) {
    val latest by rememberUpdatedState(s)
    val commit by rememberUpdatedState(edit)
    var rows by remember { mutableStateOf(s.queue) }
    val list=rememberLazyListState()
    val density=LocalDensity.current
    val haptic=LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<String?>(null) }
    var center by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableIntStateOf(0) }
    var captured by remember { mutableStateOf(false) }
    var touchedId by remember { mutableStateOf<String?>(null) }
    var downY by remember { mutableFloatStateOf(0f) }
    var previousY by remember { mutableFloatStateOf(0f) }
    var initialCenter by remember { mutableFloatStateOf(0f) }
    var initialFirst by remember { mutableIntStateOf(0) }
    var initialScroll by remember { mutableIntStateOf(0) }
    val touchSlop=LocalViewConfiguration.current.touchSlop
    val disallowIntercept=remember{RequestDisallowInterceptTouchEvent()}
    val enabled=!s.busy&&!s.isLive&&s.canEditQueue
    val edge=with(density){52.dp.toPx()}
    LaunchedEffect(s.queue,s.busy,s.isLive) {
        // A new service queue invalidates an in-flight gesture, including remote/session changes.
        if(dragging!=null&&(s.busy||s.isLive||s.queue.map{it.id}!=rows.map{it.id})){dragging=null;captured=false;touchedId=null;disallowIntercept(false)}
        if(dragging==null)rows=s.queue
    }
    fun cancel(){val moved=dragging!=null;dragging=null;captured=false;touchedId=null;disallowIntercept(false);rows=latest.queue;if(moved)list.requestScrollToItem(initialFirst,initialScroll)}
    fun reorderAtPointer() {
        val id=dragging ?: return
        val visible=list.layoutInfo.visibleItemsInfo
        val target=visible.filter{it.key!=id}.minByOrNull{abs(it.offset+it.size/2f-center)} ?: return
        val from=rows.indexOfFirst{it.id==id};val to=rows.indexOfFirst{it.id==target.key}
        if(from<0||to<0)return
        val midpoint=target.offset+target.size/2f
        if((to>from&&center>midpoint)||(to<from&&center<midpoint)) {
            val first=list.firstVisibleItemIndex;val offset=list.firstVisibleItemScrollOffset
            rows=rows.toMutableList().apply{add(to,removeAt(from))}
            // Keep the viewport at the same index instead of anchoring to the moved key.
            list.requestScrollToItem(first,offset)
        }
    }
    LaunchedEffect(dragging) {
        while(dragging!=null) {
            val layout=list.layoutInfo
            val speed=when {
                center<layout.viewportStartOffset+edge -> -((layout.viewportStartOffset+edge-center)/edge).coerceIn(0f,1f)*with(density){12.dp.toPx()}
                center>layout.viewportEndOffset-edge -> ((center-layout.viewportEndOffset+edge)/edge).coerceIn(0f,1f)*with(density){12.dp.toPx()}
                else -> 0f
            }
            if(speed!=0f)list.scrollBy(speed)
            reorderAtPointer();delay(16)
        }
    }
    val modeLabel=when(s.mode){PlayMode.SEQUENTIAL->"顺序播放";PlayMode.REPEAT_ALL->"列表循环";PlayMode.REPEAT_ONE->"单条循环";PlayMode.SHUFFLE->"随机播放"}
    Column(Modifier.fillMaxWidth().heightIn(max=680.dp).padding(bottom=8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=PageSideInset,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
            Text("正在播放",fontSize=22.sp,fontWeight=FontWeight.Bold)
            Text("  ${s.queue.size}",fontSize=14.sp,color=muted(),modifier=Modifier.weight(1f))
            TextButton(clear,enabled=enabled&&s.queue.isNotEmpty()&&dragging==null){Text("清空")}
        }
        Row(Modifier.fillMaxWidth().padding(start=PageSideInset,end=PageSideInset,bottom=8.dp),verticalAlignment=Alignment.CenterVertically){
            TextButton(mode,enabled=!s.busy&&!s.isLive&&dragging==null){Glyph(if(s.mode==PlayMode.SHUFFLE)Mark.SHUFFLE else Mark.REPEAT,Modifier.size(19.dp),muted());Text(modeLabel,Modifier.padding(start=7.dp),color=muted())}
            Spacer(Modifier.weight(1f));Text("本机队列",fontSize=11.sp,color=muted())
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        if(s.queue.isEmpty()||s.isLive) {
            Box(Modifier.fillMaxWidth().padding(32.dp),contentAlignment=Alignment.Center){Text(if(s.isLive)"直播没有点播队列" else "队列还是空的，选个视频开始听吧",color=muted())}
        } else Box(Modifier.weight(1f,false).fillMaxWidth().pointerInteropFilter(requestDisallowInterceptTouchEvent=disallowIntercept) { event ->
            // Observe native CANCEL directly: Compose's synthetic release must never commit it.
            when(event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val info=list.layoutInfo.visibleItemsInfo.firstOrNull{event.y>=it.offset&&event.y<it.offset+it.size}
                    captured=enabled&&event.x>=list.layoutInfo.viewportSize.width-with(density){52.dp.toPx()}&&info!=null
                    if(captured){initialFirst=list.firstVisibleItemIndex;initialScroll=list.firstVisibleItemScrollOffset;disallowIntercept(true);touchedId=info!!.key as? String;downY=event.y;previousY=event.y;initialCenter=info.offset+info.size/2f;dragHeight=info.size}
                    captured
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if(captured) {
                        val amount=event.y-previousY;previousY=event.y
                        if(dragging==null&&touchedId!=null&&abs(event.y-downY)>touchSlop){dragging=touchedId;center=initialCenter;haptic.performHapticFeedback(HapticFeedbackType.LongPress)}
                        if(dragging==touchedId&&dragging!=null){center+=amount;reorderAtPointer()}
                    }
                    captured
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val wasCaptured=captured;disallowIntercept(false);captured=false;touchedId=null
                    val id=dragging;val to=rows.indexOfFirst{it.id==id};val from=latest.queue.indexOfFirst{it.id==id};dragging=null
                    if(id!=null&&from>=0&&to>=0&&from!=to)commit(QueueEdit.Move(id,to)) else rows=latest.queue
                    wasCaptured
                }
                android.view.MotionEvent.ACTION_CANCEL -> {val wasCaptured=captured;disallowIntercept(false);captured=false;touchedId=null;cancel();wasCaptured}
                android.view.MotionEvent.ACTION_POINTER_DOWN -> {touchedId=null;cancel();captured}
                else -> captured
            }
        }) {
            LazyColumn(state=list,modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(vertical=6.dp),userScrollEnabled=dragging==null) {
                itemsIndexed(rows,key={_,row->row.id}) { index,row ->
                    LaunchedEffect(row.bvid){loadMetadata(row.bvid)}
                    val active=row.id==s.currentId
                    val moving=row.id==dragging
                    QueueRow(row,s.metadata[row.bvid],active,s.playing,index,
                        Modifier.alpha(if(moving)0f else 1f),!s.busy&&dragging==null,
                        {select(row.id)},{commit(QueueEdit.Remove(row.id))},
                        Modifier.semantics {
                            contentDescription="拖动排序：${playbackEntryTitle(s.metadata[row.bvid],row,row.title)}"
                            stateDescription="第 ${index+1} 项，共 ${rows.size} 项"
                            if(enabled&&dragging==null)customActions=listOfNotNull(
                                if(index>0)CustomAccessibilityAction("上移"){commit(QueueEdit.Move(row.id,index-1));true} else null,
                                if(index<rows.lastIndex)CustomAccessibilityAction("下移"){commit(QueueEdit.Move(row.id,index+1));true} else null)
                        },enabled&&dragging==null)
                }
            }
            val row=rows.firstOrNull{it.id==dragging}
            if(row!=null)QueueRow(row,s.metadata[row.bvid],row.id==s.currentId,s.playing,rows.indexOf(row),
                Modifier.offset{IntOffset(0,(center-dragHeight/2f).toInt())}.shadow(8.dp,RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(14.dp)).clearAndSetSemantics{},false,{},{},Modifier,false)
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        Text("点 × 移出队列 · 按住 ≡ 拖动排序",Modifier.fillMaxWidth().padding(horizontal=PageSideInset,vertical=14.dp),fontSize=12.sp,color=muted())
    }
}

@Composable private fun QueueRow(row:QueueEntry,video:Video?,active:Boolean,playing:Boolean,index:Int,modifier:Modifier,selectEnabled:Boolean,select:()->Unit,remove:()->Unit,handle:Modifier,editEnabled:Boolean) {
    val title=playbackEntryTitle(video,row,row.title)
    val color=if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(modifier.fillMaxWidth().heightIn(min=70.dp).padding(start=PageSideInset,end=PageSideInset),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(enabled=selectEnabled,onClick=select).padding(top=12.dp,bottom=12.dp,end=6.dp)) {
            Text(title,fontSize=15.sp,lineHeight=21.sp,color=color,fontWeight=if(active)FontWeight.SemiBold else FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
            Row(Modifier.padding(top=3.dp),verticalAlignment=Alignment.CenterVertically){
                Text(video?.author?.takeIf{it.isNotBlank()} ?: "视频音频",Modifier.weight(1f,false),fontSize=11.sp,color=if(active)color.copy(alpha=.8f) else muted(),maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(" · P${row.part}",fontSize=11.sp,color=muted())
                if(active)Text(if(playing)" · 正在收听" else " · 已暂停",fontSize=11.sp,color=color)
            }
        }
        if(active)Canvas(Modifier.size(18.dp).semantics{contentDescription=if(playing)"正在收听" else "当前条目，已暂停"}){listOf(.45f,.85f,.6f).forEachIndexed{i,h->drawRoundRect(color,androidx.compose.ui.geometry.Offset(i*size.width/3f,size.height*(1-h)),androidx.compose.ui.geometry.Size(size.width/7f,size.height*h),androidx.compose.ui.geometry.CornerRadius(2f))}}
        ActionIcon(Mark.CLOSE,"从队列移除：$title",remove,Modifier.size(44.dp),muted(),editEnabled)
        Box(handle.size(44.dp),contentAlignment=Alignment.Center){Canvas(Modifier.size(20.dp)){for(y in listOf(.25f,.5f,.75f))drawLine(if(editEnabled)color.copy(alpha=.6f) else color.copy(alpha=.3f),androidx.compose.ui.geometry.Offset(3f,size.height*y),androidx.compose.ui.geometry.Offset(size.width-3f,size.height*y),strokeWidth=1.5.dp.toPx(),cap=StrokeCap.Round)}}
    }
}
