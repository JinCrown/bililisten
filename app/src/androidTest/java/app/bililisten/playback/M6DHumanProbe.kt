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

/** Known CC-labelled public samples. Does not retry the restricted search API. */
@RunWith(AndroidJUnit4::class) class M6DHumanProbe {
    @Test fun readKnownHumanAndMultipleLanguageSamples()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m6dhuman")=="1")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
        assertFalse(app.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered)
        val rows=mutableListOf<JsonObject>();var human=false;var multiple=false;var realSwitch=false
        for(bv in listOf("BV19D4y1t7vz","BV1ap42127br","BV14H4y1s7K9","BV1JE411N7UD")) {
            try {
                val video=app.content.video(bv);val part=video.parts.first();val ref=VideoRef(bv,part.cid,part.number);val tracks=app.subtitles.tracks(ref)
                val files=tracks.tracks.take(4).map{track->val cues=app.subtitles.cues(track);if(cues.isNotEmpty()&&track.option.kind==SubtitleKind.HUMAN)human=true;buildJsonObject{put("track",Json.encodeToJsonElement(track.option));put("cueCount",cues.size)}}
                if(files.size>1)multiple=true
                if(files.size>1) {
                    val original=app.settings.current();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
                    val controller=SubtitleController(scope,app.subtitles,app.accounts,app.settings,app.clock)
                    try {
                        // The app is already logged in; verify identity without replacing credentials.
                        app.accounts.verify()
                        withContext(Dispatchers.Main){controller.show(ref,app.accounts.session.value.stamp)}
                        withTimeout(15000){controller.state.first{it.status==SubtitleStatus.READY}}
                        for(track in tracks.tracks.take(2)) {
                            withContext(Dispatchers.Main){controller.select(track.option.id)}
                            withTimeout(15000){controller.state.first{it.status==SubtitleStatus.READY&&it.selected?.id==track.option.id}}
                            assertEquals(track.option.language,app.settings.current().subtitleLanguage)
                            assertTrue(controller.state.value.cues.isNotEmpty())
                        }
                        realSwitch=true
                    }finally{withContext(Dispatchers.Main){controller.hide()};scope.cancel();app.settings.update(original)}
                }
                rows+=buildJsonObject{put("bvid",bv);put("cid",ref.cid);put("zoneId",video.zoneId);put("songCandidate",MusicContent.songCandidate(video));put("tracks",JsonArray(files))}
            }catch(e:PlatformFailure){rows+=buildJsonObject{put("bvid",bv);put("failure",e.category)};if(e.kind()==FailureKind.PLATFORM_BLOCKED)break}
            catch(e:SubtitleFailure){rows+=buildJsonObject{put("bvid",bv);put("failure",e.detail)}}
        }
        val out=File(app.filesDir,"m6d-evidence").apply{mkdirs()}
        File(out,"human.json").writeText(buildJsonObject{put("humanVerified",human);put("multipleVerified",multiple);put("realTrackSwitchAndLanguagePreference",realSwitch);put("samples",JsonArray(rows))}.toString())
        assertTrue("Real human track required; evidence retained",human);assertTrue("Real multiple tracks required; evidence retained",multiple);assertTrue("Real source switch required",realSwitch)
    }
}
