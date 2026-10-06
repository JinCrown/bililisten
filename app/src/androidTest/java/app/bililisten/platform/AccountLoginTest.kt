package app.bililisten.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.content.Intent
import android.webkit.CookieManager
import android.view.accessibility.AccessibilityNodeInfo
import app.bililisten.shared.SessionStatus
import kotlinx.coroutines.flow.first

/** Opt-in reads; neither session credentials nor personal profile values leave the device. */
@RunWith(AndroidJUnit4::class)
class AccountLoginTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private fun find(label:String):AccessibilityNodeInfo? {
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {if(n==null||!n.refresh())return null;if(n.text?.toString()==label||n.contentDescription?.toString()==label)return n;for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it};return null}
        return visit(i.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(check:()->Boolean) {val until=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<until){if(check())return;Thread.sleep(100)};error("Expected account UI state did not appear")}
    private fun click(label:String) {waitFor{find(label)!=null};var node=find(label);while(node!=null&&!node.isClickable)node=node.parent;assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true);i.waitForIdleSync()}
    private fun ready(activity:WebLoginActivity):Boolean {var ready=false;i.runOnMainSync{ready=WebLoginActivity::class.java.getDeclaredField("pageReady").apply{isAccessible=true}.getBoolean(activity)};return ready}
    private fun save(name:String,data:JsonElement) {File(app.filesDir,"account-login-evidence").mkdirs();File(app.filesDir,"account-login-evidence/$name.json").writeText(data.toString())}
    private fun shot(name:String) {val out=File(app.filesDir,"account-login-evidence").apply{mkdirs()};i.uiAutomation.takeScreenshot()?.let{b->File(out,"$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}

    @Test fun unchangedValidSessionIsDetectedSavedAndReturnsWithoutButton()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("accountRead")=="1")
        val cookie=app.vault.read();assertNotNull(cookie)
        val identity=app.api.account();val generation=app.vault.generation
        val settings=app.settings.settings.first();val before=app.stores.load(app.accountKey)
        val activity=i.startActivitySync(Intent(app,WebLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WebLoginActivity
        try {
            waitFor{ready(activity)};waitFor{find("未自动返回？重新检查")!=null};shot("login-automatic-chrome")
            // Reuse this same already validated account, entirely inside the device.
            // No password entry, identity switch, purchase or remote mutation is involved.
            i.runOnMainSync{CookieManager.getInstance().apply{cookie!!.split(';').forEach{setCookie("https://www.bilibili.com/",it.trim()+"; Domain=.bilibili.com; Path=/; Secure")};flush()}}
            waitFor{activity.isFinishing};assertTrue(app.vault.generation>generation)
            assertTrue("Validated session retained",LoginSessionWatch.selectedCookie(cookie!!)==LoginSessionWatch.selectedCookie(app.vault.read()!!));assertTrue("Same account",identity.id==app.accounts.session.value.account?.id);assertEquals(SessionStatus.AUTHENTICATED,app.accounts.session.value.status)
            assertEquals(settings,app.settings.settings.first());assertTrue("Queue retained",before?.copy(savedAt=0)==app.stores.load(app.accountKey)?.copy(savedAt=0))
            save("automatic-login",buildJsonObject{put("detectedAutomatically",true);put("savedAndReturned",true);put("buttonPressed",false);put("sameAccount",true);put("settingsAndQueueRetained",true);put("credentialsExported",false)})
        } finally {i.runOnMainSync{if(!activity.isFinishing)activity.finish()}}
    }

    @Test fun invalidCandidateDoesNotReplaceCurrentAccountAndCancelClearsWebCookies()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("accountRead")=="1")
        val cookie=app.vault.read();val generation=app.vault.generation
        val activity=i.startActivitySync(Intent(app,WebLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as WebLoginActivity
        try {
            waitFor{ready(activity)}
            i.runOnMainSync{CookieManager.getInstance().setCookie("https://www.bilibili.com/","SESSDATA=invalid-account-login-fixture; Domain=.bilibili.com; Path=/; Secure")}
            waitFor{find("登录已失效。请完成网页验证，或点击重新检查。")!=null}
            assertFalse(activity.isFinishing);assertTrue("Session retained",cookie==app.vault.read());assertEquals(generation,app.vault.generation)
            click("取消登录，返回应用");waitFor{activity.isDestroyed}
            waitFor{var empty=false;i.runOnMainSync{empty=CookieManager.getInstance().getCookie("https://www.bilibili.com/").isNullOrBlank()};empty}
            save("invalid-candidate",buildJsonObject{put("automaticVerificationFailed",true);put("previousAccountRetained",true);put("cancelClearedWebCookies",true)})
        } finally {i.runOnMainSync{if(!activity.isFinishing)activity.finish()}}
    }
}
