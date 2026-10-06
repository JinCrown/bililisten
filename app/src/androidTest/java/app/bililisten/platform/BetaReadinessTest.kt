package app.bililisten.platform

import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.MainActivity
import app.bililisten.shared.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Destructive fixtures are allowed only on an explicitly opted-in disposable emulator. */
@RunWith(AndroidJUnit4::class)
class BetaReadinessTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as ListenApplication
    private val args get() = InstrumentationRegistry.getArguments()
    private val source = SourceRef(SourceKind.UP_COLLECTION, 42, 8, CollectionKind.SEASON)
    private fun guard() {
        check(args.getString("betaReadiness") == "1" && Build.HARDWARE in listOf("ranchu", "goldfish")) {
            "This probe may only run on an opted-in disposable emulator, never a user's phone"
        }
        assertNull("Real session must not be present", app.vault.read())
        assertEquals("guest", app.accountKey)
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.text?.toString()?.contains(label) == true || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun await(label: String) {
        val deadline = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < deadline) {
            if (find(label) != null) return
            Thread.sleep(100)
        }
        fail("Expected UI was not displayed: $label")
    }
    private fun click(label: String) {
        await(label)
        var node = find(label)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
    private fun launch() {
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        await("我的")
    }
    private fun settings() = UserSettings(theme = Theme.DARK, historyLimit = 321, mobilePlayback = false)
    private fun snapshot() = PlaybackSnapshot(ResumeSnapshot(account = "guest",
        entries = listOf(QueueEntry("beta-a", "BV1xx411c7mD", 62131, 1, "Beta upgrade fixture", source = source)),
        order = listOf("beta-a"), currentId = "beta-a", positionMs = 12345), 1000, PauseReason.USER)

    @Test fun freshGuestStartsWithSafeDefaultsAndEmptyLocalData() = runBlocking {
        guard()
        assertEquals("fresh", args.getString("phase"))
        assertEquals(UserSettings(), app.settings.current())
        assertTrue(app.stores.observe("guest").first().isEmpty())
        assertTrue(app.stores.bookmarks("guest").isEmpty())
        assertNull(app.stores.load("guest"))
        assertFalse(RoomRemoteHistoryStore(app.database.listenDao()).read("guest").enabled)
        launch()
        assertNull(app.vault.read())
        assertTrue(app.settings.current().historyEnabled)
        assertNull(app.settings.current().mobilePlayback)
    }

    @Test fun offlineLoginCanBeCancelledWithoutCreatingASession() {
        guard()
        assertEquals("offline", args.getString("phase"))
        launch()
        app.startActivity(Intent(app, WebLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("登录网页未能载入")
        click("取消登录，返回应用")
        await("我的")
        assertNull(app.vault.read())
    }

    @Test fun officialLoginPageOpensAndCancelLeavesGuestUsable() {
        guard()
        assertEquals("online", args.getString("phase"))
        launch()
        val activity = instrumentation.startActivitySync(Intent(app, WebLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("未自动返回？重新检查")
        await("在 B 站官方网页完成登录")
        fun web(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) web(view.getChildAt(index))?.let { return it }
            return null
        }
        var loaded = false
        val deadline = System.currentTimeMillis() + 30000
        while (!loaded && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                val browser = web(activity.window.decorView)
                loaded = browser?.progress == 100 && browser.url?.startsWith("https://passport.bilibili.com/") == true
            }
            if (!loaded) Thread.sleep(100)
        }
        assertTrue("Official HTTPS login page did not finish loading", loaded)
        assertNull(find("登录网页未能载入"))
        assertNull(find("无法清理网页登录会话"))
        click("取消登录，返回应用")
        await("我的")
        assertNull(app.vault.read())
    }

    @Test fun guestCanSearchRealPublicVideos() = runBlocking {
        guard()
        assertEquals("online", args.getString("phase"))
        assertTrue(app.api.search("music").items.isNotEmpty())
        assertNull(app.vault.read())
    }

    @Test fun seedUpgradeFixturesWithoutPlayingOrUsingARealAccount() = runBlocking<Unit> {
        guard()
        assertEquals("seed", args.getString("phase"))
        app.settings.update(settings())
        app.stores.checkpoint(snapshot(), LocalHistoryEntry("guest", VideoRef("BV1xx411c7mD", 62131, 1),
            "Beta upgrade fixture", source, 12345, System.currentTimeMillis()))
        app.stores.saveCollection(CollectionSnapshot("guest", source, emptyList(), true, 1000, 1,
            title = "Beta collection fixture", bookmarked = true))
        CredentialVault(app, "beta-upgrade-test").save("non-secret-upgrade-fixture")
        app.getSharedPreferences("beta-readiness", 0).edit().putLong("seedVersion",
            app.packageManager.getPackageInfo(app.packageName, 0).versionCode.toLong()).commit()
    }

    @Test fun sameSignerUpgradeKeepsSettingsHistoryCollectionQueueAndEncryptedKey() = runBlocking {
        guard()
        assertEquals("verify", args.getString("phase"))
        val old = app.getSharedPreferences("beta-readiness", 0).getLong("seedVersion", -1)
        assertTrue(old > 0 && app.packageManager.getPackageInfo(app.packageName, 0).versionCode > old)
        assertEquals(settings(), app.settings.current())
        assertEquals(snapshot(), app.stores.load("guest"))
        assertEquals(12345L, app.stores.observe("guest").first().single().positionMs)
        assertEquals("Beta collection fixture", app.stores.bookmarks("guest").single().title)
        assertEquals("non-secret-upgrade-fixture", CredentialVault(app, "beta-upgrade-test").apply { restore() }.read())
        assertFalse(RoomRemoteHistoryStore(app.database.listenDao()).read("guest").enabled)
        launch()
        assertEquals(12345L, app.stores.load("guest")!!.queue.positionMs)
        assertNull(app.vault.read())
    }
}
