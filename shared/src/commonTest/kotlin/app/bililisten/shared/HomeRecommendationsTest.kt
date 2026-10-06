package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.test.*

class HomeRecommendationsTest {
    @Test fun concurrentHomeSourcesSharePublicKeysAndRefreshOnlyAfterExpiry()=runTest {
        val now=1790812800000L
        var keys=0;var ranks=0;var searches=0
        val client=HttpClient(MockEngine{request->
            when(request.url.encodedPath) {
                "/x/web-interface/nav"->{
                    keys++;assertNull(request.headers["Cookie"]);delay(100)
                    respond("""{"code":-101,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png"}}}""")
                }
                "/x/web-interface/ranking/v2"->{
                    ranks++;assertEquals("fixture-session",request.headers["Cookie"])
                    assertEquals("0".repeat(32),request.url.parameters["w_rid"])
                    respond("""{"code":0,"data":{"list":[]}}""")
                }
                "/x/web-interface/wbi/search/type"->{
                    searches++;assertEquals("0".repeat(32),request.url.parameters["w_rid"])
                    respond("""{"code":0,"data":{"page":1,"numPages":1,"result":[]}}""")
                }
                else->error("Unexpected home route")
            }
        })
        try {
            val api=BiliApi(client){"fixture-session"}
            val window=RecentRecommendationWindow(now)
            coroutineScope {
                val tasks=HomeCategory.entries.filter{it!=HomeCategory.LIVE}.map { category->
                    async { api.homeRecommendations(category,window){"0".repeat(32)} }
                }+listOf(async{api.homeCreatorCandidates(window){"0".repeat(32)}},
                    async{api.popularMusicBatch(window,0,emptySet(),{}){"0".repeat(32)}})
                tasks.awaitAll()
            }
            assertEquals(1,keys);assertEquals(6,ranks);assertEquals(5,searches)
            api.homeRecommendations(HomeCategory.MUSIC,RecentRecommendationWindow(now+300000)){"0".repeat(32)}
            assertEquals(1,keys)
            api.homeRecommendations(HomeCategory.MUSIC,RecentRecommendationWindow(now+300001)){"0".repeat(32)}
            assertEquals(2,keys)
        }finally{client.close()}
    }
    @Test fun failedKeyReadIsNotCachedAndDoesNotFallBackToUnsignedRanking()=runTest {
        var reads=0;var ranks=0
        val client=HttpClient(MockEngine{request->
            if(request.url.encodedPath.endsWith("/nav")){
                reads++;if(reads==1)respond("""{"code":-412}""")
                else respond("""{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png"}}}""")
            }else{ranks++;assertNotNull(request.url.parameters["w_rid"]);respond("""{"code":0,"data":{"list":[]}}""")}
        })
        try {
            val api=BiliApi(client){null};val window=RecentRecommendationWindow(1790812800000)
            assertFailsWith<PlatformFailure>{api.homeRecommendations(HomeCategory.MUSIC,window){"0".repeat(32)}}
            assertEquals(0,ranks)
            api.homeRecommendations(HomeCategory.MUSIC,window){"0".repeat(32)}
            assertEquals(2,reads);assertEquals(1,ranks)
        }finally{client.close()}
    }
    @Test fun separateRanksUseCorrectRegionAndPreservePlatformOrder()=runTest {
        for(category in HomeCategory.entries.filter{it.rid!=null}){
            val client=HttpClient(MockEngine{request->
                if(request.url.encodedPath.endsWith("/nav"))return@MockEngine respond("""{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png"}}}""")
                assertEquals("/x/web-interface/ranking/v2",request.url.encodedPath);assertEquals(category.rid.toString(),request.url.parameters["rid"])
                assertEquals("0".repeat(32),request.url.parameters["w_rid"]);assertEquals("1790812800",request.url.parameters["wts"])
                val rows=('D'..'J').joinToString(","){"""{"bvid":"BV1xx411c7m$it","title":"$it","pic":"https://i0.hdslb.com/fixture.jpg","owner":{"mid":8,"name":"作者","face":"//i0.hdslb.com/avatar.jpg"},"duration":120}"""}
                respond("""{"code":0,"data":{"list":[$rows]}}""")
            })
            try{val rows=BiliApi(client){null}.homeRecommendations(category,RecentRecommendationWindow(1790812800000)){"0".repeat(32)};assertEquals(listOf("D","E","F","G","H"),rows.map{it.title});assertTrue(rows.all{it.cover.isNotBlank()});assertEquals(8L,rows.first().owner);assertEquals("//i0.hdslb.com/avatar.jpg",rows.first().avatar)}finally{client.close()}
        }
    }
    @Test fun recentTopicsHaveSignedDateFiltersAndExcludeOldFutureOrUnrelatedRows()=runTest {
        val now=1790812800000L;val window=RecentRecommendationWindow(now);var signedReads=0
        val client=HttpClient(MockEngine{request->
            if(request.url.encodedPath.endsWith("/nav"))respond("""{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png"}}}""")
            else{
                signedReads++;assertEquals("/x/web-interface/wbi/search/type",request.url.encodedPath);assertEquals("click",request.url.parameters["order"]);assertEquals(window.cutoffSeconds.toString(),request.url.parameters["pubtime_begin_s"]);assertEquals((now/1000).toString(),request.url.parameters["pubtime_end_s"]);assertEquals(32,request.url.parameters["w_rid"]!!.length)
                val rows=listOf(Triple('D',window.cutoffSeconds-1,"有声书"),Triple('E',now/1000+1,"有声书"),Triple('F',now/1000-1,"游戏"),Triple('G',now/1000-1,"有声书"),Triple('H',now/1000-2,"有声小说")).mapIndexed{i,(last,time,title)->"""{"bvid":"BV1xx411c7m$last","title":"$title","pubdate":$time,"play":${i*100},"tag":"$title","cover":"","pic":"//archive.biliimg.com/fixture.jpg"}"""}.joinToString(",")
                respond("""{"code":0,"data":{"page":1,"numPages":1,"result":[$rows]}}""")
            }
        })
        try{val rows=BiliApi(client){null}.homeRecommendations(HomeCategory.AUDIOBOOK,window){"0".repeat(32)};assertEquals(listOf("有声小说","有声书"),rows.map{it.title});assertTrue(rows.all{it.cover=="//archive.biliimg.com/fixture.jpg"});assertEquals(1,signedReads)}finally{client.close()}
    }
    @Test fun restrictedTopicDoesNotFallBackToUnrelatedGeneralRanking()=runTest {
        var reads=0
        val client=HttpClient(MockEngine{r->reads++;if(r.url.encodedPath.endsWith("/nav"))respond("""{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png"}}}""") else respond("""{"code":-412}""")})
        try{assertFailsWith<PlatformFailure>{BiliApi(client){null}.homeRecommendations(HomeCategory.EMOTION,RecentRecommendationWindow(1790812800000)){"0".repeat(32)}};assertEquals(2,reads)}finally{client.close()}
    }
    @Test fun wbiUsesCanonicalEscapingAndPlatformMixin(){
        var canonical=""
        val result=WbiQuery.sign(mapOf("keyword" to "有声书 !'()*","order" to "click"),"https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png","https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png",100000){canonical=it.decodeToString();"a".repeat(32)}
        assertTrue(canonical.startsWith("keyword=%E6%9C%89%E5%A3%B0%E4%B9%A6%20&order=click&wts=100"));assertEquals("有声书 ",result["keyword"]);assertEquals("a".repeat(32),result["w_rid"])
    }
    @Test fun creatorIdentityIsDeduplicatedWithoutGuessingNamesAndOldCacheStillDecodes() {
        val a=Recommendation("a","a",null,author="同名",owner=8,avatar="avatar-a")
        val rows=recommendedCreators(listOf(a,a.copy(bvid="b"),a.copy(owner=9,avatar="avatar-b"),a.copy(owner=0),a.copy(owner=-1),a.copy(owner=10,author=" ")))
        assertEquals(setOf(8L,9L),rows.map{it.mid}.toSet());assertEquals("avatar-a",rows.single{it.mid==8L}.avatar)
        val old=kotlinx.serialization.json.Json.decodeFromString<Recommendation>("""{"bvid":"a","title":"old","tags":null}""")
        assertEquals(0L,old.owner);assertEquals("",old.avatar);assertTrue(recommendedCreators(listOf(old)).isEmpty())
    }
    @Test fun creatorsReadBeyondTopFiveVideosAndReturnTenUniqueMusicAccounts()=runTest {
        val client=HttpClient(MockEngine{r->
            if(r.url.encodedPath.endsWith("/nav"))respond("""{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png"}}}""")
            else {
                assertEquals("3",r.url.parameters["rid"])
                val rows=List(24){index->"""{"bvid":"BV${index.toString().padStart(10,'0')}","title":"music","owner":{"mid":${8+index/2},"name":"UP ${index/2}","face":"//i0.hdslb.com/${index/2}.jpg"}}"""}.joinToString(",")
                respond("""{"code":0,"data":{"list":[{"bvid":"BV0000009999","title":"unknown","owner":{"name":"unknown"}},$rows]}}""")
            }
        })
        try{val rows=BiliApi(client){null}.homeCreators(RecentRecommendationWindow(1790812800000)){"0".repeat(32)}
            assertTrue(rows.all{it.mid in 8L..19L});assertEquals(10,rows.size);assertEquals(10,rows.distinctBy{it.mid}.size)
        }finally{client.close()}
    }
}
