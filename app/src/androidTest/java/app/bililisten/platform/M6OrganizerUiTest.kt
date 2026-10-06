package app.bililisten.platform

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import app.bililisten.playback.ControllerPlaybackPort
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4::class)
class M6OrganizerUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private fun find(label:String):AccessibilityNodeInfo? {
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo?{if(n==null||!n.refresh())return null;if(n.text?.toString()==label||n.contentDescription?.toString()==label)return n;for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it};return null}
        return visit(i.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(label:String){val end=System.currentTimeMillis()+25000;while(System.currentTimeMillis()<end){if(find(label)!=null)return;Thread.sleep(150)};error("Missing UI: $label")}
    private fun click(label:String){waitFor(label);val end=System.currentTimeMillis()+5000;while(System.currentTimeMillis()<end){var n=find(label);while(n!=null&&!n.isClickable)n=n.parent;if(n?.isEnabled==true&&n.performAction(AccessibilityNodeInfo.ACTION_CLICK)){i.waitForIdleSync();Thread.sleep(350);return};Thread.sleep(150)};error("Not clickable: $label")}
    private fun start(){app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));waitFor("首页");Thread.sleep(3000)}
    @Test fun organizerEntryAndCancelledCreateNeverWriteRemote()=runBlocking {
        start();click("收藏");click("整理");waitFor("收藏整理与查找");waitFor("新建收藏夹")
        click("新建收藏夹");waitFor("保存到B站");waitFor("私有：仅本人可见");click("取消")
        click("返回收藏");waitFor("我的收藏")
        assertNull(app.organizer.load().pending)
    }
    @Test fun videoSharePreviewAndCancelPreserveQueueAndPlayback()=runBlocking {
        start()
        val port=withContext(Dispatchers.Main){ControllerPlaybackPort(app)}
        try {
            withTimeout(10000){port.state.first{it.connected};withContext(Dispatchers.Main){port.flush()}}
            val before=port.state.value
            app.startActivity(Intent(app,MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT,"https://www.bilibili.com/video/BV1xx411c7mD/?p=1").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            waitFor("分享转入");waitFor("立即播放");waitFor("下一条播放");waitFor("加入队列")
            assertEquals(before.queue,port.state.value.queue);assertEquals(before.currentId,port.state.value.currentId);assertEquals(before.requested,port.state.value.requested)
            click("取消");assertEquals(before.queue,port.state.value.queue)
            File(app.filesDir,"m6a-evidence").apply{mkdirs()}.resolve("share-ui.json").writeText("{\"videoPreview\":true,\"threeActionsVisible\":true,\"previewDidNotReplaceQueue\":true,\"previewDidNotTogglePlayback\":true,\"cancelPreservedQueue\":true}")
        }finally{withContext(Dispatchers.Main){port.close()}}
    }
    @Test fun liveShareRequiresConsentAndCancelDoesNotConnect()=runBlocking {
        start();app.startActivity(Intent(app,MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT,"https://live.bilibili.com/6").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        waitFor("分享转入");waitFor("开始收听直播");waitFor("同意使用含视频数据的混流，只输出声音，会增加流量。")
        var button=find("开始收听直播");while(button!=null&&!button.isClickable)button=button.parent
        assertNotNull(button);assertFalse(button!!.isEnabled);click("取消")
    }
    @Test fun explicitNextAppendAndImmediateActionsUseRealPlayerAndRestoreOriginalQueue()=runBlocking {
        start()
        val port=withContext(Dispatchers.Main){ControllerPlaybackPort(app)}
        withTimeout(10000){port.state.first{it.connected};withContext(Dispatchers.Main){port.pause();port.flush()}}
        val account=app.accounts.session.value.stamp.account
        val original=app.stores.load(account)
        val settings=app.settings.settings.first()
        suspend fun shareAndClick(label:String){
            app.startActivity(Intent(app,MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,"https://www.bilibili.com/video/BV1xx411c7mD/?p=1").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            waitFor(label);click(label);delay(800)
        }
        try {
            app.settings.update(settings.copy(historyEnabled=false))
            // Start from a known paused two-item queue; actual account snapshot is restored in finally.
            val video=app.api.video("BV1rHh16CESS");val part=video.parts.first()
            val rows=listOf(QueueEntry("m6-a",video.bvid,part.cid,part.number,video.title),QueueEntry("m6-b",video.bvid,part.cid,part.number,video.title))
            val fixture=ResumeSnapshot(account=account,entries=rows,order=rows.map{it.id},currentId="m6-a",positionMs=1000)
            withContext(Dispatchers.Main){port.replace(fixture,false)}
            shareAndClick("下一条播放")
            withTimeout(8000){port.state.first{it.queue.size==3}}
            assertFalse(port.state.value.requested);assertEquals("m6-a",port.state.value.currentId)
            assertEquals("BV1xx411c7mD",port.state.value.queue[1].bvid)
            shareAndClick("加入队列")
            withTimeout(8000){port.state.first{it.queue.size==4}}
            assertFalse(port.state.value.requested);assertEquals("BV1xx411c7mD",port.state.value.queue.last().bvid)
            shareAndClick("立即播放")
            withTimeout(10000){port.state.first{it.queue.size==1&&it.queue.single().bvid=="BV1xx411c7mD"&&it.requested}}
            withContext(Dispatchers.Main){port.pause();port.flush()}
            // The ordinary open-content action uses the same direct-play path as list rows.
            click("粘贴链接或 BV 号");waitFor("打开视频")
            fun editable(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? { if(n==null||!n.refresh())return null;if(n.isEditable)return n;for(k in 0 until n.childCount)editable(n.getChild(k))?.let{return it};return null }
            val field=editable(i.uiAutomation.rootInActiveWindow) ?: error("Missing input")
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);Thread.sleep(300)
            assertTrue(editable(i.uiAutomation.rootInActiveWindow)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,android.os.Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"BV1rHh16CESS")}))
            click("打开视频")
            withTimeout(10000){port.state.first{it.queue.size==1&&it.queue.single().bvid=="BV1rHh16CESS"&&it.requested}}
            assertNull(find("播放全部分 P"))
            withContext(Dispatchers.Main){port.pause();port.flush()}
            click("返回")
            File(app.filesDir,"m6a-evidence").apply{mkdirs()}.resolve("share-actions.json").writeText("{\"nextInsertedAfterCurrent\":true,\"appendAddedAtTail\":true,\"enqueueDidNotAutoplay\":true,\"immediateRequestedPlayback\":true}")
            File(app.filesDir,"m6a-evidence/direct-play.json").writeText("{\"contentOpenRequestedPlaybackDirectly\":true,\"noPartOrQueuePicker\":true,\"firstPartSelected\":true}")
        } finally {
            withContext(Dispatchers.Main){port.pause();port.flush();if(original!=null)port.replace(original.queue,false)else port.clear();port.flush();port.close()}
            if(original!=null)app.stores.checkpoint(original,null)
            app.settings.update(settings)
        }
    }
}
