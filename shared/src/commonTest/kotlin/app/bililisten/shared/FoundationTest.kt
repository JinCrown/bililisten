package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class FoundationTest {
    private val clock = object : Clock { override fun nowMs() = 1000L }
    private val source = SourceRef(SourceKind.PUBLIC_FAVORITES, 9, 8)
    private fun snapshot(account: String = "7") = PlaybackSnapshot(ResumeSnapshot(account = account,
        entries = listOf(QueueEntry("a", "BV1xx411c7mD", 1, 2, "fixture", source = source), QueueEntry("b", "BV1xx411c7mD", 1, 2, "duplicate")),
        order = listOf("b", "a"), currentId = "a", positionMs = 12345, mode = PlayMode.SHUFFLE), 900)
    private class Accounts : AccountRepository {
        override val session = MutableStateFlow(AccountSession(SessionStamp("7", 1), SessionStatus.AUTHENTICATED, Account(7, "fixture")))
        override suspend fun verify() = session.value.account
        override suspend fun accept(cookie: String) = error("unused")
        override suspend fun logout() { session.value = AccountSession(SessionStamp("guest", 2), SessionStatus.GUEST) }
        override fun requireCurrent(stamp: SessionStamp) { if (stamp != session.value.stamp) throw PlatformFailure("stale") }
    }
    private class Store : PlaybackStateStore, CollectionStore {
        var saved: PlaybackSnapshot? = null
        var mirror: CollectionSnapshot? = null
        var checkpoint: SourceCheckpoint? = null
        val mutations = mutableMapOf<String, MutationRecord>()
        override suspend fun load(account: String) = saved
        override suspend fun checkpoint(snapshot: PlaybackSnapshot, heard: LocalHistoryEntry?) { saved = snapshot }
        override suspend fun collection(account: String, source: SourceRef) = mirror
        override suspend fun saveCollection(snapshot: CollectionSnapshot) { mirror = snapshot }
        override suspend fun mutation(account: String) = mutations[account]
        override suspend fun saveMutation(record: MutationRecord) { check(record.account !in mutations); mutations[record.account] = record }
        override suspend fun removeMutation(account: String) { mutations.remove(account) }
        override suspend fun updateCheckpoint(account: String, source: SourceRef) = checkpoint
        override suspend fun saveUpdate(checkpoint: SourceCheckpoint) { this.checkpoint = checkpoint }
    }
    private fun sources(action: suspend () -> Unit = {}) = object : SourceRepository {
        override suspend fun content(source: SourceRef, account: Long?, page: Int): SourceContentPage { action(); return SourceContentPage(ContentSource(source,"fixture","",0), emptyList(),1,false) }
        override suspend fun publicFolders(owner: Long, page: Int) = error("unused")
        override suspend fun followed(account: Long, page: Int) = error("unused")
        override suspend fun resolve(link: SourceLink, account: Long?) = error("unused")
    }
    @Test fun resumePreservesDuplicateIdsShufflePartProgressAndNeverAutoplays() = runTest {
        val store = Store().apply { saved = snapshot() }
        val result = ResumeCoordinator(store, Accounts(), sources(), clock).prepare()!!
        assertFalse(result.autoplay); assertEquals(listOf("b", "a"), result.snapshot.queue.order)
        assertEquals(12345, result.snapshot.queue.positionMs); assertEquals(2, result.snapshot.queue.entries[0].part)
        assertEquals(PauseReason.RESTORED, result.snapshot.pauseReason)
    }
    @Test fun restoreRejectsCrossAccountFutureClockPermissionAndChangedSession() = runTest {
        val store = Store(); val accounts = Accounts()
        store.saved = snapshot("8")
        assertFailsWith<IllegalArgumentException> { ResumeCoordinator(store, accounts, sources(), clock).prepare() }
        store.saved = snapshot().copy(savedAt = 90000)
        assertFailsWith<IllegalArgumentException> { ResumeCoordinator(store, accounts, sources(), clock).prepare() }
        store.saved = snapshot()
        assertFailsWith<PlatformFailure> { ResumeCoordinator(store, accounts, sources { throw PlatformFailure("private") }, clock).prepare() }
        assertFailsWith<PlatformFailure> { ResumeCoordinator(store, accounts, sources { accounts.logout() }, clock).prepare() }
    }
    @Test fun partialMirrorDoesNotDeleteCompleteBaselineOrHeardState() = runTest {
        val accounts = Accounts(); val store = Store().apply { checkpoint = SourceCheckpoint("7",source,setOf("old"),setOf("heard")) }
        val page = SourceContentPage(ContentSource(source,"fixture","",2),listOf(FavoriteItem("new","")),1,true)
        val coordinator = CollectionCoordinator(store,accounts,clock)
        coordinator.save(page,accounts.session.value.stamp)
        assertEquals(setOf("old"),store.checkpoint!!.ids); assertFalse(store.mirror!!.complete)
        coordinator.save(page.copy(hasMore = false),accounts.session.value.stamp)
        assertEquals(setOf("new"),store.checkpoint!!.ids); assertEquals(setOf("heard"),store.checkpoint!!.heard)
        assertEquals(1001,store.mirror!!.revision)
    }
    @Test fun identicalReadsShareWorkAndOneCancellationKeepsOtherWaiter() = runTest {
        val governor = RequestGovernor(backgroundScope); val ready = CompletableDeferred<Unit>(); var calls = 0
        val key = RequestKey(1,"read","x")
        val a = async { governor.read(key) { calls++; ready.await(); 7 } }
        val b = async { governor.read(key) { calls++; ready.await(); 7 } }
        runCurrent(); assertEquals(1,calls); a.cancelAndJoin(); ready.complete(Unit)
        assertEquals(7,b.await()); assertEquals(1,calls)
    }
    @Test fun lastWaiterCancellationCancelsTransport() = runTest {
        val governor = RequestGovernor(backgroundScope); var stopped = false
        val job = launch { governor.read(RequestKey(1,"a","b")) { try { awaitCancellation() } finally { stopped = true } } }
        runCurrent(); job.cancelAndJoin(); runCurrent(); assertTrue(stopped)
    }
    @Test fun concurrencyIsBoundedAndTransientRetryIsExactlyOnce() = runTest {
        val governor = RequestGovernor(backgroundScope,2); var active = 0; var peak = 0
        val tasks = (0..5).map { n -> async { governor.read(RequestKey(1,"g","$n")) { active++; peak=maxOf(peak,active); delay(100); active--; n } } }
        tasks.awaitAll(); assertEquals(2,peak)
        var count = 0
        assertFailsWith<PlatformFailure> { governor.read(RequestKey(1,"error","x")) { count++; throw PlatformFailure("网络请求失败") } }
        assertEquals(2,count)
    }
    @Test fun platformBlockStopsQueuedAndFutureRelatedReadsUntilExplicitRetry() = runTest {
        val governor = RequestGovernor(backgroundScope,1); var calls = 0
        val first = async { runCatching { governor.read(RequestKey(1,"search","1")) { calls++; delay(10); throw PlatformFailure("limited",412) } } }
        val queued = async { runCatching { governor.read(RequestKey(1,"search","2")) { calls++; 1 } } }
        first.await(); assertTrue(queued.await().isFailure); assertEquals(1,calls)
        assertFailsWith<PlatformFailure> { governor.read(RequestKey(1,"search","3")) { calls++; 1 } }
        assertEquals(1,calls)
        assertEquals(5,governor.read(RequestKey(2,"search","1")) { 5 })
        governor.acknowledge(1); assertEquals(6,governor.read(RequestKey(1,"search","1")) { 6 })
    }
    @Test fun expiredSessionBlocksDifferentEndpointWithoutRetry() = runTest {
        val governor = RequestGovernor(backgroundScope); var calls = 0
        assertFailsWith<PlatformFailure> { governor.read(RequestKey(1,"account","1")) { calls++; throw PlatformFailure("expired",-101) } }
        assertFailsWith<PlatformFailure> { governor.read(RequestKey(1,"folder","1")) { calls++; 1 } }
        assertEquals(1,calls)
    }
    private class Vault : CredentialStore {
        var cookie: String? = "fixture"; override var generation = 0L
        override fun read() = cookie
        override fun save(cookie: String) { this.cookie=cookie; generation++ }
        override fun clear() { cookie=null; generation++ }
    }
    @Test fun networkFailureKeepsCredentialButExplicitExpiryClearsIt() = runTest {
        var code = 500; val vault = Vault(); var cleared = 0
        val client = HttpClient(MockEngine { respond("{\"code\":$code}") })
        val accounts = SessionAccountRepository(BiliApi(client) { vault.read() },vault) { cleared++ }
        assertFailsWith<PlatformFailure> { accounts.verify() }; assertEquals("fixture",vault.read())
        assertEquals(SessionStatus.UNVERIFIED,accounts.session.value.status)
        code = -101; assertFailsWith<PlatformFailure> { accounts.verify() }; assertNull(vault.read()); assertEquals(1,cleared)
        assertEquals(SessionStatus.GUEST,accounts.session.value.status); client.close()
    }
    @Test fun rejectedCandidateDoesNotReplaceExistingCredential() = runTest {
        val vault = Vault(); val client = HttpClient(MockEngine { respond("{\"code\":-101}") })
        val accounts = SessionAccountRepository(BiliApi(client) { vault.read() },vault)
        assertFailsWith<PlatformFailure> { accounts.accept("bad-fixture") }; assertEquals("fixture",vault.read()); client.close()
    }
    @Test fun interruptedFavoriteWritePersistsJournalAndReconciliationDoesNotPost() = runTest {
        val store=Store(); val accounts=Accounts(); var posts=0; var member=false
        val client = HttpClient(MockEngine { request ->
            if(request.method == HttpMethod.Post) { posts++; member=true; throw CancellationException("fixture interruption") }
            if (request.url.encodedPath == "/x/web-interface/nav") respond("""{"code":0,"data":{"isLogin":true,"mid":7,"uname":"fixture"}}""")
            else respond("""{"code":0,"data":{"list":[{"id":9,"title":"fixture","media_count":1,"fav_state":${if(member) 1 else 0}}]}}""")
        })
        val repo=BiliFavoriteRepository(BiliApi(client){ "bili_jct=fixture" },accounts,store,clock){"op"}
        assertFailsWith<CancellationException> { repo.change(7,1,9,true) }
        assertEquals(1,posts); assertNotNull(store.mutation("7")); assertNull(store.mutation("8"))
        assertTrue(repo.reconcile(7)!!.present!!); assertNull(store.mutation("7")); assertEquals(1,posts); client.close()
    }
    @Test fun boundedDiagnosticsHaveOnlyTimeAndEnumsAndSettingsRejectInvalidRetention() {
        val log=DiagnosticLog(clock,2)
        log.record(DiagnosticEvent.START); log.record(DiagnosticEvent.READ_FAILED,FailureKind.NETWORK); log.record(DiagnosticEvent.WRITE_PENDING)
        assertEquals(2,log.preview().lines().size); assertFalse(log.preview().contains("START"))
        assertTrue(log.preview().length < 32768)
        assertFailsWith<IllegalArgumentException>{UserSettings(historyDays=0).checked()}
        assertFailsWith<IllegalArgumentException>{PlaybackSnapshot(snapshot().queue,0,shuffleVersion=2).checked()}
        assertFailsWith<IllegalArgumentException>{VideoRef("bad",1,1)}
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD/?p=2&t=12",BiliJumpPort.url(JumpSnapshot(VideoRef("BV1xx411c7mD",1,2),12345,null)))
    }
}
