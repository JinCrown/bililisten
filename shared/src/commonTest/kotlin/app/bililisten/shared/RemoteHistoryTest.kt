package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class RemoteHistoryTest {
    private val row=RemoteHistoryItem(1,"BV1xx411c7mD",142,42,"歌曲","P42",95123,5000)
    private class Accounts:AccountRepository {
        override val session=MutableStateFlow(AccountSession(SessionStamp("7",1),SessionStatus.AUTHENTICATED,Account(7,"fixture")))
        override suspend fun verify()=session.value.account
        override suspend fun accept(cookie:String)=error("unused")
        override suspend fun logout(){session.value=AccountSession(SessionStamp("guest",2),SessionStatus.GUEST)}
        override fun requireCurrent(stamp:SessionStamp){if(stamp!=session.value.stamp)throw PlatformFailure("stale")}
    }
    private class Store:RemoteHistoryStore {
        val rows=MutableStateFlow<Map<String,RemoteHistoryData>>(emptyMap());val cleared=mutableListOf<String>()
        override fun observe(account:String)=rows.map{it[account] ?: RemoteHistoryData()}
        override suspend fun read(account:String)=rows.value[account] ?: RemoteHistoryData()
        override suspend fun changeMode(account:String,data:RemoteHistoryData,cutoff:Long){cleared+=account;rows.value=rows.value+(account to data.checked())}
        override suspend fun save(account:String,data:RemoteHistoryData,expectedEpoch:Long){check(read(account).enabled&&read(account).epoch==expectedEpoch);rows.value=rows.value+(account to data.checked())}
    }
    private class Remote:RemoteHistoryPort {
        var rows=listOf<RemoteHistoryItem>();var writes=0;var deletes=0;var pages=0;var shadow=false;var failRead=false;var uncertain=false;var failWrite=false;var skipped=0
        var readHook:suspend ()->Unit={};var repeatCursor=false
        override suspend fun paused()=shadow
        override suspend fun page(cursor:HistoryCursor?):RemoteHistoryPage{pages++;readHook();if(failRead)throw PlatformFailure("offline");return RemoteHistoryPage(rows,if(repeatCursor)HistoryCursor(1,1,"archive")else null,skipped)}
        override suspend fun report(item:RemoteHistoryItem,account:String):RemoteHistoryItem{assertEquals("7",account);writes++;if(uncertain)throw HistoryWriteUncertain();if(failWrite)throw PlatformFailure("blocked");rows=listOf(item)+rows.filterNot{it.key==item.key};return item.copy(aid=1)}
        override suspend fun delete(key:String?,account:String){assertEquals("7",account);deletes++;if(uncertain)throw HistoryWriteUncertain();rows=if(key==null)emptyList()else rows.filterNot{(if(it.roomId>0)"live_${it.roomId}" else "archive_${it.aid}")==key}}
    }
    private class Fixture {
        val accounts=Accounts();val store=Store();val remote=Remote();var now=1000L
        val sync=RemoteHistorySync(remote,store,accounts,object:Clock{override fun nowMs()=now})
        val stamp get()=accounts.session.value.stamp
        suspend fun enable(){sync.setEnabled(stamp,true)}
    }
    @Test fun defaultOffNeverReadsOrUploadsAndEnableImportsWithoutUploadingOldLocal()=runTest {
        val f=Fixture();assertFalse(f.sync.enabled("7"));f.sync.refresh(f.stamp);f.sync.record(row,"7")
        assertEquals(0,f.remote.pages);assertEquals(0,f.remote.writes)
        f.remote.rows=listOf(row);f.enable();assertEquals(listOf("7"),f.store.cleared);assertEquals(listOf(row),f.store.read("7").items);assertEquals(0,f.remote.writes)
        f.sync.record(row.copy(viewedAt=1000),"7");assertEquals(0,f.remote.writes)
    }
    @Test fun failedOrIncompleteEnableAndOfficialPausedNeverClearLocal()=runTest {
        val f=Fixture();f.remote.failRead=true;assertFailsWith<PlatformFailure>{f.enable()};assertTrue(f.store.cleared.isEmpty())
        f.remote.failRead=false;f.remote.repeatCursor=true;assertFailsWith<PlatformFailure>{f.enable()};assertTrue(f.store.cleared.isEmpty())
        f.remote.repeatCursor=false;f.remote.shadow=true;assertFailsWith<PlatformFailure>{f.enable()};assertTrue(f.store.cleared.isEmpty())
    }
    @Test fun accountChangesDuringImportNeverApplyOldResultOrClearEitherAccount()=runTest {
        val f=Fixture();f.remote.readHook={f.accounts.logout()};assertFailsWith<PlatformFailure>{f.enable()};assertTrue(f.store.cleared.isEmpty());assertFalse(f.sync.enabled("7"));assertFalse(f.sync.enabled("guest"))
    }
    @Test fun twoWayRefreshReadsChangedCidAndReportsOnlyActualNewHeardProgress()=runTest {
        val f=Fixture();f.remote.rows=listOf(row);f.enable();f.sync.record(row.copy(positionMs=100123,viewedAt=6000),"7")
        assertEquals(1,f.remote.writes);assertEquals(100123L,f.store.read("7").visible().single().positionMs)
        val changed=row.copy(cid=149,page=49,viewedAt=7000,positionMs=50000);f.remote.rows=listOf(changed);f.sync.refresh(f.stamp)
        assertEquals(changed,f.store.read("7").items.single());assertTrue(f.store.read("7").reports.isEmpty())
    }
    @Test fun offlineQueuedReportSurvivesSerializationAndNewerCloudWins()=runTest {
        val f=Fixture();f.enable();f.remote.failWrite=true;f.sync.record(row,"7");assertEquals(HistoryReportPhase.QUEUED,f.store.read("7").reports.single().phase)
        val restored=Json.decodeFromString<RemoteHistoryData>(Json.encodeToString(f.store.read("7")));assertEquals(f.store.read("7"),restored)
        f.remote.failWrite=false;f.remote.rows=listOf(row.copy(positionMs=60000,viewedAt=10000));f.sync.refresh(f.stamp)
        assertEquals(1,f.remote.writes);assertTrue(f.store.read("7").reports.isEmpty());assertEquals(60000L,f.store.read("7").items.single().positionMs)
    }
    @Test fun uncertainReportIsReadBackAndNeverReplayedByRefreshOrSameCheckpoint()=runTest {
        val f=Fixture();f.enable();f.remote.uncertain=true;f.sync.record(row,"7");assertEquals(HistoryReportPhase.UNKNOWN,f.store.read("7").reports.single().phase)
        f.sync.refresh(f.stamp);f.sync.record(row,"7");assertEquals(1,f.remote.writes)
        f.remote.rows=listOf(row.copy(positionMs=95000));f.sync.refresh(f.stamp);assertTrue(f.store.read("7").reports.isEmpty());assertEquals(1,f.remote.writes)
    }
    @Test fun interruptedSendingRecordAlsoRequiresReadBackWithoutReplay()=runTest {
        val f=Fixture();f.enable();val d=f.store.read("7");f.store.save("7",d.copy(reports=listOf(HistoryReport(row,HistoryReportPhase.SENDING))),d.epoch)
        f.sync.refresh(f.stamp);assertEquals(0,f.remote.writes);assertEquals(1,f.store.read("7").reports.size)
    }
    @Test fun uncertainDeletionRequiresRefreshAndDoesNotResend()=runTest {
        val f=Fixture();f.remote.rows=listOf(row);f.enable();f.remote.uncertain=true
        assertFailsWith<HistoryWriteUncertain>{f.sync.delete(f.stamp,row.key)};assertFailsWith<PlatformFailure>{f.sync.delete(f.stamp,row.key)}
        f.sync.refresh(f.stamp);assertEquals(1,f.remote.deletes);assertNotNull(f.store.read("7").deletion)
        f.remote.rows=emptyList();f.sync.refresh(f.stamp);assertNull(f.store.read("7").deletion);assertEquals(1,f.remote.deletes)
    }
    @Test fun uncertainClearCannotBeAcknowledgedWhenUnsupportedPlatformRecordsRemain()=runTest {
        val f=Fixture();f.enable();f.remote.uncertain=true;assertFailsWith<HistoryWriteUncertain>{f.sync.delete(f.stamp,null)}
        f.remote.skipped=1;f.sync.refresh(f.stamp);assertNotNull(f.store.read("7").deletion);assertEquals(1,f.remote.deletes)
    }
    @Test fun switchingOffStopsReportsAndResetsMirrorWithoutClearingOfficialHistory()=runTest {
        val f=Fixture();f.enable();f.remote.uncertain=true;f.sync.record(row,"7");f.accounts.session.value=f.accounts.session.value.copy(status=SessionStatus.UNVERIFIED)
        f.sync.setEnabled(f.stamp,false);assertFalse(f.sync.enabled("7"));assertTrue(f.store.read("7").visible().isEmpty());assertEquals(0,f.remote.deletes)
        f.sync.record(row.copy(viewedAt=7000),"7");assertEquals(1,f.remote.writes)
        f.accounts.session.value=f.accounts.session.value.copy(status=SessionStatus.AUTHENTICATED);f.remote.uncertain=false;f.now=10000;f.enable();f.sync.record(row,"7");assertEquals(1,f.remote.writes)
    }
    @Test fun liveRepeatsAreLimitedButANewLaterListenCanBeReported()=runTest {
        val f=Fixture();f.enable();val live=RemoteHistoryItem(roomId=6,title="直播",viewedAt=5000)
        f.sync.record(live,"7");f.sync.record(live.copy(viewedAt=6000),"7");assertEquals(1,f.remote.writes)
        f.sync.record(live.copy(viewedAt=65000),"7");assertEquals(2,f.remote.writes)
    }
    @Test fun knownOfflineIdentityQueuesActualProgressWithoutPostingUntilVerified()=runTest {
        val f=Fixture();f.enable();f.accounts.session.value=f.accounts.session.value.copy(status=SessionStatus.UNVERIFIED)
        f.sync.record(row,"7");assertEquals(0,f.remote.writes);assertEquals(row,f.store.read("7").reports.single().item)
        f.accounts.session.value=f.accounts.session.value.copy(status=SessionStatus.AUTHENTICATED);f.sync.refresh(f.stamp)
        assertEquals(1,f.remote.writes);assertTrue(f.store.read("7").reports.isEmpty())
    }
    @Test fun apiParsesActualPartCompletedAndLiveAndRejectsMissingCursor()=runTest {
        var malformed=false
        val client=HttpClient(MockEngine{respond("""{"code":0,"data":{"list":[{"title":"歌曲","view_at":5,"progress":-1,"history":{"business":"archive","oid":1,"bvid":"BV1xx411c7mD","cid":142,"page":42,"part":"P42"}},{"title":"直播","view_at":4,"history":{"business":"live","oid":6}},{"history":{"business":"article"}}]${if(malformed)""else ",\"cursor\":{\"max\":0}"}}}""")})
        try{val api=BiliApi(client){"SESSDATA=fixture"};val page=api.historyPage();assertEquals(1,page.unsupported);assertEquals(42,page.items[0].page);assertTrue(page.items[0].finished);assertEquals(0L,page.items[0].positionMs);assertEquals(5000L,page.items[0].viewedAt);assertEquals(6L,page.items[1].roomId);malformed=true;assertFailsWith<PlatformFailure>{api.historyPage()}}finally{client.close()}
    }
    @Test fun reportBindsCapturedCookieAcrossMetadataReadsAndDoesNotPostToNewAccount()=runTest {
        var cookie="SESSDATA=f;DedeUserID=7;bili_jct=c";var gen=1L;var posts=0
        val client=HttpClient(MockEngine{req->when {
            req.method==HttpMethod.Post->{posts++;respond("""{"code":0}""")}
            req.url.encodedPath=="/x/v2/history/shadow"->respond("""{"code":0,"data":false}""")
            else->{cookie="SESSDATA=g;DedeUserID=8;bili_jct=d";gen++;respond("""{"code":0,"data":{"aid":1,"bvid":"BV1xx411c7mD","title":"歌曲","pages":[{"cid":142,"page":42,"part":"P42","duration":200}],"owner":{"mid":7,"name":"fixture"}}}""")}
        }})
        try{assertFailsWith<PlatformFailure>{BiliApi(client,generation={gen}){cookie}.reportHistory(row,"7")};assertEquals(0,posts)}finally{client.close()}
    }
    @Test fun apiReportsSecondsAndUsesResolvedCidWithCsrfAndDeletionValidatesAccount()=runTest {
        var form="";var posts=0
        val client=HttpClient(wbiEngine{req->when {
            req.method==HttpMethod.Post->{posts++;form=(req.body as OutgoingContent.ByteArrayContent).bytes().decodeToString();respond("""{"code":0}""")}
            req.url.encodedPath=="/x/v2/history/shadow"->respond("""{"code":0,"data":false}""")
            else->respond("""{"code":0,"data":{"View":{"aid":1,"bvid":"BV1xx411c7mD","title":"歌曲","pages":[{"cid":142,"page":42,"part":"P42","duration":200}],"owner":{"mid":7,"name":"fixture"}}}}""")
        }})
        try{val api=wbiApi(client){"SESSDATA=f;DedeUserID=7;bili_jct=c"};val result=api.reportHistory(row,"7");assertEquals(142L,result.cid);assertTrue(form.contains("progress=95"));assertTrue(form.contains("csrf=c"));assertTrue(form.contains("cid=142"));assertEquals(1,posts);assertFailsWith<PlatformFailure>{api.deleteHistory(null,"8")};assertEquals(1,posts)}finally{client.close()}
    }
}
