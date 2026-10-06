package app.bililisten.playback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Prepare and restore only. The two-hour playback itself runs without instrumentation. */
@RunWith(AndroidJUnit4::class)
class M5SoakPreparation {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
    private val phase=InstrumentationRegistry.getArguments().getString("m5phase")
    private val backup get()=File(app.noBackupFilesDir,"m5-soak-original.json")
    @Test fun prepareStandalonePlayback()=runBlocking {
        assumeTrue(phase=="prepare-soak")
        check(!backup.exists()) { "Restore prior soak backup first" }
        val account=app.accounts.verify() ?: error("Existing test account required")
        app.accountKey=account.id.toString()
        val original=app.stores.load(app.accountKey) ?: error("Existing resume required")
        val settings=app.settings.settings.first()
        val video=app.content.video("BV1c4411d7jb")
        val part=video.parts.single{it.number==2}
        backup.writeText(buildJsonObject {
            put("snapshot",Json.encodeToJsonElement(original));put("settings",Json.encodeToJsonElement(settings))
        }.toString())
        try {
            val entry=QueueEntry("m5-soak",video.bvid,part.cid,part.number,"${video.title} · ${part.title}")
            val queue=ResumeSnapshot(account=app.accountKey,entries=listOf(entry),order=listOf(entry.id),currentId=entry.id,positionMs=0,mode=PlayMode.REPEAT_ONE)
            app.settings.update(settings.copy(historyEnabled=false,mobilePlayback=false))
            app.stores.checkpoint(PlaybackSnapshot(queue,app.clock.nowMs(),PauseReason.USER),null)
            assertEquals(PlayMode.REPEAT_ONE,app.stores.load(app.accountKey)!!.queue.mode)
            assertTrue(app.settings.settings.first().historyEnabled)
        } catch(e:Throwable) { restore();throw e }
    }
    private suspend fun restore(){
        val data=Json.parseToJsonElement(backup.readText()).jsonObject
        val snapshot=Json.decodeFromJsonElement<PlaybackSnapshot>(data.getValue("snapshot"))
        val settings=Json.decodeFromJsonElement<UserSettings>(data.getValue("settings"))
        app.stores.checkpoint(snapshot,null);app.settings.update(settings)
        assertEquals(snapshot,app.stores.load(snapshot.queue.account))
        assertEquals(settings,withTimeout(5000){app.settings.settings.first{it==settings}})
        check(backup.delete())
    }
    @Test fun restoreOriginalAfterHostStoppedApp()=runBlocking {
        assumeTrue(phase=="restore-soak")
        check(backup.exists()) { "No soak backup: refusing to replace user state" }
        restore()
    }
}
