package app.bililisten.platform

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import android.view.InputDevice
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import app.bililisten.shared.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

@androidx.media3.common.util.UnstableApi @RunWith(AndroidJUnit4::class)
class LiveUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private var activity:MainActivity?=null
    private var posterFile:File?=null
    private val out get()=File(app.filesDir,"m6h-evidence").apply{mkdirs()}
    private fun nodes(label:String):List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun visit(n:AccessibilityNodeInfo?){if(n==null||!n.refresh()||!n.isVisibleToUser)return;if(n.text?.toString()?.contains(label)==true||n.contentDescription?.toString()==label)result+=n;for(k in 0 until n.childCount)visit(n.getChild(k))}
        visit(i.uiAutomation.rootInActiveWindow);return result
    }
    private fun waitFor(label:String){val until=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<until){if(nodes(label).isNotEmpty())return;Thread.sleep(50)};error("Missing $label")}
    private fun photo(name:String){Thread.sleep(600);i.uiAutomation.takeScreenshot()?.let{b->File(out,"fixture-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun swipeCard(bounds:Rect) {
        val down=android.os.SystemClock.uptimeMillis();val start=bounds.left+bounds.width()*.8f;val end=bounds.left+bounds.width()*.2f;val y=bounds.top+bounds.height()*.4f
        fun event(action:Int,x:Float){val e=MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;assertTrue(i.uiAutomation.injectInputEvent(e,true));e.recycle()}
        event(MotionEvent.ACTION_DOWN,start)
        for(k in 1..16){Thread.sleep(25);event(MotionEvent.ACTION_MOVE,start+(end-start)*k/16)}
        event(MotionEvent.ACTION_UP,end);Thread.sleep(750)
    }
    private fun poster():String {
        val bitmap=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply{
            drawColor(0xff1f5655.toInt());val p=Paint(Paint.ANTI_ALIAS_FLAG)
            p.color=0xffc3e6dc.toInt();drawRect(36f,28f,290f,332f,p)
            p.color=0xffeb909c.toInt();drawCircle(446f,180f,114f,p)
            p.color=0xff1f5655.toInt();drawCircle(446f,180f,52f,p)
            p.textSize=38f;drawText("LIVE",68f,102f,p);p.textSize=24f;drawText("MUSIC SESSION",68f,142f,p)
        }
        val url="https://i0.hdslb.com/m6h-fiction-${java.util.UUID.randomUUID()}.png"
        val key=MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(""){"%02x".format(it)}
        val file=File(app.cacheDir,"covers-v1/$key");check(!file.exists());file.parentFile?.mkdirs()
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();posterFile=file
        check(runBlocking{app.covers.load(url)}!=null)
        return url
    }
    private fun start()=(i.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity).also{activity=it}
    private fun fixture(a:MainActivity,theme:Theme,font:Float,content:@Composable ()->Unit){i.runOnMainSync{a.setContent{ListenTheme(theme){val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,font)){Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=18.dp)){content()}}}}}}}
    @Test fun roomAndPopularityRankingFitLightDarkAndLargeFontWithoutAutoplay() {
        val a=start();var actions=0;val cover=poster()
        val room=LiveRoom(6,77,8,1,"周末音乐现场 · 示例直播间","示例主播",cover,"音乐")
        val s=ScreenState(accountChecked=true,liveRoom=room,liveStreams=listOf(LiveStream("https://fixture.bilivideo.com/live.flv","flv","avc",80)))
        try {
            for((theme,font,name)in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"large"))) {
                fixture(a,theme,font){Column(Modifier.verticalScroll(rememberScrollState())){LiveRoomContent(s,"https://live.bilibili.com/6",{},{actions++},{actions++},{actions++},{actions++},{actions++})}}
                waitFor("收听直播");waitFor("当前来源包含视频数据");waitFor("正在直播");photo("room-$name")
                val bounds=Rect();nodes("收听直播").first().getBoundsInScreen(bounds);assertTrue(bounds.width()>0&&bounds.height()>0)
                fixture(a,theme,font){HomeLiveRanking(s.copy(liveRankings=(1L..5).map{LiveRanking(it,8,"现场音乐 $it · 示例长标题","示例主播",cover,123456,"音乐")}),{actions++},{actions++})}
                waitFor("直播人气排行 · 前 5");photo("ranking-$name")
            }
            assertEquals(0,actions)
        }finally{i.runOnMainSync{a.finish()};posterFile?.delete()}
    }
    @Test fun liveControlsExposeOnlyAnActualWindowAndCurrentPlayingIdentity() {
        val a=start();val state=mutableStateOf(ScreenState(isLive=true,playingTitle="正在收听的房间",liveRoom=LiveRoom(99,99,1,1,"另一个被查看的房间","其他主播"),
            liveExperience=LiveExperience(77,"正在收听的房间","示例主播",poster(),LivePhase.PAUSED,StreamKind.MIXED)))
        try {
            fixture(a,Theme.LIGHT,1f){PlayerPage(state.value,null,null,20000,100000,Modifier.fillMaxSize(),{},{},{},{},{},{},{},{},{},{},{},{},{},{})}
            waitFor("正在收听的房间");waitFor("回到当前直播");assertTrue(nodes("播放进度").isEmpty());assertTrue(nodes("另一个被查看的房间").isEmpty());photo("player-no-window")
            i.runOnMainSync{state.value=state.value.copy(canSeek=true,liveExperience=state.value.liveExperience.copy(canSeekWindow=true,windowMs=60000))}
            waitFor("可回退窗口");waitFor("播放进度");photo("player-window")
        }finally{i.runOnMainSync{a.finish()};posterFile?.delete()}
    }
    @Test fun liveRankingSharesVideoCarouselGeometryAndSwipesToTheCorrectRoomWithoutAutoplay() {
        val a=start();val cover=poster();val opened=mutableListOf<Long>();var videoClicks=0
        val rows=(1L..5).map{LiveRanking(it,8,"现场音乐 $it · 示例长标题","示例主播",cover,123456,"音乐")}
        val videos=(1..5).map{Recommendation("fixture-video-$it","示例音乐视频 $it",null,cover,"示例 UP",180)}
        try {
            for((theme,font,name)in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.6f,"large"))) {
                fixture(a,theme,font){HeroCarousel(videos,HomeCategory.MUSIC){videoClicks++}}
                waitFor("收听推荐：示例音乐视频 1");waitFor("1 / 5")
                val videoBounds=Rect();nodes("收听推荐：示例音乐视频 1").first().getBoundsInScreen(videoBounds);photo("video-hero-$name")
                fixture(a,theme,font){HomeLiveRanking(ScreenState(liveRankings=rows),{opened+=it},{error("Unexpected refresh")})}
                waitFor("直播第 1 名：现场音乐 1 · 示例长标题，示例主播");waitFor("1 / 5")
                val liveBounds=Rect();nodes("直播第 1 名：现场音乐 1 · 示例长标题，示例主播").first().getBoundsInScreen(liveBounds)
                assertEquals(videoBounds,liveBounds);assertEquals(0,videoClicks);photo("ranking-$name")
                val before=opened.size;swipeCard(liveBounds);waitFor("2 / 5")
                waitFor("直播第 2 名：现场音乐 2 · 示例长标题，示例主播");assertEquals(before,opened.size)
                if(name=="large")photo("ranking-next")
                var node:AccessibilityNodeInfo?=nodes("直播第 2 名：现场音乐 2 · 示例长标题，示例主播").first()
                while(node!=null&&!node.isClickable)node=node.parent
                assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true);i.waitForIdleSync()
                assertEquals(before+1,opened.size);assertEquals(2L,opened.last())
            }
        }finally{i.runOnMainSync{a.finish()};posterFile?.delete()}
    }
}
