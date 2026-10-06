package app.bililisten.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.shared.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeFileStoreTest {
    @Test fun previewsStayBoundedIsolatedExpireAndRecoverFromCorruption()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"startup-test-${System.nanoTime()}")
        var now=100000L
        val store=HomeFileStore(directory,CoverStore(context),object:Clock{override fun nowMs()=now})
        try {
            val rows=(1..40).map{Recommendation("BV1xx411c7mD","item$it",null)}
            store.recommendations("7",rows)
            assertEquals(20,store.read("7")!!.recommendations.size);assertNull(store.read("8"))
            val music=PopularMusic("BV1xx411c7mD","music","cover","UP",6000000,150,
                ContentSource(SourceRef(SourceKind.UP_COLLECTION,22,8,CollectionKind.SEASON),"season","UP",42,"cover"))
            store.popularMusic("7",listOf(music,music.copy(plays=4999999)))
            assertEquals(listOf(music),store.read("7")!!.popularMusic);assertNull(store.read("8"))
            val video=Video("BV1xx411c7mD",1,"title",listOf(VideoPart(1,1,"P1")))
            store.metadata("7",video);assertEquals(video,store.read("7")!!.metadata[video.bvid])
            store.clearMetadata("7");assertTrue(store.read("7")!!.metadata.isEmpty())
            assertEquals(20,store.read("7")!!.recommendations.size)
            now+=8L*86400000;assertNull(store.read("7"))
            store.recommendations("7",rows);assertNotNull(store.read("7"))
            directory.listFiles()!!.single().writeText("broken")
            assertNull(store.read("7"));store.recommendations("7",rows);assertNotNull(store.read("7"))
            for(account in 8..15)store.recommendations("$account",rows)
            assertTrue(directory.listFiles()!!.size<=4)
            assertTrue(directory.listFiles()!!.all{it.length()<=512*1024})
        } finally {directory.listFiles().orEmpty().forEach{it.delete()};directory.delete()}
    }
}
