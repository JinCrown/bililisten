package app.bililisten.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.session.SessionResult
import app.bililisten.ListenApplication
import app.bililisten.MainActivity
import app.bililisten.R
import app.bililisten.playback.ListenService
import app.bililisten.playback.PlaybackCommands
import app.bililisten.shared.Theme
import kotlinx.coroutines.*
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
open class PlaybackWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) = refresh(context)
    override fun onEnabled(context: Context) = refresh(context)
    override fun onDeleted(context: Context, ids: IntArray) { (context.applicationContext as ListenApplication).widgets.refreshCurrent() }
    override fun onDisabled(context: Context) { (context.applicationContext as ListenApplication).widgets.refreshCurrent(force = true) }
    private fun refresh(context: Context) {
        val pending = goAsync()
        (context.applicationContext as ListenApplication).widgets.launchRefresh { pending.finish() }
    }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONTROL) { super.onReceive(context, intent); return }
        val app = context.applicationContext as ListenApplication
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (style(context, id) == null) return
        if (intent.getStringExtra("token") != app.widgets.token) { app.widgets.refreshCurrent(); return }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
            try {
                val connection = MediaController.Builder(app, SessionToken(app, ComponentName(app, ListenService::class.java))).buildAsync()
                future = connection
                withTimeout(8000) {
                    val controller = suspendCancellableCoroutine<MediaController> { continuation ->
                        connection.addListener({ if (continuation.isActive) try { continuation.resume(connection.get()) } catch (e: Exception) { continuation.resumeWithException(e) } }, ContextCompat.getMainExecutor(app))
                    }
                    val result = controller.sendCustomCommand(PlaybackCommands.command(PlaybackCommands.WIDGET_CONTROL), Bundle(intent.extras ?: Bundle.EMPTY))
                    suspendCancellableCoroutine<Unit> { continuation ->
                        continuation.invokeOnCancellation { result.cancel(false) }
                        result.addListener({ if (continuation.isActive) try {
                            if(result.get().resultCode != SessionResult.RESULT_SUCCESS)app.widgets.problem("操作未完成，请打开应用检查")
                            continuation.resume(Unit)
                        } catch (e: Exception) { continuation.resumeWithException(e) } }, ContextCompat.getMainExecutor(context))
                    }
                }
            } catch (_: Exception) {
                app.widgets.problem("连接未完成，请打开应用重试")
            } finally { future?.let(MediaController::releaseFuture); pending.finish(); cancel() }
        }
    }
    companion object {
        const val ACTION_CONTROL = "app.bililisten.WIDGET_CONTROL"
        const val OPEN_PLAYER = "app.bililisten.WIDGET_OPEN_PLAYER"
        fun component(context: Context, style: WidgetStyle = WidgetStyle.STRIP) = ComponentName(context, when(style) {
            WidgetStyle.VINYL -> VinylWidget::class.java
            WidgetStyle.COVER -> CoverWidget::class.java
            WidgetStyle.STRIP -> PlaybackWidget::class.java
        })
        fun style(context: Context, id: Int): WidgetStyle? {
            val provider = AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider ?: return null
            return WidgetStyle.entries.firstOrNull { component(context, it) == provider }
        }
        fun ids(context: Context, style: WidgetStyle? = null): IntArray =
            (style?.let { listOf(it) } ?: WidgetStyle.entries).flatMap { AppWidgetManager.getInstance(context).getAppWidgetIds(component(context, it)).toList() }.toIntArray()
        fun pin(context: Context, style: WidgetStyle = WidgetStyle.STRIP): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(component(context, style), null, null)
        }
    }
}

@UnstableApi class VinylWidget : PlaybackWidget()
@UnstableApi class CoverWidget : PlaybackWidget()

