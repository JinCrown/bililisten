package app.bililisten.shared

import kotlin.test.*
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*

class PlaybackRulesTest {
    private fun entry(id:String)=QueueEntry(id,"BV1xx411c7mD",1,2,"fixture",source=SourceRef(SourceKind.PUBLIC_FAVORITES,8,9))
    private fun queue()=ResumeSnapshot(account="7",entries=listOf(entry("a"),entry("b"),entry("c")),order=listOf("a","b","c"),currentId="b",positionMs=12345)
    @Test fun nextInsertionPreservesCurrentProgressAndDuplicateContentIds() {
        val result=QueueEditor.apply(queue(),QueueEdit.Add(listOf(entry("d")),true),1)!!
        assertEquals(listOf("a","b","d","c"),result.order);assertEquals("b",result.currentId);assertEquals(12345L,result.positionMs)
        assertEquals(2L,result.queueVersion);assertEquals(4,result.entries.size)
        assertFailsWith<IllegalArgumentException>{QueueEditor.apply(result,QueueEdit.Add(listOf(entry("b"))),2)}
    }
    @Test fun shuffledEditingAndRestartPreserveExactOrderAndVersion() {
        val shuffled=queue().withMode(PlayMode.SHUFFLE,Random(7))
        val moved=QueueEditor.apply(shuffled,QueueEdit.Move("a",0),shuffled.queueVersion)!!
        assertEquals("a",moved.order.first());assertEquals("b",moved.currentId)
        val restored=SnapshotCodec.decode(SnapshotCodec.encode(moved))
        assertEquals(moved,restored);assertEquals(moved,restored.withMode(PlayMode.SHUFFLE,Random(100)))
        assertEquals(moved.order,restored.withMode(PlayMode.SEQUENTIAL).order)
        assertFailsWith<IllegalArgumentException>{QueueEditor.apply(moved,QueueEdit.Remove("c"),1)}
    }
    @Test fun removeCurrentSelectsSuccessorAtZeroAndClearDoesNotInventSnapshot() {
        val removed=QueueEditor.apply(queue(),QueueEdit.Remove("b"),1)!!
        assertEquals("c",removed.currentId);assertEquals(0L,removed.positionMs)
        assertNull(QueueEditor.apply(queue(),QueueEdit.Clear,1))
        val one=queue().copy(entries=listOf(entry("b")),order=listOf("b"))
        assertNull(QueueEditor.apply(one,QueueEdit.Remove("b"),1))
        assertFailsWith<IllegalArgumentException>{QueueEditor.apply(queue(),QueueEdit.Move("b",100),1)}
    }
    @Test fun oldSnapshotsGainVersionWithoutChangingSourceAndBadVersionIsRejected() {
        val old="""{"account":"7","entries":[{"id":"a","bvid":"BV1xx411c7mD","cid":1,"part":2,"title":"fixture","sourceFolder":42}],"order":["a"],"currentId":"a","positionMs":12345}"""
        val saved=SnapshotCodec.decode(old);assertEquals(1L,saved.queueVersion)
        assertEquals(SourceRef(SourceKind.OWN_FAVORITES,42,7),saved.entries.single().resolvedSource("7"))
        assertFailsWith<IllegalArgumentException>{saved.copy(queueVersion=0).checked()}
    }
    @Test fun meteredChoiceIsSeparateFromCachingAndOfflineNeverAllowsPlayback() {
        assertEquals(PlaybackIssue.MOBILE_CHOICE,PlaybackNetworkPolicy.blocked(NetworkKind.METERED,null))
        assertEquals(PlaybackIssue.MOBILE_DISABLED,PlaybackNetworkPolicy.blocked(NetworkKind.METERED,false))
        assertNull(PlaybackNetworkPolicy.blocked(NetworkKind.METERED,true))
        assertNull(PlaybackNetworkPolicy.blocked(NetworkKind.UNMETERED,null))
        assertEquals(PlaybackIssue.OFFLINE,PlaybackNetworkPolicy.blocked(NetworkKind.OFFLINE,true))
        assertFalse(CachePolicy().automaticAudioCache)
    }
    @Test fun retryBudgetRejectsOfflineExhaustedAndMissingContent() {
        assertEquals(500L,StreamRetryPolicy.retryDelayMs(1,true,403,false))
        assertEquals(500L,StreamRetryPolicy.retryDelayMs(1,true,null,true))
        assertNull(StreamRetryPolicy.retryDelayMs(2,true,403,false))
        assertNull(StreamRetryPolicy.retryDelayMs(1,false,403,true))
        assertNull(StreamRetryPolicy.retryDelayMs(1,true,404,false))
    }
    @Test fun periodicCheckpointIsThrottledAndStaleEventsRejected() {
        val policy=CheckpointPolicy()
        assertFalse(policy.periodic(0,false));assertTrue(policy.periodic(0,true))
        repeat(49){assertFalse(policy.periodic((it+1)*100L,true))};assertTrue(policy.periodic(5000,true))
        assertFalse(policy.accepts(queue(),0,"b"));assertFalse(policy.accepts(queue(),1,"a"));assertTrue(policy.accepts(queue(),1,"b"))
    }
    @Test fun offlineCredentialOnlyIdentifiesLocalHistoryAndNeverAuthenticates()=runTest {
        val vault=object:CredentialStore{
            override val generation=1L
            override fun read()="DedeUserID=7; SESSDATA=offline-fixture"
            override fun save(cookie:String)=error("unused")
            override fun clear()=error("must keep credential")
        }
        val client=HttpClient(MockEngine { throw java.io.IOException("offline") })
        try {
            val accounts=SessionAccountRepository(BiliApi(client){vault.read()},vault)
            assertEquals("7",accounts.session.value.stamp.account)
            assertFailsWith<PlatformFailure>{accounts.verify()}
            assertEquals("7",accounts.session.value.stamp.account);assertEquals(SessionStatus.UNVERIFIED,accounts.session.value.status)
            assertFailsWith<PlatformFailure>{accounts.requireCurrent(accounts.session.value.stamp)}
        }finally{client.close()}
    }
}
