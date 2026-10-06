package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class AccountHomeFeedTest {
    private fun rows(start:Int,count:Int)=List(count){i->PopularMusic("BV"+(start+i).toString().padStart(10,'0'),"生活 ${start+i}","","作者",23,authorId=(start+i).toLong())}
    @Test fun oldSettingsDefaultOnAndRoundTripKeepsIndependentHistorySettings() {
        assertTrue(Json.decodeFromString<UserSettings>("{}").musicRecommendations)
        val s=UserSettings(musicRecommendations=false,historyKeepAll=true,mobilePlayback=false)
        assertEquals(s,Json.decodeFromString<UserSettings>(Json.encodeToString(s)))
        assertEquals(0,PopularMusic.valid(rows(1,1)).size)
        assertEquals(1,PopularMusic.valid(rows(1,1),false).size)
    }
    @Test fun pagesKeepServerOrderFillTwelveAndReuseVerifiedAuthors()=runTest {
        val cursors=mutableListOf<Long>();var generation=1L
        val feed=AccountHomeFeed({cursor->cursors+=cursor;when(cursor){0L->AccountHomePage(rows(1,8),8);8L->AccountHomePage(rows(9,8),16);else->AccountHomePage(rows(17,8),null)}},{generation})
        val first=feed.load(emptySet()){}
        assertEquals(rows(1,12),first);assertEquals(listOf(0L,8L),cursors)
        assertEquals((1L..10L).toList(),feed.creators(emptySet()).map{it.mid});assertEquals(2,cursors.size)
        val second=feed.load(first.map{it.bvid}.toSet()){}
        assertEquals(rows(13,12).map{it.bvid},second.map{it.bvid});assertEquals(listOf(0L,8L,16L),cursors)
        assertTrue(feed.load((first+second).map{it.bvid}.toSet()){}.isEmpty());assertEquals(3,cursors.size)
        generation++;assertEquals(first,feed.load(emptySet()){});assertEquals(0L,cursors[3])
    }
    @Test fun filteredPagesAreBoundedAndOldAccountResultsCannotEnterPool()=runTest {
        var generation=1L;var reads=0
        val empty=AccountHomeFeed({cursor->reads++;AccountHomePage(emptyList(),cursor+1)},{generation})
        assertTrue(empty.load(emptySet()){}.isEmpty());assertEquals(3,reads)
        val stale=AccountHomeFeed({generation++;AccountHomePage(rows(1,12),12)},{generation})
        assertFailsWith<CancellationException>{stale.load(emptySet()){}}
    }
    @Test fun appRouteUsesCurrentCookieFiltersNonVideoAndVerifiesAidWithoutMusicThreshold()=runTest {
        val calls=mutableListOf<String>()
        val client=HttpClient(MockEngine{r->
            calls+=r.url.encodedPath
            when(r.url.encodedPath) {
                "/x/v2/feed/index"->{assertEquals("app.bilibili.com",r.url.host);assertEquals("SESSDATA=fixture",r.headers["Cookie"]);assertEquals("android",r.url.parameters["mobi_app"]);assertEquals("55",r.url.parameters["idx"])
                    respond("""{"code":0,"data":{"items":[{"card_goto":"av","goto":"av","param":"2","args":{"aid":2},"can_play":1,"idx":66},{"card_goto":"av","goto":"av","param":"1","can_play":1,"idx":67},{"card_goto":"ad_av","goto":"av","param":"3","can_play":1,"idx":68},{"card_goto":"av","goto":"av","param":"4","ad_info":{},"can_play":1,"idx":69},{"card_goto":"live","goto":"live","param":"5","idx":70}]}}""")}
                "/x/web-interface/nav"->{assertNull(r.headers["Cookie"]);respond("""{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png"}}}""")}
                "/x/web-interface/wbi/view/detail"->{assertEquals("SESSDATA=fixture",r.headers["Cookie"]);assertNotNull(r.url.parameters["w_rid"]);val aid=r.url.parameters["aid"]!!.toLong();assertTrue(aid in 1L..2L)
                    respond("""{"code":0,"data":{"View":{"aid":$aid,"bvid":"BV${aid.toString().padStart(10,'0')}","title":"生活","tid":160,"pic":"","owner":{"mid":8,"name":"UP","face":"avatar"},"stat":{"view":23},"pages":[{},{}]}}}""")}
                else->error("Unexpected network request")
            }
        })
        try {
            val page=BiliApi(client){"SESSDATA=fixture"}.accountHomePage(55,RecentRecommendationWindow(1790812800000)){"0".repeat(32)}
            assertEquals(listOf("BV0000000002","BV0000000001"),page.rows.map{it.bvid});assertEquals(70L,page.next)
            assertTrue(page.rows.all{it.plays==23L&&it.parts==2&&it.authorId==8L});assertEquals(2,calls.count{it.endsWith("view/detail")})
        }finally{client.close()}
    }
    @Test fun mobileRestrictionDoesNotFallBackToPopularOrWebFeed()=runTest {
        var calls=0
        val client=HttpClient(MockEngine{r->calls++;assertEquals("/x/v2/feed/index",r.url.encodedPath);respond("""{"code":-352,"message":"restricted"}""")})
        try {assertEquals(-352,assertFailsWith<PlatformFailure>{BiliApi(client){null}.accountHomePage(0,RecentRecommendationWindow(1790812800000)){"0".repeat(32)}}.code);assertEquals(1,calls)}finally{client.close()}
    }
}
