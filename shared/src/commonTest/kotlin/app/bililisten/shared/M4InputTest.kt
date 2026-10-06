package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class M4InputTest {
    @Test fun shortLinkIsAnonymousAndStopsAtValidatedVideo()=runTest {
        var requests=0
        val client=HttpClient(MockEngine{request->requests++;assertEquals("b23.tv",request.url.host);assertNull(request.headers["Cookie"])
            respond("",HttpStatusCode.Found,headersOf("Location","https://www.bilibili.com/video/BV1xx411c7mD/?p=2"))}){followRedirects=false}
        try{assertEquals("BV1xx411c7mD",BiliApi(client){"private-cookie"}.resolveVideoInput("分享视频 https://b23.tv/abc"));assertEquals(1,requests)}finally{client.close()}
    }
    @Test fun redirectToArbitraryHostIsRejectedBeforeSecondRequest()=runTest {
        var requests=0
        val client=HttpClient(MockEngine{requests++;respond("",HttpStatusCode.Found,headersOf("Location","https://evil.example/video/BV1xx411c7mD"))}){followRedirects=false}
        try{assertFailsWith<PlatformFailure>{BiliApi(client){"secret"}.resolveVideoInput("https://b23.tv/abc")};assertEquals(1,requests)}finally{client.close()}
    }
    @Test fun redirectLoopHasThreeRequestLimit()=runTest {
        var requests=0
        val client=HttpClient(MockEngine{requests++;respond("",HttpStatusCode.Found,headersOf("Location","https://b23.tv/loop"))}){followRedirects=false}
        try{assertFailsWith<PlatformFailure>{BiliApi(client){null}.resolveVideoInput("https://b23.tv/loop")};assertEquals(3,requests)}finally{client.close()}
    }
    @Test fun metadataKeepsMissingCountsUnknownAndUsesRealOwnerAndCover()=runTest {
        val client=HttpClient(wbiEngine{respond("""{"code":0,"data":{"View":{"bvid":"BV1xx411c7mD","aid":1,"title":"真实标题","pic":"https://i0.hdslb.com/a.jpg","owner":{"name":"作者","mid":7,"face":"avatar"},"duration":3600,"pages":[{"cid":2,"page":1,"part":"P1"}]}}}""")})
        try{val video=wbiApi(client){null}.video("BV1xx411c7mD");assertEquals("作者",video.author);assertEquals(3600,video.duration);assertNull(video.views);assertEquals("https://i0.hdslb.com/a.jpg",video.cover)}finally{client.close()}
    }
}
