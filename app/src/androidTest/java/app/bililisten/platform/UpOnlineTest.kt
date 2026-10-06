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
class UpOnlineTest {
    @Test fun officialSearchAndUploadsConfirmCreatorIdentityAcrossPages()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
        val folder=File(app.filesDir,"up-library-evidence").apply{mkdirs()}
        val report=File(folder,"official.json")
        var searched=false
        try {
            val result=app.upLibrary.search("JLRS-LeoFM")
            val profile=result.items.first{it.name=="JLRS-LeoFM"};searched=true
            val first=app.upLibrary.uploads(profile.mid)
            val second=app.upLibrary.uploads(profile.mid,2)
            assertEquals(profile.source,first.source.ref);assertEquals(profile.source,second.source.ref)
            assertTrue(first.items.isNotEmpty());assertTrue(first.source.count>1000);assertTrue(first.hasMore)
            assertTrue(second.items.isNotEmpty());assertTrue(second.items.any{it.bvid !in first.items.map{row->row.bvid}})
            val video=app.content.video(first.items.first().bvid)
            assertEquals(profile.mid,video.owner);assertTrue(video.parts.first{it.number==1}.cid>0)
            report.writeText("""{"search":true,"creatorMid":${profile.mid},"total":${first.source.count},"firstPage":${first.items.size},"secondPage":${second.items.size},"p1OwnerVerified":true,"userPlaybackStarted":false,"remoteWrites":false}""")
        }catch(e:PlatformFailure){
            report.writeText("""{"search":$searched,"uploads":false,"failureCode":${e.code ?: 0},"userPlaybackStarted":false,"remoteWrites":false}""")
            throw e
        }
    }
}
