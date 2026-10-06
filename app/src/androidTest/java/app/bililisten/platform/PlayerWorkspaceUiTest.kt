package app.bililisten.platform

import android.content.Intent
import android.graphics.Rect
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import org.json.JSONArray
import org.json.JSONObject

/** All action callbacks are isolated; this preview cannot write accounts or change real playback. */
@androidx.media3.common.util.UnstableApi @RunWith(AndroidJUnit4::class)
class PlayerWorkspaceUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private val parts=(1..150).map{VideoPart(it.toLong(),it,if(it==17)"示例当前歌曲" else "示例歌曲 $it",204)}
    private val video=Video("BV1xx411c7mD",1,"150 首经典歌曲合集 · 示例资料",parts,author="示例音乐 UP",owner=8,duration=30600,description="这是视频简介的示例内容。",likes=1234,coins=321,favorites=99,replies=8,copyright=1)
    private val entry=QueueEntry("fixture",video.bvid,17,17,video.title+" · 示例当前歌曲")
    private val page=mutableStateOf("player")
    private val state=mutableStateOf(ScreenState(account=Account(7,"示例账号"),accountChecked=true,queue=listOf(entry),currentId=entry.id,positionMs=20000,durationMs=204000,
        videoDetails=VideoDetailsView(video.bvid,video=video,tags=listOf("音乐"),relations=VideoRelations(false,0,false),coinBalance=100.5),
        subtitles=SubtitleView(VideoRef(video.bvid,17,17),SubtitleStatus.READY,cues=(0..15).map{SubtitleCue(it*5000L,(it+1)*5000L,"示例字幕第 ${it+1} 句")})))
    private val queuePreview=mutableStateOf(false)
    private var actions=0;private var triples=0
    @Volatile private var chrome=0f
    private val menu=mutableStateOf("")
    private fun nodes(label:String):List<AccessibilityNodeInfo>{val result=mutableListOf<AccessibilityNodeInfo>();fun visit(n:AccessibilityNodeInfo?){if(n==null||!n.refresh()||!n.isVisibleToUser)return;if(n.text?.toString()==label||n.contentDescription?.toString()==label)result+=n;for(k in 0 until n.childCount)visit(n.getChild(k))};visit(i.uiAutomation.rootInActiveWindow);return result}
    private fun waitFor(label:String){val end=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<end){if(nodes(label).isNotEmpty())return;Thread.sleep(50)};error("Missing $label")}
    private fun click(label:String){waitFor(label);var n:AccessibilityNodeInfo?=nodes(label).first();while(n!=null&&!n.isClickable)n=n.parent;assertTrue(n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true);i.waitForIdleSync();Thread.sleep(350)}
    private fun event(down:Long,action:Int,x:Float,y:Float){val e=MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;assertTrue(i.uiAutomation.injectInputEvent(e,true));e.recycle()}
    private fun swipe(right:Boolean){val r=Rect();i.uiAutomation.rootInActiveWindow.getBoundsInScreen(r);val down=android.os.SystemClock.uptimeMillis();val x=r.width()*(if(right).18f else .82f);val to=r.width()*(if(right).82f else .18f);val y=r.top+r.height()*.42f;event(down,MotionEvent.ACTION_DOWN,x,y);for(k in 1..16){Thread.sleep(25);event(down,MotionEvent.ACTION_MOVE,x+(to-x)*k/16,y)};event(down,MotionEvent.ACTION_UP,to,y);Thread.sleep(800)}
    private fun hold(label:String,ms:Long){waitFor(label);val r=Rect();nodes(label).first().getBoundsInScreen(r);val down=android.os.SystemClock.uptimeMillis();event(down,MotionEvent.ACTION_DOWN,r.centerX().toFloat(),r.centerY().toFloat());Thread.sleep(ms);event(down,MotionEvent.ACTION_UP,r.centerX().toFloat(),r.centerY().toFloat());Thread.sleep(300)}
    private fun photo(name:String){val p=File(app.filesDir,"player-workspace-evidence").apply{mkdirs()};i.uiAutomation.takeScreenshot()?.let{b->File(p,"fixture-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun bounds(label:String):Rect {waitFor(label);return Rect().also{nodes(label).first().getBoundsInScreen(it)}}
    private fun headerBounds()=bounds("返回")
    private fun assertHeaderStable(before:Rect){
        val after=headerBounds()
        // Accessibility bounds can round a fractional dp edge differently by one pixel.
        assertTrue("Shared navigation stays in place",listOf(before.left-after.left,before.top-after.top,before.right-after.right,before.bottom-after.bottom).all{kotlin.math.abs(it)<=2})
    }
    private fun assertPlayerIdentity(){
        assertEquals(1,nodes("示例当前歌曲").size);assertEquals(1,nodes(video.title).size)
        assertTrue("Title belongs below the cover",bounds("当前播放标题").top>=bounds("播放封面").bottom)
        assertTrue(nodes("视频详情标题").isEmpty())
    }
    private fun assertDetailsIdentity(){
        assertEquals(1,nodes(video.title).size);assertTrue(nodes("当前播放标题").isEmpty())
        assertTrue(nodes("示例当前歌曲").isEmpty());assertTrue(nodes("播放封面").isEmpty())
        assertTrue(bounds("视频详情标题").bottom<bounds(if(triples>0)"已点赞" else "点赞").top)
        waitFor("查看 UP 主投稿");waitFor(if(triples>0)"剩余硬币 98.5" else "剩余硬币 100.5")
    }
    private fun assertSubtitleIdentityAbsent(){
        assertTrue(nodes("当前播放标题").isEmpty());assertTrue(nodes("视频详情标题").isEmpty())
        assertTrue(nodes("所属视频标题").isEmpty());assertTrue(nodes("示例当前歌曲").isEmpty())
        assertTrue(nodes(video.title).isEmpty());assertTrue(nodes("查看 UP 主投稿").isEmpty());assertTrue(nodes("播放封面").isEmpty())
        waitFor("滚动字幕");waitFor("重新读取字幕")
    }
    private fun start():MainActivity {assertFalse(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked);return i.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity}
    private fun fixture(a:MainActivity,theme:Theme=Theme.LIGHT,font:Float=1f,width:Dp?=null){i.runOnMainSync{a.setContent{ListenTheme(theme){val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,font)){
        Column(Modifier.fillMaxSize().safeDrawingPadding()){Text("界面预览 · 示例数据",fontSize=10.sp)
            if(queuePreview.value)QueuePanel(state.value.copy(metadata=mapOf(video.bvid to video),canEditQueue=true),{actions++},{actions++},{},{})
            else PlayerWorkspace(state.value,video,entry,page.value,{page.value=it},Modifier.weight(1f).then(width?.let{Modifier.width(it)} ?: Modifier.fillMaxWidth()),back={},creator={menu.value="UP 投稿入口"},timer={menu.value="定时入口"},lyrics={menu.value="歌词入口"},audio={menu.value="音质入口"},player={
                PlayerPage(state.value,video,entry,20000,204000,Modifier,{}, {actions++},{actions++},{actions++},{actions++},{queuePreview.value=true},{},{},{page.value="subtitles"},{},{},{},{},{})
            },subtitles={SubtitlePage(state.value,video,Modifier,{page.value="player"},{actions++},{actions++},{actions++},{actions++},{},{},{},{},{})},details={
                VideoDetailsPage(state.value,video,entry,20000,204000,Modifier,{}, {actions++},{actions++},{actions++},{actions++},{}, {},{},{},
                    {_,_,action,_->if(action==EngagementAction.TRIPLE){triples++;state.value=state.value.copy(videoDetails=state.value.videoDetails.copy(relations=VideoRelations(true,2,true),message="三连完成",coinBalance=98.5))}}, {},{}, {},{})
            },chrome={chrome=it})
        }
    }}}}}
    @Test fun bufferingHintNeverMovesPlayerContentAcrossThemesAndLargeFont(){val a=start();try{
        val evidence=JSONArray()
        for((theme,font,name)in listOf(Triple(Theme.LIGHT,1f,"light"),Triple(Theme.DARK,1f,"dark"),
            Triple(Theme.LIGHT,1.6f,"large"),Triple(Theme.DARK,1f,"live"))){
            val live=name=="live"
            i.runOnMainSync{page.value="player";queuePreview.value=false;state.value=state.value.copy(
                buffering=false,playRequested=true,isLive=live,canSeek=true,canPrevious=true,canNext=true,
                liveExperience=state.value.liveExperience.copy(title="示例音乐直播",anchor="示例主播"))}
            fixture(a,theme,font,if(name=="large")320.dp else null);waitFor("播放封面");i.waitForIdleSync();Thread.sleep(200)
            val labels=if(live)listOf("播放封面","当前播放标题","暂停直播","回到当前直播")
                else listOf("播放封面","当前播放标题","播放进度","后退 15 秒","1.0 倍速","前进 15 秒","暂停","上一条","下一条","播放队列")
            val baseline=labels.associateWith{bounds(it)}
            assertTrue(nodes("正在缓冲…").isEmpty());photo("buffering-$name-idle")
            var maximum=0
            for(buffering in listOf(true,false,true,false)){
                i.runOnMainSync{state.value=state.value.copy(buffering=buffering)};i.waitForIdleSync();Thread.sleep(150)
                if(buffering){waitFor("正在缓冲…");assertEquals(1,nodes("正在缓冲…").size)}
                else assertTrue(nodes("正在缓冲…").isEmpty())
                for((label,before)in baseline){
                    val after=bounds(label)
                    val drift=listOf(before.left-after.left,before.top-after.top,before.right-after.right,before.bottom-after.bottom).maxOf{kotlin.math.abs(it)}
                    maximum=maxOf(maximum,drift);assertTrue("$name $label moved by $drift pixels during buffering",drift<=1)
                }
                if(buffering){val hint=bounds("正在缓冲…");assertTrue(hint.height()>0);assertTrue(hint.bottom<=bounds(if(live)"暂停直播" else "后退 15 秒").top)}
            }
            i.runOnMainSync{state.value=state.value.copy(buffering=true)};waitFor("正在缓冲…");photo("buffering-$name-active")
            i.runOnMainSync{state.value=state.value.copy(buffering=false)};i.waitForIdleSync();Thread.sleep(150);photo("buffering-$name-restored")
            val row=JSONObject().put("case",name).put("fontScale",font).put("widthDp",if(name=="large")320 else JSONObject.NULL)
                .put("maximumAnchorDriftPx",maximum).put("transitions",4).put("bufferingHintVisibleOnlyWhenBuffering",true)
            evidence.put(row)
        }
        assertEquals(0,actions);assertEquals(0,triples)
        val folder=File(app.filesDir,"player-buffering-evidence").apply{check(mkdirs()||isDirectory)}
        File(folder,"bounds.json").writeText(JSONObject().put("cases",evidence).put("playbackCommands",0).put("remoteWrites",0).toString(2))
    }finally{i.runOnMainSync{a.finish()}}}
    @Test fun onePersistentHeaderAcrossSwipeAndTabsPreservesActions(){val a=start();try{
        fixture(a);waitFor("播放封面");assertPlayerIdentity();val header=headerBounds();photo("player-light")
        swipe(true);waitFor("点赞");assertEquals("videoDetails",page.value);assertHeaderStable(header);assertDetailsIdentity();photo("details-light")
        hold("点赞",1000);assertEquals(0,triples);hold("点赞",2500);waitFor("三连完成");assertEquals(1,triples)
        click("切换到字幕");waitFor("滚动字幕");assertHeaderStable(header);assertSubtitleIdentityAbsent();assertEquals(1f,chrome,.001f);photo("subtitles-light")
        click("播放更多操作");waitFor("定时");waitFor("歌词");waitFor("音质");click("定时");assertEquals("定时入口",menu.value)
        swipe(true);waitFor("播放封面");assertEquals("player",page.value);assertHeaderStable(header);assertPlayerIdentity();assertEquals(0f,chrome,.001f)
        click("切换到视频详情");waitFor("已点赞");assertHeaderStable(header);assertDetailsIdentity();click("切换到播放");waitFor("播放封面");assertEquals(0,actions)
        click("播放队列");waitFor("示例当前歌曲");waitFor("从队列移除：示例当前歌曲");waitFor("拖动排序：示例当前歌曲")
        assertTrue(nodes(entry.title).isEmpty());assertTrue(nodes(video.title).isEmpty());assertEquals(0,actions);photo("queue-light")
    }finally{i.runOnMainSync{a.finish()}}}
    @Test fun allThreeViewsRemainReadableInDarkAndLargeFont(){val a=start();try{
        for((theme,font,name)in listOf(Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.4f,"large"))){i.runOnMainSync{page.value="player"};fixture(a,theme,font);waitFor("播放封面");assertPlayerIdentity();val header=headerBounds();photo("player-$name")
            click("切换到视频详情");waitFor("点赞");assertHeaderStable(header);assertDetailsIdentity();photo("details-$name")
            click("切换到字幕");waitFor("滚动字幕");assertHeaderStable(header);assertSubtitleIdentityAbsent();photo("subtitles-$name")}
        assertEquals(0,actions);assertEquals(0,triples)
    }finally{i.runOnMainSync{a.finish()}}}
}
