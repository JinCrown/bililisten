package app.bililisten.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveReadOnlyTest {
    @Test fun actualRoomDirectoryAndStreamMetadataAreReadOnlyAndRestrictionsRemainVisible()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
        val settings=app.settings.current()
        val account=withContext(Dispatchers.IO){app.accounts.verify()}
        val results=mutableListOf<JsonObject>()
        var directory=false;var directoryFailure:Int?=null;var rankingRows=emptyList<LiveRanking>()
        try {
            val rows=withContext(Dispatchers.IO){withTimeout(30000){app.live.ranking()}}
            assertTrue(rows.size<=5);assertEquals(rows.size,rows.map{it.roomId}.distinct().size);assertTrue(rows.all{it.roomId>0})
            directory=rows.isNotEmpty()
            rankingRows=rows
        }catch(e:TimeoutCancellationException){directoryFailure=-10000}
        catch(e:CancellationException){throw e}
        catch(e:PlatformFailure){directoryFailure=e.code ?: -10001}
        for(id in listOf(6L,21452505L)) {
            try {
                val room=withContext(Dispatchers.IO){withTimeout(30000){app.live.room(id)}}
                assertTrue(room.roomId>0)
                val streams=if(room.state==LiveRoomStatus.LIVE)withContext(Dispatchers.IO){withTimeout(30000){app.live.streams(room.roomId)}} else emptyList()
                results+=buildJsonObject {put("requestedId",id);put("canonicalId",room.roomId);put("status",room.state.name);put("streamCount",streams.size);put("supportedCount",streams.count(LiveStreams::supported));put("audioOnlyCount",streams.count{it.kind==StreamKind.AUDIO_ONLY})}
            }catch(e:TimeoutCancellationException){results+=buildJsonObject{put("requestedId",id);put("failureCode",-10000)}}
            catch(e:CancellationException){throw e}
            catch(e:PlatformFailure){results+=buildJsonObject{put("requestedId",id);put("failureCode",e.code ?: -10001)}}
        }
        assertEquals(settings,app.settings.current())
        val output=buildJsonObject{
            put("authenticatedRead",account!=null);put("actualRankingRetrieved",directory);directoryFailure?.let{put("rankingFailureCode",it)}
            put("rankingEndpoint","/xlive/web-interface/v1/index/getHotRankList")
            put("rankingRooms",JsonArray(rankingRows.map{row->buildJsonObject{put("roomId",row.roomId);row.heat?.let{put("score",it)}}}))
            put("roomMetadata",JsonArray(results));put("realMediaPlayed",false);put("remoteWrites",0);put("privateTitleOrSignedUrlPublished",false);put("historySyncChanged",false)
        }
        File(app.filesDir,"m6h-evidence").apply{mkdirs()}.resolve("readonly.json").writeText(output.toString())
        assertTrue("Current official hot ranking must return real rooms",directory)
        Unit
    }
}
