package app.bililisten.platform

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M5WebViewTest {
    @Test fun missingProviderExplainsLoginFailureWithoutCrashing() {
        assumeFalse(WebSession.available())
        val i=InstrumentationRegistry.getInstrumentation()
        i.targetContext.startActivity(Intent(i.targetContext,WebLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val until=System.currentTimeMillis()+10000
        while(System.currentTimeMillis()<until){
            val nodes=i.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("返回继续使用").orEmpty()
            if(nodes.isNotEmpty()) { assertTrue(nodes.first().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));return }
            Thread.sleep(100)
        }
        fail("Missing WebView fallback was not displayed")
    }
}
