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

/** Drag previews are local; only release commits one local layout update. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable internal fun LibraryManagementPage(s:ScreenState,modifier:Modifier,back:()->Unit,section:(LibrarySection)->Unit,
    hide:(SourceRef,Boolean)->Unit,move:(SourceRef,Int)->Unit,reset:(Boolean)->Unit,cancelLoading:()->Unit) {
    Column(modifier.fillMaxSize().padding(horizontal=PageSideInset)) {
        Row(Modifier.heightIn(min=64.dp),verticalAlignment=Alignment.CenterVertically){ActionIcon(Mark.BACK,"返回收藏",back);Text("收藏管理",fontSize=22.sp,fontWeight=FontWeight.Bold)}
        Text("隐藏不需要听的来源，按住 ≡ 拖动调整顺序",fontSize=13.sp,color=muted())
        Text("只改变本机显示；换手机需通过数据转移导入",Modifier.padding(top=4.dp,bottom=10.dp),fontSize=11.sp,color=muted())
        FavoriteSourceTabs(s.managementSection.ordinal){if(!s.busy)section(LibrarySection.entries[it])}
        if(s.managementLoading)Row(verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(18.dp));Text(s.message,Modifier.weight(1f).padding(8.dp),fontSize=12.sp);TextButton(cancelLoading){Text("取消加载")}}
        s.error?.let{Text(it,Modifier.padding(vertical=8.dp),color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
        if(s.managementReady)Row(verticalAlignment=Alignment.CenterVertically){
            Text("${s.managementSources.size} 个来源 · ${s.managementSources.count{s.library.hidden(it.ref)}} 个已隐藏",Modifier.weight(1f),fontSize=11.sp,color=muted())
            TextButton({reset(false)},enabled=!s.busy){Text("全部显示",fontSize=12.sp)}
            TextButton({reset(true)},enabled=!s.busy){Text("默认顺序",fontSize=12.sp)}
        }
        if(s.managementSources.isEmpty()&&!s.managementLoading)EmptyState(if(s.managementReady)"暂无可管理的来源" else "来源尚未读取",if(s.account==null&&s.managementSection!=LibrarySection.UP)"登录后可管理自己的收藏和追更来源" else "可以重新读取来源；已有隐藏与排序设置保留","重新读取",{section(s.managementSection)})
        key(s.managementSection,s.account?.id){ManagedSourceList(s,Modifier.weight(1f),hide,move)}
        if(s.busy&&!s.managementLoading)LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable private fun ManagedSourceList(s:ScreenState,modifier:Modifier,hide:(SourceRef,Boolean)->Unit,move:(SourceRef,Int)->Unit) {
    fun key(row:ContentSource)=SourceCodec.encode(row.ref)
    val ordered=s.library.apply(s.managementSection,s.managementSources,true){it.ref}
    val latest by rememberUpdatedState(ordered)
    val commit by rememberUpdatedState(move)
    var rows by remember{mutableStateOf(ordered)}
    var pending by remember{mutableStateOf(false)}
    val list=rememberLazyListState();val density=LocalDensity.current;val haptic=LocalHapticFeedback.current
    var dragging by remember{mutableStateOf<String?>(null)}
    var center by remember{mutableFloatStateOf(0f)};var dragHeight by remember{mutableIntStateOf(0)}
    var captured by remember{mutableStateOf(false)};var touchedId by remember{mutableStateOf<String?>(null)}
    var downY by remember{mutableFloatStateOf(0f)};var previousY by remember{mutableFloatStateOf(0f)}
    var initialCenter by remember{mutableFloatStateOf(0f)};var initialFirst by remember{mutableIntStateOf(0)};var initialScroll by remember{mutableIntStateOf(0)}
    val touchSlop=LocalViewConfiguration.current.touchSlop
    val disallowIntercept=remember{RequestDisallowInterceptTouchEvent()}
    val enabled=s.managementReady&&!s.busy
    val edge=with(density){52.dp.toPx()}
    LaunchedEffect(ordered,s.busy,s.managementReady) {
        if(dragging!=null&&(s.busy||!s.managementReady||ordered.map(::key).toSet()!=rows.map(::key).toSet())){dragging=null;captured=false;touchedId=null;disallowIntercept(false)}
        if(!s.busy)pending=false
        if(dragging==null&&!pending)rows=ordered
    }
    fun cancel(){val moved=dragging!=null;dragging=null;captured=false;touchedId=null;disallowIntercept(false);pending=false;rows=latest;if(moved)list.requestScrollToItem(initialFirst,initialScroll)}
    fun reorderAtPointer(settle:Boolean=false){
        val id=dragging ?: return;val visible=list.layoutInfo.visibleItemsInfo
        val target=visible.filter{it.key!=id}.minByOrNull{abs(it.offset+it.size/2f-center)} ?: return
        val from=rows.indexOfFirst{key(it)==id};val to=rows.indexOfFirst{key(it)==target.key}
        if(from<0||to<0)return
        val midpoint=target.offset+target.size/2f
        if((to>from&&center>midpoint)||(to<from&&center<midpoint)||(settle&&center in target.offset.toFloat()..(target.offset+target.size).toFloat())){
            val first=list.firstVisibleItemIndex;val offset=list.firstVisibleItemScrollOffset
            rows=rows.toMutableList().apply{add(to,removeAt(from))};list.requestScrollToItem(first,offset)
        }
    }
    LaunchedEffect(dragging){while(dragging!=null){
        val layout=list.layoutInfo
        val speed=when{center<layout.viewportStartOffset+edge->-((layout.viewportStartOffset+edge-center)/edge).coerceIn(0f,1f)*with(density){12.dp.toPx()};center>layout.viewportEndOffset-edge->((center-layout.viewportEndOffset+edge)/edge).coerceIn(0f,1f)*with(density){12.dp.toPx()};else->0f}
        if(speed!=0f)list.scrollBy(speed);reorderAtPointer();delay(16)
    }}
    Box(modifier.fillMaxWidth().pointerInteropFilter(requestDisallowInterceptTouchEvent=disallowIntercept){event->
        when(event.actionMasked){
            android.view.MotionEvent.ACTION_DOWN->{
                val info=list.layoutInfo.visibleItemsInfo.firstOrNull{event.y>=it.offset&&event.y<it.offset+it.size}
                captured=enabled&&event.x>=list.layoutInfo.viewportSize.width-with(density){52.dp.toPx()}&&info!=null
                if(captured){initialFirst=list.firstVisibleItemIndex;initialScroll=list.firstVisibleItemScrollOffset;disallowIntercept(true);touchedId=info!!.key as? String;downY=event.y;previousY=event.y;initialCenter=info.offset+info.size/2f;dragHeight=info.size};captured
            }
            android.view.MotionEvent.ACTION_MOVE->{if(captured){val amount=event.y-previousY;previousY=event.y;if(dragging==null&&touchedId!=null&&abs(event.y-downY)>touchSlop){dragging=touchedId;center=initialCenter;haptic.performHapticFeedback(HapticFeedbackType.LongPress)};if(dragging==touchedId&&dragging!=null){center+=amount;reorderAtPointer()}};captured}
            android.view.MotionEvent.ACTION_UP->{val wasCaptured=captured;reorderAtPointer(true);disallowIntercept(false);captured=false;touchedId=null;val id=dragging;val to=rows.indexOfFirst{key(it)==id};val from=latest.indexOfFirst{key(it)==id};dragging=null;if(id!=null&&from>=0&&to>=0&&from!=to){pending=true;commit(rows[to].ref,to)} else rows=latest;wasCaptured}
            android.view.MotionEvent.ACTION_CANCEL->{val wasCaptured=captured;cancel();wasCaptured}
            android.view.MotionEvent.ACTION_POINTER_DOWN->{val wasCaptured=captured;cancel();wasCaptured}
            else->captured
        }
    }){
        LazyColumn(state=list,modifier=Modifier.fillMaxSize(),contentPadding=PaddingValues(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp),userScrollEnabled=dragging==null){
            itemsIndexed(rows,key={_,row->key(row)}){index,row->
                val hidden=s.library.hidden(row.ref)
                ManagedSourceRow(row,hidden,Modifier.alpha(if(key(row)==dragging)0f else 1f),enabled&&dragging==null,{hide(row.ref,!hidden)},Modifier.semantics{
                    contentDescription="拖动来源：${row.title}";stateDescription="第 ${index+1} 项，共 ${rows.size} 项"
                    if(enabled&&dragging==null)customActions=listOfNotNull(if(index>0)CustomAccessibilityAction("上移"){commit(row.ref,index-1);true} else null,if(index<rows.lastIndex)CustomAccessibilityAction("下移"){commit(row.ref,index+1);true} else null)
                })
            }
        }
        rows.firstOrNull{key(it)==dragging}?.let{row->ManagedSourceRow(row,s.library.hidden(row.ref),Modifier.offset{IntOffset(0,(center-dragHeight/2f).toInt())}.shadow(8.dp,RoundedCornerShape(18.dp)).clearAndSetSemantics{},false,{},Modifier)}
    }
}
@Composable private fun ManagedSourceRow(row:ContentSource,hidden:Boolean,modifier:Modifier,enabled:Boolean,toggle:()->Unit,handle:Modifier){
    val handleColor=muted().copy(alpha=if(enabled).7f else .3f)
    val compact=LocalDensity.current.fontScale>1.3f||LocalConfiguration.current.screenWidthDp<340
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
        Row(Modifier.padding(start=12.dp,top=12.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically){
            if(!compact)Cover(row.cover,Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).alpha(if(hidden).45f else 1f))
            Column(Modifier.weight(1f).padding(horizontal=10.dp)){
                Text(row.title,fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold,color=if(hidden)muted() else MaterialTheme.colorScheme.onSurface,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text((if(row.ref.kind==SourceKind.UP_COLLECTION)"合集" else if(row.ref.kind==SourceKind.UP_UPLOADS)"UP 投稿" else "收藏夹")+" · "+if(hidden)"已隐藏" else "显示中",Modifier.padding(top=4.dp),fontSize=11.sp,color=muted())
            }
            TextButton(toggle,enabled=enabled,modifier=Modifier.semantics{contentDescription=(if(hidden)"恢复显示：" else "隐藏来源：")+row.title},contentPadding=PaddingValues(horizontal=6.dp)){Text(if(hidden)"显示" else "隐藏",fontSize=12.sp)}
            Box(handle.size(48.dp),contentAlignment=Alignment.Center){Canvas(Modifier.size(20.dp)){for(y in listOf(.25f,.5f,.75f))drawLine(handleColor,androidx.compose.ui.geometry.Offset(3f,size.height*y),androidx.compose.ui.geometry.Offset(size.width-3f,size.height*y),strokeWidth=1.5.dp.toPx(),cap=StrokeCap.Round)}}
        }
    }
}
