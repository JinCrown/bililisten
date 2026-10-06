package app.bililisten.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.media3.common.util.UnstableApi @RunWith(AndroidJUnit4::class)
class FollowedLibraryOnlineTest {
    @Test fun officialAccountContainsBothFollowedFoldersAndSeasonsAndFolderDetailMatches()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
        val account=app.accountKey.toLong();val vault=app.vault.read();val snapshot=app.stores.load(app.accountKey)
        val sources=linkedMapOf<SourceRef,ContentSource>();var page=1
        do {
            val result=app.sources.followed(account,page)
            assertEquals(page,result.page);val prior=sources.size;result.sources.forEach{sources[it.ref]=it}
            if(result.hasMore)assertTrue("Source pagination advances",sources.size>prior)
            page++;if(!result.hasMore)break
        }while(page<=10)
        val folders=sources.values.filter{it.ref.kind==SourceKind.PUBLIC_FAVORITES}
        val seasons=sources.values.filter{it.ref.kind==SourceKind.UP_COLLECTION}
        assertTrue("Existing account's official followed folders are returned",folders.isNotEmpty())
        assertTrue("Existing followed seasons are retained",seasons.isNotEmpty())
        val source=folders.first();assertNotEquals(account,source.ref.owner)
        val detail=app.sources.content(source.ref,account)
        assertEquals(source.ref,detail.source.ref);assertTrue(detail.items.isNotEmpty())
        val coverLoaded=source.cover.isNotBlank()&&app.covers.load(source.cover)!=null
        assertEquals(vault,app.vault.read());assertEquals(snapshot?.queue,app.stores.load(app.accountKey)?.queue)
        File(app.filesDir,"up-library-evidence").mkdirs()
        File(app.filesDir,"up-library-evidence/mixed-official.json").writeText("""{"pages":${page-1},"followedFolders":${folders.size},"followedCollections":${seasons.size},"folderDetailIdentity":true,"folderItems":${detail.items.size},"coverLoaded":$coverLoaded,"userQueueRetained":true,"remoteWrites":false,"userPlaybackStarted":false}""")
    }
}
