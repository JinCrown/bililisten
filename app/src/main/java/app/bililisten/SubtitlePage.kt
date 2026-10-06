package app.bililisten

import androidx.compose.foundation.*
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.bililisten.shared.*

@Composable fun SubtitlePage(s: ScreenState,video: Video?,modifier: Modifier,back:()->Unit,toggle:()->Unit,
    seek:(Long)->Unit,previous:()->Unit,next:()->Unit,queue:()->Unit,retry:()->Unit,select:(String)->Unit,mode:(Boolean)->Unit,login:()->Unit) {
    val subtitle=s.subtitles
    val timeline=remember(subtitle.cues){SubtitleTimeline(subtitle.cues)}
    val active=remember(timeline,s.positionMs){timeline.active(s.positionMs)}
    var languageMenu by remember{mutableStateOf(false)}
    val white=Color.White
    Box(modifier.fillMaxSize().background(Color(0xFF29243C))) {
        Cover(video?.cover.orEmpty(),Modifier.fillMaxSize().blur(45.dp).alpha(.22f))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xAA42344F),Color(0xF21C1A29)))))
        Column(Modifier.fillMaxSize().padding(horizontal=PageSideInset)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton({languageMenu=true},enabled=subtitle.options.isNotEmpty()) {
                        Text(subtitle.selected?.let{"${it.label} · ${it.kind.label} ▾"} ?: "字幕语言",color=white,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    DropdownMenu(languageMenu,{languageMenu=false}) {
                        subtitle.options.forEach { option -> DropdownMenuItem(text={Text("${option.label} · ${option.kind.label}${if(option==subtitle.selected)" · 已选择" else ""}")},onClick={languageMenu=false;select(option.id)}) }
                    }
                }
                TextButton({mode(false)}){Text("滚动字幕",color=if(!s.settings.subtitleLineMode)ImmersivePink else white,fontSize=11.sp)}
                TextButton({mode(true)}){Text("逐行字幕",color=if(s.settings.subtitleLineMode)ImmersivePink else white,fontSize=11.sp)}
                ActionIcon(Mark.REFRESH,"重新读取字幕",retry,Modifier.size(36.dp),white.copy(alpha=.65f),enabled=!s.busy)
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when(subtitle.status) {
                    SubtitleStatus.READY -> {
                        if(s.settings.subtitleLineMode) {
                            val shown=active.ifEmpty {
                                val nearby=timeline.nearby(s.positionMs)
                                if(subtitle.cues.getOrNull(nearby)?.let{s.positionMs>=it.fromMs}==true)listOf(nearby) else emptyList()
                            }
                            key(timeline) {
                                Crossfade(shown,Modifier.fillMaxSize(),animationSpec=tween(100),label="字幕句子切换") { indices ->
                                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=14.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                                        indices.forEach { index -> Text(subtitle.cues[index].content,Modifier.fillMaxWidth().padding(vertical=7.dp).semantics{
                                            contentDescription=if(index in active)"当前字幕" else "跟随字幕"
                                        },color=ImmersivePink,fontSize=22.sp,fontWeight=FontWeight.Bold,textAlign=TextAlign.Center) }
                                    }
                                }
                            }
                        } else SubtitleScroll(subtitle,timeline,s.positionMs,active,s.canSeek,seek)
                    }
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                        if(subtitle.status==SubtitleStatus.LOADING)CircularProgressIndicator(color=ImmersivePink,modifier=Modifier.size(30.dp)) else Glyph(Mark.SUBTITLE,Modifier.size(35.dp),white.copy(alpha=.6f))
                        Text(when(subtitle.status) {
                            SubtitleStatus.LOADING -> "正在读取字幕"
                            SubtitleStatus.EMPTY -> "暂无独立字幕"
                            SubtitleStatus.LOGIN_REQUIRED -> "读取字幕需要登录"
                            SubtitleStatus.RESTRICTED -> "字幕读取受限"
                            SubtitleStatus.EXPIRED -> "字幕地址已失效"
                            SubtitleStatus.FAILED -> "字幕读取失败"
                            else -> "当前暂无可读取的点播字幕"
                        },Modifier.padding(top=12.dp),color=white,fontSize=18.sp,textAlign=TextAlign.Center)
                        Text(subtitle.message,Modifier.padding(top=8.dp),color=white.copy(alpha=.75f),fontSize=12.sp,textAlign=TextAlign.Center)
                        if(subtitle.status==SubtitleStatus.LOGIN_REQUIRED)TextButton(login){Text("去登录",color=ImmersivePink)}
                        if(subtitle.status !in setOf(SubtitleStatus.LOADING,SubtitleStatus.IDLE,SubtitleStatus.UNSUPPORTED))TextButton(retry){Text("重试字幕",color=ImmersivePink)}
                    }
                }
            }
            if(subtitle.status==SubtitleStatus.READY)Text(subtitle.message,color=white.copy(alpha=.6f),fontSize=10.sp,maxLines=2,modifier=Modifier.padding(vertical=4.dp))
            PlaybackSeek(s.positionMs,s.durationMs,s.canSeek,seek,true)
            SubtitleControls(s,toggle,previous,next,queue)
        }
    }
}

