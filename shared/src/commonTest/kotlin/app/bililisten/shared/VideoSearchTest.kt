package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class VideoSearchTest {
    private val keys="""{"code":-101,"data":{"isLogin":false,"wbi_img":{"img_url":"https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png"}}}"""
    private val success="""{"code":0,"data":{"page":1,"numPages":1,"numResults":1,"result":[{"bvid":"BV1xx411c7mD","title":"<em>音乐</em>&amp;现场"}]}}"""
    private class Time(var now:Long=100000):Clock{override fun nowMs()=now}
    private fun api(client:HttpClient,time:Time=Time(),governor:RequestGovernor?=null)=BiliApi(client,governor,searchClock=time,searchMd5={"a".repeat(32)}){"fixture-session"}

    @Test fun signedVideoSearchPreservesFiltersAndUsesPublicKeysWithoutLeakingCredentials()=runTest {
        val paths=mutableListOf<String>()
        val client=HttpClient(MockEngine{r->
            paths+=r.url.encodedPath;assertEquals("api.bilibili.com",r.url.host);assertEquals(URLProtocol.HTTPS,r.url.protocol)
            if(r.url.encodedPath.endsWith("/nav")){assertNull(r.headers[HttpHeaders.Cookie]);respond(keys)}else{
                assertEquals("fixture-session",r.headers[HttpHeaders.Cookie]);assertEquals("video",r.url.parameters["search_type"])
                assertEquals("名字 & 空格",r.url.parameters["keyword"]);assertEquals("click",r.url.parameters["order"])
                assertEquals("3",r.url.parameters["tids"]);assertEquals("20",r.url.parameters["page_size"])
                assertEquals("1430654",r.url.parameters["web_location"]);assertEquals("pc",r.url.parameters["platform"])
                assertEquals("100",r.url.parameters["wts"]);assertEquals("a".repeat(32),r.url.parameters["w_rid"]);respond(success)
            }
        })
        try{assertEquals("音乐&现场",api(client).search(" 名字 & 空格 ",order="click",tid=3).items.single().title)
            assertEquals(listOf("/x/web-interface/nav","/x/web-interface/wbi/search/type"),paths)
        }finally{client.close()}
    }
    @Test fun publicSigningKeysAreCachedBrieflyButEverySearchGetsCurrentTimestamp()=runTest {
        val time=Time();var navs=0;val timestamps=mutableListOf<String?>()
        val client=HttpClient(MockEngine{r->if(r.url.encodedPath.endsWith("/nav")){navs++;respond(keys)}else{timestamps+=r.url.parameters["wts"];respond(success)}})
        try{val api=api(client,time);api.search("first");time.now=101000;api.search("second");assertEquals(1,navs)
            time.now=401001;api.search("third");assertEquals(2,navs);assertEquals(listOf<String?>("100","101","401"),timestamps)
        }finally{client.close()}
    }
    @Test fun anActualChallengeIsNotRetriedOrConvertedToEmptyResultsAndManualRetryCanRecover()=runTest {
        var searches=0;var blocked=true;val governor=RequestGovernor(backgroundScope)
        val client=HttpClient(MockEngine{r->if(r.url.encodedPath.endsWith("/nav"))respond(keys)else{searches++;respond(if(blocked)"""{"code":-352}""" else success)}})
        try{val api=api(client,governor=governor)
            assertEquals(-352,assertFailsWith<PlatformFailure>{api.search("first")}.code)
            assertEquals(-352,assertFailsWith<PlatformFailure>{api.search("second")}.code);assertEquals(1,searches)
            blocked=false;governor.acknowledge(0);assertEquals(1,api.search("second").items.size);assertEquals(2,searches)
        }finally{client.close()}
    }
    @Test fun malformedOrWrongPageSearchDoesNotAppearAsSuccessfulEmptyResults()=runTest {
        for(body in listOf("""{"code":0,"data":{"page":1,"numPages":1,"numResults":1}}""",
            """{"code":0,"data":{"page":2,"numPages":2,"numResults":1,"result":[]}}""",
            """{"code":0,"data":{"page":1,"numPages":-1,"numResults":1,"result":[]}}""")){
            val client=HttpClient(MockEngine{r->respond(if(r.url.encodedPath.endsWith("/nav"))keys else body)})
            try{assertFailsWith<PlatformFailure>{api(client).search("fixture")}}finally{client.close()}
        }
    }
    @Test fun invalidQueryDoesNotReadKeysOrSearch()=runTest {
        var calls=0;val client=HttpClient(MockEngine{calls++;respond(keys)})
        try{val api=api(client);assertFailsWith<IllegalArgumentException>{api.search(" ")};assertFailsWith<IllegalArgumentException>{api.search("q",0)}
            assertFailsWith<IllegalArgumentException>{api.search("q",order="unknown")};assertEquals(0,calls)
        }finally{client.close()}
    }
    @Test fun failedSigningKeyReadNeverFallsBackToUnsignedEndpoint()=runTest {
        var calls=0;val client=HttpClient(MockEngine{r->calls++;assertEquals("/x/web-interface/nav",r.url.encodedPath);respond("limited",HttpStatusCode.PreconditionFailed)})
        try{assertEquals(412,assertFailsWith<PlatformFailure>{api(client).search("fixture")}.code);assertEquals(1,calls)}finally{client.close()}
    }
}
