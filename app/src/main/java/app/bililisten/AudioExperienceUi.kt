package app.bililisten

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bililisten.shared.*

@Composable fun AudioExperiencePanel(s:ScreenState,refresh:()->Unit,choose:(AudioChoice)->Unit,official:()->Unit,output:(Int?)->Unit,externalOnly:(Boolean)->Unit) {
    val audio=s.audio
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        PanelHeading("音质与音频输出","好声音，从合适的音轨开始",Mark.QUEUE)
        Text(if(s.isLive)"直播音源由房间取流决定，点播音质偏好不应用于直播" else "默认自动选择当前身份可获取、设备可解码的最高音质",fontSize=13.sp,color=muted())
        PanelCard(accent=true){Text((if(audio.selected!=null&&!audio.decoded)"待播放音轨：" else "当前音质：")+(audio.options.firstOrNull{it.choice==audio.selected}?.label ?: if(s.isLive)"直播流" else "尚未确认"),fontWeight=FontWeight.Medium)
        Text("音质偏好："+AudioExperienceRules.preference(s.settings.audioChoice),fontSize=12.sp,color=muted())
        if(audio.decodedLabel.isNotBlank())Text("${if(audio.decoded)"已解码" else "解码格式"}：${audio.decodedLabel}",fontSize=12.sp,color=muted())
        if(audio.checking || s.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        audio.restriction?.let{Text(it.message,fontSize=13.sp,color=MaterialTheme.colorScheme.error)}
        if(audio.notice.isNotBlank())Text(audio.notice,fontSize=12.sp,color=muted())
        }
        if(!s.isLive) {
            PanelCard{Text("可用音轨",fontSize=16.sp,fontWeight=FontWeight.Bold)
            TextButton(refresh,enabled=!s.busy&&!audio.checking&&s.queue.isNotEmpty()){Text("重新检查实际音轨")}
            OutlinedButton({choose(AudioChoice())},enabled=!s.busy&&!audio.checking&&s.queue.isNotEmpty(),modifier=Modifier.fillMaxWidth()){
                Text("自动最高可用${if(s.settings.audioChoice.id==null)" · 已选择" else ""}")
            }
            audio.options.forEach { option ->
                OutlinedButton({choose(option.choice)},enabled=option.supported&&!s.busy&&!audio.checking,modifier=Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(option.label+if(option.choice==audio.selected)" · 当前音轨" else "")
                        Text(listOfNotNull(option.sampleRate?.let{"$it Hz"},option.channels?.let{"$it 声道"},if(!option.supported)"设备解码不支持" else if(option.choice==s.settings.audioChoice)"已保存为偏好" else null).joinToString(" · "),fontSize=11.sp)
                    }
                }
            }
            if(audio.options.isEmpty())Text("当前没有已确认的音轨选项；先选择内容，再检查。未返回无损或杜比不等于缺少会员权益。",fontSize=12.sp,color=muted())
            Text("Hi-Res 需同时确认无损编码、采样率和位深；杜比与杜比全景声按源格式分别标注。设备输出不保证空间效果。",fontSize=12.sp,color=muted())
            TextButton(official,enabled=!s.busy){Text("去 B 站确认账户权益")}
            Text("第三方权益以 B 站为准，返回后重新读取；本应用功能免费。",fontSize=11.sp,color=muted())
            }
        }
        PanelCard{Text("音频输出",fontSize=18.sp,fontWeight=FontWeight.Bold)
        Text("实际输出："+s.output.routedName.ifBlank{"尚未报告"})
        s.output.sinkSampleRate?.let{Text("播放器输出采样率：$it Hz（系统可能再次重采样）",fontSize=12.sp,color=muted())}
        Text(s.output.notice,fontSize=12.sp,color=muted())
        TextButton({output(null)},enabled=!s.busy){Text("跟随系统输出${if(s.settings.preferredOutputType==null)" · 已选择" else ""}")}
        s.output.devices.forEach { device ->
            OutlinedButton({output(device.id)},enabled=!s.busy,modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(device.name+if(device.id==s.output.preferredId)" · 优先输出" else "")
                    Text("${if(device.sampleRates.isEmpty())"采样率由系统协商" else "设备报告采样率："+device.sampleRates.joinToString()+" Hz"}；${if(device.channelCounts.isEmpty())"声道由系统协商" else "声道："+device.channelCounts.joinToString()}",fontSize=11.sp)
                }
            }
        }
        SettingToggle("仅使用外部音频输出","未连接耳机、蓝牙或其他外部音频时保持暂停；接回后需手动播放",s.settings.externalOutputOnly,change=externalOnly)
        Text("输出选择保存为设备类型偏好，由系统最终决定路由；切换输出或断开耳机后暂停，避免误外放。",fontSize=12.sp,color=muted())
        }
    }
}
