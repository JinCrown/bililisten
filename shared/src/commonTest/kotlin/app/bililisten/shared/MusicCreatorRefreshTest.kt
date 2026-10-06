package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.random.Random
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MusicCreatorRefreshTest {
    private val nav="""{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png"}}}"""
    private fun ranking(start:Int)="""{"code":0,"data":{"list":[${(start until start+30).joinToString(","){"""{"bvid":"BV${it.toString().padStart(10,'0')}","title":"music","owner":{"mid":$it,"name":"UP $it","face":"avatar-$it"}}"""}}]}}"""
    @Test fun randomSamplingUsesTheWholeUniqueCreatorPool() {
        val rows=(1L..30L).map{UpProfile(it,"UP $it","avatar-$it")}
        val first=selectRecommendedCreators(rows,random=Random(7))
        assertEquals(10,first.size);assertNotEquals((1L..10L).toSet(),first.map{it.mid}.toSet())
        val second=selectRecommendedCreators(rows,first.map{it.mid}.toSet(),Random(8))
        assertEquals(10,second.size);assertTrue(first.none{old->second.any{it.mid==old.mid}})
        assertTrue(second.all{it.avatar=="avatar-${it.mid}"})
    }
    @Test fun smallPoolPrioritizesUnusedAccountsThenReusesOnlyRealOwners() {
        val rows=(1L..15L).map{UpProfile(it,"UP $it")}
        val picked=selectRecommendedCreators(rows+rows.first()+UpProfile(16," "),(1L..10L).toSet(),Random(42))
        assertEquals(10,picked.size);assertEquals(10,picked.distinctBy{it.mid}.size)
        assertTrue(picked.map{it.mid}.containsAll((11L..15L).toList()));assertTrue(picked.all{it.mid in 1L..15L})
    }
    @Test fun repositoryRefreshUsesCachedRankingAndInvalidatesItForAnotherSession()=runTest {
        var generation=1L;var reads=0
        val client=HttpClient(MockEngine{r->if(r.url.encodedPath.endsWith("/nav"))respond(nav) else {
            assertEquals("3",r.url.parameters["rid"]);assertTrue(r.url.encodedPath.endsWith("/ranking/v2"));reads++;respond(ranking(generation.toInt()*100))
        }})
        try {
            val repo=BiliRecommendationRepository(BiliApi(client,generation={generation}){null},{RecentRecommendationWindow(100000)}){"0".repeat(32)}
            val first=repo.creators();val second=repo.creators(first.map{it.mid}.toSet())
            assertEquals(1,reads);assertEquals(10,second.size);assertTrue(first.none{old->second.any{it.mid==old.mid}})
            generation=2;val next=repo.creators(second.map{it.mid}.toSet());assertEquals(2,reads)
            assertTrue(next.all{it.mid in 200L..229L})
        }finally{client.close()}
    }
    @Test fun expiredPoolRereadsAndDoesNotHidePlatformRestrictions()=runTest {
        var now=100000L;var restricted=false;var reads=0
        val client=HttpClient(MockEngine{r->if(r.url.encodedPath.endsWith("/nav"))respond(nav) else {
            reads++;respond(if(restricted)"""{"code":-412}""" else ranking(100))
        }})
        try {
            val repo=BiliRecommendationRepository(BiliApi(client){null},{RecentRecommendationWindow(now)}){"0".repeat(32)}
            val first=repo.creators();now+=6L*3600000+1;restricted=true
            assertFailsWith<PlatformFailure>{repo.creators(first.map{it.mid}.toSet())};assertEquals(2,reads)
        }finally{client.close()}
    }
    @Test fun lateResponseCannotSeedTheNextAccountsPool()=runTest {
        var generation=1L
        val client=HttpClient(MockEngine(MockEngineConfig().apply{dispatcher=StandardTestDispatcher(testScheduler);addHandler{r->
            if(r.url.encodedPath.endsWith("/nav"))respond(nav) else {val owner=generation.toInt()*100;delay(1000);respond(ranking(owner))}
        }}))
        try {
            val repo=BiliRecommendationRepository(BiliApi(client,generation={generation}){null},{RecentRecommendationWindow(100000)}){"0".repeat(32)}
            val old=async{runCatching{repo.creators()}};runCurrent();generation=2;advanceUntilIdle()
            assertTrue(old.await().isFailure);assertTrue(repo.creators().all{it.mid in 200L..229L})
        }finally{client.close()}
    }
}
