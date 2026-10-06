package app.bililisten

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import app.bililisten.shared.*
import kotlinx.coroutines.*

@Composable fun ShareSheet(input:String,app:ListenApplication,vm:MainViewModel,s:ScreenState,openLive:(Long)->Unit={}) {
    var video by remember(input,s.account?.id){mutableStateOf<Video?>(null)}
    var room by remember(input,s.account?.id){mutableStateOf<LiveRoom?>(null)}
    var part by remember(input){mutableIntStateOf(1)}
    var error by remember(input){mutableStateOf<String?>(null)}
    var busy by remember(input){mutableStateOf(true)}
    var retry by remember(input){mutableIntStateOf(0)}
    LaunchedEffect(input,s.account?.id,s.accountChecked,retry){
        if(!s.accountChecked)return@LaunchedEffect
        busy=true;error=null;video=null;room=null
        val stamp=app.accounts.session.value.stamp
        try {
            val target=withContext(Dispatchers.IO){app.api.resolveSharedInput(input)}
            when(target){is SharedTarget.Video->{val result=withContext(Dispatchers.IO){app.api.video(target.bvid)};app.accounts.requireCurrent(stamp);video=result;part=target.part.takeIf{p->result.parts.any{it.number==p}} ?: 1}
                is SharedTarget.Live->{val result=withContext(Dispatchers.IO){app.api.liveRoom(target.room)};app.accounts.requireCurrent(stamp);room=result}}
        }catch(e:CancellationException){throw e}catch(e:Exception){error=(e as? PlatformFailure)?.category ?: "分享读取失败，请重试"}finally{busy=false}
    }
    AlertDialog(onDismissRequest=vm::dismissShare,title={Text("分享转入")},text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState())){
        InfoCard("请选择如何处理；收到分享不会自动播放或收藏。",Mark.SHARE)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let{Text(it,color=MaterialTheme.colorScheme.error);TextButton({retry++}){Text("重试读取")}}
        video?.let{v->Spacer(Modifier.height(12.dp));Cover(v.cover,Modifier.fillMaxWidth().aspectRatio(1.78f).clip(RoundedCornerShape(18.dp)));Spacer(Modifier.height(12.dp));Text(v.title,style=MaterialTheme.typography.titleMedium)
            v.parts.forEach{p->Row(verticalAlignment=Alignment.CenterVertically){RadioButton(part==p.number,{part=p.number});Text("P${p.number} · ${p.title}",Modifier.weight(1f))}}
            val selected=v.parts.firstOrNull{it.number==part}
            if(selected!=null){
                Button({vm.sharedVideo(v,selected,0)},modifier=Modifier.fillMaxWidth(),enabled=!s.busy&&s.accountChecked){Text("立即播放")}
                TextButton({vm.sharedVideo(v,selected,1)},enabled=!s.busy&&s.accountChecked&&!s.isLive){Text("下一条播放")}
                TextButton({vm.sharedVideo(v,selected,2)},enabled=!s.busy&&s.accountChecked&&!s.isLive){Text("加入队列")}
                if(s.isLive)Text("正在直播；请先返回点播队列后再加入。立即播放会切换到该视频。")
            }
        }
        room?.let{r->Text(r.title);Text("${r.anchor} · ${r.state.label}")
            Button({openLive(r.roomId)},enabled=!s.busy&&s.accountChecked){Text("查看直播房间")}
        }
    }},confirmButton={TextButton(vm::dismissShare){Text("取消")}})
}
