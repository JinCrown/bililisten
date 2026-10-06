package app.bililisten.platform

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BottomBarImeTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private fun nodes():List<AccessibilityNodeInfo> {
        val result=mutableListOf<AccessibilityNodeInfo>()
        fun walk(node:AccessibilityNodeInfo?) { if(node==null)return;result+=node;for(k in 0 until node.childCount)walk(node.getChild(k)) }
        walk(i.uiAutomation.rootInActiveWindow);return result
    }
    private fun find(label:String)=nodes().firstOrNull{it.text?.toString()==label||it.contentDescription?.toString()==label}
    private fun waitFor(description:String,condition:()->Boolean) { val end=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<end){if(condition())return;Thread.sleep(100)};error(description) }
    private fun bounds(n:AccessibilityNodeInfo)=Rect().also{n.getBoundsInScreen(it)}
    @Test fun keyboardCoversAnchoredBarsAndBackRestoresSearchAndControls() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("imeui")=="1")
        val activity=i.startActivitySync(Intent().setClassName(i.targetContext.packageName,"app.bililisten.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        fun ime():Boolean {var value=false;i.runOnMainSync{value=ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true};return value}
        waitFor("Homepage and paused miniplayer required"){find("首页")!=null&&find("展开播放页")!=null}
        val before=bounds(checkNotNull(find("首页")))
        var rootHeight=0
        i.runOnMainSync{rootHeight=activity.window.decorView.height}
        val density=activity.resources.displayMetrics.density
        // A selected Compose Tab need not expose ACTION_CLICK. Measure its selected semantics node.
        var tab=find("首页")!!;while(!tab.isSelected&&tab.parent!=null)tab=tab.parent
        assertTrue("Home navigation tab must be selected",tab.isSelected)
        val maxHeight=if(activity.resources.configuration.fontScale>1.01f)90f else 54f
        assertTrue("Compact tab hit target: ${bounds(tab).height()/density} dp, bounds=${bounds(tab)}, density=$density",bounds(tab).height()/density in 48f..maxHeight)
        val field=nodes().first{it.isEditable}
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitFor("Keyboard did not open"){ime()}
        Thread.sleep(350)
        val text=Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"ui-check")}
        // A semantics node can be replaced while startup/IME finishes a frame. Confirm
        // the input was applied before testing whether Back preserves it.
        var inputAccepted=false
        waitFor("Search input action did not apply ui-check") {
            val editable=nodes().firstOrNull{it.isEditable&&it.packageName?.toString()==i.targetContext.packageName}
            if(editable?.text?.toString()=="ui-check")true
            else { inputAccepted=editable?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text)==true||inputAccepted;false }
        }
        assertTrue("Search input action accepted",inputAccepted)
        i.waitForIdleSync()
        var afterHeight=0;var keyboardTop=0
        i.runOnMainSync {
            afterHeight=activity.window.decorView.height
            keyboardTop=afterHeight-(ViewCompat.getRootWindowInsets(activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
        }
        assertEquals("Keyboard must not resize the whole app window",rootHeight,afterHeight)
        find("首页")?.let{assertEquals(before,bounds(it));assertTrue(bounds(it).top>=keyboardTop)}
        find("展开播放页")?.let{assertTrue("Mini player must not sit above the keyboard",bounds(it).top>=keyboardTop)}
        val editor=checkNotNull(nodes().firstOrNull{it.isEditable})
        assertTrue("Search input remains above keyboard",bounds(editor).bottom<keyboardTop)
        val out=File(i.targetContext.filesDir,"bottom-bar-evidence").apply{mkdirs()}
        i.uiAutomation.takeScreenshot()?.let{bitmap->File(out,"keyboard.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
        i.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        waitFor("Keyboard did not close"){!ime()&&find("首页")!=null&&find("展开播放页")!=null}
        assertEquals(before,bounds(checkNotNull(find("首页"))))
        val restored=checkNotNull(nodes().firstOrNull{it.isEditable});assertEquals("ui-check",restored.text.toString())
        restored.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"")})
        assertNotNull("Paused playback action must remain available",find("播放"))
        assertNull("No automatic playback after IME navigation",find("暂停"))
        File(out,"result.json").writeText("""{"rootHeight":$rootHeight,"heightWithKeyboard":$afterHeight,"keyboardTop":$keyboardTop,"tabHeightDp":${bounds(tab).height()/density},"fontScale":${activity.resources.configuration.fontScale},"searchTextPreserved":true,"autoplay":false}""")
    }
}
