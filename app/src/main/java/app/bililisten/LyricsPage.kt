package app.bililisten

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.*
import app.bililisten.shared.*

@Composable fun LyricsPage(s:ScreenState,video:Video?,modifier:Modifier,back:()->Unit,toggle:()->Unit,seek:(Long)->Unit,
    previous:()->Unit,next:()->Unit,queue:()->Unit,search:(String,String)->Unit,select:(Long)->Unit,offset:(Long)->Unit,confirm:()->Unit) {
    val view=s.lyrics
    var name by rememberSaveable(view.video){mutableStateOf("")}
    var artist by rememberSaveable(view.video){mutableStateOf("")}
    var edit by rememberSaveable(view.video){mutableStateOf(!view.confirmed)}
    val focus=LocalFocusManager.current
    val document=view.document
    val cues=document?.cues.orEmpty()
    val timeline=remember(cues){SubtitleTimeline(cues)}
    val active=if(view.confirmed)timeline.active(s.positionMs-view.offsetMs) else emptyList()
    val list=rememberLazyListState()
    var follow by rememberSaveable(view.video,document?.candidate?.id){mutableStateOf(true)}
    val dragged by list.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged){if(dragged)follow=false}
    LaunchedEffect(active.firstOrNull(),follow,view.confirmed,edit){if(follow && view.confirmed && active.isNotEmpty())list.animateScrollToItem(active.first()+(if(edit)3 else 2))}
    Column(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF42344F),Color(0xFF1C1A29)))).padding(horizontal=PageSideInset)) {
        Row(Modifier.fillMaxWidth().heightIn(min=60.dp),verticalAlignment=Alignment.CenterVertically){
            ActionIcon(Mark.BACK,"返回播放页",back,color=Color.White)
            Text("歌词",Modifier.weight(1f),color=Color.White,fontSize=22.sp,fontWeight=FontWeight.Bold)
            TextButton({edit=!edit}){Text(if(edit)"收起搜索" else "重新选版本",color=Color.White,fontSize=11.sp)}
        }
        Text(video?.title ?: s.playingTitle,color=Color.White,fontSize=13.sp,maxLines=2)
        Text(if(MusicContent.songCandidate(video))"音乐分区提示：歌曲候选 · 尚不能确定具体歌曲版本" else "可手动匹配歌曲；普通视频或纯音乐可能没有歌词",color=Color.White.copy(alpha=.65f),fontSize=10.sp)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=list,contentPadding=PaddingValues(vertical=8.dp)) {
            if(edit) item {
                PanelCard {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("按歌名和演唱版本查找",fontWeight=FontWeight.Bold,fontSize=13.sp)
                    OutlinedTextField(name,{name=it.take(200)},label={Text("准确歌名（含现场 / 翻唱版本关键词）")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(artist,{artist=it.take(200)},label={Text("歌手，可留空；视频作者不等于歌手")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    Text("点击查询后仅把填写的歌名、歌手发送至 LRCLIB。请核对来源和版本，歌词不会自动替代字幕。",fontSize=10.sp,color=muted(),modifier=Modifier.padding(top=6.dp))
                    Button({focus.clearFocus();follow=false;search(name,artist)},enabled=view.video!=null&&name.isNotBlank()&&view.status !in setOf(LyricsStatus.SEARCHING,LyricsStatus.LOADING),modifier=Modifier.fillMaxWidth()){Text("查询歌词候选")}
                }}
            }
            item {
                Text(view.message,color=Color.White.copy(alpha=.8f),fontSize=12.sp,modifier=Modifier.padding(vertical=9.dp))
                if(view.status in setOf(LyricsStatus.SEARCHING,LyricsStatus.LOADING))LinearProgressIndicator(Modifier.fillMaxWidth(),color=ListenPink)
            }
            if(document==null)items(view.candidates,key={it.id}) {candidate ->
                Surface({select(candidate.id)},Modifier.fillMaxWidth().padding(vertical=4.dp),shape=RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(12.dp)){Text(candidate.name,fontWeight=FontWeight.Bold);Text("${candidate.artist} · ${candidate.album.ifBlank{"专辑未提供"}}",fontSize=12.sp);Text("${timeLabel(candidate.durationMs)} · 来源 ${candidate.source}${if(candidate.instrumental)" · 纯音乐" else ""}",fontSize=11.sp,color=muted())}
                }
            }
            if(document!=null) {
                item {
                    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=Color.White.copy(alpha=.07f),contentColor=Color.White){Column(Modifier.padding(16.dp)) {
                        Text("${document.candidate.name} · ${document.candidate.artist}",fontWeight=FontWeight.Bold,fontSize=13.sp)
                        Text("${document.candidate.album.ifBlank{"专辑未提供"}} · ${timeLabel(document.candidate.durationMs)} · ${document.candidate.source} #${document.candidate.id}",fontSize=11.sp,color=Color.White.copy(alpha=.65f))
                        if(cues.isNotEmpty()) {
                            Text("歌词延后 ${view.offsetMs / 1000.0} 秒（负值为提前）",fontSize=12.sp)
                            Row(Modifier.horizontalScroll(rememberScrollState())) {TextButton({offset(view.offsetMs-500)}){Text("提前 0.5 秒",color=Color(0xFFFF94BD))};TextButton({offset(view.offsetMs+500)}){Text("延后 0.5 秒",color=Color(0xFFFF94BD))};TextButton({offset(0)}){Text("归零",color=Color(0xFFFF94BD))}}
                            if(!view.confirmed)Button({focus.clearFocus();edit=false;follow=true;confirm()}){Text("版本与时间已核对，启用同步")}
                            else TextButton({follow=true}){Text("跟随当前歌词",color=Color(0xFFFF94BD))}
                        }else Text("静态歌词 · 来源没有可用逐行时间轴",fontSize=12.sp,color=Color.White.copy(alpha=.65f))
                    }}
                }
                if(cues.isEmpty())item {Text(document.plain,Modifier.padding(vertical=16.dp),color=Color.White,fontSize=17.sp)}
                else itemsIndexed(cues,key={index,_->index}) {index,cue ->
                    val words=document.words[index].orEmpty()
                    val text=lyricLineText(cue,words,s.positionMs-view.offsetMs,view.confirmed,index in active,view.offsetMs)
                    Text(text,Modifier.fillMaxWidth().clickable(enabled=view.confirmed&&s.canSeek){seek((cue.fromMs+view.offsetMs).coerceIn(0,s.durationMs.coerceAtLeast(0)))}.padding(vertical=12.dp,horizontal=8.dp),
                        color=if(index in active)ImmersivePink else Color.White.copy(alpha=.7f),fontSize=if(index in active)23.sp else 17.sp,fontWeight=if(index in active)FontWeight.Bold else FontWeight.Normal,textAlign=TextAlign.Center)
                }
            }
        }
        PlaybackSeek(s.positionMs,s.durationMs,s.canSeek,seek,true)
        SubtitleControls(s,toggle,previous,next,queue)
    }
}

internal fun lyricLineText(cue:SubtitleCue,words:List<LyricWord>,positionMs:Long,confirmed:Boolean,active:Boolean,offsetMs:Long=0):AnnotatedString=buildAnnotatedString {
    if(!confirmed)append("${timeLabel((cue.fromMs+offsetMs).coerceAtLeast(0))}  ")
    if(confirmed && active && words.isNotEmpty()) {
        words.forEach {word->withStyle(SpanStyle(color=if(positionMs>=word.fromMs)ImmersivePink else Color.White.copy(alpha=.7f))){append(word.text)}}
    } else append(cue.content)
}
