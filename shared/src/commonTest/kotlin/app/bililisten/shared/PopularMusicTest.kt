package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.delay
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PopularMusicTest {
    private val window=RecentRecommendationWindow(1790812800000)
    private val nav="""{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png"}}}"""
    @Test fun detailsUseTwoRequestsAtATimeAndPublishBeforeLastBatch()=runTest {
        var active=0;var peak=0;var completed=0;var firstReady=-1
        val keywords=mutableSetOf<String>();val pages=mutableSetOf<String>()
        val client=HttpClient(MockEngine(MockEngineConfig().apply { dispatcher=StandardTestDispatcher(testScheduler);addHandler { r->when(r.url.encodedPath) {
            "/x/web-interface/nav"->respond(nav)
            "/x/web-interface/wbi/search/type"->{
                keywords+=r.url.parameters["keyword"]!!;pages+=r.url.parameters["page"]!!
                respond("""{"code":0,"data":{"page":2,"result":[${(1..8).joinToString(","){"""{"bvid":"BV${it.toString().padStart(10,'0')}","play":6000000}"""}}]}}""")
            }
            "/x/web-interface/wbi/view/detail"->{active++;peak=maxOf(peak,active);delay(100);active--;completed++
                assertNotNull(r.url.parameters["w_rid"])
                respond("""{"code":0,"data":{"View":{"bvid":"${r.url.parameters["bvid"]}","title":"song","tid":28,"pic":"cover","owner":{"mid":8,"name":"UP"},"stat":{"view":6000000},"pages":[{}]}}}""")
            }
            else->error("unexpected")
        }}}))
        try {
            val rows=BiliApi(client){null}.popularMusicBatch(window,2,setOf("BV0000000001"),{if(firstReady<0)firstReady=completed},{"0".repeat(32)})
            assertEquals(setOf("2"),pages);assertEquals(setOf("音乐","歌曲合集","音乐合集"),keywords)
            assertEquals(2,peak);assertEquals(2,firstReady);assertEquals(7,completed)
            assertEquals(7,rows.size);assertTrue(rows.none{it.bvid=="BV0000000001"})
        }finally{client.close()}
    }
    @Test fun firstVerifiedRowsDoNotWaitForTheSlowestKeyword()=runTest {
        var readyAt=-1L
        val client=HttpClient(MockEngine(MockEngineConfig().apply {dispatcher=StandardTestDispatcher(testScheduler);addHandler {r->when(r.url.encodedPath) {
            "/x/web-interface/nav"->respond(nav)
            "/x/web-interface/wbi/search/type"->{if(r.url.parameters["keyword"]!="音乐")delay(1000);respond("""{"code":0,"data":{"page":1,"result":[{"bvid":"BV1xx411c7mD","play":6000000}]}}""")}
            "/x/web-interface/wbi/view/detail"->{delay(100);respond("""{"code":0,"data":{"View":{"bvid":"BV1xx411c7mD","title":"song","tid":28,"pic":"cover","owner":{"mid":8,"name":"UP"},"stat":{"view":6000000},"pages":[{}]}}}""")}
            else->error("Unexpected request")
        }}}))
        try {
            val rows=BiliApi(client){null}.popularMusicBatch(window,0,emptySet(),{if(readyAt<0)readyAt=testScheduler.currentTime},{"0".repeat(32)})
            assertEquals(100L,readyAt);assertEquals(1000L,testScheduler.currentTime);assertEquals(1,rows.size)
        }finally{client.close()}
    }
    @Test fun allTimeMusicUsesRealDetailCountsAndDistinctCollectionIdentity()=runTest {
        var searches=0;var details=0
        val client=HttpClient(MockEngine { r->
            when(r.url.encodedPath) {
                "/x/web-interface/nav"->respond(nav)
                "/x/web-interface/wbi/search/type"->{
                    searches++;assertEquals("3",r.url.parameters["tids"]);assertEquals("click",r.url.parameters["order"])
                    assertNull(r.url.parameters["pubtime_begin_s"]);assertEquals("1",r.url.parameters["page"])
                    respond("""{"code":0,"data":{"page":1,"result":[${('D'..'I').joinToString(","){"""{"bvid":"BV1xx411c7m$it","play":10000000}"""}}]}}""")
                }
                "/x/web-interface/wbi/view/detail"->{
                    details++;val bv=r.url.parameters["bvid"]!!;val last=bv.last()
                    val count=if(last=='E')3 else 1
                    val views=if(last=='D')1999999 else if(last=='H')1000000 else 2000000
                    val season=if(last in "FGH")""", "ugc_season":{"id":22,"mid":8,"title":"音乐合集","cover":"season.jpg","ep_count":42,"stat":{"view":${if(last=='H')1999999 else 9000000}}}""" else ""
                    respond("""{"code":0,"data":{"View":{"bvid":"$bv","title":"视频 $last","tid":${if(last=='I')4 else 28},"pic":"song.jpg","owner":{"mid":8,"name":"UP"},"stat":{"view":$views},"pages":[${List(count){"""{"cid":${it+1},"page":${it+1}}"""}.joinToString(",")}]$season}}}""")
                }
                else->error("Unexpected ${r.url.encodedPath}: audio and writes forbidden")
            }
        })
        try {
            val rows=BiliApi(client){null}.popularMusic(window){"0".repeat(32)}
            assertEquals(3,searches);assertEquals(6,details);assertEquals(2,rows.size)
            val collection=rows.single{it.collection!=null};val video=rows.single{it.collection==null}
            assertEquals("合集 · 42 个视频",collection.typeLabel);assertEquals(9000000L,collection.plays)
            assertEquals(22L,collection.collection!!.ref.id);assertEquals("season.jpg",collection.cover)
            assertEquals("多分 P · 共 3 P",video.typeLabel);assertEquals(2000000L,video.plays)
        } finally {client.close()}
    }
    @Test fun lowViewedMemberCanRecommendOnlyVerifiedHighViewedCollection()=runTest {
        val client=HttpClient(MockEngine { r->when(r.url.encodedPath) {
            "/x/web-interface/nav"->respond(nav)
            "/x/web-interface/wbi/search/type"->respond("""{"code":0,"data":{"page":1,"result":[{"bvid":"BV1xx411c7mD","play":123}]}}""")
            "/x/web-interface/wbi/view/detail"->respond("""{"code":0,"data":{"View":{"bvid":"BV1xx411c7mD","title":"song","tid":28,"pic":"song.jpg","owner":{"mid":8,"name":"UP"},"stat":{"view":123},"pages":[{}],"ugc_season":{"id":22,"mid":8,"title":"音乐合集","ep_count":3,"stat":{"view":5000000}}}}}""")
            else->error("Unexpected request")
        }})
        try {assertEquals(5000000L,BiliApi(client){null}.popularMusic(window){"0".repeat(32)}.single().plays)}finally{client.close()}
    }
    @Test fun restrictionStopsReadAndNeverFallsBackToUnqualifiedRanking()=runTest {
        var reads=0
        val client=HttpClient(MockEngine { r->reads++;if(r.url.encodedPath.endsWith("nav"))respond(nav) else respond("""{"code":-412}""")})
        try {assertFailsWith<PlatformFailure>{BiliApi(client){null}.popularMusic(window){"0".repeat(32)}};assertTrue(reads in 2..4)}finally{client.close()}
    }
    @Test fun thresholdAndLabelsDoNotGuessPartsFromTitle() {
        val single=PopularMusic("BV1xx411c7mD","歌曲合集（标题）","","UP",2000000)
        assertEquals("单视频",single.typeLabel)
        assertEquals(listOf(single),PopularMusic.select(listOf(single.copy(plays=1999999),single,single)))
        assertTrue(PopularMusic.select(listOf(single.copy(parts=0),single.copy(bvid="bad"))).isEmpty())
    }
    @Test fun thresholdThenRandomSampleIsNotTheTwelveHighestCounts() {
        val rows=(1..30).map{PopularMusic("BV"+it.toString().padStart(10,'0'),"song $it","","UP",5000000L+it)}
        val picked=PopularMusic.select(rows,kotlin.random.Random(7))
        assertEquals(12,picked.size);assertEquals(12,picked.map{it.key}.toSet().size)
        assertTrue(picked.all{it.plays>=PopularMusic.MIN_PLAYS})
        assertNotEquals(rows.sortedByDescending{it.plays}.take(12).toSet(),picked.toSet())
    }
    @Test fun continuingPastFourPagesDoesNotCycleBackToPageOne()=runTest {
        val pages=mutableListOf<String>()
        val client=HttpClient(MockEngine {r->when(r.url.encodedPath) {
            "/x/web-interface/nav"->respond(nav)
            "/x/web-interface/wbi/search/type"->{pages+=r.url.parameters["page"]!!;respond("""{"code":0,"data":{"page":5,"result":[]}}""")}
            else->error("No audio or writes")
        }})
        try {assertTrue(BiliApi(client){null}.popularMusicBatch(window,8,emptySet(),{},{"0".repeat(32)}).isEmpty());assertEquals(listOf("5","5","5"),pages)}finally{client.close()}
    }
}
