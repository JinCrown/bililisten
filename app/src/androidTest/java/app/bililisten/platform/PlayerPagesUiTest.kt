package app.bililisten.platform

import android.content.Intent
import android.graphics.Rect
import android.view.MotionEvent
import android.view.InputDevice
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import app.bililisten.shared.*
import app.bililisten.playback.PlaybackPositionTicker
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated player callbacks: fixture gestures never play, seek or change the user's saved queue. */
@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4::class)
class PlayerPagesUiTest {
    private val i = InstrumentationRegistry.getInstrumentation()
    private val app = i.targetContext.applicationContext as ListenApplication
    private val entry = QueueEntry("preview", "BV1xx411c7mD", 1, 1, "播放器界面预览")
    private val state = mutableStateOf(ScreenState(queue=listOf(entry), currentId=entry.id,
        playingTitle=entry.title, canSeek=true, positionMs=1500, durationMs=5000,
        subtitles=SubtitleView(VideoRef(entry.bvid,1,1), SubtitleStatus.READY,
            cues=(0 until 20).map { SubtitleCue(it*250L,(it+1)*250L,"示例字幕内容 ${it+1}") })))
    private val page = mutableStateOf("player")
    private val destination = mutableStateOf<String?>(null)
    private var seeks = 0
    private var toggles = 0
    private var routeChanges = 0
    @Volatile private var chromeFraction = 0f

