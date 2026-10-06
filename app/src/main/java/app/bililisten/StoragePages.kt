package app.bililisten

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import app.bililisten.shared.*

private fun sizeLabel(bytes:Long)="%.1f MB".format(bytes/1048576.0)
fun LazyListScope.downloadItems(s:ScreenState,vm:MainViewModel,confirm:(String,String,()->Unit)->Unit) {
    item {
        PageIntro("把声音留在身边","只保存独立音频，下载完成后持续离线听",Mark.DOWNLOAD)
        PanelCard(accent=true){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("我的音频",fontWeight=FontWeight.Bold,fontSize=16.sp);Text("${s.downloads.count{it.phase==DownloadPhase.COMPLETE}} 项已完成 · ${s.downloads.size} 项任务",fontSize=12.sp,color=muted())};StatusTag("本机保存",Mark.CHECK)}
            Button({confirm("下载当前队列的音频？","会逐项核对下载范围，仅保存队列中各分 P 的完整普通音轨；不下载视频，不开始播放。",vm::downloadQueue)},Modifier.fillMaxWidth(),enabled=s.queue.isNotEmpty()&&!s.isLive&&!s.busy){Glyph(Mark.DOWNLOAD,Modifier.size(18.dp));Text("下载当前队列（${s.queue.size} 项）",Modifier.padding(start=8.dp))}
        }
    }
    if(s.downloads.isEmpty())item{EmptyState("当前账号暂无下载任务","播放页点击“下载音频”，喜欢的内容就能随时听")}
    items(s.downloads,key={it.id}) { row ->
        PanelCard {
            Row(verticalAlignment=Alignment.CenterVertically){IconBadge(if(row.phase==DownloadPhase.COMPLETE)Mark.CHECK else Mark.DOWNLOAD);Column(Modifier.weight(1f).padding(start=12.dp)){Text(row.title,fontWeight=FontWeight.SemiBold,fontSize=14.sp);Text("P${row.video.part} · ${row.phase.label}",Modifier.padding(top=3.dp),fontSize=12.sp,color=muted())}}
            Text(sizeLabel(row.bytes)+(row.total?.let{" / ${sizeLabel(it)}"} ?: ""),fontSize=12.sp,color=muted())
            if(row.phase==DownloadPhase.RUNNING) {
                val total=row.total
                if(total!=null)LinearProgressIndicator(progress={(row.bytes.toFloat()/total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),drawStopIndicator={})
                else LinearProgressIndicator(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
            }
            if(row.message.isNotBlank())Text(row.message,fontSize=12.sp,color=if(row.phase in setOf(DownloadPhase.FAILED,DownloadPhase.BLOCKED))MaterialTheme.colorScheme.error else muted())
            Row(verticalAlignment=Alignment.CenterVertically) {
                when(row.phase) {
                    DownloadPhase.COMPLETE -> FilledTonalButton({vm.playDownload(row.id)},enabled=!s.busy){Glyph(Mark.PLAY,Modifier.size(16.dp));Text("离线收听",Modifier.padding(start=6.dp))}
                    DownloadPhase.RUNNING,DownloadPhase.CHECKING,DownloadPhase.QUEUED -> FilledTonalButton({vm.pauseDownload(row.id)},enabled=!s.busy){Text("暂停")}
                    else -> FilledTonalButton({vm.resumeDownload(row.id)},enabled=!s.busy){Text(if(row.phase==DownloadPhase.PAUSED)"继续" else "重试")}
                }
                Spacer(Modifier.weight(1f))
                TextButton({confirm(if(row.phase==DownloadPhase.COMPLETE)"删除这份下载？" else "取消并删除任务？","只删除这项任务及其本机音频，历史和收藏保留。"){vm.removeDownload(row.id)}},enabled=!s.busy){Text(if(row.phase==DownloadPhase.COMPLETE)"删除" else "取消",color=muted())}
            }
            if(row.phase==DownloadPhase.PAUSED)Text("服务器支持范围请求且文件未变化时继续；否则重新开始。",fontSize=11.sp,color=muted())
        }
    }
    item{InfoCard("只供本机收听，不设到期时间。付费、试听、高阶音轨、混流和直播暂不下载。各账号列表独立，退出不删文件。",Mark.INFO)}
}

@Composable fun StorageControls(s:ScreenState,usage:StorageUsage,update:(UserSettings)->Unit,
    confirm:(String,String,()->Unit)->Unit,clearPictures:()->Unit,clearAutomatic:()->Unit,clearOrdinary:()->Unit,networkSettings:()->Unit) {
    val settings=s.settings.storage
    val total=usage.records+usage.pictures+usage.automatic+usage.downloads
    val rows=listOf(Triple("列表与记录",usage.records,Color(0xFF9279C7)),Triple("图片",usage.pictures,Color(0xFF68A1D7)),Triple("自动音频缓存",usage.automatic,ListenPink),Triple("主动下载及未完成部分",usage.downloads,Color(0xFF4AAE9E)))
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        PanelCard(accent=true){
            Text("本机空间",fontWeight=FontWeight.Bold,fontSize=16.sp)
            Row(verticalAlignment=Alignment.Bottom){Text("%.1f".format(total/1048576.0),fontSize=38.sp,fontWeight=FontWeight.Bold);Text(" MB",Modifier.padding(bottom=7.dp),fontSize=14.sp,color=muted())}
            Text("所有本机账号合计",fontSize=11.sp,color=muted())
            Row(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {if(total>0)rows.filter{it.second>0}.forEach{(_,bytes,color)->Box(Modifier.weight((bytes.toDouble()/total).toFloat().coerceAtLeast(.001f)).fillMaxHeight().background(color))}}
            rows.forEach{(label,bytes,color)->Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(7.dp).background(color,CircleShape));Text(label,Modifier.weight(1f).padding(start=8.dp),fontSize=12.sp,color=muted());Text(sizeLabel(bytes),fontSize=12.sp,fontWeight=FontWeight.SemiBold)}}
        }
        PanelCard {
            SettingToggle("自动音频缓存","收听时保存可缓存的普通音频片段",settings.automaticAudio){on->
                if(on)confirm("开启自动音频缓存？","收听已确认允许下载的普通音轨时，会保存已读音频片段；不预下载整条队列。默认仅非计费网络，空间达到上限会淘汰旧片段。"){update(s.settings.copy(storage=settings.copy(automaticAudio=true)))}
                else update(s.settings.copy(storage=settings.copy(automaticAudio=false)))
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            Text("自动缓存上限：${settings.cacheMiB} MB",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(64,256,512).forEach{cap->Pill("${cap} MB",settings.cacheMiB==cap){update(s.settings.copy(storage=settings.copy(cacheMiB=cap)))}}}
            Text("默认关闭；关闭后停止新写入，已有缓存保留。空间满时只淘汰旧缓存，不动主动下载。",fontSize=12.sp,color=muted())
        }
        PanelCard {
            Text("网络使用",fontWeight=FontWeight.Bold,fontSize=16.sp)
            SettingToggle("允许自动缓存使用计费网络","移动数据等计费网络",settings.cacheOnMetered){on->
                if(on)confirm("允许自动缓存使用计费网络？","启用后，自动缓存可使用移动数据或其他计费网络；仍需开启自动音频缓存。"){update(s.settings.copy(storage=settings.copy(cacheOnMetered=true)))}
                else update(s.settings.copy(storage=settings.copy(cacheOnMetered=false)))
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            SettingToggle("允许主动下载使用计费网络","完整音频下载可能消耗较多流量",settings.downloadOnMetered){on->
                if(on)confirm("允许下载使用计费网络？","下载完整音频可能消耗较多流量。这与移动数据播放、自动缓存设置独立。"){update(s.settings.copy(storage=settings.copy(downloadOnMetered=true)))}
                else update(s.settings.copy(storage=settings.copy(downloadOnMetered=false)))
            }
            TextButton(networkSettings){Text("移动数据播放设置（独立控制）");Glyph(Mark.CHEVRON,Modifier.size(15.dp))}
        }
        PanelCard {
            Text("释放缓存空间",fontWeight=FontWeight.Bold,fontSize=16.sp)
            Text("清理图片与自动缓存，保留账号、历史、续听和主动下载。",fontSize=12.sp,color=muted())
            Button({confirm("清理普通缓存？","清理图片与自动音频缓存。账号、列表、历史、续听和主动下载均保留。",clearOrdinary)},Modifier.fillMaxWidth()){Glyph(Mark.CACHE,Modifier.size(18.dp));Text("清理普通缓存",Modifier.padding(start=8.dp))}
            TextButton({confirm("清理自动音频缓存？","不会删除主动下载和收听记录。",clearAutomatic)},Modifier.fillMaxWidth()){Text("只清理自动音频缓存")}
            TextButton({confirm("清理封面缓存？","再次打开内容时会重新加载图片。",clearPictures)},Modifier.fillMaxWidth()){Text("只清理封面缓存")}
        }
        InfoCard("缓存片段不作为完整离线下载。主动下载最多 500 项，单个音频上限 512 MB，保留至少 32 MB 可用空间；断网、空间不足或账号变化时停止，请手动继续。")
    }
}