@Composable private fun SubtitleScroll(view: SubtitleView,timeline: SubtitleTimeline,position: Long,active: List<Int>,seekable: Boolean,seek:(Long)->Unit) {
    val list=rememberLazyListState()
    var following by rememberSaveable(view.video,view.selected?.id){mutableStateOf(true)}
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) {if(dragged)following=false}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val offset=with(LocalDensity.current){(maxHeight.toPx()/3).toInt()}
        // Follow the last started sentence through a pause, without extending its real interval.
        val focus=timeline.nearby(position)
        LaunchedEffect(focus,following,view.selected?.id) {if(following && view.cues.isNotEmpty())list.animateScrollToItem(focus,-offset)}
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,contentPadding=PaddingValues(vertical=12.dp)) {
                itemsIndexed(view.cues,key={index,_->index}) {index,cue ->
                    val current=index in active
                    val followed=index==focus && position>=cue.fromMs
                    val emphasized=current || followed
                    // Color changes must not resize wrapped text or move neighbouring rows.
                    Text(cue.content,Modifier.fillMaxWidth().clickable(enabled=seekable){seek(cue.fromMs)}.background(if(emphasized)Color.White.copy(alpha=.07f) else Color.Transparent,RoundedCornerShape(18.dp)).padding(vertical=16.dp,horizontal=14.dp).semantics{
                        if(current)contentDescription="当前字幕"
                        else if(followed)contentDescription="跟随字幕"
                    },color=if(emphasized)ImmersivePink else Color.White.copy(alpha=.68f),fontSize=22.sp,lineHeight=30.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center)
                }
            }
            if(!following)TextButton({following=true},Modifier.align(Alignment.CenterHorizontally)){Text("跟随当前字幕",color=ImmersivePink)}
        }
    }
}

@Composable internal fun SubtitleControls(s: ScreenState,toggle:()->Unit,previous:()->Unit,next:()->Unit,queue:()->Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceEvenly) {
        ActionIcon(Mark.PREVIOUS,"上一条",previous,color=Color.White,enabled=s.canPrevious)
        FilledIconButton(toggle,Modifier.size(56.dp).semantics{contentDescription=if(s.playRequested)"暂停" else "播放"},colors=IconButtonDefaults.filledIconButtonColors(containerColor=ListenPink,contentColor=Color.White)) {Glyph(if(s.playRequested)Mark.PAUSE else Mark.PLAY,Modifier.size(30.dp))}
        ActionIcon(Mark.NEXT,"下一条",next,color=Color.White,enabled=s.canNext)
        ActionIcon(Mark.QUEUE,"播放队列",queue,color=Color.White)
    }
}
