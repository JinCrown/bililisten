package app.bililisten

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.bililisten.platform.TransferPeer

@Composable internal fun TransferPage(vm:TransferViewModel,done:()->Unit){
    val s by vm.state.collectAsStateWithLifecycle();val sending by vm.wifi.sending.collectAsStateWithLifecycle();val peers by vm.wifi.peers.collectAsStateWithLifecycle()
    var code by rememberSaveableText();var manual by rememberSaveableText();var selected by remember{mutableStateOf<TransferPeer?>(null)}
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){it?.let(vm::export)}
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(vm::read)}
    DisposableEffect(Unit){onDispose{vm.leave()}}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)){
        PageIntro("换手机，记录跟着走","两台手机登录同一 B 站账号，连接同一 Wi-Fi",Mark.SHARE)
        InfoCard("迁移来源入口、收藏管理中的隐藏与显示顺序、视频与直播历史、已听标记和本地别名。换手机后导入，即可保留只想听的收藏。")
        PanelCard {
            Text("Wi-Fi 迁移",fontSize=19.sp,fontWeight=FontWeight.Bold)
            if(LocalDensity.current.fontScale>1.2f){
                Button(vm::startSending,Modifier.fillMaxWidth(),enabled=!s.busy){Text("发送数据")}
                OutlinedButton(vm::discover,Modifier.fillMaxWidth(),enabled=!s.busy){Text("接收数据")}
            }else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Button(vm::startSending,Modifier.weight(1f),enabled=!s.busy){Text("发送数据")}
                OutlinedButton(vm::discover,Modifier.weight(1f),enabled=!s.busy){Text("接收数据")}
            }
            if(sending.active){
                Text("在新手机输入配对码",fontSize=12.sp,color=muted())
                Text(sending.code.chunked(4).joinToString(" "),fontWeight=FontWeight.Bold,fontSize=23.sp,color=MaterialTheme.colorScheme.primary)
                Text("手动连接地址：${sending.address}",fontSize=12.sp,color=muted())
                TextButton({vm.wifi.stopSending()}){Text("停止发送")}
            }
            if(sending.message.isNotBlank())Text(sending.message,fontSize=12.sp,color=muted())
            if(s.receiveMode){
                Text("附近的旧手机",fontWeight=FontWeight.SemiBold)
                if(peers.isEmpty())Text("正在寻找…请在旧手机点发送数据。未找到时可手动连接。",fontSize=12.sp,color=muted())
                peers.forEach{peer->OutlinedButton({selected=peer;manual=""},enabled=!s.busy){Text((if(selected==peer)"✓ " else "")+peer.name)}}
                OutlinedTextField(manual,{manual=it;selected=null},Modifier.fillMaxWidth(),label={Text("手动连接地址（可选）")},placeholder={Text("192.168.1.2:12345")},singleLine=true)
                OutlinedTextField(code,{code=it},Modifier.fillMaxWidth(),label={Text("旧手机显示的配对码")},singleLine=true)
                Button({
                    val peer=selected ?: TransferPeer("手动连接",manual.substringBefore(':').trim(),manual.substringAfter(':',"").toIntOrNull() ?: 0)
                    vm.receive(peer,code)
                },enabled=!s.busy&&code.isNotBlank()&&(selected!=null||manual.isNotBlank())){Text("接收并预览")}
            }
        }
        if(s.preview!=null){val p=s.preview!!;PanelCard(accent=true){
            Text("导入预览",fontSize=19.sp,fontWeight=FontWeight.Bold)
            Text("视频历史 ${p.history.size} 条 · 直播历史 ${p.liveHistory.size} 条\n来源入口 ${p.bookmarks.size} 个 · 已听来源 ${p.heard.size} 个\n本地别名 ${p.aliases.size} 个 · 视频排序 ${p.orders.size} 组\n收藏管理 ${p.library.layouts.size} 类 · 隐藏 ${p.library.layouts.sumOf{it.hidden.size}} 个来源",fontSize=13.sp)
            Text("合并只改本机记录，保留已有别名与视频排序；收藏隐藏和顺序按每类较新的设置保留。不改变 B 站远端收藏，不替换当前播放队列。",fontSize=12.sp,color=muted())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button({vm.merge(done)},enabled=!s.busy){Text("确认合并")};TextButton(vm::cancelPreview,enabled=!s.busy){Text("取消导入")}}
        }}
        if(s.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(s.message.isNotBlank())InfoCard(s.message)
        PanelCard {
            Text("文件迁移（备用）",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
            Text("导出文件后，将文件带到新手机再导入。文件包含个人收听记录，请自行保管。",fontSize=12.sp,color=muted())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton({vm.prepareExport();create.launch("哔哩听视频-迁移-${java.time.LocalDate.now()}.json")},enabled=!s.busy){Text("导出文件")}
                TextButton({open.launch(arrayOf("application/json","application/octet-stream"))},enabled=!s.busy){Text("导入文件")}
            }
        }
        Text("不迁移登录凭据、待核对的远端操作、设置、当前队列、下载音频或缓存。B 站自己的收藏和追更关系登录后从平台读取。离开此页会停止 Wi-Fi 发送和发现。",fontSize=12.sp,color=muted())
    }
}
@Composable private fun rememberSaveableText()=androidx.compose.runtime.saveable.rememberSaveable{mutableStateOf("")}
