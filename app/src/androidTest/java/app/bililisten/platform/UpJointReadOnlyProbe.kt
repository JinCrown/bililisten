package app.bililisten.platform

import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UpJointReadOnlyProbe {
    @Test fun inspectPublicCreatorUploadsWithoutPlayingOrWriting()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
        val settings=app.settings.current();val cookie=app.vault.read();val generation=app.vault.generation
        val queue=app.stores.load(app.accountKey)
        val result=buildJsonObject {
            try {
                val page=app.upLibrary.uploads(3546694058773380,1,UploadOrder.NEWEST,"")
                put("uploadRead",true);put("rows",page.items.size)
                var collaborators=0
                for(row in page.items.take(3)) {
                    val video=app.content.video(row.bvid)
                    if(video.owner!=3546694058773380 && video.hasCreator(3546694058773380))collaborators++
                }
                put("verifiedJointVideos",collaborators)
            }catch(e:PlatformFailure){put("uploadRead",false);put("category",e.category);put("code",e.code)}
            put("remoteWrites",0);put("playbackCommands",0)
        }
        assertEquals(settings,app.settings.current());assertEquals(cookie,app.vault.read());assertEquals(generation,app.vault.generation);assertEquals(queue,app.stores.load(app.accountKey))
        File(app.filesDir,"seven-issues-evidence").apply{mkdirs()}.resolve("up-readonly.json").writeText(result.toString())
    }
}
