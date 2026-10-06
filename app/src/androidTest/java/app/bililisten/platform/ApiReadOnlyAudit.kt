package app.bililisten.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.BuildConfig
import app.bililisten.shared.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Opt-in GETs only. Evidence contains no account IDs, message text, cookies or signed media URLs. */
@RunWith(AndroidJUnit4::class)
class ApiReadOnlyAudit {
    @Test fun inspectCurrentProtocolsWithoutPlaybackOrRemoteWrites()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("apiReadOnlyAudit")=="1")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
        val owner=app.accountKey
        val beforeSettings=app.settings.current()
        val beforeQueue=app.stores.load(owner)
        val beforeCookie=app.vault.read()
        val beforeGeneration=app.vault.generation
        val client=HttpClient(OkHttp) {
            followRedirects=false
            install(HttpTimeout){requestTimeoutMillis=15000;connectTimeoutMillis=10000;socketTimeoutMillis=15000}
            engine{config{
                cache(null);followRedirects(false);followSslRedirects(false)
                addInterceptor {chain->
                    check(chain.request().method=="GET"){"Read-only audit forbids remote mutations"}
                    chain.proceed(chain.request())
                }
            }}
        }
        val md5:(ByteArray)->String={bytes->MessageDigest.getInstance("MD5").digest(bytes).joinToString(""){"%02x".format(it)}}
        val api=BiliApi(client,generation={app.vault.generation},searchClock=app.clock,searchMd5=md5,credentials=app.vault::read)
        val messages=BiliMessageRepository(api)
        val rows=mutableListOf<JsonObject>()
        val blocked=mutableSetOf<String>()
        suspend fun <T> read(name:String,host:String="api",block:suspend ()->T):T? {
            if(host in blocked){rows+=buildJsonObject{put("check",name);put("status","skipped_after_platform_challenge")};return null}
            return try {
                val value=withTimeout(20000){block()}
                rows+=buildJsonObject{put("check",name);put("status","success")}
                delay(350)
                value
            }catch(e:CancellationException){throw e}catch(e:PlatformFailure){
                rows+=buildJsonObject{put("check",name);put("status","unavailable");put("category",e.category);put("code",e.code)}
                if(e.code in listOf(-352,-403,-412,403,412,429))blocked+=host
                null
            }catch(_:Exception){rows+=buildJsonObject{put("check",name);put("status","unexpected_response")};null}
        }
        try {
            val account=read("account_nav"){api.account()}
            read("account_stat"){api.followingCount()}
            read("history_paused_read"){api.historyPaused()}
            read("history_cursor_read"){api.historyPage()}
            val video=read("signed_rich_video_detail"){api.video("BV1xYWBeMERL")}
            if(video!=null) {
                for(part in video.parts.take(2)) {
                    read("signed_audio_P${part.number}"){api.audioProbe(video.bvid,part.cid,true).also{assertTrue(it.tracks.isNotEmpty())}}
                    read("signed_subtitles_P${part.number}"){api.subtitleTracks(VideoRef(video.bvid,part.cid,part.number))}
                }
                read("video_tags"){api.videoTags(video.bvid)}
                read("video_relation_read"){api.videoRelations(video.bvid)}
                read("signed_up_profile"){api.upProfile(video.owner,app.clock.nowMs(),md5)}
                read("signed_up_uploads"){api.upUploads(video.owner,1,UploadOrder.NEWEST,"",app.clock.nowMs(),md5)}
            }
            read("signed_video_search"){api.search("音乐",1)}
            read("signed_up_search"){api.upSearch("音乐",1,app.clock.nowMs(),md5)}
            read("music_ranking"){api.homeRecommendations(HomeCategory.MUSIC,RecentRecommendationWindow(app.clock.nowMs()),md5)}
            if(account!=null) {
                val folders=read("own_folders_read"){api.folders(account.id)}
                folders?.firstOrNull()?.let { folder->
                    read("favorite_items_read"){api.favorites(folder.id,1)}
                    read("folder_info_read"){api.ownFolder(account.id,folder.id)}
                }
                read("public_folders_read"){api.publicFolders(account.id)}
                read("followed_sources_read"){api.followedSources(account.id)}
            }
            read("live_ranking","live"){api.liveRanking()}
            val room=read("consolidated_live_room","live"){api.liveRoom(21452505)}
            if(room!=null)read("live_playinfo_v2","live"){api.liveStreams(room.roomId)}
            val inbox=read("signed_private_sessions","im"){messages.sessions(null)}
            inbox?.items?.firstOrNull()?.let{session->read("signed_private_conversation","im"){messages.conversation(session,null)}}
            read("message_unread_read"){messages.unread()}
            for(category in listOf(MessageCategory.REPLY,MessageCategory.AT,MessageCategory.LIKE,MessageCategory.SYSTEM)) {
                read("notice_${category.name.lowercase()}",if(category==MessageCategory.SYSTEM)"message" else "api"){messages.notices(category,null)}
            }
            val assist=BiliSearchAssistRepository(api)
            read("signed_search_square"){assist.hot()}
            read("current_search_suggest"){assist.suggest("音乐")}
            read("season_archives_read"){api.seasonSource(57445,1,2142762)}
            read("series_metadata_and_archives_read"){api.seriesSource(547718,1958703906)}
            assertEquals(beforeSettings,app.settings.current())
            assertEquals(beforeQueue,app.stores.load(owner))
            assertEquals(beforeCookie,app.vault.read())
            assertEquals(beforeGeneration,app.vault.generation)
            assertEquals(owner,app.accountKey)
        }finally {
            client.close()
            File(app.filesDir,"api-audit").apply{mkdirs()}.resolve("readonly.json").writeText(buildJsonObject {
                put("version",BuildConfig.VERSION_NAME)
                put("device",android.os.Build.MODEL);put("sdk",android.os.Build.VERSION.SDK_INT)
                put("checks",JsonArray(rows));put("remoteWrites",0);put("playbackCommands",0)
                put("allReadsSucceeded",rows.isNotEmpty()&&rows.all{it["status"]?.jsonPrimitive?.content=="success"})
                put("settingsPreserved",beforeSettings==app.settings.current())
                put("queuePreserved",beforeQueue==app.stores.load(owner))
                put("credentialsPreserved",beforeCookie==app.vault.read()&&beforeGeneration==app.vault.generation)
            }.toString())
        }
        assertTrue("At least one migrated content read must succeed",rows.any{it["check"]?.jsonPrimitive?.content=="signed_rich_video_detail"&&it["status"]?.jsonPrimitive?.content=="success"})
    }
}
