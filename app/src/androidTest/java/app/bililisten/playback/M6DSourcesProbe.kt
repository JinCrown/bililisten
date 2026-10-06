package app.bililisten.playback

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

/** Read-only source availability. Never confirms a lyric match on the user's behalf. */
@RunWith(AndroidJUnit4::class) class M6DSourcesProbe {
    @Test fun realHumanLanguagesAndExternalLyricsAvailability()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m6dsources")=="1")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
        val rows=mutableListOf<JsonObject>();val out=File(app.filesDir,"m6d-evidence").apply{mkdirs()}
        val ids=mutableListOf("BV1gE411E763","BV1s5fZYdELc","BV1Yt411u7UD")
        try {ids+=app.api.search("CC字幕 英文",order="click").items.take(4).map{it.bvid}}catch(e:PlatformFailure){rows+=buildJsonObject{put("searchFailure",e.category)}}
        for(bv in ids.distinct()) {
            try {
                val video=app.content.video(bv);val part=video.parts.first();val ref=VideoRef(bv,part.cid,part.number);val tracks=app.subtitles.tracks(ref)
                val files=tracks.tracks.take(4).map{track->val cues=app.subtitles.cues(track);buildJsonObject{put("track",Json.encodeToJsonElement(track.option));put("cueCount",cues.size)}}
                rows+=buildJsonObject{put("bvid",bv);put("cid",ref.cid);put("zoneId",video.zoneId);put("zoneName",video.zoneName);put("songCandidate",MusicContent.songCandidate(video));put("tracks",JsonArray(files))}
            }catch(e:PlatformFailure){rows+=buildJsonObject{put("bvid",bv);put("failure",e.category)};if(e.kind()==FailureKind.PLATFORM_BLOCKED)break}
            catch(e:SubtitleFailure){rows+=buildJsonObject{put("bvid",bv);put("failure",e.detail)}}
        }
        var lyrics:JsonObject=buildJsonObject{put("complete",false)}
        try {
            val candidates=app.lyrics.search("Chandelier","Sia")
            assertTrue(candidates.isNotEmpty());val chosen=candidates.first();val doc=app.lyrics.get(chosen.id)
            assertEquals(chosen.id,doc.candidate.id);assertTrue(doc.plain.isNotEmpty())
            lyrics=buildJsonObject{put("complete",true);put("selectedMetadata",Json.encodeToJsonElement(doc.candidate));put("candidateCount",candidates.size);put("cueCount",doc.cues.size);put("sourceTextExported",false);put("bilibiliVersionMatchConfirmed",false)}
        }catch(e:Exception){lyrics=buildJsonObject{put("complete",false);put("failure",(e as? PlatformFailure)?.category ?: "source network failure")}}
        File(out,"sources.json").writeText(buildJsonObject{put("subtitles",JsonArray(rows));put("lyrics",lyrics)}.toString())
        assertTrue("External lyrics availability required; evidence retained",lyrics["complete"]!!.jsonPrimitive.boolean)
    }
}
