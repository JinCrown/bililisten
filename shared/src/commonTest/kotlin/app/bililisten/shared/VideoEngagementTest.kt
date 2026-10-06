package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class VideoEngagementTest {
    private val bv="BV1xx411c7mD"
    private val video=Video(bv,1,"fixture",listOf(VideoPart(1,2,"song")),owner=8,copyright=1)
    private class Accounts:AccountRepository {
        override val session=MutableStateFlow(AccountSession(SessionStamp("7",1),SessionStatus.AUTHENTICATED,Account(7,"fixture")))
        override suspend fun verify()=session.value.account
        override suspend fun accept(cookie:String)=error("unused")
        override suspend fun logout(){session.value=AccountSession(SessionStamp("guest",2),SessionStatus.GUEST)}
        override fun requireCurrent(stamp:SessionStamp){if(stamp!=session.value.stamp)throw PlatformFailure("stale")}
    }
    private class Store:CollectionStore {
        var pending:MutationRecord?=null
        override suspend fun collection(account:String,source:SourceRef):CollectionSnapshot?=null
        override suspend fun saveCollection(snapshot:CollectionSnapshot){}
        override suspend fun mutation(account:String)=pending?.takeIf{it.account==account}
        override suspend fun saveMutation(record:MutationRecord){check(pending==null);pending=Json.decodeFromString(Json.encodeToString(record))}
        override suspend fun removeMutation(account:String){if(pending?.account==account)pending=null}
        override suspend fun updateCheckpoint(account:String,source:SourceRef):SourceCheckpoint?=null
        override suspend fun saveUpdate(checkpoint:SourceCheckpoint){}
    }
    private class Fixture {
        val accounts=Accounts();val store=Store();var reads=0;var writes=0
        var relation=VideoRelations(false,0,false)
        var outcome=MutationOutcome.CONFIRMED
        var receipt:TripleReceipt?=TripleReceipt(true,true,true,2)
        var failure:Exception?=null
        var failureStep:EngagementStep?=null
        var targetPresent=false
        var folderAvailable=true
        var foldersHook:()->Unit={}
        val folderWrites=mutableListOf<Long>()
        val stepWrites=mutableListOf<EngagementStep>()
        val targets=mutableListOf<Boolean>()
        val port=object:VideoEngagementPort {
            override suspend fun folders(account:Long,aid:Long):List<FavoriteFolder>{foldersHook();return if(folderAvailable)listOf(FavoriteFolder(9,"自选音乐",1,targetPresent),FavoriteFolder(10,"其他收藏夹",1,true))else emptyList()}
            override suspend fun favorite(account:Long,aid:Long,folder:Long):MutationOutcome{beforePost();folderWrites+=folder;if(outcome==MutationOutcome.CONFIRMED){targetPresent=true;relation=relation.copy(favorite=true)};return outcome}
            override suspend fun relations(bvid:String):VideoRelations{reads++;return relation}
            override suspend fun tags(bvid:String)=listOf("音乐")
            fun beforePost(){val step=store.pending!!.engagement!!.step;stepWrites+=step;writes++;if(failureStep==null||step==failureStep)failure?.let{throw it}}
            override suspend fun like(account:String,bvid:String,liked:Boolean):MutationOutcome{beforePost();targets+=liked;if(outcome==MutationOutcome.CONFIRMED)relation=relation.copy(liked=liked);return outcome}
            override suspend fun coin(account:String,bvid:String,amount:Int):MutationOutcome{beforePost();if(outcome==MutationOutcome.CONFIRMED)relation=relation.copy(coins=relation.coins+amount);return outcome}
            override suspend fun triple(account:String,bvid:String):TripleReceipt?{beforePost();return receipt}
        }
        val repo=VideoEngagementRepository(port,accounts,store,object:Clock{override fun nowMs()=1000L}){"fixture"}
        val stamp get()=accounts.session.value.stamp
    }
    @Test fun likeUsesFreshStateToToggleBothDirections()=runTest {
        val f=Fixture();f.repo.change(f.stamp,video,EngagementAction.LIKE);f.repo.change(f.stamp,video,EngagementAction.LIKE)
        assertEquals(listOf(true,false),f.targets);assertEquals(2,f.writes);assertNull(f.store.pending)
    }
    @Test fun coinChecksOriginalRepostOwnAndRemainingLimitsBeforePost()=runTest {
        for((v,amount,before) in listOf(Triple(video.copy(owner=7),1,0),Triple(video.copy(copyright=2),2,0),Triple(video,1,2),Triple(video.copy(copyright=0),1,0),Triple(video,0,0))) {
            val f=Fixture();f.relation=f.relation.copy(coins=before)
            assertFailsWith<PlatformFailure>{f.repo.change(f.stamp,v,EngagementAction.COIN,amount)}
            assertEquals(0,f.writes);assertNull(f.store.pending)
        }
        val f=Fixture();f.repo.change(f.stamp,video,EngagementAction.COIN,2);assertEquals(2,f.relation.coins)
    }
    @Test fun customTripleUsesSelectedFolderAndAlreadyDoneSkipsWrites()=runTest {
        val f=Fixture();assertTrue(f.repo.change(f.stamp,video,EngagementAction.TRIPLE,favoriteFolder=9).message.startsWith("三连完成"))
        assertEquals(listOf(9L),f.folderWrites);assertEquals(listOf(EngagementStep.TRIPLE_LIKE,EngagementStep.TRIPLE_COIN,EngagementStep.TRIPLE_FAVORITE),f.stepWrites)
        assertEquals(2,f.relation.coins);assertNull(f.store.pending)
        f.repo.change(f.stamp,video,EngagementAction.TRIPLE,favoriteFolder=9);assertEquals(3,f.writes)
    }
    @Test fun knownCoinFailureReportsPartialAndNextHoldOnlyCompletesMissingActions()=runTest {
        val f=Fixture();f.failure=PlatformFailure("硬币不足",-104);f.failureStep=EngagementStep.TRIPLE_COIN
        assertTrue(f.repo.change(f.stamp,video,EngagementAction.TRIPLE,favoriteFolder=9).message.startsWith("三连未全部完成"));assertNull(f.store.pending)
        assertTrue(f.relation.liked);assertEquals(0,f.relation.coins);assertTrue(f.folderWrites.isEmpty())
        f.failure=null
        assertTrue(f.repo.change(f.stamp,video,EngagementAction.TRIPLE,favoriteFolder=9).message.startsWith("三连完成"))
        assertEquals(listOf(true),f.targets);assertEquals(2,f.relation.coins);assertEquals(listOf(9L),f.folderWrites)
    }
    @Test fun alreadyFavoritedElsewhereStillAddsSelectedFolderWithoutSpendingAgain()=runTest {
        val f=Fixture();f.relation=VideoRelations(true,2,true)
        f.repo.change(f.stamp,video,EngagementAction.TRIPLE,favoriteFolder=9)
        assertEquals(listOf(9L),f.folderWrites);assertEquals(listOf(EngagementStep.TRIPLE_FAVORITE),f.stepWrites);assertEquals(2,f.relation.coins)
    }
    @Test fun missingSelectionDeletedFolderAndStaleFolderReadCannotWrite()=runTest {
        val f=Fixture();assertFailsWith<PlatformFailure>{f.repo.change(f.stamp,video,EngagementAction.TRIPLE)};assertEquals(0,f.writes)
        f.folderAvailable=false;assertEquals(9L,f.repo.change(f.stamp,video,EngagementAction.TRIPLE,favoriteFolder=9).unavailableFolder);assertEquals(0,f.writes)
        val g=Fixture();g.foldersHook={g.accounts.session.value=AccountSession(SessionStamp("8",2),SessionStatus.AUTHENTICATED,Account(8,"other"))}
        assertFailsWith<PlatformFailure>{g.repo.change(g.stamp,video,EngagementAction.TRIPLE,favoriteFolder=9)};assertEquals(0,g.writes)
    }
    @Test fun unknownTripleCoinSurvivesSerializationAndReconciliationNeverResumesWrites()=runTest {
        val f=Fixture();f.relation=VideoRelations(true,0,true);f.outcome=MutationOutcome.UNKNOWN
        assertTrue(f.repo.change(f.stamp,video,EngagementAction.TRIPLE,favoriteFolder=9).pending)
        assertEquals(9L,f.store.pending!!.folder);assertEquals(EngagementStep.TRIPLE_COIN,f.store.pending!!.engagement!!.step)
        assertFailsWith<PlatformFailure>{f.repo.change(f.stamp,video,EngagementAction.TRIPLE,favoriteFolder=9)}
        f.relation=f.relation.copy(coins=2)
        val result=f.repo.reconcile(f.stamp);assertFalse(result.pending);assertTrue(result.message.contains("尚未全部完成"));assertEquals(1,f.writes);assertNull(f.store.pending)
        f.outcome=MutationOutcome.CONFIRMED;f.repo.change(f.stamp,video,EngagementAction.TRIPLE,favoriteFolder=9)
        assertEquals(2,f.writes);assertEquals(listOf(9L),f.folderWrites);assertEquals(2,f.relation.coins)
    }
    @Test fun unknownTripleFavoriteRequiresTheExactFolderNotGlobalFavoriteFlag()=runTest {
        val f=Fixture();f.relation=VideoRelations(true,1,true);f.outcome=MutationOutcome.UNKNOWN
        assertTrue(f.repo.change(f.stamp,video,EngagementAction.TRIPLE,favoriteFolder=9).pending)
        assertTrue(f.repo.reconcile(f.stamp).pending);assertEquals(1,f.writes)
        f.targetPresent=true;assertFalse(f.repo.reconcile(f.stamp).pending);assertNull(f.store.pending);assertEquals(1,f.writes)
    }
    @Test fun tripleOwnOrUnknownCopyrightIsRejectedBeforeAnyWrite()=runTest {
        for(v in listOf(video.copy(owner=7),video.copy(copyright=0))){val f=Fixture();assertFailsWith<PlatformFailure>{f.repo.change(f.stamp,v,EngagementAction.TRIPLE,favoriteFolder=9)};assertEquals(0,f.writes)}
    }
    @Test fun uncertainCoinSurvivesSerializationBlocksRepeatsAndReconcilesOnlyReads()=runTest {
        val f=Fixture();f.outcome=MutationOutcome.UNKNOWN
        assertTrue(f.repo.change(f.stamp,video,EngagementAction.COIN,2).pending)
        assertEquals(2,f.store.pending!!.engagement!!.coinGoal)
        assertFailsWith<PlatformFailure>{f.repo.change(f.stamp,video,EngagementAction.COIN,2)}
        assertTrue(f.repo.reconcile(f.stamp).pending);assertEquals(1,f.writes)
        f.relation=VideoRelations(false,2,false);assertFalse(f.repo.reconcile(f.stamp).pending)
        assertNull(f.store.pending);assertEquals(1,f.writes)
    }
    @Test fun cancelledTripleKeepsJournalAndKnownRejectionClearsIt()=runTest {
        val f=Fixture();f.failure=CancellationException("after POST")
        assertFailsWith<CancellationException>{f.repo.change(f.stamp,video,EngagementAction.TRIPLE,favoriteFolder=9)}
        assertEquals(EngagementStep.TRIPLE_LIKE,f.store.pending!!.engagement!!.step)
        f.relation=VideoRelations(true,2,true);f.repo.reconcile(f.stamp);assertEquals(1,f.writes)
        f.failure=PlatformFailure("硬币不足",-104)
        f.relation=VideoRelations(false,0,false)
        assertFailsWith<PlatformFailure>{f.repo.change(f.stamp,video,EngagementAction.COIN,1)};assertNull(f.store.pending)
    }
    @Test fun staleSessionAndOtherPendingMutationCannotPost()=runTest {
        val f=Fixture();val old=f.stamp;f.accounts.logout()
        assertFailsWith<PlatformFailure>{f.repo.change(old,video,EngagementAction.LIKE)};assertEquals(0,f.writes)
        val g=Fixture();g.store.pending=MutationRecord("old","7",1,9,true,1)
        assertFailsWith<PlatformFailure>{g.repo.change(g.stamp,video,EngagementAction.TRIPLE)};assertEquals(0,g.writes)
    }
    @Test fun repostTripleUsesOneCoinAndExistingCoinIsNotToppedUp()=runTest {
        val f=Fixture();f.repo.change(f.stamp,video.copy(copyright=2),EngagementAction.TRIPLE,favoriteFolder=9);assertEquals(1,f.relation.coins)
        val g=Fixture();g.relation=g.relation.copy(coins=1);g.repo.change(g.stamp,video,EngagementAction.TRIPLE,favoriteFolder=9)
        assertEquals(1,g.relation.coins);assertFalse(EngagementStep.TRIPLE_COIN in g.stepWrites)
    }
    @Test fun customFolderProtocolWritesOnlySelectedFolderAndNeverDeletesOthers()=runTest {
        val accounts=Accounts();val store=Store();var favorite=false;val posts=mutableListOf<String>()
        val client=HttpClient(MockEngine{r->
            assertEquals("api.bilibili.com",r.url.host)
            if(r.method==HttpMethod.Post) {
                assertNotNull(store.pending);posts+=r.url.encodedPath
                val form=parseQueryString((r.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
                assertEquals("token",form["csrf"])
                when(r.url.encodedPath) {
                    "/x/web-interface/archive/like"->{assertEquals(bv,form["bvid"]);assertEquals("1",form["like"])}
                    "/x/web-interface/coin/add"->{assertEquals(bv,form["bvid"]);assertEquals("2",form["multiply"]);assertEquals("0",form["select_like"])}
                    "/x/v3/fav/resource/deal"->{assertEquals("1",form["rid"]);assertEquals("2",form["type"]);assertEquals("9",form["add_media_ids"]);assertEquals("",form["del_media_ids"]);favorite=true}
                    else->error("Unexpected write")
                }
                respond("""{"code":0,"data":{}}""")
            }else when(r.url.encodedPath) {
                "/x/web-interface/nav"->respond("""{"code":0,"data":{"isLogin":true,"mid":7,"uname":"fixture"}}""")
                "/x/web-interface/archive/relation"->respond("""{"code":0,"data":{"like":false,"coin":0,"favorite":true}}""")
                "/x/v3/fav/folder/created/list-all"->{assertEquals("7",r.url.parameters["up_mid"]);assertEquals("1",r.url.parameters["rid"]);respond("""{"code":0,"data":{"list":[{"id":9,"title":"自选音乐","media_count":1,"fav_state":${if(favorite)1 else 0}},{"id":10,"title":"其他收藏夹","media_count":1,"fav_state":1}]}}""")}
                else->error("Unexpected read")
            }
        })
        try {
            val api=BiliApi(client){"DedeUserID=7; bili_jct=token"}
            val repo=VideoEngagementRepository(BiliVideoEngagementPort(api),accounts,store,object:Clock{override fun nowMs()=1L}){"fixture"}
            assertTrue(repo.change(accounts.session.value.stamp,video,EngagementAction.TRIPLE,favoriteFolder=9).message.startsWith("三连完成"))
            assertEquals(listOf("/x/web-interface/archive/like","/x/web-interface/coin/add","/x/v3/fav/resource/deal"),posts);assertNull(store.pending)
        }finally{client.close()}
    }
    @Test fun balanceCannotLeakAcrossAccountsOrTriggerPosts()=runTest {
        val accounts=Accounts();val store=Store();var reads=0
        val client=HttpClient(MockEngine{r->
            assertEquals(HttpMethod.Get,r.method);assertEquals("/x/web-interface/nav",r.url.encodedPath);reads++
            respond("""{"code":0,"data":{"isLogin":true,"mid":8,"uname":"other","money":999}}""")
        })
        try {
            val repo=VideoEngagementRepository(BiliVideoEngagementPort(BiliApi(client){"fixture"}),accounts,store,object:Clock{override fun nowMs()=0L}){"fixture"}
            assertFailsWith<PlatformFailure>{repo.coinBalance(accounts.session.value.stamp)}
            assertEquals(1,reads);assertNull(store.pending)
            val old=accounts.session.value.stamp;accounts.logout()
            assertFailsWith<PlatformFailure>{repo.coinBalance(old)};assertEquals(1,reads)
        }finally{client.close()}
    }
    @Test fun relationReadsCombinedAccountFlagsAndRejectsMissingValues()=runTest {
        for(body in listOf("""{"like":true,"coin":2,"favorite":true}""","""{"like":true,"favorite":true}""")) {
            val client=HttpClient(MockEngine{r->assertEquals("/x/web-interface/archive/relation",r.url.encodedPath);assertEquals(bv,r.url.parameters["bvid"]);respond("""{"code":0,"data":$body}""")})
            try{val api=BiliApi(client){"fixture"};if(body.contains("coin"))assertEquals(VideoRelations(true,2,true),api.videoRelations(bv))else assertFailsWith<PlatformFailure>{api.videoRelations(bv)}}finally{client.close()}
        }
    }
    @Test fun nativeTripleProtocolHasSinglePostAndStrictPartialReceipt()=runTest {
        var posts=0
        val client=HttpClient(MockEngine{r->if(r.method==HttpMethod.Post){posts++;assertEquals("/x/web-interface/archive/like/triple",r.url.encodedPath)
            val form=parseQueryString((r.body as OutgoingContent.ByteArrayContent).bytes().decodeToString());assertEquals(bv,form["bvid"]);assertEquals("token",form["csrf"]);assertNull(form["multiply"])
            assertEquals("bili_jct=token; buvid3=fixture",r.headers["Cookie"])
            respond("""{"code":0,"data":{"like":true,"coin":false,"fav":true,"multiply":0}}""")
        }else respond("""{"code":0,"data":{"isLogin":true,"mid":7,"uname":"fixture"}}""")})
        try{assertEquals(TripleReceipt(true,false,true,0),BiliApi(client){"bili_jct=token; buvid3=fixture"}.tripleVideo("7",bv));assertEquals(1,posts)}finally{client.close()}
    }
    @Test fun tripleTransportAndMalformedReceiptStayUnknownWithoutRetry()=runTest {
        for(body in listOf<String?>(null,"""{"code":0,"data":{"like":true,"coin":true,"fav":true}}""")) {
            var posts=0;val client=HttpClient(MockEngine{r->if(r.method==HttpMethod.Post){posts++;if(body==null)error("connection lost");respond(body)}else respond("""{"code":0,"data":{"isLogin":true,"mid":7,"uname":"fixture"}}""")})
            try{assertNull(BiliApi(client){"bili_jct=token"}.tripleVideo("7",bv));assertEquals(1,posts)}finally{client.close()}
        }
    }
    @Test fun accountChangesDuringPreflightPreventPost()=runTest {
        var generation=1L;var posts=0
        val client=HttpClient(MockEngine{r->if(r.method==HttpMethod.Post)posts++;generation++;respond("""{"code":0,"data":{"isLogin":true,"mid":7,"uname":"fixture"}}""")})
        try{assertFailsWith<PlatformFailure>{BiliApi(client,generation={generation}){"bili_jct=token"}.tripleVideo("7",bv)};assertEquals(0,posts)}finally{client.close()}
    }
    @Test fun singleCoinProtocolAndInsufficientCoinsError()=runTest {
        var posts=0;val client=HttpClient(MockEngine{r->if(r.method==HttpMethod.Post){posts++;val form=parseQueryString((r.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())
            assertEquals("1",form["multiply"]);assertEquals("0",form["select_like"]);respond("""{"code":-104,"message":"not enough"}""")
        }else respond("""{"code":0,"data":{"isLogin":true,"mid":7,"uname":"fixture"}}""")})
        try{assertEquals("硬币不足",assertFailsWith<PlatformFailure>{BiliApi(client){"bili_jct=token"}.coinVideo("7",bv,1)}.category);assertEquals(1,posts)}finally{client.close()}
    }
}
