package app.bililisten.playback

import android.net.ConnectivityManager
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@UnstableApi @RunWith(AndroidJUnit4::class)
class M6DOnlineProbe {
    @Test fun readRealTracksGuestAndLoggedThenValidateMediaPositionSyncWithoutHistoryWrites()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m6donline")=="1")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
        val network=app.getSystemService(ConnectivityManager::class.java)
        assertNotNull(network.activeNetwork);assertFalse(network.isActiveNetworkMetered)
        val logged=app.accounts.verify();assertNotNull("Keep existing logged account",logged)
        val owner=app.accounts.session.value.stamp.account;val original=app.stores.load(owner);val settings=app.settings.current()
        val fixture="m6d-online-fixture"
        val guestClient=HttpClient(OkHttp){followRedirects=false;install(HttpTimeout){requestTimeoutMillis=15000};engine{config{cache(null);followRedirects(false)}}}
        val guest=BiliApi(guestClient,searchClock=app.clock,searchMd5={bytes->java.security.MessageDigest.getInstance("MD5").digest(bytes).joinToString(""){"%02x".format(it)}}){null}
        val rows=mutableListOf<JsonObject>();var sample:Pair<VideoRef,List<SubtitleCue>>?=null
        var sampleOptions=emptyList<SubtitleOption>();var sampleVideo:Video?=null
        var platformBlocked=false
        try {
            for(bvid in listOf("BV1NZBXYbEYD","BV17ghy6AEEs","BV1Yt411u7UD","BV1U1421r7SM","BV1c4411d7jb")) {
                try {
                    val video=app.content.video(bvid)
                    for(part in video.parts.take(if(bvid=="BV1c4411d7jb")2 else 1)) {
                        val ref=VideoRef(bvid,part.cid,part.number)
                        val tracks=app.subtitles.tracks(ref)
                        val anonymous=guest.subtitleTracks(ref)
                        val files=mutableListOf<JsonObject>()
                        for(track in tracks.tracks.take(3)) {
                            val cues=app.subtitles.cues(track)
                            files+=buildJsonObject{put("option",Json.encodeToJsonElement(track.option));put("cueCount",cues.size);put("firstFromMs",cues.firstOrNull()?.fromMs ?: -1);put("lastToMs",cues.lastOrNull()?.toMs ?: -1)}
                            if(sample==null && cues.isNotEmpty()) {sample=ref to cues;sampleVideo=video;sampleOptions=tracks.tracks.map{it.option}}
                        }
                        rows+=buildJsonObject {
                            put("bvid",bvid);put("cid",part.cid);put("part",part.number)
                            put("zone",buildJsonObject{put("id",video.zoneId);put("name",video.zoneName)})
                            put("loggedNeedsLogin",tracks.needsLogin);put("loggedTracks",Json.encodeToJsonElement(tracks.tracks.map{it.option}))
                            put("guestNeedsLogin",anonymous.needsLogin);put("guestTrackCount",anonymous.tracks.size);put("files",JsonArray(files))
                        }
                    }
                } catch(e:PlatformFailure) {
                    rows+=buildJsonObject{put("bvid",bvid);put("failure",e.category);put("code",e.code)}
                    if(e.kind()==FailureKind.PLATFORM_BLOCKED) {platformBlocked=true;break}
                } catch(e:SubtitleFailure) {rows+=buildJsonObject{put("bvid",bvid);put("failure",e.detail);put("status",e.status.name)}}
            }
            val out=File(app.filesDir,"m6d-evidence").apply{mkdirs()}
            File(out,"probe.json").writeText(buildJsonObject {put("complete",sample!=null);put("platformBlocked",platformBlocked);put("samples",JsonArray(rows));put("sourceTextExported",false)}.toString())
            assertNotNull("At least one real readable subtitle sample required; probes preserved",sample)
            val (ref,cues)=sample!!;val timeline=SubtitleTimeline(cues)
            app.accountKey=fixture;app.settings.update(settings.copy(historyEnabled=false,externalOutputOnly=false,preferredOutputType=null))
            val port=withContext(Dispatchers.Main){ControllerPlaybackPort(app)}
            try {
                withTimeout(15000){port.state.first{it.connected}}
                val entry=QueueEntry("m6d-online",ref.bvid,ref.cid,ref.part,sampleVideo!!.title)
                val point=cues.first().fromMs+50
                withContext(Dispatchers.Main){port.replace(ResumeSnapshot(account=fixture,entries=listOf(entry),order=listOf(entry.id),currentId=entry.id,positionMs=point),true)}
                withTimeout(30000){port.state.first{it.playing}}
                withContext(Dispatchers.Main){port.pause();port.flush();port.speed(1.5f)}
                val before=port.state.value
                val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
                val controller=SubtitleController(scope,app.subtitles,app.accounts,app.settings,app.clock)
                try {
                    withContext(Dispatchers.Main){controller.show(ref,app.accounts.session.value.stamp)}
                    withTimeout(30000){controller.state.first{it.status!=SubtitleStatus.LOADING && it.status!=SubtitleStatus.IDLE}}
                    assertEquals(SubtitleStatus.READY,controller.state.value.status)
                    assertEquals(before.queue,port.state.value.queue);assertEquals(before.queueVersion,port.state.value.queueVersion)
                    assertFalse(port.state.value.requested);assertEquals(before.positionMs,port.state.value.positionMs)
                    val seek=cues.first().fromMs
                    withContext(Dispatchers.Main){port.seekTo(seek);port.flush()}
                    assertEquals(listOf(0),timeline.active(seek))
                    assertEquals(1.5f,port.state.value.speed,0f)
                    assertTrue(app.stores.observe(fixture).first().isEmpty())
                    File(out,"online.json").writeText(buildJsonObject{put("complete",true);put("realCueCount",cues.size);put("sample",Json.encodeToJsonElement(ref));put("options",Json.encodeToJsonElement(sampleOptions));put("positionAndQueuePreserved",true);put("speed",port.state.value.speed);put("seekTimelineVerified",true);put("autoplay",false);put("realSoundConfirmed",false)}.toString())
                }finally{withContext(Dispatchers.Main){controller.hide()};scope.cancel()}
            }finally{
                withContext(Dispatchers.Main){port.clear()};app.settings.update(settings);app.accountKey=owner
                if(original!=null)withContext(Dispatchers.Main){port.replace(original.queue,false);port.flush()}
                withContext(Dispatchers.Main){port.close()}
                app.database.listenDao().clearPlayback(fixture);app.database.listenDao().clearHistory(fixture)
            }
        }finally{guestClient.close();app.accountKey=owner;app.settings.update(settings)}
    }
}
