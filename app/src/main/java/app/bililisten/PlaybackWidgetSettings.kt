package app.bililisten

import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import app.bililisten.widget.PlaybackWidget
import app.bililisten.widget.WidgetCoordinator
import app.bililisten.widget.WidgetState
import app.bililisten.widget.WidgetStyle

@UnstableApi @Composable
internal fun PlaybackWidgetSettings(s: ScreenState, notice: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ListenApplication
    var style by remember { mutableStateOf(WidgetStyle.VINYL) }
    var expandedStrip by remember { mutableStateOf(false) }
    val entry = s.queue.firstOrNull { it.id == s.currentId } ?: s.resume?.let { q -> q.entries.firstOrNull { it.id == q.currentId } }
    val cover = entry?.takeUnless { it.offline }?.let { s.metadata[it.bvid]?.cover }.orEmpty()
    val coverKey = "${s.account?.id}/${entry?.id}/$cover"
    val coverImage by produceState<Pair<String,Bitmap?>?>(null,coverKey) {
        value = null
        if(cover.isNotBlank())value=coverKey to app.covers.load(cover)
    }
    val preview = WidgetState(title = if(s.isLive)s.playingTitle else entry?.title ?: "尚未播放", mediaId = if(s.isLive)"live" else entry?.id.orEmpty(),
        part = entry?.part ?: 0, live = s.isLive, requested = s.playRequested, buffering = s.buffering,
        positionMs = s.positionMs, durationMs = s.durationMs, canSeek = s.canSeek, canNext = s.canNext, canPrevious = s.canPrevious,
        restored = s.queue.isEmpty() && s.resume != null, issue = s.playbackIssue)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            WidgetStyle.entries.forEachIndexed { index, choice ->
                SegmentedButton(selected=style==choice,onClick={style=choice},shape=SegmentedButtonDefaults.itemShape(index,WidgetStyle.entries.size)) { Text(choice.title) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
            Text(style.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(style.size, style = MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth(), contentAlignment=Alignment.Center) {
        AndroidView(factory = { android.widget.FrameLayout(it) }, modifier = if(style==WidgetStyle.STRIP)Modifier.fillMaxWidth().height(if(expandedStrip)184.dp else 88.dp) else Modifier.size(208.dp), update = { host ->
            host.removeAllViews()
            val view = WidgetCoordinator.views(context, preview, AppWidgetManager.INVALID_APPWIDGET_ID, "preview", artwork=coverImage?.takeIf { it.first==coverKey }?.second, expanded = style==WidgetStyle.STRIP && expandedStrip, style=style,
                heightDp=if(style==WidgetStyle.STRIP)if(expandedStrip)184 else 88 else 208).apply(context, host)
            fun disable(v: android.view.View) { v.isClickable = false; v.isEnabled = false; if(v is android.view.ViewGroup)for(i in 0 until v.childCount)disable(v.getChildAt(i)) }
            disable(view)
            host.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        })
        }
        if(style == WidgetStyle.STRIP) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("紧凑", "展开").forEachIndexed { index, label ->
                    SegmentedButton(selected=expandedStrip==(index==1),onClick={expandedStrip=index==1},shape=SegmentedButtonDefaults.itemShape(index,2)) { Text(label) }
                }
            }
        }
        Text("已添加 ${PlaybackWidget.ids(context,style).size} 个", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = {
            try { if(!PlaybackWidget.pin(context,style))notice("当前桌面不支持应用内添加，请从桌面的小组件列表添加") }
            catch (_: Exception) { notice("暂时无法打开桌面添加窗口，请从桌面的小组件列表添加") }
        }) { Glyph(Mark.ADD, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("添加到桌面") }
    }
}
