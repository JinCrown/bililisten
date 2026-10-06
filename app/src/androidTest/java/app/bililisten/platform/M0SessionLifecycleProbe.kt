package app.bililisten.platform

import android.webkit.CookieManager
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.MainViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference

/** Explicit device-only probes. Real credentials stay in this process and are never logged/exported. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class M0SessionLifecycleProbe {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private val phase=InstrumentationRegistry.getArguments().getString("m0phase")
    private fun <T> main(f:()->T):T {val v=AtomicReference<T>();val e=AtomicReference<Throwable>();i.runOnMainSync{try{v.set(f())}catch(t:Throwable){e.set(t)}};e.get()?.let{throw it};return v.get()}
    private fun waitUntil(f:()->Boolean){val end=System.currentTimeMillis()+35000;while(System.currentTimeMillis()<end){if(f())return;Thread.sleep(100)};throw AssertionError("Session condition timed out")}
    private fun save(name:String,value:JsonObject){File(app.filesDir,"m0-evidence").mkdirs();File(app.filesDir,"m0-evidence/$name.json").writeText(value.toString())}
    private fun keyExists()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.containsAlias("bili-listen-m0-session")

    @Test fun realLogoutAndRejectedSessionThenRestoreOriginalAccount()=runBlocking {
        assumeTrue(phase=="session-lifecycle")
        val original=app.vault.read() ?: throw AssertionError("Requires an existing user session")
        val account=app.api.account()
        val originalResume=app.database.listenDao().resume(account.id.toString())
        val store=ViewModelStore()
        val vm=main{MainViewModel(app).also{store.put("session-probe",it)}}
        try {
            waitUntil{main{!vm.state.value.busy&&vm.state.value.connected&&vm.state.value.account?.id==account.id}}
            main{CookieManager.getInstance().setCookie("https://www.bilibili.com/","m0_logout_fixture=1; Secure");vm.logout()}
            waitUntil{main{!vm.state.value.busy&&vm.state.value.account==null}}
            waitUntil{main{CookieManager.getInstance().getCookie("https://www.bilibili.com/").isNullOrEmpty()}}
            assertNull(app.vault.read());assertFalse(File(app.noBackupFilesDir,"session.enc").exists());assertFalse(keyExists())
            assertEquals("guest",app.accountKey)
            assertEquals(originalResume,app.database.listenDao().resume(account.id.toString()))
            // A deliberately invalid local session exercises the real server rejection and cleanup path.
            // It is not represented as a naturally expired or revoked real account session.
            app.vault.save("SESSDATA=m0-invalid-local-fixture; bili_jct=m0-invalid-fixture")
            main{vm.checkAccount()}
            waitUntil{main{!vm.state.value.busy}}
            assertNull(app.vault.read());assertFalse(keyExists())
            assertTrue(main{vm.state.value.message.contains("登录已失效")})
            assertFalse(main{vm.state.value.playRequested})
            save("session-lifecycle",buildJsonObject{put("realViewModelLogout",true);put("vaultFileRemoved",true);put("keyRemoved",true);put("webCookiesCleared",true);put("resumePreserved",true);put("invalidFixtureRejectedByRealServer",true);put("invalidSessionCleared",true);put("naturalExpiryTested",false);put("credentialExported",false)})
        }finally {
            app.vault.save(original)
            main{vm.loginFinished()}
            waitUntil{main{!vm.state.value.busy&&vm.state.value.account?.id==account.id}}
            assertFalse(main{vm.state.value.playRequested})
            main{store.clear()}
        }
    }

    @Test fun offlineStartupKeepsValidCredentialAndBlocksResume() {
        assumeTrue(phase=="session-offline")
        val before=File(app.noBackupFilesDir,"session.enc").readBytes()
        require(app.vault.read()!=null)
        val store=ViewModelStore()
        val vm=main{MainViewModel(app).also{store.put("offline-probe",it)}}
        try {
            waitUntil{main{!vm.state.value.busy}}
            assertFalse(main{vm.state.value.accountChecked})
            assertNotNull(app.vault.read())
            assertArrayEquals(before,File(app.noBackupFilesDir,"session.enc").readBytes())
            assertFalse(main{vm.state.value.playRequested})
            save("session-offline",buildJsonObject{put("realOfflineRequestFailed",true);put("encryptedCredentialUnchanged",true);put("accountNotFalselyExpired",true);put("resumeBlockedUntilVerification",true);put("autoplay",false)})
        }finally{main{store.clear()}}
    }
}
