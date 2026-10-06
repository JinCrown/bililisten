package app.bililisten.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText

/** Opt-in, bounded live probes. The normal test suite never contacts a real account. */
@RunWith(AndroidJUnit4::class)
class M0OnlineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val app = instrumentation.targetContext.applicationContext as ListenApplication
    private fun enabled(phase: String) = assumeTrue(args.getString("m0phase") == phase)
    private fun save(name: String, data: JsonElement) {
        File(app.filesDir,"m0-evidence").mkdirs()
        File(app.filesDir,"m0-evidence/$name.json").writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(),data))
    }

    @Test fun accountAndMusicFolderRead() = runBlocking {
        enabled("account")
        val account = app.api.account()
        assertTrue(account.id > 0)
        val folders = app.api.folders(account.id)
        val music = folders.filter { "音乐" in it.title }
        save("account", buildJsonObject {
            put("restoredAccountValid",true); put("folderCount",folders.size)
            put("musicFolders",buildJsonArray { music.forEach { f -> add(buildJsonObject {
                put("id",f.id); put("title",f.title); put("count",f.count); put("attr",f.attr)
            }) } })
            put("credentialsExported",false)
        })
        assertTrue("No music folder found; choose explicitly",music.isNotEmpty())
    }

    @Test fun authenticatedContentAndSearch() = runBlocking {
        enabled("content")
        val results = mutableListOf<JsonObject>()
        val samples = listOf("music" to "BV1U1421r7SM", "knowledge" to "BV17ghy6AEEs", "game" to "BV1Qtht61EiG", "multipart" to "BV1c4411d7jb")
        for ((kind,bvid) in samples) {
            try {
                val video = app.api.video(bvid)
                val parts = video.parts.take(2).map { part ->
                    val audio = app.api.audioProbe(bvid,part.cid)
                    buildJsonObject {
                        put("cid",part.cid); put("part",part.number); put("title",part.title)
                        put("mixedAvailable",audio.mixedAvailable); put("durationMs",audio.durationMs)
                        put("tracks",buildJsonArray { audio.tracks.forEach { t -> add(buildJsonObject {
                            put("id",t.id); put("mime",t.mime); put("codec",t.codec); put("bandwidth",t.bandwidth)
                        }) } })
                    }
                }
                results += buildJsonObject { put("kind",kind);put("bvid",bvid);put("partCount",video.parts.size);put("parts",JsonArray(parts)) }
            } catch (failure: PlatformFailure) {
                results += buildJsonObject { put("kind",kind);put("code",failure.code);put("category",failure.category) }
                if (failure.code in listOf(403,412,429,-403,-412,-352)) break
            }
        }
        save("content",JsonArray(results))
        val searches=mutableListOf<JsonObject>()
        for ((query,page) in listOf("科普" to 1,"科普" to 2,"m0-no-result-20260928-8be617d" to 1)) {
            try { val result=app.api.search(query,page)
                searches += buildJsonObject { put("query",query); put("page",result.page);put("pages",result.pages);put("total",result.total)
                    put("items",buildJsonArray { result.items.take(3).forEach { add(buildJsonObject { put("bvid",it.bvid);put("title",it.title) }) } }) }
            } catch (failure: PlatformFailure) { searches += buildJsonObject { put("query",query);put("code",failure.code) }; break }
        }
        save("search",JsonArray(searches))
    }

    @Test fun musicFolderWriteAndRollback() = runBlocking {
        enabled("favorite")
        // Separate explicit test argument is required; never selects a folder by position.
        val title = requireNotNull(args.getString("folderTitle"))
        val bvid = requireNotNull(args.getString("bvid"))
        val account = app.api.account(); val video = app.api.video(bvid)
        val folder = app.api.folders(account.id,video.aid).single { it.title == title }
        require(folder.contains == false) { "Video already existed; not allowed to remove it" }
        val outcome = app.api.changeFavorite(account.id,video.aid,folder.id,true)
        save("favorite-added",buildJsonObject { put("outcome",outcome.name);put("bvid",bvid);put("wasPresent",false);put("folderTitle",title) })
        assertEquals(MutationOutcome.CONFIRMED,outcome)
        // Leave the addition visible for official-client verification. Removal is a separate phase.
    }

    @Test fun rollbackOnlyRecordedAddition() = runBlocking {
        enabled("rollback")
        val proof = Json.parseToJsonElement(File(app.filesDir,"m0-evidence/favorite-added.json").readText()).jsonObject
        require(proof["wasPresent"]!!.jsonPrimitive.boolean == false)
        require(proof["outcome"]!!.jsonPrimitive.content == "CONFIRMED")
        val bvid=proof["bvid"]!!.jsonPrimitive.content; val title=proof["folderTitle"]!!.jsonPrimitive.content
        require(args.getString("folderTitle") == title)
        val account=app.api.account();val video=app.api.video(bvid)
        val folder=app.api.folders(account.id,video.aid).single { it.title == title }
        val outcome=app.api.changeFavorite(account.id,video.aid,folder.id,false)
        save("favorite-rollback",buildJsonObject { put("outcome",outcome.name);put("bvid",bvid);put("folderTitle",title) })
        assertTrue(outcome != MutationOutcome.UNKNOWN)
    }
    @Test fun reconcileSpecificTestRelationship() = runBlocking {
        enabled("reconcile")
        val account=app.api.account();val video=app.api.video("BV1rHh16CESS")
        val folder=app.api.folders(account.id,video.aid).single { it.title=="音乐" }
        val first=app.api.favorites(folder.id,1);val second=app.api.favorites(folder.id,2)
        val present=first.items.any{it.bvid==video.bvid}
        save("favorite-readback",buildJsonObject{put("contains",folder.contains);put("count",folder.count);put("targetInFirstPage",present);put("page1Size",first.items.size);put("page1HasMore",first.hasMore);put("page2Size",second.items.size);put("page2HasMore",second.hasMore)})
        if(folder.contains==true&&present) {
            val file=File(app.filesDir,"m0-evidence/favorite-added.json")
            val proof=Json.parseToJsonElement(file.readText()).jsonObject.toMutableMap()
            require(proof["wasPresent"]!!.jsonPrimitive.boolean==false)
            proof["outcome"]=JsonPrimitive("CONFIRMED");file.writeText(JsonObject(proof).toString())
        }
    }
    @Test fun boundedDiscoveryAndFolderPermissions() = runBlocking {
        enabled("discovery")
        val account=app.api.account(); val folders=app.api.folders(account.id)
        val result=mutableListOf<JsonElement>()
        result+=buildJsonObject{put("ownFolderCount",folders.size);put("privateFolderCount",folders.count{((it.attr?:0)and 1)==1})}
        // Read-only sample. No foreign or unrelated folder is ever passed to a write endpoint.
        folders.firstOrNull{((it.attr?:0)and 1)==1}?.let{folder->
            val page=app.api.favorites(folder.id,1)
            result+=buildJsonObject{put("ownPrivateReadable",true);put("loadedCount",page.items.size);put("hasMore",page.hasMore)}
        }
        try{val foreign=app.api.folders(2);result+=buildJsonObject{put("foreignOwnerPublicFolderCount",foreign.size);put("foreignWritesAttempted",false)}}
        catch(e:PlatformFailure){result+=buildJsonObject{put("foreignReadCode",e.code);put("foreignWritesAttempted",false)}}
        try{val recs=app.api.recommendations();result+=buildJsonObject{put("recommendations",buildJsonArray{recs.forEach{r->add(buildJsonObject{put("bvid",r.bvid);put("tagCount",r.tags?.size);put("hasMusicTag",r.tags?.any{it in setOf("音乐","演奏","翻唱","纯音乐")}==true)})}})}}
        catch(e:PlatformFailure){result+=buildJsonObject{put("recommendationError",e.code);put("category",e.category)}}
        for(id in listOf(6L,21452505L)){
            try{val room=app.api.liveRoom(id);val streams=if(room.status==1)app.api.liveStreams(room.roomId)else emptyList()
                result+=buildJsonObject{put("requestedRoom",id);put("resolvedRoom",room.roomId);put("title",room.title);put("anchor",room.anchor);put("status",room.status);put("formats",buildJsonArray{streams.forEach{add(it.format+"/"+it.codec)}});put("played",false)}
            }catch(e:PlatformFailure){result+=buildJsonObject{put("requestedRoom",id);put("code",e.code)}}
        }
        save("discovery-permissions",JsonArray(result))
    }
    @Test fun extendedTracksAndDeviceDecoders() = runBlocking {
        enabled("entitlements")
        val probe=app.api.audioProbe("BV1U1421r7SM",1599921141,extended=true)
        val codecs=android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.filter{!it.isEncoder}
        save("entitlements",buildJsonObject{
            put("accountClass","VIP (user confirmed)");put("requestFnval",4048)
            put("tracks",buildJsonArray{probe.tracks.forEach{track->
                val decoderMime=when { track.codec.startsWith("mp4a") -> "audio/mp4a-latm";track.codec.equals("flac",true) -> "audio/flac";track.codec=="ec-3" -> "audio/eac3";else -> track.mime }
                add(buildJsonObject{put("id",track.id);put("codec",track.codec);put("mime",track.mime);put("decoderMime",decoderMime);put("bandwidth",track.bandwidth);put("deviceHasMimeDecoder",codecs.any{c->c.supportedTypes.any{it==decoderMime}})})}})
            put("ordinaryAccountDeferredByUser",true)
        })
    }

    @Test fun boundedFollowupSearchWithoutChangingIdentityOrEndpoint()=runBlocking {
        enabled("search-followup")
        val results=mutableListOf<JsonElement>()
        // A new, explicitly requested follow-up after the earlier run has ended; no automatic retry.
        for((query,page) in listOf("科普" to 2,"m0-no-result-20260928-8be617d" to 1)) {
            try {
                val result=app.api.search(query,page)
                results+=buildJsonObject{put("query",query);put("requestedPage",page);put("returnedPage",result.page);put("pages",result.pages);put("total",result.total);put("count",result.items.size);put("validIdentities",result.items.all{Bvid.parse(it.bvid)==it.bvid});put("firstBvid",result.items.firstOrNull()?.bvid)}
                save("search-followup",JsonArray(results))
                Thread.sleep(5000)
            }catch(e:PlatformFailure) {
                results+=buildJsonObject{put("query",query);put("page",page);put("code",e.code);put("category",e.category);put("stoppedFurtherSearch",true)}
                save("search-followup",JsonArray(results));break
            }
        }
    }

    @Test fun readOnlyForeignAndAnonymousFolderPermissions()=runBlocking {
        enabled("permissions-followup")
        val account=app.api.account()
        val music=app.api.folders(account.id).single{it.title=="音乐"}
        val client=HttpClient(OkHttp){followRedirects=false;install(HttpTimeout){requestTimeoutMillis=15000};engine{config{cache(null)}}}
        val results=mutableListOf<JsonElement>()
        try {
            val own=app.api.favorites(music.id,1)
            results+=buildJsonObject{put("context","owner/music");put("readable",true);put("items",own.items.size);put("attr",music.attr)}
            try {
                val anonymous=BiliApi(client){null}.favorites(music.id,1)
                results+=buildJsonObject{put("context","anonymous/music");put("readable",true);put("items",anonymous.items.size);put("sameItemsAsOwner",anonymous.items==own.items)}
            }catch(e:PlatformFailure){results+=buildJsonObject{put("context","anonymous/music");put("code",e.code);put("category",e.category)}}
            // Public reference sample from PiliPala's API declaration; never send a foreign write.
            val response=client.get("https://api.bilibili.com/x/v3/fav/folder/created/list") {
                header("Referer","https://www.bilibili.com/");header("User-Agent","Mozilla/5.0 BiliListen-M0/0.0.3")
                parameter("up_mid","17340771");parameter("pn","1");parameter("ps","10")
            }
            val json=runCatching{Json.parseToJsonElement(response.bodyAsText()).jsonObject}.getOrNull()
            val data=json?.get("data") as? JsonObject
            results+=buildJsonObject{put("context","anonymous/foreign-owner");put("httpStatus",response.status.value);put("code",json?.get("code")?:JsonNull);put("dataIsNull",json?.get("data")==JsonNull);put("publicFolderCount",(data?.get("list") as? JsonArray)?.size);put("foreignWritesAttempted",false)}
            save("permissions-followup",JsonArray(results))
        }finally{client.close()}
    }
}
