package app.bililisten.platform

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.MainActivity
import app.bililisten.ListenApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@androidx.media3.common.util.UnstableApi
@RunWith(AndroidJUnit4::class)
class M3UiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private fun find(text:String):AccessibilityNodeInfo? {
        fun walk(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(n==null)return null
            if(n.text?.toString()==text)return n
            for(index in 0 until n.childCount)walk(n.getChild(index))?.let{return it}
            return null
        }
        return walk(i.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(text:String) {
        val end=System.currentTimeMillis()+15000
        while(System.currentTimeMillis()<end){if(find(text)!=null)return;Thread.sleep(100)}
        throw AssertionError("Missing UI: $text")
    }
    private fun click(text:String) {
        waitFor(text);var node=find(text)
        while(node!=null&&!node.isClickable)node=node.parent
        assertNotNull(node);assertTrue(node!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    @Test fun threeTabsExposeCorrectGuestOrAccountStateAndSettings() {
        val app=i.targetContext.applicationContext as ListenApplication
        app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor("首页");waitFor("收藏");waitFor("我的")
        click("收藏")
        if(app.vault.read()==null)waitFor("请登录账户") else waitFor("我的收藏")
        click("我的")
        if(app.vault.read()==null){waitFor("未登录");waitFor("点击登录")}
        else waitFor("编辑资料 ›")
        // The formal screen exposes Settings as an icon with an accessibility label.
        fun labelled(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(n==null)return null
            if(n.contentDescription?.toString()=="设置")return n
            for(index in 0 until n.childCount)labelled(n.getChild(index))?.let{return it}
            return null
        }
        var settings=labelled(i.uiAutomation.rootInActiveWindow)
        while(settings!=null&&!settings.isClickable)settings=settings.parent
        assertTrue(settings!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        click("音质与播放");waitFor("音质：自动选择当前最高可用");waitFor("允许移动数据收听")
    }
}
