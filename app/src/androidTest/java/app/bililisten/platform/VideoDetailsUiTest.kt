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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.media3.common.util.UnstableApi @RunWith(AndroidJUnit4::class)
class VideoDetailsUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private val entry=QueueEntry("fixture","BV1xx411c7mD",1,1,"示例歌曲")
    private val video=Video(entry.bvid,1,"示例歌曲",listOf(VideoPart(1,1,"P1",241)),author="示例音乐 UP",owner=8,duration=241,description="这是视频简介的示例内容。\n第二行简介。",likes=1234,coins=321,favorites=99,replies=8,copyright=1)
    private val page=mutableStateOf("player")
    private val state=mutableStateOf(ScreenState(account=Account(7,"示例账号"),accountChecked=true,queue=listOf(entry),currentId=entry.id,positionMs=12345,durationMs=241000,videoDetails=VideoDetailsView(entry.bvid,video=video,tags=listOf("音乐","听歌"),relations=VideoRelations(false,0,false))))
    private var likes=0;private var triples=0;private var coins=0;private var favoriteAdds=0;private var favoriteOpens=0;private var playbackCalls=0
    @Volatile private var chrome=0f
    private fun find(label:String):AccessibilityNodeInfo? {
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {if(n==null||!n.refresh()||!n.isVisibleToUser)return null
            if(n.text?.toString()==label||n.contentDescription?.toString()==label)return n
            for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it};return null}
        return visit(i.uiAutomation.rootInActiveWindow)
    }
    private fun stateDescription(label:String):String? {var node=find(label);while(node!=null){node.stateDescription?.let{return it.toString()};node=node.parent};return null}
    private fun waitFor(label:String){val end=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<end){if(find(label)!=null)return;Thread.sleep(50)};error("Missing $label")}
    private fun click(label:String){waitFor(label);var node=find(label);while(node!=null&&!node.isClickable)node=node.parent;assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true);i.waitForIdleSync();Thread.sleep(250)}
    private fun event(down:Long,action:Int,x:Float,y:Float){val e=MotionEvent.obtain(down,android.os.SystemClock.uptimeMillis(),action,x,y,0);e.source=InputDevice.SOURCE_TOUCHSCREEN;assertTrue(i.uiAutomation.injectInputEvent(e,true));e.recycle()}
    private fun swipe(right:Boolean){val r=Rect();i.uiAutomation.rootInActiveWindow.getBoundsInScreen(r);val down=android.os.SystemClock.uptimeMillis();val x=r.width()*(if(right).18f else .82f);val to=r.width()*(if(right).82f else .18f);val y=r.top+r.height()*.25f
        event(down,MotionEvent.ACTION_DOWN,x,y);for(k in 1..16){Thread.sleep(25);event(down,MotionEvent.ACTION_MOVE,x+(to-x)*k/16,y)};event(down,MotionEvent.ACTION_UP,to,y);Thread.sleep(800)}
    private fun hold(label:String,ms:Long){waitFor(label);val r=Rect();find(label)!!.getBoundsInScreen(r);val down=android.os.SystemClock.uptimeMillis();event(down,MotionEvent.ACTION_DOWN,r.centerX().toFloat(),r.centerY().toFloat());Thread.sleep(ms);event(down,MotionEvent.ACTION_UP,r.centerX().toFloat(),r.centerY().toFloat());Thread.sleep(300)}
    private fun photo(name:String){val p=File(app.filesDir,"video-details-evidence").apply{mkdirs()};i.uiAutomation.takeScreenshot()?.let{b->File(p,"fixture-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun start():MainActivity {assertFalse(app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked);return i.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity}
    private fun fixture(a:MainActivity,theme:Theme=Theme.LIGHT,font:Float=1f){i.runOnMainSync{a.setContent{ListenTheme(theme){val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,font)){
        Column(Modifier.fillMaxSize().safeDrawingPadding()){Text("界面预览 · 示例数据")
            PlayerWorkspace(state.value,video,entry,page.value,{page.value=it},Modifier.weight(1f),back={},creator={},timer={},lyrics={},audio={},player={Text("封面页预览",Modifier.fillMaxSize())},subtitles={Text("字幕页预览",Modifier.fillMaxSize())},details={
                VideoDetailsPage(state.value,video,entry,12345,241000,Modifier,{page.value="player"},{playbackCalls++},{playbackCalls++},{playbackCalls++},{playbackCalls++},{}, {},{},{favoriteOpens++},
                    {_,_,action,amount->val before=state.value.videoDetails.relations!!;val after=when(action){EngagementAction.LIKE->{likes++;before.copy(liked=!before.liked)};EngagementAction.COIN->{coins++;before.copy(coins=before.coins+amount)};EngagementAction.TRIPLE->{triples++;VideoRelations(true,2,true)}}
                        state.value=state.value.copy(videoDetails=state.value.videoDetails.copy(relations=after,message=if(action==EngagementAction.TRIPLE)"三连完成"else"操作完成"))}, {},{}, {},{},favorite={favoriteAdds++;state.value=state.value.copy(videoDetails=state.value.videoDetails.copy(relations=state.value.videoDetails.relations!!.copy(favorite=true)))})
            },chrome={chrome=it})
        }
    }}}}}
    @Test fun swipeHoldCancelCompletionAndSolidStatesDoNotTouchRealAccount(){val a=start();try{
        fixture(a);waitFor("封面页预览");swipe(true);waitFor("切换到视频详情");waitFor("点赞");assertEquals("videoDetails",page.value);assertEquals(0f,chrome,.001f)
        photo("light-before");hold("点赞",1000);assertEquals(0,triples);assertEquals(0,likes)
        click("点赞");waitFor("已点赞");assertEquals("已点赞",stateDescription("已点赞"));click("已点赞");waitFor("点赞");assertEquals(2,likes)
        click("投币");waitFor("确认投币");click("取消");assertEquals(0,coins)
        click("收藏");assertEquals(1,favoriteAdds);assertEquals(0,favoriteOpens);hold("收藏",900);assertEquals(1,favoriteAdds);assertEquals(1,favoriteOpens)
        hold("点赞",2500);waitFor("三连完成");waitFor("已投 2 枚");assertEquals(1,triples);assertEquals("已完成",stateDescription("收藏"));photo("light-complete")
        swipe(false);waitFor("封面页预览");assertEquals(0f,chrome,.001f);swipe(false);waitFor("字幕页预览");assertEquals(1f,chrome,.001f);swipe(true);waitFor("封面页预览");assertEquals(0f,chrome,.001f)
        assertEquals(0,playbackCalls)
    }finally{i.runOnMainSync{a.finish()}}}
    @Test fun darkAndLargeFontKeepDetailsReadable(){val a=start();try{
        for((theme,font,name)in listOf(Triple(Theme.DARK,1f,"dark"),Triple(Theme.LIGHT,1.4f,"large"))){i.runOnMainSync{page.value="videoDetails"};fixture(a,theme,font);waitFor("切换到视频详情");waitFor("示例歌曲");waitFor("投币");photo(name)}
        assertEquals(0,playbackCalls);assertEquals(0,triples)
    }finally{i.runOnMainSync{a.finish()}}}
    @Test fun realMetadataAndAccountRelationsReadOnly()=runBlocking<Unit> {
        val account=app.api.account();val snapshot=app.stores.load(account.id.toString()) ?: error("No paused snapshot")
        val bvid=snapshot.queue.entries.first{it.id==snapshot.queue.currentId}.bvid
        val v=app.api.video(bvid);val tags=app.api.videoTags(bvid);val relations=app.api.videoRelations(bvid)
        assertEquals(bvid,v.bvid);assertTrue(v.parts.isNotEmpty());assertTrue(v.description.isNotBlank());assertNotNull(v.likes);assertTrue(relations.coins in 0..2)
        val selected=app.settings.current().defaultFavoriteFolders[account.id.toString()]
        val target=selected?.let{app.api.folders(account.id,v.aid).firstOrNull{folder->folder.id==it.id}}
        if(selected!=null){assertNotNull(target);assertNotNull(target!!.contains)}
        File(app.filesDir,"video-details-evidence").apply{mkdirs()}.resolve("online.json").writeText("""{"metadata":true,"description":true,"stats":true,"tagsCount":${tags.size},"combinedRelations":true,"defaultConfigured":${selected!=null},"targetFolderVerified":${target!=null},"remoteWrites":false,"userPlaybackStarted":false}""")
    }
}
