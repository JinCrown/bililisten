package app.bililisten

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.bililisten.shared.*
import java.time.Instant
import java.time.ZoneId

@Composable fun PlayerPage(s:ScreenState,video:Video?,entry:QueueEntry?,position:Long,duration:Long,modifier:Modifier,back:()->Unit,toggle:()->Unit,seek:(Long)->Unit,previous:()->Unit,next:()->Unit,queue:()->Unit,mode:()->Unit,favorite:()->Unit,subtitles:()->Unit,official:()->Unit,notice:(String)->Unit,listening:()->Unit,seekBy:(Long)->Unit,audio:()->Unit,lyrics:()->Unit={},download:()->Unit={},speed:()->Unit=listening,creator:()->Unit={},chooseFavorite:()->Unit=favorite,liveEdge:()->Unit={},restoreVod:()->Unit={}){
    val cover=if(s.isLive)s.liveExperience.cover else video?.cover.orEmpty()
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){
        Cover(cover,Modifier.fillMaxSize().blur(55.dp).alpha(.16f))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.background.copy(alpha=.35f),MaterialTheme.colorScheme.background.copy(alpha=.95f)),startY=100f)))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=PageSideInset)){
            Box(Modifier.fillMaxWidth().padding(top=4.dp,bottom=14.dp).semantics{contentDescription="播放封面"},contentAlignment=Alignment.Center){
                Cover(cover,Modifier.fillMaxWidth().aspectRatio(1.6f).shadow(16.dp,RoundedCornerShape(28.dp),ambientColor=ListenPink.copy(alpha=.12f),spotColor=ListenPink.copy(alpha=.12f)).clip(RoundedCornerShape(28.dp)).border(1.dp,MaterialTheme.colorScheme.surface.copy(alpha=.6f),RoundedCornerShape(28.dp)))
                if(entry?.offline==true||s.isLive)Box(Modifier.align(Alignment.BottomEnd).padding(12.dp)){StatusTag(if(s.isLive)"LIVE" else "离线音频",Mark.PLAY)}
            }
            PlayerIdentity(s,video,entry,creator)
            Spacer(Modifier.height(2.dp))
            if(s.isLive) {
                Text("LIVE · ${if(s.liveExperience.kind==StreamKind.AUDIO_ONLY)"独立音频" else "含视频数据的来源"}",Modifier.fillMaxWidth().padding(top=10.dp),textAlign=TextAlign.Center,color=ListenPink,fontSize=12.sp)
                if(s.liveExperience.notice.isNotBlank())Text(s.liveExperience.notice,Modifier.fillMaxWidth().padding(vertical=8.dp),fontSize=12.sp,textAlign=TextAlign.Center,color=muted())
                if(s.liveExperience.canSeekWindow) {
                    Text("可回退窗口",fontSize=11.sp,color=muted())
                    PlaybackSeek(position,s.liveExperience.windowMs,s.canSeek,seek)
                }
                TextButton(liveEdge,Modifier.align(Alignment.CenterHorizontally),enabled=!s.busy){Glyph(Mark.TV,Modifier.size(16.dp));Text("回到当前直播",Modifier.padding(start=6.dp))}
            }
            else PlaybackSeek(position,duration,s.canSeek,seek)
            s.playbackIssue?.let{Text(it.message,Modifier.padding(vertical=5.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
            // Keep this line measured when idle so buffering never moves the controls.
            Text(if(s.buffering)"正在缓冲…" else "",Modifier.fillMaxWidth(),textAlign=TextAlign.Center,
                fontSize=12.sp,lineHeight=18.sp,minLines=1,maxLines=1,overflow=TextOverflow.Ellipsis,color=muted())
            if(!s.isLive || s.liveExperience.canSeekWindow)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){
                TextButton({seekBy(-LongListening.SEEK_MS)},Modifier.weight(1f),enabled=s.canSeek){Text("后退 15 秒",fontSize=12.sp,textAlign=TextAlign.Center)}
                TextButton(speed,Modifier.weight(1f),enabled=!s.isLive){Text(if(s.isLive)"直播 · 1 倍速" else "${s.speed} 倍速",fontSize=12.sp,textAlign=TextAlign.Center)}
                TextButton({seekBy(LongListening.SEEK_MS)},Modifier.weight(1f),enabled=s.canSeek){Text("前进 15 秒",fontSize=12.sp,textAlign=TextAlign.Center)}
            }
            if(s.timerRemainingMs>0)Text("定时停止 · 剩余 ${timeLabel(s.timerRemainingMs)}",Modifier.fillMaxWidth(),textAlign=TextAlign.Center,color=ListenPink,fontSize=12.sp)
            else if(s.pauseReason==PauseReason.TIMER)Text("定时已结束，已暂停并保存位置",Modifier.fillMaxWidth(),textAlign=TextAlign.Center,color=ListenPink,fontSize=12.sp)
            PlaybackControls(s,toggle,previous,next,queue,mode)
            if(!s.isLive)Row(Modifier.fillMaxWidth().padding(vertical=18.dp),horizontalArrangement=Arrangement.SpaceEvenly){
                Box(Modifier.weight(1f),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){
                    FavoriteButton("播放器收藏",s.currentFavoriteBvid==entry?.bvid && s.currentFavoriteFolder==s.settings.defaultFavoriteFolders[s.account?.id?.toString()]?.id && s.currentFavoritePresent==true,entry!=null&&!s.isLive&&!s.busy,favorite,chooseFavorite)
                    Text("收藏",fontSize=10.sp,color=MaterialTheme.colorScheme.primary)
                }}
                Box(Modifier.weight(1f),contentAlignment=Alignment.Center){ControlLabel(Mark.DOWNLOAD,"下载音频",download,enabled=entry!=null&&!s.isLive&&!entry.offline)}
                Box(Modifier.weight(1f),contentAlignment=Alignment.Center){ControlLabel(Mark.SUBTITLE,"查看字幕",subtitles,enabled=!s.isLive)}
            }
            Surface(official,shape=RoundedCornerShape(18.dp),color=blush(),modifier=Modifier.fillMaxWidth(),enabled=entry!=null||s.isLive){Row(Modifier.padding(horizontal=15.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically){Glyph(Mark.TV,Modifier.size(29.dp),ListenPink);Text(if(s.isLive)"去 B站看直播" else "去 B 站看视频",Modifier.weight(1f).padding(start=12.dp),color=ListenPink,fontWeight=FontWeight.Bold,fontSize=17.sp);Glyph(Mark.CHEVRON,Modifier.size(18.dp),muted())}}
            Text(if(s.isLive)"返回后保持暂停" else "返回后保持暂停 · 分 P 与时间由 B 站处理",Modifier.fillMaxWidth().padding(top=5.dp),fontSize=10.sp,color=muted(),textAlign=TextAlign.Center)
            if(s.isLive && s.resume!=null)OutlinedButton(restoreVod,Modifier.fillMaxWidth().padding(top=12.dp),enabled=!s.busy){Text("返回保留的点播队列（暂停）")}
            val index=s.queue.indexOfFirst{it.id==s.currentId};val upcoming=s.queue.getOrNull(index+1)
            if(!s.isLive)Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth().padding(top=12.dp,bottom=18.dp)){
                Column(Modifier.padding(12.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text("下一个播放",Modifier.weight(1f),fontWeight=FontWeight.Bold);TextButton(queue){Text("查看全部",fontSize=11.sp)}}
                    if(upcoming==null)Text(if(s.isLive)"直播没有下一条" else "队列已经到底了",fontSize=12.sp,color=muted())
                    else{val info=s.metadata[upcoming.bvid];VideoRowCard(upcoming.title,info?.cover.orEmpty(),info?.author.orEmpty(),info?.duration ?: 0,onClick=next,onMore=queue)}
                }
            }
        }
        // Saving a handoff snapshot must not insert a row and shift the tapped page.
        if(s.busy)LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp).semanticsLabel("正在准备内容"),color=ListenPink)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlaybackSeek(position:Long,duration:Long,enabled:Boolean,seek:(Long)->Unit,light:Boolean=false){
    var drag by remember{mutableStateOf<Float?>(null)}
    val maximum=duration.coerceAtLeast(1).toFloat()
    val accent=if(light)ImmersivePink else MaterialTheme.colorScheme.primary
    val colors=SliderDefaults.colors(thumbColor=accent,activeTrackColor=accent,inactiveTrackColor=if(light)Color.White.copy(alpha=.25f) else MaterialTheme.colorScheme.surfaceVariant,disabledActiveTrackColor=accent.copy(alpha=.45f),disabledInactiveTrackColor=MaterialTheme.colorScheme.surfaceVariant)
    Column{Slider(value=if(duration>0)(drag ?: position.toFloat()).coerceIn(0f,maximum) else 0f,onValueChange={drag=it},onValueChangeFinished={drag?.let{seek(it.toLong())};drag=null},valueRange=0f..maximum,enabled=enabled,modifier=Modifier.fillMaxWidth().height(31.dp).semanticsLabel("播放进度"),colors=colors,
            thumb={Box(Modifier.width(13.dp).fillMaxHeight(),contentAlignment=Alignment.Center){
                Box(Modifier.size(13.dp).background(accent,CircleShape))
            }},track={state->SliderDefaults.Track(state,Modifier.height(4.dp),colors=colors,enabled=enabled,thumbTrackGapSize=0.dp,drawStopIndicator={})})
        Row(Modifier.fillMaxWidth()){Text(timeLabel((drag?.toLong() ?: position)),Modifier.weight(1f),fontSize=11.sp,color=if(light)Color.White else muted());Text(if(duration>0)timeLabel(duration) else "—:—",fontSize=11.sp,color=if(light)Color.White else muted())}}
}
@Composable private fun PlaybackControls(s:ScreenState,toggle:()->Unit,previous:()->Unit,next:()->Unit,queue:()->Unit,mode:()->Unit,light:Boolean=false){
    val color=if(light)Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    if(s.isLive) {
        Row(Modifier.fillMaxWidth().padding(vertical=20.dp),horizontalArrangement=Arrangement.Center) {
            FilledIconButton(toggle,Modifier.size(72.dp).semanticsLabel(if(s.playRequested)"暂停直播" else "继续直播"),
                colors=IconButtonDefaults.filledIconButtonColors(containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary)) {
                Glyph(if(s.playRequested)Mark.PAUSE else Mark.PLAY,Modifier.size(35.dp))
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween){
        Box(Modifier.weight(1f),contentAlignment=Alignment.Center){ControlLabel(if(light)Mark.BACK else if(s.mode==PlayMode.SHUFFLE)Mark.SHUFFLE else Mark.REPEAT,if(light)"返回播放页" else when(s.mode){PlayMode.SEQUENTIAL->"顺序播放";PlayMode.REPEAT_ALL->"列表循环";PlayMode.REPEAT_ONE->"单条循环";PlayMode.SHUFFLE->"随机播放"},mode,enabled=!s.isLive,color=if(light)Color.White else MaterialTheme.colorScheme.primary)}
        Box(Modifier.weight(1f),contentAlignment=Alignment.Center){ActionIcon(Mark.PREVIOUS,"上一条",previous,Modifier.size(48.dp),color,s.canPrevious)}
        Box(Modifier.weight(1.4f),contentAlignment=Alignment.Center){FilledIconButton(toggle,Modifier.size(72.dp).shadow(12.dp,CircleShape,ambientColor=ListenPink.copy(alpha=.2f),spotColor=ListenPink.copy(alpha=.2f)).semanticsLabel(if(s.playRequested)"暂停" else "播放"),colors=IconButtonDefaults.filledIconButtonColors(containerColor=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary)){Glyph(if(s.playRequested)Mark.PAUSE else Mark.PLAY,Modifier.size(35.dp))}}
        Box(Modifier.weight(1f),contentAlignment=Alignment.Center){ActionIcon(Mark.NEXT,"下一条",next,Modifier.size(48.dp),color,s.canNext)}
        Box(Modifier.weight(1f),contentAlignment=Alignment.Center){ControlLabel(Mark.QUEUE,"播放队列",queue,enabled=!s.isLive,color=color)}
    }
}
@Composable private fun ControlLabel(mark:Mark,label:String,onClick:()->Unit,enabled:Boolean=true,color:Color=MaterialTheme.colorScheme.onSurfaceVariant){Column(Modifier.widthIn(min=48.dp,max=75.dp).clickable(enabled=enabled,onClick=onClick).padding(vertical=5.dp).semanticsLabel(label),horizontalAlignment=Alignment.CenterHorizontally){Box(Modifier.size(44.dp).background(if(lightBackground(color))Color.White.copy(alpha=.08f) else MaterialTheme.colorScheme.surface.copy(alpha=.8f),CircleShape),contentAlignment=Alignment.Center){Glyph(mark,Modifier.size(23.dp),if(enabled)color else color.copy(alpha=.3f))};Text(label,Modifier.padding(top=5.dp),fontSize=10.sp,color=if(enabled)color else color.copy(alpha=.3f),textAlign=TextAlign.Center)}}
private fun Modifier.semanticsLabel(label:String)=this.then(Modifier.semantics{contentDescription=label})


private fun lightBackground(color:Color)=color==Color.White
