package app.bililisten.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.R
import app.bililisten.playback.ListenService
import app.bililisten.playback.PlaybackCommands
import app.bililisten.shared.*
import app.bililisten.storage.AudioFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json

@UnstableApi @RunWith(AndroidJUnit4::class)
class PlaybackWidgetTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val app = i.targetContext.applicationContext as ListenApplication
    private val out get() = File(app.filesDir, "m6g-evidence").apply { mkdirs() }
    private fun <T> main(block: () -> T): T {
        val result = AtomicReference<T>(); val failure = AtomicReference<Throwable>()
        i.runOnMainSync { try { result.set(block()) } catch(e:Throwable) { failure.set(e) } }
        failure.get()?.let { throw it }; return result.get()
    }
    private fun waitFor(check: () -> Boolean) {
        val end = System.currentTimeMillis() + 12000
        while(System.currentTimeMillis() < end) { if(check())return; Thread.sleep(40) }
        error("Widget condition timed out")
    }
    @Test fun recoverInterruptedFixtureOnly() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("m6gcleanup") == "1")
        val marker=File(app.noBackupFilesDir,"m6g-test-preserve.json")
        if(marker.exists()) { app.settings.update(Json.decodeFromString<UserSettings>(marker.readText()));marker.delete() }
        InstrumentationRegistry.getArguments().getString("m6ghistory")?.let { value ->
            require(value in listOf("true","false"));app.settings.update(app.settings.current().copy(historyEnabled=value.toBoolean()))
        }
        app.database.listenDao().clearPlayback("6035");app.database.listenDao().clearHistory("6035")
        main{val host=AppWidgetHost(app,6036);host.appWidgetIds.forEach(host::deleteAppWidgetId);host.stopListening()}
        File(app.cacheDir,"m6g-audio-fixture").deleteRecursively()
        Unit
    }
    private fun render(name:String, theme:Theme, width:Int, height:Int, expanded:Boolean, scale:Float=1f, live:Boolean=false, empty:Boolean=false, style:WidgetStyle=WidgetStyle.STRIP) = runBlocking {
        val saved = app.settings.current()
        try {
            app.settings.update(saved.copy(theme=theme))
            val config = android.content.res.Configuration(app.resources.configuration).apply { fontScale=scale }
            val context = android.view.ContextThemeWrapper(app.createConfigurationContext(config), android.R.style.Theme_Material_Light_NoActionBar)
            val state = if(empty)WidgetState() else WidgetState(mediaId="fixture", title="示例音乐现场合集 · 很长的标题用于检验大字体和两行边界", part=12,
                live=live, requested=true, positionMs=75000, durationMs=180000, canSeek=true, canPrevious=true, canNext=true)
            main {
                val host = android.widget.FrameLayout(context)
                val poster=Bitmap.createBitmap(400,240,Bitmap.Config.ARGB_8888)
                Canvas(poster).apply {
                    drawColor(0xff639a93.toInt())
                    val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    paint.color=0xffeaafad.toInt();drawRect(200f,0f,400f,240f,paint)
                    paint.color=0xffe9eddb.toInt();drawCircle(200f,105f,58f,paint)
                    paint.color=0xff285551.toInt();paint.textSize=24f;drawText("LIVE",172f,114f,paint)
                }
                val view = WidgetCoordinator.views(context,state,7,"fixture",artwork=poster,expanded=expanded,style=style,heightDp=height).apply(context,host)
                host.addView(view)
                val density = context.resources.displayMetrics.density
                val w = (width*density).toInt(); val h = (height*density).toInt()
                host.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));host.layout(0,0,w,h)
                val title=view.findViewById<TextView>(R.id.widget_title);val status=view.findViewById<TextView>(R.id.widget_status)
                assertTrue(title.measuredWidth>0);assertTrue(title.bottom<=title.parent.let{it as View}.height)
                assertTrue(status.bottom<=status.parent.let{it as View}.height)
                assertTrue(title.lineHeight*title.lineCount<=title.height+2*density)
                assertTrue(status.top>=title.bottom)
                assertEquals(if(live)View.GONE else View.VISIBLE,view.findViewById<View>(R.id.widget_next).visibility)
                assertEquals(if(expanded&&!live&&!empty)View.VISIBLE else View.GONE,view.findViewById<View>(R.id.widget_progress_row).visibility)
                assertTrue(view.findViewById<View>(R.id.widget_play).height>=44*density)
                val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);host.draw(Canvas(bitmap))
                File(out,"fixture-$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
        } finally { app.settings.update(saved) }
    }
    @Test fun lightAndDarkCompactExpandedViewsInflateAndFit() {
        render("compact-light",Theme.LIGHT,250,128,false)
        render("expanded-light",Theme.LIGHT,320,184,true)
        render("compact-dark",Theme.DARK,250,128,false)
        render("expanded-dark",Theme.DARK,320,184,true)
    }
    @Test fun minimumWidthLargeFontsLiveAndEmptyStayBounded() {
        render("large-compact",Theme.LIGHT,250,128,false,1.6f)
        render("large-expanded",Theme.DARK,250,200,true,1.6f)
        render("live",Theme.LIGHT,250,184,true,live=true)
        render("empty",Theme.LIGHT,250,128,false,empty=true)
        for(style in listOf(WidgetStyle.VINYL,WidgetStyle.COVER)) {
            render("${style.name.lowercase()}-light",Theme.LIGHT,180,200,false,style=style)
            render("${style.name.lowercase()}-dark",Theme.DARK,180,200,false,style=style)
            render("${style.name.lowercase()}-small",Theme.LIGHT,128,148,false,1.6f,style=style)
            render("${style.name.lowercase()}-minimum",Theme.LIGHT,128,110,false,style=style)
            render("${style.name.lowercase()}-minimum-large",Theme.DARK,128,110,false,1.6f,style=style)
            render("${style.name.lowercase()}-live",Theme.DARK,148,172,false,1.6f,live=true,style=style)
            render("${style.name.lowercase()}-empty",Theme.LIGHT,148,172,false,empty=true,style=style)
        }
        render("strip-one-row",Theme.LIGHT,320,88,false,1.6f)
        render("strip-minimum",Theme.LIGHT,250,50,false)
        render("strip-minimum-large",Theme.DARK,250,50,false,1.6f)
        render("strip-expanded-boundary",Theme.DARK,250,176,true,1.6f)
    }
    @Test fun nativeHostAdditionResizeRemovalReadsStateWithoutAutoplay() = runBlocking {
        val owner=app.accounts.session.value.stamp.account
        val before=app.stores.load(owner)
        val manager=AppWidgetManager.getInstance(app)
        val host=main{AppWidgetHost(app,6035)}
        val id=main{host.allocateAppWidgetId()}
        try {
            assertTrue("Test-only bind grant required",manager.bindAppWidgetIdIfAllowed(id,PlaybackWidget.component(app)))
            val info=requireNotNull(manager.getAppWidgetInfo(id))
            assertEquals(0,info.updatePeriodMillis)
            val view=main{host.startListening();host.createView(app,id,info)}
            val state=WidgetState(account=owner,mediaId="fixture",version=1,title="小组件测试内容",part=2)
            main{app.widgets.publish(state,true)}
            waitFor{main{view.findViewById<TextView>(R.id.widget_title)?.text?.toString()==state.title}}
            manager.updateAppWidgetOptions(id,Bundle().apply{putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,184);putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,320)})
            waitFor{main{view.findViewById<TextView>(R.id.widget_title)?.maxLines==2}}
            assertFalse(requireNotNull(main{app.widgets.current}).requested)
            main{app.widgets.invalidate()}
            waitFor{main{view.findViewById<TextView>(R.id.widget_title)?.text?.toString()=="尚未播放"}}
            assertEquals(before?.queue,app.stores.load(owner)?.queue)
            File(out,"host.json").writeText("""{"nativeHostBound":true,"resizeUpdated":true,"invalidatedTitleCleared":true,"snapshotUnchanged":true,"autoAddedToLauncher":false,"autoplay":false}""")
        } finally {
            main{host.deleteAppWidgetId(id);host.stopListening();app.widgets.accountChanged(owner)}
        }
        assertNull(manager.getAppWidgetInfo(id))
    }
    private class Accounts:AccountRepository {
        override val session=MutableStateFlow(AccountSession(SessionStamp("6035",1),SessionStatus.AUTHENTICATED,Account(6035,"示例账号")))
        override suspend fun verify()=session.value.account
        override suspend fun accept(cookie:String)=error("unused")
        override suspend fun logout(){session.value=AccountSession(SessionStamp("guest",2),SessionStatus.GUEST)}
        override fun requireCurrent(stamp:SessionStamp){check(stamp==session.value.stamp)}
    }
    @Test fun threeStylesBindUpdateAndRemoveIndependently() = runBlocking {
        val owner=app.accounts.session.value.stamp.account
        val before=app.stores.load(owner)
        val manager=AppWidgetManager.getInstance(app)
        val host=main{AppWidgetHost(app,6038)}
        val owned=mutableListOf<Int>()
        try {
            val views=WidgetStyle.entries.associateWith { style ->
                val id=main{host.allocateAppWidgetId()};owned+=id
                assertTrue(manager.bindAppWidgetIdIfAllowed(id,PlaybackWidget.component(app,style)))
                val info=requireNotNull(manager.getAppWidgetInfo(id))
                if(style != WidgetStyle.STRIP) {
                    val limit=(110*app.resources.displayMetrics.density).toInt()+1
                    assertTrue("Default height must fit two short rows",info.minHeight<=limit)
                    assertTrue("Resize minimum must not force a third row",info.minResizeHeight<=limit)
                } else {
                    val limit=(50*app.resources.displayMetrics.density).toInt()+1
                    assertTrue(info.minHeight<=limit)
                    assertTrue(info.minResizeHeight<=limit)
                }
                if(android.os.Build.VERSION.SDK_INT>=31) {
                    assertEquals(if(style==WidgetStyle.STRIP)4 else 2,info.targetCellWidth)
                    assertEquals(if(style==WidgetStyle.STRIP)1 else 2,info.targetCellHeight)
                }
                assertEquals(style,PlaybackWidget.style(app,id))
                id to main{host.startListening();host.createView(app,id,info)}
            }
            val state=WidgetState(account=owner,mediaId="styles-fixture",title="三款共同的暂停内容",part=3)
            main{app.widgets.publish(state,true)}
            waitFor{main{views.values.all{(_,v)->v.findViewById<TextView>(R.id.widget_title)?.text?.toString()==state.title}}}
            for(style in WidgetStyle.entries) {
                val (id,view)=views.getValue(style)
                fun resize(height:Int)=manager.updateAppWidgetOptions(id,Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,if(style==WidgetStyle.STRIP)320 else 128)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,height)
                })
                resize(if(style==WidgetStyle.STRIP)50 else 110)
                waitFor{main{view.findViewById<TextView>(R.id.widget_title)?.textSize?.let { kotlin.math.abs(it-11*app.resources.displayMetrics.scaledDensity)<1 }==true}}
                if(style!=WidgetStyle.COVER)assertEquals(View.GONE,main{view.findViewById<View>(R.id.widget_cover).visibility})
                resize(if(style==WidgetStyle.STRIP)88 else 200)
                waitFor{main{view.findViewById<TextView>(R.id.widget_title)?.textSize?.let { kotlin.math.abs(it-(if(style==WidgetStyle.STRIP)15 else 14)*app.resources.displayMetrics.scaledDensity)<1 }==true}}
                assertEquals(View.VISIBLE,main{view.findViewById<View>(R.id.widget_cover).visibility})
            }
            val removed=views.getValue(WidgetStyle.VINYL).first
            main{host.deleteAppWidgetId(removed)};owned.remove(removed)
            main{app.widgets.publish(state.copy(title="保留的小组件继续同步"),true)}
            waitFor{main{views.filterKeys{it!=WidgetStyle.VINYL}.values.all{(_,v)->v.findViewById<TextView>(R.id.widget_title)?.text?.toString()=="保留的小组件继续同步"}}}
            assertEquals(before?.queue,app.stores.load(owner)?.queue)
            assertFalse(requireNotNull(main{app.widgets.current}).requested)
            main{app.widgets.invalidate()}
            waitFor{main{views.filterKeys{it!=WidgetStyle.VINYL}.values.all{(_,v)->v.findViewById<TextView>(R.id.widget_title)?.text?.toString()=="尚未播放"}}}
            File(out,"styles.json").writeText("""{"distinctProviders":3,"twoSquareProvidersTarget2x2":true,"sharedPausedState":true,"removeOneKeepsOthersUpdating":true,"accountInvalidationClearsAll":true,"snapshotUnchanged":true,"actualLauncherPinned":false}""")
        } finally {
            main{owned.forEach(host::deleteAppWidgetId);host.stopListening();app.widgets.accountChanged(owner)}
        }
    }
    private fun silence():ByteArray {
        val size=16000*2*120
        return ByteBuffer.allocate(size+44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(size+36);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1)
            putInt(16000);putInt(32000);putShort(2);putShort(16);put("data".toByteArray());putInt(size)
        }.array()
    }
    private class Body(private val bytes:ByteArray):AudioResponse {
        var position=0
        override val status=200;override val start=0L;override val total=bytes.size.toLong();override val etag:String?=null
        override suspend fun read(buffer:ByteArray):Int {if(position==bytes.size)return -1;val n=minOf(buffer.size,bytes.size-position);bytes.copyInto(buffer,0,position,position+n);position+=n;return n}
        override fun close() {}
    }
    @Test fun widgetAppSystemSessionAndColdResumeShareOneSilentPlayerAndRejectStaleActions() = runBlocking {
        val realAccounts=app.accounts;val owner=app.accountKey;val settings=app.settings.current()
        val marker=File(app.noBackupFilesDir,"m6g-test-preserve.json")
        check(!marker.exists());marker.writeText(Json.encodeToString(settings))
        val accountsField=ListenApplication::class.java.getDeclaredField("accounts").apply{isAccessible=true}
        val downloadsField=ListenApplication::class.java.getDeclaredField("downloads\$delegate").apply{isAccessible=true}
        val downloadsDelegate=downloadsField.get(app)
        val accounts=Accounts();val directory=File(app.cacheDir,"m6g-audio-fixture").apply{mkdirs()}
        val bytes=silence()
        val files=AudioFiles(app,directory,{DownloadAudioSource(AudioTrack(30280,"https://fixture.bilivideo.com/audio","audio/mp4","mp4a.40.2",32000),120000)},
            {NetworkKind.UNMETERED},{_,_,_->Body(bytes)},{accounts.session.value.stamp},{_,_->})
        val host=main{AppWidgetHost(app,6036)};val widgetId=main{host.allocateAppWidgetId()}
        var future:com.google.common.util.concurrent.ListenableFuture<MediaController>?=null
        var secondFuture:com.google.common.util.concurrent.ListenableFuture<MediaController>?=null
        var controller:MediaController?=null
        try {
            main{accountsField.set(app,accounts);downloadsField.set(app,lazy{files});app.accountKey="6035";app.widgets.accountChanged("6035")}
            app.settings.update(settings.copy(externalOutputOnly=false,preferredOutputType=null,storage=StorageSettings()))
            files.enqueue(listOf(QueueEntry("source-a","BV1xx411c7mD",1,1,"静音测试 P1"),QueueEntry("source-b","BV1xx411c7mD",2,2,"静音测试 P2")),false)
            files.drain();assertTrue(files.records.value.all{it.phase==DownloadPhase.COMPLETE})
            val entries=files.records.value.map{it.entry()}
            assertTrue(AppWidgetManager.getInstance(app).bindAppWidgetIdIfAllowed(widgetId,PlaybackWidget.component(app,WidgetStyle.VINYL)))
            val widget=main{host.startListening();host.createView(app,widgetId,AppWidgetManager.getInstance(app).getAppWidgetInfo(widgetId))}
            future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()}
            secondFuture=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()}
            val c=future.get(12,TimeUnit.SECONDS);controller=c;val second=secondFuture.get(12,TimeUnit.SECONDS)
            fun command(action:String,args:Bundle=Bundle.EMPTY)=main{c.sendCustomCommand(PlaybackCommands.command(action),args)}.get(12,TimeUnit.SECONDS).resultCode
            fun request(state:WidgetState,action:String,token:String=app.widgets.token)=Bundle().apply{
                putString("token",token);putString("owner",state.account);putString("mediaId",state.mediaId);putLong("version",state.version);putString("control",action)
            }
            val q=ResumeSnapshot(account="6035",entries=entries,order=entries.map{it.id},currentId=entries.first().id,positionMs=12345)
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackCommands.REPLACE_QUEUE,Bundle().apply{putString("snapshot",SnapshotCodec.encode(q));putBoolean("play",false)}))
            waitFor{main{widget.findViewById<TextView>(R.id.widget_title)?.text?.toString()==entries.first().title}}
            assertFalse(main{c.playWhenReady});assertEquals(12345L,main{c.currentPosition})
            main{widget.findViewById<View>(R.id.widget_play).performClick()}
            waitFor{main{c.isPlaying&&second.isPlaying}}
            waitFor{app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.count{it.notification.contentIntent!=null}==1}
            i.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.MEDIA_CONTENT_CONTROL)
            val system=app.getSystemService(android.media.session.MediaSessionManager::class.java).getActiveSessions(null).single{it.packageName==app.packageName}
            system.transportControls.pause()
            waitFor{main{!c.playWhenReady&&!second.playWhenReady&&app.widgets.current?.requested==false}}
            assertEquals(main{c.mediaMetadata.title?.toString()},requireNotNull(system.metadata).getString(android.media.MediaMetadata.METADATA_KEY_TITLE))
            val previous=requireNotNull(main{app.widgets.current})
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackCommands.WIDGET_CONTROL,request(previous,"next")))
            waitFor{main{c.currentMediaItem?.mediaId==entries.last().id&&second.currentMediaItem?.mediaId==entries.last().id}}
            assertFalse(main{c.playWhenReady})
            assertNotEquals(SessionResult.RESULT_SUCCESS,command(PlaybackCommands.WIDGET_CONTROL,request(previous,"play")))
            val current=requireNotNull(main{app.widgets.current})
            assertNotEquals(SessionResult.RESULT_SUCCESS,command(PlaybackCommands.WIDGET_CONTROL,request(current.copy(version=current.version+10),"play")))
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackCommands.FLUSH))
            val snapshot=requireNotNull(app.stores.load("6035"))
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackCommands.CLEAR))
            assertFalse(main{c.playWhenReady});assertEquals(0,main{c.mediaItemCount})
            val restored=current.copy(version=snapshot.queue.queueVersion,positionMs=snapshot.queue.positionMs,restored=true)
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackCommands.WIDGET_CONTROL,request(restored,"play")))
            waitFor{main{c.isPlaying}};assertTrue(main{c.currentPosition}>=snapshot.queue.positionMs)
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackCommands.WIDGET_CONTROL,request(requireNotNull(main{app.widgets.current}),"pause")))
            waitFor{main{!c.playWhenReady}}
            val stale=request(requireNotNull(main{app.widgets.current}),"play")
            main{app.widgets.invalidate();accounts.session.value=AccountSession(SessionStamp("guest",2),SessionStatus.GUEST);app.widgets.accountChanged("guest")}
            assertNotEquals(SessionResult.RESULT_SUCCESS,command(PlaybackCommands.WIDGET_CONTROL,stale))
            waitFor{main{app.widgets.current?.account!="6035"}}
            assertFalse(main{c.playWhenReady})
            assertFalse(main{app.widgets.current?.mediaId in entries.map{it.id}})
            File(out,"service.json").writeText("""{"widgetPendingIntentPlayed":true,"silentLocalFixture":true,"appControllersAgree":true,"platformMediaSessionPauseAgrees":true,"notificationUsesSameSession":true,"pausedNextPreservesPause":true,"staleItemVersionAccountRejected":true,"emptyServiceResumeAtSavedPosition":true,"autoplayOnAddition":false,"remoteWrites":0,"actualLauncherPinned":false,"lockScreenVisualTested":false}""")
        } finally {
            controller?.let{c->runCatching{main{c.pause();c.sendCustomCommand(PlaybackCommands.command(PlaybackCommands.CLEAR),Bundle.EMPTY)}.get(10,TimeUnit.SECONDS)}}
            main{future?.let(MediaController::releaseFuture);secondFuture?.let(MediaController::releaseFuture);host.deleteAppWidgetId(widgetId);host.stopListening();accountsField.set(app,realAccounts);downloadsField.set(app,downloadsDelegate);app.accountKey=owner;app.widgets.accountChanged(realAccounts.session.value.stamp.account)}
            i.uiAutomation.dropShellPermissionIdentity()
            app.settings.update(settings)
            marker.delete()
            app.database.listenDao().clearPlayback("6035");app.database.listenDao().clearHistory("6035")
            directory.deleteRecursively()
        }
    }
}
