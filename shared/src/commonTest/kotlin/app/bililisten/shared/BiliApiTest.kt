package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BiliApiTest {
    @Test fun accountBalanceKeepsDecimalZeroAndUnavailableDistinct()=runTest {
        for((field,expected)in listOf("123.5" to 123.5,"0" to 0.0,"-1" to null,"null" to null,"{}" to null)) {
            val client=HttpClient(MockEngine{r->assertEquals("/x/web-interface/nav",r.url.encodedPath);respond("""{"code":0,"data":{"isLogin":true,"mid":7,"uname":"fixture","money":$field}}""")})
            try{assertEquals(expected,BiliApi(client){"fixture"}.account().coinBalance)}finally{client.close()}
        }
    }
    @Test fun candidateLoginDoesNotReplaceStoredCookieAndOnlyTargetsApi() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("api.bilibili.com", request.url.host)
            assertEquals("https", request.url.protocol.name)
            assertEquals("candidate", request.headers["Cookie"])
            respond("""{"code":0,"data":{"isLogin":true,"mid":7,"uname":"测试账号"}}""")
        })
        try { assertEquals(7L, BiliApi(client) { "old-session" }.account("candidate").id) } finally { client.close() }
    }
    @Test fun platformChallengeIsNotReportedAsEmptyLibrary() = runTest {
        val client = HttpClient(MockEngine { respond("""{"code":-412,"message":"challenge"}""") })
        try {
            val failure = assertFailsWith<PlatformFailure> { BiliApi(client) { null }.folders(7) }
            assertEquals(-412, failure.code)
        } finally { client.close() }
    }
    @Test fun absentIndependentAudioNeverFallsBackToMuxedVideo() = runTest {
        var calls=0
        val client = HttpClient(wbiEngine { request ->
            calls++;assertEquals("/x/player/wbi/playurl",request.url.encodedPath)
            respond("""{"code":0,"data":{"durl":[{"url":"https://example.org/video.mp4"}]}}""")
        })
        try { assertFailsWith<PlatformFailure> { wbiApi(client){null}.audio("BV1xx411c7mD", 1) };assertEquals(1,calls) } finally { client.close() }
    }
    @Test fun paginationRetainsHasMoreAndServerPage() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("2", request.url.parameters["pn"])
            assertEquals("42", request.url.parameters["media_id"])
            respond("""{"code":0,"data":{"has_more":true,"medias":[{"bvid":"BV1xx411c7mD","title":"分 P 视频"}]}}""")
        })
        try {
            val page = BiliApi(client) { null }.favorites(42, 2)
            assertTrue(page.hasMore); assertEquals(1, page.items.size)
        } finally { client.close() }
    }
    @Test fun notLoggedInAndMalformedResponseAreDistinctFailures() = runTest {
        val client = HttpClient(MockEngine { respond("""{"code":0,"data":{"isLogin":false}}""") })
        try { assertEquals(-101, assertFailsWith<PlatformFailure> { BiliApi(client) { null }.account() }.code) } finally { client.close() }
        val invalid = HttpClient(MockEngine { respond("<html>verification required</html>") })
        try { assertEquals("平台响应格式变化", assertFailsWith<PlatformFailure> { BiliApi(invalid) { null }.account() }.category) } finally { invalid.close() }
    }
}
