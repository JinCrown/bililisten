package app.bililisten.platform

import android.app.KeyguardManager
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Own-app navigation only. The official rights link is left for user-operated verification. */
@RunWith(AndroidJUnit4::class)
class M6CAudioUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private fun nodes():List<AccessibilityNodeInfo> {
        val out=mutableListOf<AccessibilityNodeInfo>()
        fun walk(n:AccessibilityNodeInfo?){if(n==null)return;out+=n;for(k in 0 until n.childCount)walk(n.getChild(k))}
        walk(i.uiAutomation.rootInActiveWindow);return out
    }
    private fun find(text:String)=nodes().firstOrNull{it.text?.toString()==text||it.contentDescription?.toString()==text}
    private fun waitFor(text:String){val end=System.currentTimeMillis()+25000;while(System.currentTimeMillis()<end){if(find(text)!=null)return;Thread.sleep(100)};error("Missing $text")}
    private fun click(text:String){waitFor(text);var n=find(text)!!;while(!n.isClickable&&n.parent!=null)n=n.parent;assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));i.waitForIdleSync();Thread.sleep(250)}
    @Test fun settingsAndPlayerExposeSameQualityPanelAndOfficialActionWithoutAutoplay() {
        val app=i.targetContext.applicationContext as ListenApplication
        assertFalse("Manual unlock required",app.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val activity=i.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as MainActivity
        waitFor("首页");Thread.sleep(2000)
        click("我的");click("设置");click("音质与播放");click("实际音质与输出");waitFor("音质与音频输出")
        waitFor("去 B 站确认账户权益")
        assertNotNull(find("重新检查实际音轨"))
        val field=MainActivity::class.java.getDeclaredField("playbackVm").apply{isAccessible=true}
        val vm=field.get(activity) as MainViewModel
        assertFalse(vm.state.value.playRequested)
        val out=File(app.filesDir,"m6c-evidence").apply{mkdirs()}
        i.uiAutomation.takeScreenshot()?.let{bmp->File(out,"audio-panel.png").outputStream().use{bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
        i.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);Thread.sleep(400)
        click("返回");click("返回");click("展开播放页")
        click("播放更多操作");click("音质");waitFor("音质与音频输出")
        assertFalse(vm.state.value.playRequested)
        File(out,"ui.json").writeText("""{"settingsEntry":true,"playerEntry":true,"samePanel":true,"autoplay":false,"officialButtonVisible":true,"officialExternalLaunchTested":false}""")
    }
}
