package app.bililisten.platform

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4::class)
class M4UiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private fun find(label:String):AccessibilityNodeInfo? {
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(n==null)return null
            if(!n.refresh())return null // Compose can replace virtual nodes while the page changes.
            if(n.text?.toString()==label || n.contentDescription?.toString()==label)return n
            for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it}
            return null
        };return visit(i.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(label:String){val until=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<until){if(find(label)!=null)return;Thread.sleep(100)};shot("failure");error("Missing UI label: $label")}
    private fun click(label:String){
        waitFor(label)
        fun actionable(node:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(node==null)return null
            if(node.text?.toString()==label || node.contentDescription?.toString()==label){var n:AccessibilityNodeInfo?=node;while(n!=null&&!n.isClickable)n=n.parent;if(n?.isEnabled==true)return n}
            for(k in 0 until node.childCount)actionable(node.getChild(k))?.let{return it};return null
        }
        val end=System.currentTimeMillis()+5000
        var target=actionable(i.uiAutomation.rootInActiveWindow)
        while(target==null&&System.currentTimeMillis()<end){Thread.sleep(100);target=actionable(i.uiAutomation.rootInActiveWindow)}
        var clicked=target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
        // Folder metadata can replace a semantics node between lookup and click.
        while(!clicked&&System.currentTimeMillis()<end){Thread.sleep(100);target=actionable(i.uiAutomation.rootInActiveWindow);clicked=target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true}
        if(!clicked)shot("failure")
        assertTrue("No actionable label: $label",clicked);i.waitForIdleSync();Thread.sleep(250)
    }
    private fun start(){app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));waitFor("首页")}
    private fun shot(name:String){Thread.sleep(500);val dir=File(app.filesDir,"m4-ui").apply{mkdirs()};i.uiAutomation.takeScreenshot()?.let{bitmap->File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}}
    @Test fun formalLightDarkScreensAndPlayerExpansionStaySilent()=runBlocking {
        val original=app.settings.settings.first()
        try {
            app.settings.update(original.copy(theme=Theme.LIGHT));start();Thread.sleep(4500);shot("formal-home-light")
            if(find("展开播放页")!=null)waitFor("播放") // Mini-player action must have a real accessible label.
            click("收藏");Thread.sleep(2000);shot("formal-favorites-light");click("我的");Thread.sleep(1500);shot("formal-mine-light")
            if(find("展开播放页")!=null){
                click("展开播放页");waitFor("去 B 站看视频");shot("formal-player-light")
                assertNull(find("暂停"));click("查看字幕");waitFor("字幕");assertNull(find("示例字幕"));shot("formal-subtitles");click("返回播放页");waitFor("去 B 站看视频");click("返回")
                waitFor("我的收听")
            }
            click("设置");click("外观");click("深色");Thread.sleep(350);shot("formal-appearance-dark");click("返回");click("返回");shot("formal-mine-dark")
            click("首页");shot("formal-home-dark")
        }finally{app.settings.update(original)}
    }
    @Test fun navigationSettingsReturnAndThreeSources() {
        start();shot("home");click("收藏")
        if(app.vault.read()==null)waitFor("请登录账户") else {waitFor("我的收藏");waitFor("追更合集");waitFor("UP收藏")}
        shot("favorites");click("我的");waitFor("最近收听");waitFor("下载管理");waitFor("缓存管理");waitFor("播放历史");shot("mine")
        click("设置");waitFor("外观");shot("settings");click("外观");waitFor("跟随系统");waitFor("浅色");waitFor("深色");click("返回");waitFor("隐私与本机历史");click("返回");waitFor("我的收听")
    }
    @Test fun linksRequireExplicitInputAndSearchKeepsQueryAcrossTabs(){
        start();click("粘贴链接或 BV 号");waitFor("粘贴链接");waitFor("打开视频");shot("link");click("返回");waitFor("首页")
        // No clipboard access or remote write occurs in this test.
        click("搜索视频");waitFor("搜索 B 站视频 · 可输入标题或作者关键词")
        fun editable(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(n==null)return null;if(n.isEditable)return n
            for(k in 0 until n.childCount)editable(n.getChild(k))?.let{return it};return null
        }
        val field=editable(i.uiAutomation.rootInActiveWindow)!!
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"界面测试关键词")}))
        waitFor("界面测试关键词");shot("search-input");click("返回");waitFor("首页")
        click("收藏");click("我的");click("首页");waitFor("界面测试关键词")
    }
    @Test fun cacheClearLeavesOtherOwnedFilesUntouched()=runBlocking {
        val root=File(app.cacheDir,"m4-cache-isolation").apply{mkdirs()}
        val context=object:android.content.ContextWrapper(app){override fun getCacheDir()=root}
        val covers=File(root,"covers-v1").apply{mkdirs()};File(covers,"cover").writeText("image")
        val unrelated=File(root,"history-marker").apply{writeText("keep")}
        val vaultBefore=app.vault.read();val snapshot=app.stores.load(app.accountKey)
        try{
            val store=CoverStore(context);assertEquals(5L,store.bytes());store.clear();assertEquals(0L,store.bytes());assertEquals("keep",unrelated.readText())
            // Avoid printing credentials on failure. A live service may refresh only savedAt.
            assertTrue("Cover cleanup must preserve the session",vaultBefore==app.vault.read())
            val after=app.stores.load(app.accountKey)
            assertTrue("Cover cleanup must preserve queue, position and playback metadata",snapshot?.copy(savedAt=0)==after?.copy(savedAt=0))
        }
        finally{root.deleteRecursively()}
    }
    @Test fun coverHostsRejectCredentialsAndArbitraryDestinations(){
        assertNull(CoverStore.safeUrl("https://evil.example/image.jpg"));assertNull(CoverStore.safeUrl("https://i0.hdslb.com.evil.example/a"));assertNull(CoverStore.safeUrl("https://name:secret@i0.hdslb.com/a"))
        assertEquals("https://i0.hdslb.com/a",CoverStore.safeUrl("//i0.hdslb.com/a"))
        assertEquals("https://archive.biliimg.com/a.jpg",CoverStore.safeUrl("//archive.biliimg.com/a.jpg"))
        assertEquals("https://archive.biliimg.com/a.jpg",CoverStore.safeUrl("http://archive.biliimg.com/a.jpg"))
        assertNull(CoverStore.safeUrl("https://archive.biliimg.com.evil.example/a"))
        assertNull(CoverStore.safeUrl("https://name:secret@archive.biliimg.com/a"))
    }
    @Test fun systemBackClosesQueueBeforeNavigatingAway(){
        start();Thread.sleep(1200)
        if(find("播放队列")!=null){click("播放队列");waitFor("清空");shot("queue");i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);Thread.sleep(300);waitFor("首页")}
        click("粘贴链接或 BV 号");waitFor("粘贴链接");i.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);Thread.sleep(300);waitFor("首页")
    }
    @Test fun ownMusicFolderShowsReadOnlyDetails(){
        if(app.vault.read()==null)return
        start();click("收藏");waitFor("音乐");click("音乐");waitFor("播放全部");waitFor("随机播放");shot("music-folder-detail")
        click("返回");waitFor("我的收藏")
    }
    @Test fun scrollableLayoutKeepsNavigationAndSettingsReachable(){
        fun reveal(label:String){
            Thread.sleep(500)
            fun scrollable(n:AccessibilityNodeInfo?):AccessibilityNodeInfo?{if(n==null)return null;if(n.isScrollable)return n;for(k in 0 until n.childCount)scrollable(n.getChild(k))?.let{return it};return null}
            repeat(35){
                if(find(label)!=null)return
                scrollable(i.uiAutomation.rootInActiveWindow)?.let{node->
                    val rect=android.graphics.Rect();node.getBoundsInScreen(rect)
                    val window=android.graphics.Rect();i.uiAutomation.rootInActiveWindow.getBoundsInScreen(window);rect.intersect(window)
                    find("展开播放页")?.let{mini->val b=android.graphics.Rect();mini.getBoundsInScreen(b);if(b.top>rect.top)rect.bottom=minOf(rect.bottom,b.top-24)}
                    find("返回")?.let{back->val b=android.graphics.Rect();back.getBoundsInScreen(b);rect.top=maxOf(rect.top,b.bottom+12)}
                    val x=rect.centerX().toFloat();val from=rect.top+rect.height()*.75f;val to=rect.top+rect.height()*.50f
                    val down=android.os.SystemClock.uptimeMillis()
                    fun event(action:Int,y:Float,time:Long){val e=android.view.MotionEvent.obtain(down,time,action,x,y,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;i.uiAutomation.injectInputEvent(e,true);e.recycle()}
                    event(android.view.MotionEvent.ACTION_DOWN,from,down)
                    for(step in 1..16){Thread.sleep(25);event(android.view.MotionEvent.ACTION_MOVE,from+(to-from)*step/16,android.os.SystemClock.uptimeMillis())}
                    Thread.sleep(150)
                    event(android.view.MotionEvent.ACTION_UP,to,android.os.SystemClock.uptimeMillis())
                }
                Thread.sleep(300)
            }
            waitFor(label)
        }
        start();Thread.sleep(1500);shot("layout-home");click("收藏");if(app.vault.read()==null)reveal("请登录账户") else waitFor("我的收藏");shot("layout-favorites")
        click("我的");waitFor("设置");shot("layout-mine-top");click("设置");reveal("外观");shot("layout-settings");click("外观");waitFor("跟随系统");click("返回");click("返回")
        reveal("最近收听");reveal("下载管理");reveal("缓存管理");reveal("播放历史");shot("layout-mine-tiles");click("首页")
    }
    @Test fun componentPreviewsLightDarkAndLargeText(){
        for((name,dark,font) in listOf(Triple("components-light",false,1f),Triple("components-dark",true,1f),Triple("components-large-font",false,1.6f))){
            val intent=Intent().setClassName(app.packageName,"app.bililisten.ComponentPreviewActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("dark",dark).putExtra("font",font)
            app.startActivity(intent);waitFor("组件预览 · 示例数据");shot(name)
        }
        start()
    }
}
