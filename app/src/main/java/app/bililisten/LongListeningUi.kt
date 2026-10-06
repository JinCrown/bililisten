package app.bililisten

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import app.bililisten.shared.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun historyDate(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
@Composable fun HistoryFilters(query:String,onQuery:(String)->Unit,type:HistoryType,onType:(HistoryType)->Unit,origin:HistoryOrigin?,onOrigin:(HistoryOrigin?)->Unit) {
    PanelCard {
        OutlinedTextField(query,onQuery,Modifier.fillMaxWidth(),label={Text("搜索收听记录 · 标题 / BV / 房间号")},singleLine=true)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) { HistoryType.entries.forEach { FilterChip(type==it,{onType(it)},label={Text(it.label)},colors=historyChipColors()) } }
        if(type!=HistoryType.LIVE)Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(origin==null,{onOrigin(null)},label={Text("所有来源")},colors=historyChipColors())
            HistoryOrigin.entries.forEach { FilterChip(origin==it,{onOrigin(it)},label={Text(it.label)},colors=historyChipColors()) }
        }
    }
}
@Composable fun LiveHistoryCard(row:LiveHistoryEntry,play:()->Unit,delete:()->Unit) {
    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick=play)) {
                Text(row.title,fontWeight=FontWeight.SemiBold)
                Text("直播 · 房间 ${row.roomId} · ${historyDate(row.playedAt)}",fontSize=11.sp,color=muted())
                Text("检查房间并收听当前直播",fontSize=12.sp,color=MaterialTheme.colorScheme.primary)
            }
            TextButton(delete){Text("删除")}
        }
    }
}
@Composable private fun SettingSwitch(title:String,detail:String,enabled:Boolean,onChange:(Boolean)->Unit) {SettingToggle(title,detail,enabled,change=onChange)}
@Composable fun HistoryPrivacy(settings:UserSettings,update:(UserSettings)->Unit,clear:()->Unit,resetPreference:()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        PanelHeading("你的收听记录","本机保存 · 保留与清理",Mark.HISTORY)
        PanelCard{
        Text("保留策略",fontWeight=FontWeight.Bold)
        Text(if(settings.historyKeepAll)"全部保留，不设条数或时长上限" else "保留 ${settings.historyDays} 天，最多 ${settings.historyLimit} 条（视频与直播合计）",fontSize=12.sp,color=muted())
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf(30,90,365).forEach { days -> FilterChip(!settings.historyKeepAll&&settings.historyDays==days,{update(settings.copy(historyKeepAll=false,historyDays=days))},label={Text("$days 天")},colors=historyChipColors()) }
            FilterChip(settings.historyKeepAll,{update(settings.copy(historyKeepAll=true))},label={Text("全部保留")},colors=historyChipColors())
        }
        SettingSwitch("退出登录时删除本机历史","仅退出登录时，删除当前身份的本机历史",settings.historyDeleteOnExit){update(settings.copy(historyDeleteOnExit=it))}
        Text("锁屏、切到后台、划掉最近任务或系统结束进程不会触发这项删除。默认退出保留；账号间隔离。续听快照独立保留，重新打开保持暂停。",fontSize=12.sp,color=muted())
        OutlinedButton(clear,Modifier.fillMaxWidth()){Text("清空当前身份历史")}
        }
        PanelCard{Text("推荐偏好",fontWeight=FontWeight.SemiBold)
        TextButton(resetPreference){Text("重置推荐偏好（保留历史）")}
        }
        Text("关闭实时同步B站最近记录时，历史仅在本机保存；开启后将读取和写入 B站观看历史。清理历史不取消收藏、不删除下载，也不重置推荐偏好。推荐偏好不会上传。",fontSize=12.sp,color=muted())
    }
}
@Composable fun LongListeningPanel(s:ScreenState,speed:(Float)->Unit,timer:(Int)->Unit,continuous:(Boolean)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        PanelHeading("长内容收听","按自己的节奏，把好内容听完",Mark.CLOCK)
        PlaybackSpeedPanel(s,speed)
        PanelCard{SettingSwitch("连续播放分 P","用于下次打开视频或选择分 P；保持现有队列",s.settings.continuousParts,continuous)}
        SleepTimerPanel(s,timer)
    }
}
@Composable fun PlaybackSpeedPanel(s:ScreenState,speed:(Float)->Unit) {
        PanelCard{
        Text("播放倍速",fontSize=18.sp,fontWeight=FontWeight.Bold)
        Text(if(s.isLive)"直播固定 1 倍速，不支持快进快退" else "当前 ${s.speed} 倍速 · 快进快退 15 秒",color=MaterialTheme.colorScheme.primary)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            LongListening.speeds.forEach { value -> FilterChip(s.speed==value,{speed(value)},enabled=!s.isLive&&s.queue.isNotEmpty()&&!s.busy,label={Text("${value}x")},colors=historyChipColors()) }
        }
        Text("倍速只作用于当前视频及其分 P，随当前队列保存；新打开的其他视频和直播使用 1 倍速。",fontSize=12.sp,color=muted())
        }
}
@Composable fun SleepTimerPanel(s:ScreenState,timer:(Int)->Unit) {
        var minutes by rememberSaveable { mutableStateOf("30") }
        PanelCard{Text("定时停止",fontSize=18.sp,fontWeight=FontWeight.Bold)
        Text(if(s.timerRemainingMs>0)"剩余 ${timeLabel(s.timerRemainingMs)}" else if(s.pauseReason==PauseReason.TIMER)"已到时停止并保存" else "未开启",color=MaterialTheme.colorScheme.primary)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf(10,20,30,60,90).forEach { value -> OutlinedButton({timer(value)},enabled=!s.busy){Text("$value 分钟")} }
        }
        Row(verticalAlignment=Alignment.CenterVertically) {
            OutlinedTextField(minutes,{minutes=it.filter(Char::isDigit).take(3)},Modifier.weight(1f),label={Text("自定义 1—180 分钟")},singleLine=true)
            TextButton({minutes.toIntOrNull()?.let(timer)},enabled=!s.busy&&minutes.toIntOrNull() in 1..180){Text("开始计时")}
        }
        if(s.timerRemainingMs>0)TextButton({timer(0)}){Text("取消定时")}
        Text("锁屏、后台和手动暂停期间继续倒计时；到时暂停并保存位置，需手动继续。退出账号或进程结束会取消计时，重开不会自动播放。",fontSize=12.sp,color=muted())
        }
}

@Composable private fun historyChipColors() = FilterChipDefaults.filterChipColors(selectedContainerColor=blush(),selectedLabelColor=MaterialTheme.colorScheme.primary)
