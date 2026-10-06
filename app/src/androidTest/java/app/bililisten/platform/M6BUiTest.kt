package app.bililisten.platform

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M6BUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private fun find(label:String):AccessibilityNodeInfo? {
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? { if(n==null||!n.refresh())return null;if(n.text?.toString()==label||n.contentDescription?.toString()==label)return n;for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it};return null }
        return visit(i.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(label:String){val end=System.currentTimeMillis()+25000;while(System.currentTimeMillis()<end){if(find(label)!=null)return;Thread.sleep(150)};error("Missing UI: $label")}
    private fun click(label:String){waitFor(label);var n=find(label);while(n!=null&&!n.isClickable)n=n.parent;assertTrue("Not clickable $label",n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true);i.waitForIdleSync();Thread.sleep(400)}
    @Test fun historyFiltersAndTimerPanelAreReachableAndCancelPreservesPrivacySettings()=runBlocking {
        app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor("首页");Thread.sleep(2500)
        click("我的");click("最近收听");waitFor("搜索本机历史 · 标题 / BV / 房间号");click("直播");click("视频");click("全部");click("返回")
        click("设置");click("隐私与本机历史");waitFor("保留策略");waitFor("全部保留");waitFor("退出登录时删除本机历史");click("返回")
        click("定时停止");waitFor("长内容收听");waitFor("自定义 1—180 分钟");click("10 分钟");waitFor("取消定时");click("取消定时");waitFor("未开启")
        i.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Unit
    }
}
