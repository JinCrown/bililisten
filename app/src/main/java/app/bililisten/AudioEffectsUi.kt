package app.bililisten

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import app.bililisten.shared.*
import kotlin.math.roundToInt

@Composable fun AudioEffectsPanel(s:ScreenState,choose:(EffectsSettings)->Unit,retry:()->Unit) {
    val pref=s.settings.effects;val eq=s.effects.equalizer;val bass=s.effects.bass
    var presets by remember{mutableStateOf(false)}
    val editable=s.connected&&!s.busy
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        PanelHeading("均衡器与音效","为你的声音，调出喜欢的质感",Mark.QUEUE)
        Text("默认关闭；主动开启后使用本机音效。打开面板、调节或重试均不会自动播放。",fontSize=12.sp,color=muted())
        PanelCard(accent=true){EffectSwitch("主动开启音效","音效总开关",pref.enabled,editable){choose(pref.copy(enabled=it))}
        Text("实际输出：${s.effects.routeName.ifBlank{"尚未报告；实际播放后由系统确认"}}",fontSize=12.sp,color=muted())
        Text("系统回读不等于已确认听感；不同设备和输出可能不同。",fontSize=11.sp,color=muted())
        }
        PanelCard{EffectSwitch("均衡器","均衡器开关",pref.equalizer,editable&&pref.enabled){choose(pref.copy(equalizer=it))}
        EffectState("均衡器",eq)
        Text("保存的预设：${pref.preset?.let(::effectPresetLabel) ?: if(pref.bands.isEmpty())"平直" else "自定义"}",fontSize=12.sp)
        Box {
            OutlinedButton({presets=true},enabled=editable&&pref.enabled&&pref.equalizer&&eq.phase==EffectPhase.APPLIED){Text("选择均衡器预设")}
            DropdownMenu(presets,{presets=false}) {
                DropdownMenuItem(text={Text("平直 · 所有频段归零")},onClick={presets=false;choose(pref.copy(preset=null,bands=emptyList()))})
                eq.presets.forEach {preset -> DropdownMenuItem(text={Text("设备预设 · ${effectPresetLabel(preset.name)}")},onClick={presets=false;choose(pref.copy(preset=preset.name,bands=emptyList()))})}
            }
        }
        if(eq.bands.isEmpty())Text("建立实际音频会话并启用后显示设备频段与预设。",fontSize=12.sp,color=muted())
        else {
            Text(if(eq.phase==EffectPhase.APPLIED)"实际回读频段 · 可自定义" else "本会话最近回读频段 · 当前未确认生效",fontSize=12.sp,fontWeight=FontWeight.Medium)
            eq.bands.forEach {band ->
                val label=if(band.frequencyMilliHz>=1_000_000)"${band.frequencyMilliHz/1_000_000.0} kHz" else "${band.frequencyMilliHz/1000.0} Hz"
                var drag by remember(band.frequencyMilliHz,band.levelMilliBel){mutableStateOf<Float?>(null)}
                Row(Modifier.fillMaxWidth()){Text(label,Modifier.weight(1f),fontSize=12.sp);Text("${(drag?.toInt() ?: band.levelMilliBel)/100.0} dB",fontSize=12.sp)}
                Slider(drag ?: band.levelMilliBel.toFloat(),{drag=it},valueRange=eq.minLevel.toFloat()..eq.maxLevel.toFloat(),
                    steps=((eq.maxLevel-eq.minLevel)/100-1).coerceAtLeast(0),enabled=editable&&pref.enabled&&pref.equalizer&&eq.phase==EffectPhase.APPLIED,
                    onValueChangeFinished={drag?.let{choose(pref.custom(eq.bands,band.frequencyMilliHz,it.roundToInt()))};drag=null},
                    modifier=Modifier.fillMaxWidth().semantics{contentDescription="均衡器 $label"})
            }
        }
        }
        PanelCard{EffectSwitch("低音增强","低音增强开关",pref.bass,editable&&pref.enabled){choose(pref.copy(bass=it))}
        EffectState("低音增强",bass)
        Text("保存强度：${pref.bassStrength/10}%${bass.actualBassStrength?.let{" · ${if(bass.phase==EffectPhase.APPLIED)"系统回读" else "最近回读"}：${it/10.0}%"}.orEmpty()}",fontSize=12.sp)
        var strength by remember(pref.bassStrength){mutableStateOf<Float?>(null)}
        Slider(strength ?: pref.bassStrength.toFloat(),{strength=it},valueRange=0f..1000f,steps=19,
            enabled=editable&&pref.enabled&&pref.bass&&bass.phase==EffectPhase.APPLIED&&bass.adjustableBass,
            onValueChangeFinished={strength?.let{choose(pref.copy(bassStrength=it.roundToInt()))};strength=null},modifier=Modifier.semantics{contentDescription="低音增强强度"})
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            TextButton(retry,enabled=editable&&pref.enabled){Text("重新检查音效")}
            TextButton({choose(EffectsSettings())},enabled=editable){Text("恢复默认并关闭")}
        }
        Text("设置在本机保存；会话或输出变化时重新应用。不支持或失败时保留偏好、释放对应音效，收听仍可继续。",fontSize=11.sp,color=muted())
    }
}
private fun effectPresetLabel(name:String):String {
    val translated=when(name){"Normal"->"常规";"Classical"->"古典";"Dance"->"舞曲";"Flat"->"平直";"Folk"->"民谣";"Heavy Metal"->"重金属";"Hip Hop"->"嘻哈";"Jazz"->"爵士";"Pop"->"流行";"Rock"->"摇滚";else->return name}
    return "$translated（$name）"
}
@Composable private fun EffectState(label:String,detail:EffectDetail) {
    Text("$label：${detail.phase.label}",fontWeight=FontWeight.Medium,color=if(detail.phase==EffectPhase.APPLIED)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    if(detail.message.isNotBlank())Text(detail.message,fontSize=12.sp,color=muted())
}
@Composable private fun EffectSwitch(label:String,description:String,value:Boolean,enabled:Boolean,change:(Boolean)->Unit) {
    SettingToggle(label,"",value,enabled,description,change)
}
