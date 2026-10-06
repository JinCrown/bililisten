package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AudioQualityTest {
    private fun track(id: Int, codec: String, rate: Long) = AudioTrack(id, "https://example.org/$id", "audio/mp4", codec, rate)
    private val aacLow = track(30216, "mp4a.40.5", 64000)
    private val aacHigh = track(30280, "mp4a.40.2", 192000)
    private val dolby = track(30250, "ec-3", 1000000)
    private val flac = track(30251, "fLaC", 800000)

    @Test fun ordinaryTracksChooseHighestAvailableRegardlessOfResponseOrder() {
        assertEquals(aacHigh, AudioQuality.best(listOf(aacHigh, aacLow)))
        assertEquals(aacHigh, AudioQuality.best(listOf(aacLow, aacHigh)))
    }
    @Test fun losslessHasPreferenceOverHigherBitrateSpatialStream() {
        assertEquals(flac, AudioQuality.best(listOf(aacHigh, dolby, flac)))
    }
    @Test fun unsupportedHighTierFallsBackToBestCompatibleStream() {
        assertEquals(dolby, AudioQuality.best(listOf(aacHigh, dolby, flac)) { it != flac })
        assertEquals(aacHigh, AudioQuality.best(listOf(aacLow, flac, aacHigh, dolby)) { it.codec.startsWith("mp4a") })
        assertNull(AudioQuality.best(listOf(aacHigh, flac)) { false })
    }
    @Test fun unknownCodecOrMissingUrlNeverWinsByBitrateOrTrackId() {
        assertEquals(aacHigh, AudioQuality.best(listOf(aacHigh, track(30251, "unknown", Long.MAX_VALUE), flac.copy(url = ""))))
        assertNull(AudioQuality.best(emptyList()))
    }
    @Test fun guestAndAuthenticatedBothRequestExtendedAudioWithoutChangingCredentials() = runTest {
        for (cookie in listOf(null, "test-session")) {
            val client = HttpClient(wbiEngine { request ->
                assertEquals(cookie, request.headers["Cookie"])
                assertEquals("/x/player/wbi/playurl",request.url.encodedPath)
                assertEquals("100",request.url.parameters["wts"])
                assertEquals("a".repeat(32),request.url.parameters["w_rid"])
                assertEquals("4048", request.url.parameters["fnval"])
                respond("""{"code":0,"data":{"dash":{"audio":[{"id":30216,"baseUrl":"https://example.org/low","codecs":"mp4a.40.5","bandwidth":64000},{"id":30280,"baseUrl":"https://example.org/high","codecs":"mp4a.40.2","bandwidth":192000}]}}}""")
            })
            try { assertEquals("https://example.org/high", wbiApi(client) { cookie }.audio("BV1xx411c7mD", 1)) }
            finally { client.close() }
        }
    }
    @Test fun extendedBranchesAreParsedAndFilteredByDeviceCapabilities() = runTest {
        val client = HttpClient(wbiEngine {
            respond("""{"code":0,"data":{"dash":{"audio":[{"id":30280,"baseUrl":"https://example.org/aac","codecs":"mp4a.40.2","bandwidth":192000}],"dolby":{"audio":[{"id":30250,"baseUrl":"https://example.org/dolby","codecs":"ec-3","bandwidth":1000000}]},"flac":{"audio":{"id":30251,"base_url":"https://example.org/flac","codecs":"fLaC","bandwidth":800000,"audioSamplingRate":"96000","channels":2}}}}}""")
        })
        try {
            val api = wbiApi(client) { "test-session" }
            assertEquals("https://example.org/flac", api.audio("BV1xx411c7mD", 1))
            val repository = BiliContentRepository(api) { it.codec == "mp4a.40.2" }
            assertEquals("https://example.org/aac", repository.audio("BV1xx411c7mD", 1))
            assertEquals("没有设备支持的独立音轨", assertFailsWith<PlatformFailure> {
                api.audio("BV1xx411c7mD", 1) { false }
            }.category)
            val lossless = api.audioProbe("BV1xx411c7mD", 1, true).tracks.first { it.id == 30251 }
            assertEquals(96000, lossless.sampleRate); assertEquals(2, lossless.channels)
        } finally { client.close() }
    }
}
