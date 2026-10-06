package app.bililisten.playback

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlinx.serialization.json.*

/** Explicit opt-in, read-only remote requests, one item in the authorized music folder. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class M1OnlineRegression {
    @Test fun repositoriesAndPlaybackPortRetainSessionAndRestoreOriginalLocalState() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m1online") == "1")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
        val account=app.accounts.verify()!!; app.accountKey=account.id.toString()
        val original=app.stores.load(app.accountKey) ?: error("Existing resume required for this scoped regression")
        val stamp=app.accounts.session.value.stamp
        val folder=app.favorites.folders(account.id).single{it.title=="音乐"}
        val source=SourceRef(SourceKind.OWN_FAVORITES,folder.id,account.id)
        val page=app.sources.content(source,account.id)
        CollectionCoordinator(app.stores,app.accounts,app.clock).save(page,stamp)
        assertEquals(source,app.stores.collection(app.accountKey,source)!!.source)
        val video=app.content.video(page.items.first().bvid); val part=video.parts.first()
        val oldHistory=app.database.listenDao().historyEntry(app.accountKey,video.bvid,part.cid)
        val player=withContext(Dispatchers.Main){ControllerPlaybackPort(app)}
        try {
            withTimeout(15000){while(!player.state.value.connected)delay(50)}
            assertFalse(player.state.value.requested)
            val entry=QueueEntry("m1-smoke",video.bvid,part.cid,part.number,video.title,source=source)
            val queue=ResumeSnapshot(account=app.accountKey,entries=listOf(entry),order=listOf(entry.id),currentId=entry.id,positionMs=0)
            withContext(Dispatchers.Main){player.replace(queue,true)}
            withTimeout(30000){while(!player.state.value.playing){assertNull(player.state.value.error);delay(100)}}
            val before=player.state.value.positionMs; delay(3000)
            val advanced=player.state.value.positionMs-before; assertTrue(advanced>=1500)
            withContext(Dispatchers.Main){player.pause(PauseReason.EXTERNAL_VIDEO);player.flush()}
            val persisted=app.stores.load(app.accountKey)!!
            assertEquals(source,persisted.queue.entries.single().source)
            assertEquals(PauseReason.EXTERNAL_VIDEO,persisted.pauseReason)
            assertTrue(persisted.queue.positionMs>1000)
            val resume=ResumeCoordinator(app.stores,app.accounts,app.sources,app.clock).prepare()!!
            assertFalse(resume.autoplay)
            assertNotNull(app.vault.read())
            File(app.filesDir,"m1-evidence").mkdirs()
            File(app.filesDir,"m1-evidence/online.json").writeText(buildJsonObject {
                put("accountVerified",true);put("musicReadOnly",true);put("mirrorPersisted",true);put("advancedMs",advanced)
                put("sourcePreserved",true);put("pauseReason",persisted.pauseReason.name);put("resumeAutoplay",resume.autoplay)
            }.toString())
        } finally {
            withContext(Dispatchers.Main){player.pause();player.clear();player.close()}
            app.stores.checkpoint(original,null)
            if(oldHistory==null)app.database.listenDao().deleteHistory(app.accountKey,video.bvid,part.cid) else app.database.listenDao().played(oldHistory)
            assertEquals(original,app.stores.load(app.accountKey))
        }
    }
}
