package app.bililisten.platform

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import app.bililisten.shared.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@androidx.media3.common.util.UnstableApi @RunWith(AndroidJUnit4::class)
class SevenIssuesUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    @Test fun musicPagingWaitsForHorizontalBrowsingAndAppendsWithoutResizing() {
        val a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun rows(start:Int)=List(12){index->PopularMusic("BV"+(start+index).toString().padStart(10,'0'),"歌曲 ${start+index}","","UP",2000000)}
        val display=mutableStateOf(ScreenState(popularMusic=rows(1),popularMusicLoaded=true))
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val bounds=java.util.concurrent.atomic.AtomicReference<androidx.compose.ui.geometry.Rect>()
        i.runOnMainSync {a.setContent {ListenTheme(Theme.LIGHT) {
            val scope=rememberCoroutineScope()
            androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(8.dp)) {
                    Box(Modifier.onGloballyPositioned{bounds.set(it.boundsInWindow())}) {
                        HomePopularMusic(display.value,loadMore={
                            if(!display.value.popularMusicMoreBusy) {
                                calls.incrementAndGet();display.value=display.value.copy(popularMusicMoreBusy=true)
                                scope.launch {
                                    kotlinx.coroutines.delay(300)
                                    display.value=display.value.copy(popularMusic=display.value.popularMusic+rows(display.value.popularMusic.size+1),popularMusicMoreBusy=false)
                                }
                            }
                        }){}
                    }
                }
            }
        }}}
        fun swipe(horizontal:Boolean) {
            val rect=requireNotNull(bounds.get());val down=android.os.SystemClock.uptimeMillis()
            val count=16
            for(step in 0..count) {
                val fraction=step.toFloat()/count
                val x=rect.left+rect.width*(if(horizontal).85f-.7f*fraction else .5f)
                val y=rect.top+rect.height*(if(horizontal).55f else .8f-.6f*fraction)
                val action=when(step){0->android.view.MotionEvent.ACTION_DOWN;count->android.view.MotionEvent.ACTION_UP;else->android.view.MotionEvent.ACTION_MOVE}
                val event=android.view.MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0).apply{source=android.view.InputDevice.SOURCE_TOUCHSCREEN}
                assertTrue(i.uiAutomation.injectInputEvent(event,true));event.recycle();Thread.sleep(25)
            }
            i.waitForIdleSync();Thread.sleep(400)
        }
        try {
            i.waitForIdleSync();Thread.sleep(700);assertEquals(0,calls.get());val before=requireNotNull(bounds.get())
            swipe(false);assertEquals(0,calls.get())
            repeat(24){if(calls.get()<3)swipe(true)}
            assertTrue("Horizontal browsing should continue past thirty items",calls.get()>=3)
            assertTrue(display.value.popularMusic.size>=48);assertEquals(display.value.popularMusic.size,display.value.popularMusic.distinctBy{it.key}.size)
            assertEquals(before.height,requireNotNull(bounds.get()).height,.01f)
            assertEquals(rows(1),display.value.popularMusic.take(12))
        }finally{i.runOnMainSync {a.finish()}}
    }
    @Test fun allTvEntryPointsUseSamePixelsAtExistingSizes() {
        val a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val theme=mutableStateOf(Theme.LIGHT)
        val sizes=listOf(14,18,23,30,44,78,110)
        val bounds=java.util.concurrent.ConcurrentHashMap<Pair<Int,Int>,androidx.compose.ui.geometry.Rect>()
        val folder=File(i.targetContext.filesDir,"notification-controls-evidence").apply{mkdirs()}
        i.runOnMainSync {a.setContent {ListenTheme(theme.value) {
            val density=LocalDensity.current
            androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    for(size in sizes)Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        val exactSize=with(density){size.dp.roundToPx().toDp()}
                        for(entry in 0..2) {
                            val modifier=Modifier.size(exactSize).onGloballyPositioned{bounds[size to entry]=it.boundsInWindow()}
                            val color=androidx.compose.material3.MaterialTheme.colorScheme.primary
                            when(entry){0->BilibiliTvIcon(modifier,color);1->Glyph(Mark.TV,modifier,color,filled=true);else->Tv(modifier)}
                        }
                    }
                }
            }
        }}}
        try {
            for(value in listOf(Theme.LIGHT,Theme.DARK)) {
                i.runOnMainSync {theme.value=value};i.waitForIdleSync();Thread.sleep(350)
                val screenshot=i.uiAutomation.takeScreenshot() ?: error("Missing screenshot")
                try {
                    for(size in sizes) {
                        val crops=(0..2).map{entry->
                            val rect=requireNotNull(bounds[size to entry])
                            android.graphics.Bitmap.createBitmap(screenshot,rect.left.roundToInt(),rect.top.roundToInt(),rect.width.roundToInt(),rect.height.roundToInt())
                        }
                        try {
                            crops.forEachIndexed{entry,bitmap->File(folder,"unified-tv-$value-$size-$entry.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
                            for(entry in 1..2) {
                                val expected=IntArray(crops[0].width*crops[0].height)
                                val actual=IntArray(expected.size)
                                crops[0].getPixels(expected,0,crops[0].width,0,0,crops[0].width,crops[0].height)
                                crops[entry].getPixels(actual,0,crops[entry].width,0,0,crops[entry].width,crops[entry].height)
                                var delta=0L;var strong=0
                                for(pixel in expected.indices) {
                                    val differences=listOf(16,8,0).map{shift->kotlin.math.abs(((expected[pixel] ushr shift) and 255)-((actual[pixel] ushr shift) and 255))}
                                    delta+=differences.sum();if(differences.max()>48)strong++
                                }
                                val mean=delta.toDouble()/(expected.size*3)
                                println("TV pixels $value / $size dp / entry $entry: mean=$mean, strong=$strong/${expected.size}, bounds=${bounds[size to entry]}")
                                // GPU antialiasing can differ at translated vector edges.
                                assertTrue("TV geometry/color mismatch at $size dp / $value / entry $entry",mean<=3 && strong<=expected.size*.02+2)
                            }
                            val pixels=IntArray(crops[0].width*crops[0].height)
                            crops[0].getPixels(pixels,0,crops[0].width,0,0,crops[0].width,crops[0].height)
                            assertTrue("TV cannot be blank",pixels.count{it!=pixels[0]}>10)
                        }finally{crops.forEach{it.recycle()}}
                    }
                }finally{screenshot.recycle()}
            }
        }finally{i.runOnMainSync {a.finish()}}
    }
    @Test fun mineBilibiliTvUsesOriginalGeometryAndStableThemeBounds() {
        val a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val theme=mutableStateOf(Theme.LIGHT)
        val scale=mutableFloatStateOf(1f)
        val smallBounds=java.util.concurrent.atomic.AtomicReference<androidx.compose.ui.geometry.Rect>()
        val largeBounds=java.util.concurrent.atomic.AtomicReference<androidx.compose.ui.geometry.Rect>()
        val folder=File(i.targetContext.filesDir,"notification-controls-evidence").apply{mkdirs()}
        i.runOnMainSync {a.setContent {ListenTheme(theme.value) {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale.floatValue)) {
                androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(28.dp)) {
                        Row(Modifier.onGloballyPositioned{smallBounds.set(it.boundsInWindow())},horizontalArrangement=Arrangement.spacedBy(30.dp)) {
                            Glyph(Mark.TV,Modifier.size(23.dp),muted())
                            Glyph(Mark.TV,Modifier.size(23.dp),androidx.compose.material3.MaterialTheme.colorScheme.primary,filled=true)
                        }
                        Row(Modifier.onGloballyPositioned{largeBounds.set(it.boundsInWindow())},horizontalArrangement=Arrangement.spacedBy(30.dp)) {
                            Glyph(Mark.TV,Modifier.size(72.dp),muted())
                            Tv(Modifier.size(72.dp))
                        }
                    }
                }
            }
        }}}
        try {
            for((name,value,font) in listOf(Triple("tv-light",Theme.LIGHT,1f),Triple("tv-dark-large",Theme.DARK,1.3f))) {
                i.runOnMainSync {theme.value=value;scale.floatValue=font};i.waitForIdleSync();Thread.sleep(350)
                val bitmap=i.uiAutomation.takeScreenshot() ?: error("Missing screenshot")
                File(folder,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                val small=requireNotNull(smallBounds.get());val large=requireNotNull(largeBounds.get())
                assertTrue(small.width>0 && large.width>small.width)
                val left=(minOf(small.left,large.left)-24).toInt().coerceAtLeast(0)
                val top=(minOf(small.top,large.top)-24).toInt().coerceAtLeast(0)
                val right=(maxOf(small.right,large.right)+24).toInt().coerceAtMost(bitmap.width)
                val bottom=(maxOf(small.bottom,large.bottom)+24).toInt().coerceAtMost(bitmap.height)
                val preview=android.graphics.Bitmap.createBitmap(bitmap,left,top,right-left,bottom-top)
                try {File(folder,"$name-preview.png").outputStream().use{preview.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{preview.recycle();bitmap.recycle()}
            }
        }finally{i.runOnMainSync {a.finish()}}
    }
    @Test fun syntheticWordHighlightsRenderWithoutPlaybackOrAccountWrites() {
        assertFalse(i.targetContext.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val a=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val candidate=LyricsCandidate(1,"示例歌曲","示例歌手","示例专辑",60000)
        val cues=listOf(SubtitleCue(1000,5000,"一起听见心中的旋律"),SubtitleCue(5000,10000,"下一句只有逐行时间"))
        val words=cues[0].content.mapIndexed{index,char->LyricWord(1000L+index*400,null,char.toString())}
        val position=mutableLongStateOf(1000)
        val font=mutableFloatStateOf(1f)
        i.runOnMainSync {a.setContent {ListenTheme(Theme.LIGHT) {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,font.floatValue)) {
                val state=ScreenState(positionMs=position.longValue,durationMs=60000,canSeek=true,playingTitle="示例视频",
                    lyrics=LyricsView(VideoRef("BV1xx411c7mD",1,1),LyricsStatus.READY,document=LyricsDocument(candidate,cues.joinToString("\n"){it.content},cues,mapOf(0 to words)),confirmed=true,message="示例逐字时间"))
                LyricsPage(state,null,Modifier.safeDrawingPadding(),{},{},{},{},{},{},{_,_->},{},{},{})
            }
        }}}
        val folder=File(i.targetContext.filesDir,"seven-issues-evidence").apply{mkdirs()}
        try {
            for((name,value,scale) in listOf(Triple("word-first",1000L,1f),Triple("word-middle",2200L,1f),Triple("word-large",2200L,1.3f))) {
                i.runOnMainSync {position.longValue=value;font.floatValue=scale};i.waitForIdleSync();Thread.sleep(450)
                val bitmap=i.uiAutomation.takeScreenshot() ?: error("Missing screenshot")
                File(folder,"$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
            assertEquals(ImmersivePink,lyricLineText(cues[0],words,2200,true,true).spanStyles[3].item.color)
            assertNotEquals(ImmersivePink,lyricLineText(cues[0],words,2200,true,true).spanStyles[4].item.color)
        }finally{i.runOnMainSync {a.finish()}}
    }
}
