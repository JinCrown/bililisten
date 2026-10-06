package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class M6OrganizerTest {
    private val bv="BV1xx411c7mD"
    private class Accounts:AccountRepository {
        override val session=MutableStateFlow(AccountSession(SessionStamp("7",1),SessionStatus.AUTHENTICATED,Account(7,"fixture")))
        override suspend fun verify()=session.value.account
        override suspend fun accept(cookie:String)=error("unused")
        override suspend fun logout(){session.value=AccountSession(SessionStamp("guest",2),SessionStatus.GUEST)}
        override fun requireCurrent(stamp:SessionStamp){if(stamp!=session.value.stamp)throw PlatformFailure("stale")}
    }
    private class Store:OrganizerStore {
        val records=mutableMapOf<String,OrganizerData>()
        override suspend fun read(account:String)=records[account] ?: OrganizerData()
        override suspend fun write(account:String,value:OrganizerData){records[account]=value}
    }
    private open class Remote(val store:Store):OrganizerRemote {
        var folders=mutableListOf(FavoriteFolder(10,"来源",2,attr=22),FavoriteFolder(20,"目标",0,attr=23),FavoriteFolder(30,"其他",1,attr=22))
        val memberships=mutableMapOf(1L to mutableSetOf(10L,30L),2L to mutableSetOf(10L))
        var posts=0;var unavailable=false;var failRemove=false;var unknown=false;var cancelled=false
        var afterAdd:(suspend ()->Unit)?=null
        override suspend fun folders(account:Long):List<FavoriteFolder>{if(unavailable)throw PlatformFailure("offline");return folders.toList()}
        override suspend fun folder(account:Long,id:Long)=folders.first{it.id==id}
        override suspend fun writeFolder(account:Long,draft:FolderDraft):Long? {
            assertNotNull(store.read("7").pending);posts++
            when(draft.action){FolderAction.CREATE->folders.add(FavoriteFolder(40,draft.title,0,attr=if(draft.private)23 else 22));FolderAction.EDIT->{folders=folders.map{if(it.id==draft.id)it.copy(title=draft.title,attr=if(draft.private)23 else 22) else it}.toMutableList()};FolderAction.DELETE->folders.removeAll{it.id==draft.id}}
            if(cancelled)throw CancellationException("after server write")
            if(unknown){unavailable=true;throw PlatformFailure("offline")}
            return 40
        }
        override suspend fun video(bvid:String)=Video(bvid,if(bvid=="BV1xx411c7mD")1 else 2,"fixture",listOf(VideoPart(1,1,"p1")))
        override suspend fun membership(account:Long,aid:Long):List<FavoriteFolder>{if(unavailable)throw PlatformFailure("offline");return folders.map{it.copy(contains=it.id in memberships[aid].orEmpty())}}
        override suspend fun change(account:Long,aid:Long,folder:Long,add:Boolean):MutationOutcome {
            assertEquals(RowOutcome.RUNNING,store.read("7").batch!!.rows.first{it.aid==aid}.outcome);posts++
            if(!add&&failRemove)throw PlatformFailure("denied",-403)
            if(add)memberships.getValue(aid).add(folder) else memberships.getValue(aid).remove(folder)
            if(add)afterAdd?.invoke()
            if(unknown){unavailable=true;return MutationOutcome.UNKNOWN}
            return MutationOutcome.CONFIRMED
        }
    }
    @Test fun folderIntentSurvivesProcessInterruptionAndRecoveryOnlyReads()=runTest {
        val store=Store();val remote=Remote(store);val accounts=Accounts();remote.cancelled=true
        assertFailsWith<CancellationException>{Organizer(remote,accounts,store).folder(FolderDraft(FolderAction.CREATE,title="临时",private=true))}
        assertNotNull(store.read("7").pending);assertEquals(1,remote.posts)
        val recovered=Organizer(remote,accounts,store).reconcile();assertNull(recovered.pending);assertEquals(1,remote.posts)
    }
    @Test fun ambiguousCreateKeepsPendingAndNeverReposts()=runTest {
        val store=Store();val remote=Remote(store);val engine=Organizer(remote,Accounts(),store);remote.unknown=true
        assertNotNull(engine.folder(FolderDraft(FolderAction.CREATE,title="临时")).pending)
        assertFailsWith<PlatformFailure>{engine.folder(FolderDraft(FolderAction.CREATE,title="临时"))}
        remote.unavailable=false;remote.folders.add(FavoriteFolder(41,"临时",0,attr=23))
        assertNotNull(engine.reconcile().pending);assertEquals(1,remote.posts)
    }
    @Test fun deleteRequiresOwnershipAndRejectsDefaultFolderBeforePost()=runTest {
        val store=Store();val remote=Remote(store);val engine=Organizer(remote,Accounts(),store)
        assertFailsWith<PlatformFailure>{engine.folder(FolderDraft(FolderAction.DELETE,999))}
        remote.folders[0]=remote.folders[0].copy(attr=0)
        assertFailsWith<PlatformFailure>{engine.folder(FolderDraft(FolderAction.DELETE,10))};assertEquals(0,remote.posts)
    }
    @Test fun deleteRejectsChangedNameOrCountAfterConfirmation()=runTest {
        val store=Store();val remote=Remote(store);val engine=Organizer(remote,Accounts(),store)
        assertFailsWith<PlatformFailure>{engine.folder(FolderDraft(FolderAction.DELETE,10,confirmedTitle="earlier",confirmedCount=2))}
        assertFailsWith<PlatformFailure>{engine.folder(FolderDraft(FolderAction.DELETE,10,confirmedTitle="来源",confirmedCount=1))}
        assertEquals(0,remote.posts);assertNull(store.read("7").pending)
    }
    @Test fun unfinishedFavoritePreflightBlocksBeforeCreatingAnotherJournal()=runTest {
        val store=Store();val remote=object:Remote(store){override suspend fun preflight(account:Long){throw PlatformFailure("pending")}}
        assertFailsWith<PlatformFailure>{Organizer(remote,Accounts(),store).folder(FolderDraft(FolderAction.CREATE,title="new"))}
        assertNull(store.read("7").pending);assertEquals(0,remote.posts)
    }
    @Test fun movePreservesOtherMembershipAndChecksTargetBeforeSourceRemoval()=runTest {
        val store=Store();val remote=Remote(store);val result=Organizer(remote,Accounts(),store).batch(10,20,OrganizeAction.MOVE,listOf(FavoriteItem(bv,"fixture")))
        assertEquals(RowOutcome.CONFIRMED,result.batch!!.rows.single().outcome)
        assertTrue(remote.memberships.getValue(1L) == setOf(20L,30L));assertEquals(2,remote.posts)
    }
    @Test fun partialMoveIsVisibleAndCopyDoesNotDeleteSource()=runTest {
        val store=Store();val remote=Remote(store);val engine=Organizer(remote,Accounts(),store);remote.failRemove=true
        assertEquals(RowOutcome.PARTIAL,engine.batch(10,20,OrganizeAction.MOVE,listOf(FavoriteItem(bv,"fixture"))).batch!!.rows.single().outcome)
        assertTrue(remote.memberships.getValue(1L) == setOf(10L,20L,30L))
        assertEquals(RowOutcome.CONFIRMED,engine.batch(10,20,OrganizeAction.COPY,listOf(FavoriteItem(bv,"fixture"))).batch!!.rows.single().outcome)
        assertTrue(10L in remote.memberships.getValue(1L))
    }
    @Test fun offlineAfterAddStopsRemainingItemsAndReadOnlyRecoveryShowsPartial()=runTest {
        val store=Store();val remote=Remote(store);remote.unknown=true;val engine=Organizer(remote,Accounts(),store)
        val result=engine.batch(10,20,OrganizeAction.MOVE,listOf(FavoriteItem(bv,"one"),FavoriteItem("BV1rHh16CESS","two")))
        assertEquals(listOf(RowOutcome.UNKNOWN,RowOutcome.NOT_STARTED),result.batch!!.rows.map{it.outcome});assertEquals(1,remote.posts)
        remote.unavailable=false;val recovered=engine.reconcile()
        assertEquals(RowOutcome.PARTIAL,recovered.batch!!.rows[0].outcome);assertEquals(1,remote.posts)
    }
    @Test fun accountSwitchBetweenMoveStepsNeverRemovesSource()=runTest {
        val store=Store();val remote=Remote(store);val accounts=Accounts();remote.afterAdd={accounts.logout()}
        Organizer(remote,accounts,store).batch(10,20,OrganizeAction.MOVE,listOf(FavoriteItem(bv,"one")))
        assertEquals(1,remote.posts);assertTrue(10L in remote.memberships.getValue(1L));assertNull(store.read("guest").batch)
    }
    @Test fun aliasAndOrderStayInAccountWithoutChangingVideoIdentity()=runTest {
        val store=Store();val accounts=Accounts();val engine=Organizer(Remote(store),accounts,store)
        engine.alias(bv,"我的标题");engine.order(10,listOf(bv))
        assertEquals("我的标题",store.read("7").aliases[bv]);assertTrue(store.read("guest").aliases.isEmpty())
        val rows=listOf(FavoriteItem("BV1rHh16CESS","new"),FavoriteItem(bv,"original"))
        assertEquals("original",LocalFavoriteOrder.apply(rows,listOf(bv)).first().title)
        engine.alias(bv,"");assertTrue(store.read("7").aliases.isEmpty())
    }
    @Test fun remoteSearchPassesScopePartitionSortAndSecondPage()=runTest {
        val client=HttpClient(MockEngine{request->
            assertEquals("needle",request.url.parameters["keyword"]);assertEquals("1",request.url.parameters["type"])
            assertEquals("2",request.url.parameters["pn"]);assertEquals("3",request.url.parameters["tid"]);assertEquals("view",request.url.parameters["order"])
            respond("""{"code":0,"data":{"medias":[{"bvid":"BV1xx411c7mD","title":"not previously loaded"}],"has_more":true}}""")
        })
        try{val result=BiliApi(client){"fixture"}.favorites(10,2,"needle",true,"view",3);assertTrue(result.hasMore);assertEquals(bv,result.items.single().bvid)}finally{client.close()}
    }
    @Test fun renamePreservesDescriptionCoverAndExplicitPrivacy()=runTest {
        var posted=false
        val client=HttpClient(MockEngine{r->when {
            r.method==HttpMethod.Post->{posted=true;val fields=(r.body as io.ktor.client.request.forms.FormDataContent).formData;assertEquals("keep",fields["intro"]);assertEquals("https://i0.hdslb.com/x.jpg",fields["cover"]);assertEquals("1",fields["privacy"]);assertEquals("rename",fields["title"]);respond("""{"code":0,"data":{}}""")}
            r.url.encodedPath.endsWith("/nav")->respond("""{"code":0,"data":{"isLogin":true,"mid":7,"uname":"fixture"}}""")
            r.url.encodedPath.endsWith("/list-all")->respond("""{"code":0,"data":{"list":[{"id":10,"title":"old","media_count":2,"attr":23}]}}""")
            else->respond("""{"code":0,"data":{"id":10,"upper":{"mid":7},"title":"old","media_count":2,"attr":23,"intro":"keep","cover":"https://i0.hdslb.com/x.jpg"}}""")
        }})
        try{BiliApi(client){"bili_jct=fixture"}.writeFolder(7,FolderDraft(FolderAction.EDIT,10,"rename",true));assertTrue(posted)}finally{client.close()}
    }
    @Test fun sharesKeepPartAndLiveTargetsAndShortLinksNeverSendCredentials()=runTest {
        assertEquals(SharedTarget.Video(bv,2),SharedInput.direct("https://www.bilibili.com/video/$bv/?p=2"))
        assertEquals(SharedTarget.Live(6),SharedInput.direct("https://live.bilibili.com/6"))
        var calls=0
        val client=HttpClient(MockEngine{r->calls++;assertNull(r.headers["Cookie"]);respond("",HttpStatusCode.Found,headersOf("Location","https://live.bilibili.com/6"))}) { followRedirects = false }
        try{assertEquals(SharedTarget.Live(6),BiliApi(client){"secret"}.resolveSharedInput("来听 https://b23.tv/abc"));assertEquals(1,calls)}finally{client.close()}
    }
    @Test fun unsafeRedirectStopsBeforeSecondRequest()=runTest {
        for(target in listOf("https://evil.example/","http://live.bilibili.com/6","https://evil@b23.tv/x","https://b23.tv:444/x")) {
            var calls=0;val client=HttpClient(MockEngine{calls++;respond("",HttpStatusCode.Found,headersOf("Location",target))}) { followRedirects = false }
            try{assertFailsWith<PlatformFailure>{BiliApi(client){null}.resolveSharedInput("https://b23.tv/x")};assertEquals(1,calls)}finally{client.close()}
        }
    }
}
