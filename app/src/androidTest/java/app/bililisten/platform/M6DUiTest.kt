package app.bililisten.platform

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import app.bililisten.shared.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit local UI fixtures. No source texts, login, history or playback are mutated. */
@androidx.media3.common.util.UnstableApi @RunWith(AndroidJUnit4::class)
class M6DUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private fun find(text:String):AccessibilityNodeInfo? {
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {if(n==null||!n.refresh())return null;if(n.text?.toString()==text||n.contentDescription?.toString()==text)return n;for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it};return null}
        return visit(i.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(text:String){val end=System.currentTimeMillis()+20000;while(System.currentTimeMillis()<end){if(find(text)!=null)return;Thread.sleep(100)};error("Missing $text")}
    private fun click(text:String){waitFor(text);var n=find(text);while(n!=null&&!n.isClickable)n=n.parent;assertTrue(n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true);i.waitForIdleSync();Thread.sleep(200)}
    private fun start():MainActivity {
        assertFalse(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val a=i.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        waitFor("首页");return a
    }
    private fun shot(name:String){val out=File(app.filesDir,"m6d-evidence").apply{mkdirs()};i.uiAutomation.takeScreenshot()?.let{bmp->File(out,"$name.png").outputStream().use{bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()}}
    private val ref=VideoRef("BV1xx411c7mD",1,1)
    private val zh=SubtitleOption("zh","zh-CN","中文",SubtitleKind.HUMAN)
    private val en=SubtitleOption("en","en","English",SubtitleKind.AI)
    @Test fun languagesLineGapSeekAndErrorsKeepControlsVisible() {
        val a=start();val state=mutableStateOf(ScreenState(canSeek=true,positionMs=1500,durationMs=5000,
            subtitles=SubtitleView(ref,SubtitleStatus.READY,listOf(zh,en),zh,listOf(SubtitleCue(1000,2000,"本机字幕测试甲"),SubtitleCue(3000,4000,"本机字幕测试乙")))))
        var selected="";var sought=-1L;var toggles=0
        i.runOnMainSync{a.setContent{ListenTheme(Theme.LIGHT){SubtitlePage(state.value,null,Modifier,{}, {toggles++},{sought=it},{},{},{},{},{selected=it},{state.value=state.value.copy(settings=state.value.settings.copy(subtitleLineMode=it))},{})}}}
        waitFor("当前字幕");click("中文 · 人工字幕 ▾");click("English · AI 字幕");assertEquals("en",selected)
        click("逐行字幕");waitFor("本机字幕测试甲")
        i.runOnMainSync{state.value=state.value.copy(positionMs=2500)};waitFor("跟随字幕")
        waitFor("本机字幕测试甲");assertNull(find("当前字幕"));assertNull(find("当前时段暂无字幕"))
        click("滚动字幕");click("本机字幕测试乙");assertEquals(3000,sought)
        for(status in listOf(SubtitleStatus.LOGIN_REQUIRED,SubtitleStatus.EMPTY,SubtitleStatus.FAILED,SubtitleStatus.RESTRICTED,SubtitleStatus.EXPIRED)) {
            i.runOnMainSync{state.value=state.value.copy(subtitles=SubtitleView(ref,status,message="本机错误状态测试"))}
            waitFor("本机错误状态测试");click("播放")
        }
        assertEquals(5,toggles);shot("offline-subtitle-expired")
        i.runOnMainSync{a.finish()}
    }
    @Test fun lyricsRequireConfirmationAndStaticTextShowsNoTiming() {
        val a=start();val candidate=LyricsCandidate(1,"本机歌词测试","测试歌手","测试专辑",5000)
        val doc=LyricsDocument(candidate,"本机歌词测试文本",listOf(SubtitleCue(1000,3000,"本机歌词测试文本")))
        val state=mutableStateOf(ScreenState(positionMs=1500,durationMs=5000,canSeek=true,lyrics=LyricsView(ref,LyricsStatus.PREVIEW,document=doc)))
        var sought=-1L
        i.runOnMainSync{a.setContent{ListenTheme(Theme.LIGHT){LyricsPage(state.value,null,Modifier,{}, {},{sought=it},{},{},{},{_,_->},{},{state.value=state.value.copy(lyrics=state.value.lyrics.copy(offsetMs=it,confirmed=false))},{state.value=state.value.copy(lyrics=state.value.lyrics.copy(confirmed=true,status=LyricsStatus.READY))})}}}
        click("收起搜索");waitFor("${timeLabel(1000)}  本机歌词测试文本");assertEquals(-1,sought)
        click("版本与时间已核对，启用同步");click("本机歌词测试文本");assertEquals(1000,sought)
        click("延后 0.5 秒");assertFalse(state.value.lyrics.confirmed);assertEquals(500,state.value.lyrics.offsetMs)
        i.runOnMainSync{state.value=state.value.copy(lyrics=state.value.lyrics.copy(document=doc.copy(cues=emptyList())))}
        waitFor("静态歌词 · 来源没有可用逐行时间轴");assertNull(find("版本与时间已核对，启用同步"));waitFor("播放");shot("offline-static-lyrics")
        i.runOnMainSync{a.finish()}
    }
    @Test fun realAppSubtitleAndLyricsEntriesReturnWithoutAutoplay() {
        val a=start();click("展开播放页");waitFor("查看字幕");click("查看字幕");waitFor("字幕");waitFor("滚动字幕");waitFor("逐行字幕");shot("actual-subtitle-page");click("返回播放页")
        click("播放更多操作");click("歌词")
        waitFor("查询歌词候选");waitFor("歌词");shot("actual-lyrics-page");click("返回播放页");waitFor("查看字幕")
        val f=MainActivity::class.java.getDeclaredField("playbackVm").apply{isAccessible=true};assertFalse((f.get(a) as MainViewModel).state.value.playRequested)
        i.runOnMainSync{a.finish()}
    }
}
