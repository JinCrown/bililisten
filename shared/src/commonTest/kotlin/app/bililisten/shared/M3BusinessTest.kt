package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpMethod
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class M3BusinessTest {
    @Test fun hearingBeforeFirstFullRefreshDoesNotReportEveryItemAsNew() {
        val source=SourceRef(SourceKind.PUBLIC_FAVORITES,42,8)
        val previous=SourceCheckpoint("7",source,emptySet(),setOf("heard"),completeBaseline=false)
        val result=SourceUpdates.compare(previous,"7",source,listOf("heard","new"),true)
        assertTrue(result.added.isEmpty());assertTrue(result.removed.isEmpty())
        assertTrue(result.checkpoint!!.completeBaseline);assertEquals(setOf("heard"),result.checkpoint!!.heard)
    }
    private class Store : CollectionStore {
        var pending:MutationRecord?=null
        override suspend fun collection(account:String,source:SourceRef):CollectionSnapshot?=null
        override suspend fun saveCollection(snapshot:CollectionSnapshot){}
        override suspend fun mutation(account:String)=pending?.takeIf{it.account==account}
        override suspend fun saveMutation(record:MutationRecord){check(pending==null);pending=record}
        override suspend fun removeMutation(account:String){if(pending?.account==account)pending=null}
        override suspend fun updateCheckpoint(account:String,source:SourceRef):SourceCheckpoint?=null
        override suspend fun saveUpdate(checkpoint:SourceCheckpoint){}
    }
    private val source=SourceRef(SourceKind.UP_COLLECTION,42,7,CollectionKind.SEASON)
    private val clock=object:Clock{override fun nowMs()=1000L}
    private class Accounts:AccountRepository {
        override val session=MutableStateFlow(AccountSession(SessionStamp("7",1),SessionStatus.AUTHENTICATED,Account(7,"fixture")))
        override suspend fun verify()=session.value.account
        override suspend fun accept(cookie:String)=error("unused")
        override suspend fun logout(){session.value=AccountSession(SessionStamp("guest",2),SessionStatus.GUEST)}
        override fun requireCurrent(stamp:SessionStamp){if(stamp!=session.value.stamp)throw PlatformFailure("stale")}
    }
    @Test fun membershipReadsVipStatusAndMissingStatusRemainsUnknown()=runTest {
        for((fields,expected) in listOf(
            "\"vip\":{\"status\":1,\"due_date\":12345}" to Membership.VIP,
            "\"vipStatus\":0" to Membership.ORDINARY,
            "\"vip\":{}" to Membership.UNKNOWN)) {
            val client=HttpClient(MockEngine{respond("""{"code":0,"data":{"isLogin":true,"mid":7,"uname":"fixture",$fields}}""")})
            try{assertEquals(expected,BiliApi(client){null}.account().membership)}finally{client.close()}
        }
    }
    @Test fun accountGenerationChangeDropsInFlightApiResponse()=runTest {
        var generation=1L
        val client=HttpClient(MockEngine{generation++;respond("""{"code":0,"data":{"isLogin":true,"mid":7,"uname":"old"}}""")})
        try{assertFailsWith<PlatformFailure>{BiliApi(client,generation={generation}){"fixture"}.account()}}finally{client.close()}
    }
    @Test fun followCancellationPersistsIntentAndReconcileNeverPostsAgain()=runTest {
        var posts=0;var followed=false;val store=Store();val accounts=Accounts()
        val client=HttpClient(MockEngine{r->
            when {
                r.method==HttpMethod.Post->{posts++;followed=true;throw CancellationException("interrupted after server write")}
                r.url.encodedPath.endsWith("/nav")->respond("""{"code":0,"data":{"isLogin":true,"mid":7,"uname":"fixture"}}""")
                r.url.encodedPath.endsWith("seasons_archives_list")->respond("""{"code":0,"data":{"meta":{"mid":7,"season_id":42,"name":"fixture"},"page":{"page_num":1,"page_size":20,"total":0},"archives":[]}}""")
                else->respond(if(followed)"""{"code":0,"data":{"count":1,"list":[{"type":21,"id":42,"title":"fixture","media_count":0,"upper":{"mid":7,"name":"fixture"}}]}}""" else """{"code":0,"data":{"count":0,"list":[]}}""")
            }
        })
        try {
            val repo=BiliSourceRepository(BiliApi(client){"bili_jct=fixture"},accounts,store,clock)
            assertFailsWith<CancellationException>{repo.follow(7,source,true)}
            assertEquals(source,store.pending!!.followSource);assertEquals(1,posts)
            assertFailsWith<PlatformFailure>{repo.follow(7,source,true)}
            assertTrue(repo.reconcileFollow(7)!!);assertNull(store.pending);assertEquals(1,posts)
        }finally{client.close()}
    }
    @Test fun pendingFollowCannotBeReconciledAsFavoriteOrByOtherAccount()=runTest {
        val client=HttpClient(MockEngine{error("must not send request")});val store=Store();val accounts=Accounts()
        store.pending=MutationRecord("follow", "7",0,0,true,1,followSource=source)
        try {
            val api=BiliApi(client){"fixture"}
            assertFailsWith<PlatformFailure>{BiliFavoriteRepository(api,accounts,store,clock){"new"}.reconcile(7)}
            assertFailsWith<PlatformFailure>{BiliSourceRepository(api,accounts,store,clock).reconcileFollow(8)}
            assertNotNull(store.pending)
        }finally{client.close()}
    }
    @Test fun entitlementCarriesSessionVideoRequestAndTimestamp()=runTest {
        val client=HttpClient(wbiEngine{respond("""{"code":0,"data":{"dash":{"audio":[{"id":30280,"baseUrl":"https://example.org/audio","codecs":"mp4a.40.2","bandwidth":192000}]}}}""")})
        try {
            val accounts=Accounts();val ref=VideoRef("BV1xx411c7mD",2,2)
            val result=BiliEntitlementRepository(wbiApi(client){"fixture"},clock,accounts).inspect(ref)
            assertEquals(ref,result.video);assertEquals(accounts.session.value.stamp,result.session)
            assertEquals(1000L,result.checkedAt);assertEquals("web-dash-4048",result.requestMethod)
            assertEquals(EntitlementStatus.AVAILABLE,result.status)
        }finally{client.close()}
    }
}
