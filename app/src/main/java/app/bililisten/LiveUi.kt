package app.bililisten

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.bililisten.shared.*

@Composable internal fun HomeLiveRanking(s:ScreenState,open:(Long)->Unit,retry:()->Unit) {
    Column(Modifier.fillMaxWidth()) {
        if(s.liveRankings.isEmpty()) {
            EmptyState(if(s.liveRankingBusy)"正在读取直播排行" else "直播排行暂不可用",
                s.liveRankingError ?: "下拉刷新，或直接打开直播房间","刷新直播排行",retry)
        } else {
            RecommendationCarousel(s.liveRankings.mapIndexed { index,row->HeroSlide("live:${row.roomId}",row.title,row.cover,
                "直播 · 第 ${index+1} 名",listOfNotNull(row.anchor.takeIf{it.isNotBlank()},row.area.takeIf{it.isNotBlank()},row.heat?.let{"热度 ${countLabel(it)}"}).joinToString(" · "),
                "直播第 ${index+1} 名：${row.title}，${row.anchor}") },HomeCategory.LIVE.sourceLabel,"查看",Mark.TV){index->open(s.liveRankings[index].roomId)}
            s.liveRankingError?.let{Text(it,Modifier.padding(vertical=8.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
        }
    }
}

@Composable internal fun LiveRoomContent(s:ScreenState,input:String,change:(String)->Unit,inspect:()->Unit,
    play:()->Unit,official:(Long)->Unit,bookmark:()->Unit,open:(Long)->Unit) {
    val owner=s.account?.id?.toString() ?: "guest"
    val saved=s.settings.localLiveRooms[owner].orEmpty()
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input,change,Modifier.weight(1f),label={Text("直播链接 / 房间号")},singleLine=true)
            FilledIconButton(inspect,Modifier.size(48.dp),enabled=!s.busy&&input.isNotBlank()) {
                Glyph(Mark.SEARCH,Modifier.size(22.dp).semantics{contentDescription="读取直播房间"})
            }
        }
        s.liveRoom?.let { room ->
            if(room.cover.isNotBlank())Cover(room.cover,Modifier.fillMaxWidth().aspectRatio(1.78f).clip(RoundedCornerShape(8.dp)))
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(room.title.ifBlank{"直播房间 ${room.roomId}"},fontSize=21.sp,lineHeight=29.sp,fontWeight=FontWeight.Bold)
                    Text("${room.anchor} · 房间 ${room.roomId}",Modifier.padding(top=5.dp),fontSize=13.sp,color=muted())
                }
                ActionIcon(Mark.STAR,if(saved.any{it.roomId==room.roomId})"移出本机常听" else "加入本机常听",bookmark,
                    color=if(saved.any{it.roomId==room.roomId})MaterialTheme.colorScheme.primary else muted(),enabled=!s.busy)
            }
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if(room.state==LiveRoomStatus.LIVE)Color(0xFF00A67D) else muted(),RoundedCornerShape(4.dp)))
                Text(room.state.label,fontWeight=FontWeight.SemiBold,fontSize=13.sp)
                Text(room.area,fontSize=12.sp,color=muted())
                Spacer(Modifier.weight(1f))
                ActionIcon(Mark.REFRESH,"重新检查房间",inspect,enabled=!s.busy)
            }
            if(room.description.isNotBlank())Text(room.description,fontSize=12.sp,color=muted(),maxLines=4,overflow=TextOverflow.Ellipsis)
            val audio=s.liveStreams.any{it.kind==StreamKind.AUDIO_ONLY}
            if(room.state==LiveRoomStatus.LIVE)Text(if(audio)"可使用独立音频来源" else if(s.liveStreams.isNotEmpty())"当前来源包含视频数据" else "未取得可用直播来源",fontSize=12.sp,color=muted())
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Button(play,Modifier.weight(1f),enabled=room.state==LiveRoomStatus.LIVE && s.liveStreams.isNotEmpty() && !s.busy) {
                    Glyph(Mark.PLAY,Modifier.size(18.dp));Text("收听直播",Modifier.padding(start=6.dp))
                }
                OutlinedButton({official(room.roomId)},Modifier.weight(1f),enabled=!s.busy) {
                    Glyph(Mark.TV,Modifier.size(18.dp));Text("B站查看",Modifier.padding(start=6.dp))
                }
            }
        }
        if(s.liveRoom==null)LiveInput.room(input)?.let{room->OutlinedButton({official(room)},Modifier.fillMaxWidth(),enabled=!s.busy){Glyph(Mark.TV,Modifier.size(18.dp));Text("B站查看此房间",Modifier.padding(start=6.dp))}}
        if(saved.isNotEmpty()) {
            Text("本机常听",Modifier.padding(top=10.dp).semantics{heading()},fontSize=18.sp,fontWeight=FontWeight.Bold)
            saved.forEach{row->LiveSavedRow(row.title,row.anchor,row.cover,row.roomId){open(row.roomId)}}
        }
        val history=s.liveHistory.distinctBy{it.roomId}
        if(history.isNotEmpty()) {
            Text("最近收听的直播",Modifier.padding(top=10.dp).semantics{heading()},fontSize=18.sp,fontWeight=FontWeight.Bold)
            history.take(10).forEach{row->LiveSavedRow(row.title,"房间 ${row.roomId}","",row.roomId){open(row.roomId)}}
        }
    }
}

@Composable private fun LiveSavedRow(title:String,subtitle:String,cover:String,room:Long,open:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=open).padding(vertical=6.dp).semantics{contentDescription="查看直播房间 $room"},verticalAlignment=Alignment.CenterVertically) {
        if(cover.isNotBlank())Cover(cover,Modifier.size(68.dp,44.dp).clip(RoundedCornerShape(8.dp)))
        else Glyph(Mark.TV,Modifier.size(44.dp),muted())
        Column(Modifier.weight(1f).padding(horizontal=10.dp)) {
            Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(subtitle,fontSize=11.sp,color=muted(),maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        Glyph(Mark.CHEVRON,Modifier.size(16.dp),muted())
    }
}
