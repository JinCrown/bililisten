package app.bililisten

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import app.bililisten.shared.*

/** One navigation bar surrounds three views; each view owns its content hierarchy. */
@Composable internal fun PlayerWorkspace(s:ScreenState,video:Video?,entry:QueueEntry?,page:String,changePage:(String)->Unit,
    modifier:Modifier=Modifier,back:()->Unit,creator:()->Unit,timer:()->Unit,lyrics:()->Unit,audio:()->Unit,
    player:@Composable ()->Unit,subtitles:@Composable ()->Unit,details:@Composable ()->Unit,chrome:(Float)->Unit={}) {
    var fraction by remember {mutableFloatStateOf(if(page=="subtitles")1f else 0f)}
    val background=lerp(MaterialTheme.colorScheme.background,Color(0xFF42344F),fraction)
    val ink=lerp(MaterialTheme.colorScheme.onSurface,Color.White,fraction)
    val soft=lerp(muted(),Color.White.copy(alpha=.65f),fraction)
    val accent=lerp(MaterialTheme.colorScheme.primary,ImmersivePink,fraction)
    var more by remember {mutableStateOf(false)}
    Column(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(background).padding(horizontal=PageSideInset)) {
            Row(Modifier.fillMaxWidth().heightIn(min=52.dp),verticalAlignment=Alignment.CenterVertically) {
                ActionIcon(Mark.BACK,"返回",back,color=ink)
                Row(Modifier.weight(1f),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                    listOf("videoDetails" to "详情","player" to if(s.isLive)"直播" else "播放","subtitles" to "字幕").forEach{(target,label)->
                        val chosen=page==target
                        Column(Modifier.weight(1f).clickable(enabled=!s.isLive||target=="player",interactionSource=remember{MutableInteractionSource()},indication=null,role=Role.Tab){changePage(target)}
                            .semantics{contentDescription=when(target){"videoDetails"->"切换到视频详情";"subtitles"->"切换到字幕";else->"切换到播放"};selected=chosen}
                            .padding(top=12.dp,bottom=7.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(label,fontSize=if(chosen)16.sp else 14.sp,fontWeight=if(chosen)FontWeight.SemiBold else FontWeight.Medium,color=if(chosen)ink else if(s.isLive&&target!="player")soft.copy(alpha=.35f) else soft)
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.width(14.dp).height(3.dp).background(if(chosen)accent else Color.Transparent,CircleShape))
                        }
                    }
                }
                Box {
                    ActionIcon(Mark.MORE,"播放更多操作",{more=true},color=ink)
                    DropdownMenu(more,{more=false}) {
                        DropdownMenuItem(text={Text("定时")},leadingIcon={Glyph(Mark.CLOCK)},onClick={more=false;timer()})
                        DropdownMenuItem(text={Text("歌词")},leadingIcon={Glyph(Mark.SUBTITLE)},enabled=!s.isLive,onClick={more=false;lyrics()})
                        DropdownMenuItem(text={Text("音质")},leadingIcon={Glyph(Mark.QUEUE)},enabled=!s.isLive,onClick={more=false;audio()})
                    }
                }
            }

        }
        PlayerPages(page,changePage,s.isLive,Modifier.weight(1f),player,subtitles,details,chrome={fraction=it;chrome(it)})
    }
}

/** Playing title sits below its cover; details retain the full video title and author. */
@Composable internal fun PlayerIdentity(s:ScreenState,video:Video?,entry:QueueEntry?,creator:()->Unit,modifier:Modifier=Modifier,details:Boolean=false) {
    val ink=MaterialTheme.colorScheme.onSurface
    val soft=muted()
    val accent=MaterialTheme.colorScheme.primary
    val primary=when {
        s.isLive->s.liveExperience.title.takeIf{it.isNotBlank()} ?: s.playingTitle
        !details->playbackEntryTitle(video,entry,s.playingTitle)
        else->video?.title ?: entry?.title ?: s.playingTitle
    }
    val source=video?.title?.takeIf{!s.isLive&&it!=primary}
    Column(modifier.fillMaxWidth().padding(horizontal=8.dp).padding(top=3.dp,bottom=12.dp),horizontalAlignment=if(details)Alignment.Start else Alignment.CenterHorizontally) {
                Text(primary,Modifier.fillMaxWidth().semantics{contentDescription=if(details)"视频详情标题" else "当前播放标题"},fontSize=20.sp,lineHeight=28.sp,textAlign=if(details)TextAlign.Start else TextAlign.Center,fontWeight=FontWeight.Bold,color=ink,maxLines=if(details)4 else 2,overflow=TextOverflow.Ellipsis)
                source?.let{Text(it,Modifier.fillMaxWidth().padding(top=3.dp).semantics{contentDescription="所属视频标题"},fontSize=12.sp,lineHeight=17.sp,textAlign=TextAlign.Center,color=soft,maxLines=1,overflow=TextOverflow.Ellipsis)}
                Row(Modifier.fillMaxWidth().padding(top=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=if(details)Arrangement.Start else Arrangement.Center) {
                    Row(Modifier.weight(1f,fill=false).clickable(enabled=!s.isLive&&(video?.owner ?: 0)>0,onClick=creator)
                        .semantics{if(!s.isLive&&(video?.owner ?: 0)>0)contentDescription="查看 UP 主投稿"},verticalAlignment=Alignment.CenterVertically) {
                        Cover(if(s.isLive)"" else video?.avatar.orEmpty(),Modifier.size(22.dp).clip(CircleShape))
                        Text(if(s.isLive)s.liveExperience.anchor else video?.author?.ifBlank{"视频作者"} ?: "视频作者",Modifier.weight(1f,fill=false).padding(horizontal=7.dp),fontSize=12.sp,color=soft,maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(!s.isLive&&(video?.owner ?: 0)>0)Glyph(Mark.CHEVRON,Modifier.size(12.dp),soft)
                    }
                    if(entry!=null&&!s.isLive)Text("P${entry.part}",Modifier.padding(start=9.dp),fontSize=11.sp,color=accent)
                    if(entry?.offline==true)Text("离线",Modifier.padding(start=9.dp),fontSize=11.sp,color=soft)
                }
            }
}
