package app.bililisten.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountHomeOnlineTest {
    @Test fun loggedInMobileFeedAndAuthorsAreReadOnlyAndKeepSettings()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
        val cookie=app.vault.read();assertFalse(cookie.isNullOrBlank())
        val generation=app.vault.generation;val settings=app.settings.current()
        assertTrue(app.api.account().id>0)
        val rows=app.recommendations.accountHome(emptySet()){}
        val authors=app.recommendations.accountCreators(emptySet())
        assertTrue(rows.isNotEmpty());assertTrue(rows.size<=12);assertEquals(rows.size,rows.distinctBy{it.bvid}.size)
        assertTrue(rows.all{it.parts>0&&it.plays>=0});assertTrue(authors.isNotEmpty());assertTrue(authors.all{it.mid>0})
        assertTrue("Credentials must remain unchanged",cookie==app.vault.read())
        assertEquals(generation,app.vault.generation);assertTrue("Settings must remain unchanged",settings==app.settings.current())
        println("Mobile home read: videos=${rows.size}, creators=${authors.size}, belowMusicThreshold=${rows.count{it.plays<2000000}}, settingsUnchanged=true")
    }
}
