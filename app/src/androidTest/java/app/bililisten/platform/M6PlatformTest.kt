package app.bililisten.platform

import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M6PlatformTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    @Test fun implicitSharingResolvesOnlySupportedKindsAndHosts() {
        fun resolves(intent:Intent)=app.packageManager.queryIntentActivities(intent.setPackage(app.packageName),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).any{it.activityInfo.name=="app.bililisten.MainActivity"}
        assertTrue(resolves(Intent(Intent.ACTION_SEND).setType("text/plain")))
        assertFalse(resolves(Intent(Intent.ACTION_SEND).setType("application/octet-stream")))
        for(url in listOf("https://www.bilibili.com/video/BV1xx411c7mD/","https://b23.tv/example","https://live.bilibili.com/6"))assertTrue(resolves(Intent(Intent.ACTION_VIEW,Uri.parse(url))))
        assertFalse(resolves(Intent(Intent.ACTION_VIEW,Uri.parse("https://example.org/"))))
        assertFalse(resolves(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.bilibili.com/account/"))))
    }
    @Test fun cancellingCandidateLoginKeepsExistingEncryptedSession()=runBlocking {
        app.accounts.verify()
        val generation=app.vault.generation;val account=app.accounts.session.value.stamp.account
        app.startActivity(Intent(app,WebLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun find(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(n==null||!n.refresh())return null
            if(n.text?.toString()=="取消登录，返回应用")return n
            for(k in 0 until n.childCount)find(n.getChild(k))?.let{return it};return null
        }
        val end=System.currentTimeMillis()+15000;var button:AccessibilityNodeInfo?=null
        while(button==null&&System.currentTimeMillis()<end){button=find(i.uiAutomation.rootInActiveWindow);Thread.sleep(100)}
        assertNotNull("Cancel entry should be visible",button)
        assertTrue(button!!.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(500)
        assertEquals(generation,app.vault.generation);assertEquals(account,app.accounts.session.value.stamp.account)
    }
}