    private fun find(label:String):AccessibilityNodeInfo? {
        val bounds=Rect()
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(n==null || !n.refresh())return null
            n.getBoundsInScreen(bounds)
            if(n.isVisibleToUser && (n.text?.toString()==label || n.contentDescription?.toString()==label))return n
            for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it}
            return null
        }
        return visit(i.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(label:String) {
        val until=System.currentTimeMillis()+12000
        while(System.currentTimeMillis()<until){if(find(label)!=null)return;Thread.sleep(100)}
        error("Missing $label")
    }
    private fun click(label:String) {
        waitFor(label);var n=find(label)
        while(n!=null && !n.isClickable)n=n.parent
        assertTrue("Clickable $label", n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
        i.waitForIdleSync();Thread.sleep(350)
    }
    private fun swipe(fromX:Float,toX:Float,y:Float) {
        val down=android.os.SystemClock.uptimeMillis()
        fun event(action:Int,x:Float) {
            val e=MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0)
            e.source=InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(i.uiAutomation.injectInputEvent(e,true));e.recycle()
        }
        event(MotionEvent.ACTION_DOWN,fromX)
        for(k in 1..16){Thread.sleep(25);event(MotionEvent.ACTION_MOVE,fromX+(toX-fromX)*k/16)}
        event(MotionEvent.ACTION_UP,toX);Thread.sleep(750)
    }
    private fun horizontal(right:Boolean) {
        val r=Rect();i.uiAutomation.rootInActiveWindow.getBoundsInScreen(r)
        swipe(r.left+r.width()*(if(right).18f else .82f),r.left+r.width()*(if(right).82f else .18f),r.top+r.height()*.28f)
    }
    private fun shot(name:String) {
        val folder=File(app.filesDir,"player-pages-evidence").apply{mkdirs()}
        i.uiAutomation.takeScreenshot()?.let{bmp->File(folder,"$name.png").outputStream().use{bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()}
    }
    private fun start():MainActivity {
        assertFalse(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val a=i.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        waitFor("首页");return a
    }
    @OptIn(ExperimentalMaterial3Api::class)
    private fun fixture(a:MainActivity,theme:Theme=Theme.LIGHT,font:Float=1f) {
        i.runOnMainSync {a.setContent {
            ListenTheme(theme) {
                val density=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,font)) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Text("界面预览 · 示例数据")
                            PlayerWorkspace(state.value,null,entry,page.value,{page.value=it;routeChanges++},
                                Modifier.weight(1f).then(if(font>1f)Modifier.width(320.dp) else Modifier.fillMaxWidth()).background(androidx.compose.ui.graphics.Color(0xFF42344F)),back={},creator={},timer={destination.value="timer"},lyrics={destination.value="lyrics"},audio={destination.value="audio"},player={
                                PlayerPage(state.value,null,entry,state.value.positionMs,5000,Modifier,{},
                                    {toggles++},{seeks++},{},{},{},{},{},{page.value="subtitles"},{},{},
                                    {destination.value="timer"},{},{destination.value="audio"},
                                    {destination.value="lyrics"},speed={destination.value="speed"})
                            },subtitles={
                                SubtitlePage(state.value,null,Modifier,{page.value="player"},{toggles++},{seeks++},
                                    {},{},{},{},{},{},{})
                            },details={Text("视频详情预览")},chrome={chromeFraction=it})
                        }
                        when(destination.value) {
                            "timer","speed" -> ModalBottomSheet({destination.value=null},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
                                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                                    if(destination.value=="timer")SleepTimerPanel(state.value){}
                                    else PlaybackSpeedPanel(state.value){state.value=state.value.copy(speed=it)}
                                }
                            }
                        }
                    }
                }
            }
        }}
        waitFor("播放更多操作")
    }
    private fun playerBackgroundIsOpaque() {
        val bitmap=i.uiAutomation.takeScreenshot()!!
        try {
            val pixel=bitmap.getPixel(10,bitmap.height/5)
            assertTrue("Player background must cover the dark reader underneath",
                android.graphics.Color.red(pixel)>190 && android.graphics.Color.green(pixel)>190 && android.graphics.Color.blue(pixel)>190)
        } finally {bitmap.recycle()}
    }
    private fun settledChrome(value:Float) {
        val end=System.currentTimeMillis()+3000
        while(kotlin.math.abs(chromeFraction-value)>.001f && System.currentTimeMillis()<end)Thread.sleep(50)
        assertEquals(value,chromeFraction,.001f)
    }
    @Test fun sampledCueBoundaryHighlightsBothModesWithoutWaitingForScrollOrFade() {
        val a=start()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        try {
            for(lineMode in listOf(false,true)) {
                i.runOnMainSync {
                    state.value=state.value.copy(positionMs=9950,durationMs=20000,
                        settings=state.value.settings.copy(subtitleLineMode=lineMode),
                        subtitles=state.value.subtitles.copy(cues=listOf(
                            SubtitleCue(1000,5000,"示例上一句"),SubtitleCue(10000,18000,"示例下一句"))))
                    a.setContent {ListenTheme(Theme.LIGHT) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Text("界面预览 · 示例数据")
                            SubtitlePage(state.value,null,Modifier.weight(1f),{},{},{},{},{},{},{},{},{},{})
                        }
                    }}
                }
                waitFor("跟随字幕");Thread.sleep(250)
                val start=android.os.SystemClock.uptimeMillis()
                lateinit var ticker:PlaybackPositionTicker
                i.runOnMainSync {
                    ticker=PlaybackPositionTicker(scope) {
                        state.value=state.value.copy(positionMs=9950+android.os.SystemClock.uptimeMillis()-start)
                    }
                    ticker.update(true,true)
                }
                var seen=false
                while(android.os.SystemClock.uptimeMillis()-start<350) {
                    if(find("当前字幕")?.text?.toString()=="示例下一句") {seen=true;break}
                    Thread.sleep(10)
                }
                val elapsed=android.os.SystemClock.uptimeMillis()-start
                i.runOnMainSync{ticker.update(false,true)}
                assertTrue("Cue must highlight within 300ms of its start in lineMode=$lineMode (observed ${elapsed}ms)",seen && elapsed<=350)
                Thread.sleep(150)
                if(lineMode)assertNull(find("示例上一句"))
                shot(if(lineMode)"fixture-fast-line" else "fixture-fast-scroll")
                File(app.filesDir,"player-pages-evidence/latency-${if(lineMode)"line" else "scroll"}.json")
                    .writeText("""{"fixture":true,"sampledPosition":true,"cueStartsAfterMs":50,"highlightObservedAfterMs":$elapsed,"userPlaybackCommands":false}""")
            }
        } finally {scope.cancel();i.runOnMainSync{a.finish()}}
    }
    @Test fun subtitlePausesKeepRowsStableWithoutExtendingActualTiming() {
        val a=start()
        val cues=(0 until 24).map { k ->
            SubtitleCue(2000+k*5000L,3200+k*5000L,
                if(k==3)"示例长句字幕：停顿时文字保持原来的位置，不缩小也不重新换行" else "示例停顿字幕 ${k+1}")
        }
        fun position(value:Long) {
            i.runOnMainSync{state.value=state.value.copy(positionMs=value)}
            i.waitForIdleSync();Thread.sleep(900)
        }
        fun row(index:Int):Rect {
            waitFor(cues[index].content)
            return Rect().also{find(cues[index].content)!!.getBoundsInScreen(it)}
        }
        try {
            i.runOnMainSync{state.value=state.value.copy(positionMs=0,durationMs=125000,
                subtitles=state.value.subtitles.copy(cues=cues))}
            i.runOnMainSync{a.setContent {ListenTheme(Theme.LIGHT) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Text("界面预览 · 示例数据")
                    SubtitlePage(state.value,null,Modifier.weight(1f),{},{toggles++},{seeks++},{},{},{},{},{},{},{})
                }
            }}}
            waitFor("滚动字幕")
            assertNull(find("当前字幕"));assertNull(find("跟随字幕"))
            position(18000);waitFor("当前字幕")
            val activeRow=row(3);val nextRow=row(4);shot("fixture-pause-active")
            position(20000)
            assertNull(find("当前字幕"));waitFor("跟随字幕")
            assertNull(find("当前时段暂无字幕"))
            assertEquals("Gap must not resize or move the wrapped previous row",activeRow,row(3))
            assertEquals("Gap must not move the next row",nextRow,row(4));shot("fixture-pause-gap")
            position(21999);assertEquals(activeRow,row(3));assertEquals(nextRow,row(4))
            position(22500);waitFor("当前字幕")
            assertTrue("Next cue advances the list only when its start time arrives",row(4).top<nextRow.top)
            assertEquals("Changing emphasis keeps the next cue height",nextRow.height(),row(4).height())
            shot("fixture-pause-next")
            position(18000);assertEquals("Seeking backward follows the correct sentence",activeRow,row(3))
            position(117500);val last=row(23)
            position(124000);assertNull(find("当前字幕"));assertEquals("Ending pause stays stable",last,row(23))
            i.runOnMainSync{state.value=state.value.copy(positionMs=18000,
                settings=state.value.settings.copy(subtitleLineMode=true))}
            waitFor("当前字幕");Thread.sleep(900)
            val line=row(3);shot("fixture-line-active")
            position(20000);assertNull(find("当前字幕"));waitFor("跟随字幕")
            assertNull(find("当前时段暂无字幕"));assertEquals("Line-mode pause keeps the last sentence in place",line,row(3))
            shot("fixture-line-gap")
            position(22500);waitFor("当前字幕");assertNull(find(cues[3].content));row(4)
            shot("fixture-line-next")
            position(0);assertNull(find("当前字幕"));assertNull(find("跟随字幕"));assertNull(find("当前时段暂无字幕"))
            assertNull(find(cues[0].content))
            assertEquals(0,seeks);assertEquals(0,toggles)
        } finally {i.runOnMainSync{a.finish()}}
    }
    @Test fun leftSwipeRightReturnButtonAndSeekGestureAreIndependent() {
        val a=start()
        try {
            fixture(a);waitFor("切换到字幕");playerBackgroundIsOpaque();settledChrome(0f)
            assertNull(find("只听声音"));assertNull(find("更多"))
            val progress=find("播放进度")!!;val r=Rect();progress.getBoundsInScreen(r)
            swipe(r.left+r.width()*.3f,r.left+r.width()*.65f,r.centerY().toFloat())
            assertTrue("Slider still seeks",seeks>0);assertEquals("player",page.value);assertEquals(0,routeChanges)
            val sliderSeeks=seeks
            horizontal(false);waitFor("字幕");waitFor("当前字幕");assertEquals("subtitles",page.value);settledChrome(1f);shot("fixture-subtitles")
            horizontal(true);waitFor("播放");assertEquals("player",page.value);playerBackgroundIsOpaque();settledChrome(0f)
            click("切换到字幕");waitFor("字幕");click("切换到播放");waitFor("播放更多操作")
            assertEquals("player",page.value);assertEquals(0,toggles)
            playerBackgroundIsOpaque();settledChrome(0f)
            assertEquals("Swiping subtitle rows must not seek",sliderSeeks,seeks)
            i.runOnMainSync{state.value=state.value.copy(isLive=true)}
            waitFor("直播");horizontal(false);assertEquals("player",page.value)
        } finally {i.runOnMainSync{a.finish()}}
    }
    @Test fun threeMoreChoicesAndSeparateSpeedWorkAcrossThemesAndLargeFont() {
        val a=start()
        try {
            for((theme,font,name) in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"large"))) {
                i.runOnMainSync{destination.value=null;page.value="player";state.value=state.value.copy(speed=1f,isLive=false)}
                fixture(a,theme,font);shot("fixture-player-$name")
                click("播放更多操作");waitFor("定时");waitFor("歌词");waitFor("音质")
                assertNull(find("均衡器与音效"));assertNull(find("倍速"));shot("fixture-more-$name")
                click("定时");waitFor("自定义 1—180 分钟");assertNull(find("播放倍速"));shot("fixture-timer-$name")
                i.runOnMainSync{destination.value=null};Thread.sleep(500)
                click("播放更多操作");click("歌词");assertEquals("lyrics",destination.value)
                click("播放更多操作");click("音质");assertEquals("audio",destination.value)
                click("1.0 倍速");waitFor("播放倍速");assertNull(find("自定义 1—180 分钟"));click("0.75x")
                assertEquals(.75f,state.value.speed);shot("fixture-speed-$name")
            }
            assertEquals(0,toggles);assertEquals(0,seeks)
        } finally {i.runOnMainSync{a.finish()}}
    }
}
