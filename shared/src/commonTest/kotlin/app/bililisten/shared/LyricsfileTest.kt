package app.bililisten.shared

import kotlin.test.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class LyricsfileTest {
    private val yaml="""
        version: '1.0'
        metadata:
          duration_ms: 5000
        lines:
          - text: '你好'
            start_ms: 1000
            end_ms: 3000
            words:
              - text: '你'
                start_ms: 1000
                end_ms: 1800
              - text: '好'
                start_ms: 1800
                end_ms: 3000
          - text: '世界'
            start_ms: 3000
    """.trimIndent()
    @Test fun genuineWordTimesAndLineFallbackStaySeparate() {
        val result=LyricsfileParser.parse(yaml,5000)
        assertEquals(listOf(SubtitleCue(1000,3000,"你好"),SubtitleCue(3000,5000,"世界")),result.cues)
        assertEquals(listOf(1000L,1800L),result.words[0]!!.map{it.fromMs})
        assertNull(result.words[1]);assertEquals("你好\n世界",result.plain)
    }
    @Test fun rejectsUnsupportedVersionMismatchOutsideLineAndUntrustedYaml() {
        for(bad in listOf(yaml.replace("'1.0'","'9.0'"),yaml.replace("text: '好'","text: '错'"),yaml.replace("start_ms: 1800","start_ms: 800"),
            "version: '1.0'\nversion: '1.0'", "version: '1.0'\nplain: &x test\nlines: *x", "!!java.lang.String 'unsafe'",
            yaml.replace("duration_ms: 5000","duration_ms: 5000\n  offset_ms: 200"))) {
            assertFails{LyricsfileParser.parse(bad,5000)}
        }
    }
    @Test fun missingWordEndsDoNotInventOtherStartTimes() {
        val result=LyricsfileParser.parse(yaml.replace("end_ms: 1800",""),5000)
        assertNull(result.words[0]!!.first().toMs);assertEquals(1800L,result.words[0]!![1].fromMs)
    }
    @Test fun repositoryPrefersGenuineWordTimesAndFallsBackForUnsupportedFiles()=runTest {
        var file=yaml
        val client=HttpClient(MockEngine {r->
            assertEquals("lrclib.net",r.url.host);assertNull(r.headers["Cookie"])
            respond(buildJsonObject {put("id",1);put("trackName","fixture");put("duration",5);put("plainLyrics","fixture");put("syncedLyrics","[00:01.00]legacy");put("lyricsfile",file)}.toString())
        })
        try {
            val repository=LrclibRepository(client)
            val word=repository.get(1);assertEquals("你好",word.cues.first().content);assertEquals(2,word.words[0]!!.size)
            file=yaml.replace("'1.0'","'9.0'")
            val fallback=repository.get(1);assertTrue(fallback.words.isEmpty());assertEquals("legacy",fallback.cues.first().content);assertNotNull(fallback.timingNote)
        }finally{client.close()}
    }
}