/** Widget addition/refresh only reads local state; only an explicit control binds the service. */
@UnstableApi
class WidgetCoordinator(private val app: ListenApplication) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = app.getSharedPreferences("widget-identity", Context.MODE_PRIVATE)
    var token: String = preferences.getString("token", null) ?: UUID.randomUUID().toString().also { preferences.edit().putString("token", it).commit() }
        private set
    var current: WidgetState? = null; private set
    private var artwork: Bitmap? = null
    private var coverJob: Job? = null
    private var coverKey = ""
    private var revision = 0L
    private var suppressResume = false
    private var lastOwner: String? = null
    private var renderKey: Any? = null
    fun invalidate() {
        token = UUID.randomUUID().toString()
        preferences.edit().putString("token", token).commit()
        revision++; suppressResume = true; current = null
        coverJob?.cancel(); artwork = null; coverKey = ""; renderKey = null; WidgetArtwork.clear()
        refreshCurrent()
    }
    fun accountChanged(account: String) {
        if (lastOwner != null && lastOwner != account) { current = null; artwork = null; coverJob?.cancel(); revision++; WidgetArtwork.clear() }
        lastOwner = account
        suppressResume = false
        launchRefresh {}
    }
    fun publish(state: WidgetState, force: Boolean = false) {
        if (state.account != app.accounts.session.value.stamp.account || suppressResume) return
        val previous = current
        current = state
        revision++
        if (previous?.mediaId != state.mediaId || previous?.account != state.account) { artwork = null; coverJob?.cancel(); coverKey = ""; WidgetArtwork.clear() }
        if (force) renderKey = null
        refreshCurrent()
        if (PlaybackWidget.ids(app).isNotEmpty() && state.bvid.isNotBlank() && coverKey != "${state.account}/${state.bvid}") {
            coverKey = "${state.account}/${state.bvid}"
            val key = coverKey; val session = token
            coverJob = scope.launch {
                try {
                    val cover = withContext(Dispatchers.IO) { app.content.video(state.bvid).cover }
                    val bitmap = app.covers.load(cover)
                    if (coverKey == key && token == session && current?.account == state.account) { artwork = bitmap; renderKey = null; refreshCurrent() }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Artwork never blocks the controls. */ }
            }
        }
    }
    fun serviceStopped() {
        current?.let { if(it.live) { current = null; launchRefresh {} } else publish(it.copy(requested = false, buffering = false, restored = true, canPrevious = false, canNext = false, canSeek = false), true) }
    }
    fun unused() { coverJob?.cancel(); coverJob = null; artwork = null; coverKey = ""; renderKey = null; WidgetArtwork.clear() }
    fun problem(message: String) { current = (current ?: WidgetState()).copy(notice = message); refreshCurrent(force = true) }
    fun launchRefresh(done: () -> Unit) { scope.launch { try { restoreLocal(); refreshCurrent(force = true) } finally { done() } } }
    private suspend fun restoreLocal() {
        if (current != null || suppressResume || PlaybackWidget.ids(app).isEmpty()) return
        val owner = app.accounts.session.value.stamp.account
        val epoch = revision
        val saved = try { withContext(Dispatchers.IO) { app.stores.load(owner) } } catch (_: Exception) { null }
        if (revision != epoch || suppressResume || app.accounts.session.value.stamp.account != owner || current != null) return
        saved?.queue?.let { queue ->
            val entry = queue.entries.first { it.id == queue.currentId }
            publish(WidgetState(owner, entry.id, queue.queueVersion, entry.title, entry.part, entry.takeUnless { it.offline }?.bvid.orEmpty(), positionMs = queue.positionMs, restored = true))
        }
    }
    fun refreshCurrent(force: Boolean = false) {
        val ids = PlaybackWidget.ids(app)
        if (ids.isEmpty()) { unused(); return }
        val state = current?.takeIf { it.account == app.accounts.session.value.stamp.account } ?: WidgetState()
        val key = listOf(state.copy(positionMs = if (state.requested) state.positionMs / 15000 * 15000 else state.positionMs), artwork, token, dark(app), ids.toList())
        if (!force && renderKey == key) return
        renderKey = key
        val manager = AppWidgetManager.getInstance(app)
        ids.forEach { id ->
            val style = PlaybackWidget.style(app, id) ?: return@forEach
            val options = manager.getAppWidgetOptions(id)
            val expanded = style.expanded(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250), options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
            manager.updateAppWidget(id, views(app, state, id, token, artwork, expanded, style, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,200)))
        }
    }
    companion object {
        fun dark(context: Context): Boolean {
            val choice = (context.applicationContext as ListenApplication).settings.settings.value.theme
            return choice == Theme.DARK || choice == Theme.SYSTEM && context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        fun views(context: Context, state: WidgetState, id: Int, token: String, artwork: Bitmap? = null, expanded: Boolean = false, style: WidgetStyle = WidgetStyle.STRIP, heightDp: Int = 200): RemoteViews {
            val dark = dark(context)
            val expandedStrip = expanded && style == WidgetStyle.STRIP
            val foreground = if (style == WidgetStyle.COVER || dark) 0xfff7f7f8.toInt() else 0xff24262b.toInt()
            val muted = if (style == WidgetStyle.COVER || dark) 0xffd0d2d7.toInt() else 0xff747780.toInt()
            val layout = when(style) {
                WidgetStyle.VINYL -> R.layout.playback_widget_vinyl
                WidgetStyle.COVER -> R.layout.playback_widget_cover
                WidgetStyle.STRIP -> if (expandedStrip) R.layout.playback_widget_expanded else R.layout.playback_widget
            }
            val views = RemoteViews(context.packageName, layout)
            val shortStrip = style == WidgetStyle.STRIP && !expandedStrip && heightDp < 80
            val density = context.resources.displayMetrics.density
            val padding = ((if(style == WidgetStyle.STRIP && !expandedStrip)8 else 12)*density).toInt()
            views.setViewPadding(if(style == WidgetStyle.COVER)R.id.widget_cover_overlay else R.id.widget_root, padding, padding, padding, padding)
            if(style != WidgetStyle.STRIP)views.setViewPadding(R.id.widget_header, 0, 0, 0, if(style == WidgetStyle.COVER)(8*density).toInt() else 0)
            views.setTextViewTextSize(R.id.widget_title, android.util.TypedValue.COMPLEX_UNIT_SP, if(style == WidgetStyle.STRIP)15f else 14f)
            views.setTextViewTextSize(R.id.widget_status, android.util.TypedValue.COMPLEX_UNIT_SP, if(style == WidgetStyle.STRIP)11f else 10f)
            // Two-row landscape cells can be much shorter than portrait cells.
            val shortSquare = style != WidgetStyle.STRIP && heightDp < 148
            if (shortSquare) {
                val inset = (6 * context.resources.displayMetrics.density).toInt()
                views.setViewPadding(if (style == WidgetStyle.COVER) R.id.widget_cover_overlay else R.id.widget_root, inset, inset, inset, inset)
                views.setViewPadding(R.id.widget_header, 0, 0, 0, 0)
                views.setTextViewTextSize(R.id.widget_title, android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                views.setTextViewTextSize(R.id.widget_status, android.util.TypedValue.COMPLEX_UNIT_SP, 9f)
            }
            if (shortStrip) {
                views.setViewPadding(R.id.widget_root, (12*density).toInt(), density.toInt(), (12*density).toInt(), density.toInt())
                views.setTextViewTextSize(R.id.widget_title, android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                views.setTextViewTextSize(R.id.widget_status, android.util.TypedValue.COMPLEX_UNIT_SP, 9f)
            }
            if(style == WidgetStyle.STRIP)views.setViewVisibility(R.id.widget_cover, if(shortStrip)View.GONE else View.VISIBLE)
            views.setInt(R.id.widget_root, "setBackgroundResource", when {
                style == WidgetStyle.COVER -> R.drawable.widget_cover_background
                style == WidgetStyle.STRIP -> if(dark) R.drawable.widget_strip_background_dark else R.drawable.widget_strip_background
                dark -> R.drawable.widget_background_dark
                else -> R.drawable.widget_background
            })
            views.setTextViewText(R.id.widget_title, state.title.take(500))
            val twoLines = expandedStrip && (heightDp >= 192 || context.resources.configuration.fontScale < 1.4f) || style == WidgetStyle.COVER && heightDp >= 176
            views.setInt(R.id.widget_title, "setMaxLines", if (twoLines) 2 else 1)
            views.setTextViewText(R.id.widget_status, state.status)
            views.setTextColor(R.id.widget_title, foreground)
            views.setTextColor(R.id.widget_status, muted)
            views.setImageViewBitmap(R.id.widget_cover, WidgetArtwork.render(artwork?.takeIf { state.hasContent }, style == WidgetStyle.VINYL, dark))
            if (style == WidgetStyle.VINYL) views.setViewVisibility(R.id.widget_cover,
                if (shortSquare || heightDp <= 160 && context.resources.configuration.fontScale >= 1.4f) View.GONE else View.VISIBLE)
            val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java).setAction(PlaybackWidget.OPEN_PLAYER)
                .setData(Uri.parse("bililisten-widget://open/$id")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_header, open)
            views.setContentDescription(R.id.widget_header, if (state.hasContent) "打开播放器：${state.title}" else "打开哔哩听视频")
            views.setImageViewResource(R.id.widget_play, if (state.requested) R.drawable.widget_pause else R.drawable.widget_play)
            val actions = listOf(R.id.widget_previous to "previous", R.id.widget_play to if (state.requested) "pause" else "play", R.id.widget_next to "next", R.id.widget_back to "back", R.id.widget_forward to "forward")
            actions.forEach { (view, action) ->
                views.setInt(view, "setColorFilter", if(view == R.id.widget_play)0xffffffff.toInt() else foreground)
                val enabled = state.allows(action)
                views.setFloat(view, "setAlpha", if (enabled || view == R.id.widget_play && !state.hasContent) 1f else .3f)
                views.setBoolean(view, "setEnabled", enabled || view == R.id.widget_play && !state.hasContent)
                views.setContentDescription(view, when (action) { "play" -> if (state.hasContent) "播放" else "选择内容"; "pause" -> "暂停"; "previous" -> "上一条"; "next" -> "下一条"; "back" -> "后退15秒"; else -> "前进15秒" })
                val command = Intent().setComponent(PlaybackWidget.component(context, style)).setAction(PlaybackWidget.ACTION_CONTROL)
                    .setData(Uri.parse("bililisten-widget://control/$id/$token/${state.version}/${Uri.encode(state.mediaId)}/$action"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).putExtra("token", token).putExtra("owner", state.account)
                    .putExtra("mediaId", state.mediaId).putExtra("version", state.version).putExtra("control", action)
                views.setOnClickPendingIntent(view, if (!state.hasContent && view == R.id.widget_play) open else PendingIntent.getBroadcast(context, 0, command, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            val seek = expandedStrip && !state.live && state.canSeek && state.durationMs > 0
            views.setViewVisibility(R.id.widget_progress_row, if (seek) View.VISIBLE else View.GONE)
            views.setProgressBar(R.id.widget_progress, 1000, if (state.durationMs > 0) (state.positionMs.toDouble() / state.durationMs * 1000).toInt().coerceIn(0, 1000) else 0, false)
            views.setTextViewText(R.id.widget_time, "${widgetTime(state.positionMs)} / ${widgetTime(state.durationMs)}")
            views.setTextColor(R.id.widget_time, muted)
            views.setViewVisibility(R.id.widget_back, if (expandedStrip && !state.live) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_forward, if (expandedStrip && !state.live) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_previous, if (state.live || style == WidgetStyle.STRIP && !expandedStrip) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.widget_next, if (state.live) View.GONE else View.VISIBLE)
            return views
        }
    }
}
