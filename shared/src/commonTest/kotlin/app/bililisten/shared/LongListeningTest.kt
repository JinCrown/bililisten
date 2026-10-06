package app.bililisten.shared

import kotlin.test.*
import kotlinx.serialization.json.Json

class LongListeningTest {
    private val bv="BV1xx411c7mD"
    private fun row(title:String,at:Long,origin:HistoryOrigin=HistoryOrigin.LINK)=LocalHistoryEntry("7",VideoRef(bv,at,1),title,null,30,at,origin)
    @Test fun historySearchKeepsNonMusicAndUsesActualRecentOrderAcrossTypes() {
        val videos=listOf(row("数学课堂",100,HistoryOrigin.SEARCH),row("音乐演奏",10,HistoryOrigin.RECOMMENDATION))
        val lives=listOf(LiveHistoryEntry("7",6,"直播课堂",50))
        val all=HistorySearch.filter(videos,lives)
        assertEquals(listOf(100L,50L,10L),all.map{it.playedAt})
        assertEquals(1,HistorySearch.filter(videos,lives,"数学 课堂").size)
        assertEquals(2,HistorySearch.filter(videos,lives,type=HistoryType.VIDEO).size)
        assertEquals(6L,HistorySearch.filter(videos,lives,"6",HistoryType.LIVE).single().live!!.roomId)
        assertEquals("音乐演奏",HistorySearch.filter(videos,lives,origin=HistoryOrigin.RECOMMENDATION).single().title)
    }
    @Test fun allOriginsSurviveCodecIncludingLegacyFavoriteAndTypedCollection() {
        for(origin in HistoryOrigin.entries) {
            val entry=row("fixture",100,origin).entry("a")
            val q=ResumeSnapshot(entries=listOf(entry),order=listOf("a"),currentId="a",positionMs=30)
            assertEquals(origin,SnapshotCodec.decode(SnapshotCodec.encode(q)).entries.single().origin)
        }
        assertEquals(HistoryOrigin.FAVORITES,QueueEntry("a",bv,1,1,"old",sourceFolder=3).resolvedOrigin())
        assertEquals(HistoryOrigin.COLLECTION,QueueEntry("a",bv,1,1,"series",source=SourceRef(SourceKind.UP_COLLECTION,42,8,CollectionKind.SEASON)).resolvedOrigin())
    }
    @Test fun speedSurvivesRestoreAndOnlyAppliesToSameVideoNeverLive() {
        val a=QueueEntry("a",bv,1,1,"P1");val b=a.copy(id="b",cid=2,part=2);val c=a.copy(id="c",bvid="BV1rHh16CESS")
        val q=ResumeSnapshot(entries=listOf(a,b,c),order=listOf("a","b","c"),currentId="a",positionMs=12345,speeds=mapOf(bv to 1.75f))
        val saved=SnapshotCodec.decode(SnapshotCodec.encode(q))
        assertEquals(1.75f,LongListening.speed(saved,false));assertEquals(1.75f,LongListening.speed(saved.copy(currentId="b"),false))
        assertEquals(1f,LongListening.speed(saved.copy(currentId="c"),false));assertEquals(1f,LongListening.speed(saved,true))
        assertFailsWith<IllegalArgumentException>{q.copy(speeds=mapOf(bv to 3f)).checked()}
    }
    @Test fun sleepDeadlineExpiresExactlyOnceCanCancelAndReplace() {
        val timer=SleepDeadline();timer.set(120,500)
        assertEquals(60000L,timer.remaining(60500));assertFalse(timer.consumeExpired(120499));assertTrue(timer.consumeExpired(120500));assertFalse(timer.consumeExpired(120501))
        timer.set(5,1000);timer.set(0,1500);assertFalse(timer.consumeExpired(9000));assertEquals(0L,timer.remaining(9000))
        timer.set(1,10000);timer.set(5,10500);assertFalse(timer.consumeExpired(11000));assertTrue(timer.consumeExpired(15500))
        assertFailsWith<IllegalArgumentException>{timer.set(-1,0)};assertFailsWith<IllegalArgumentException>{timer.set(10801,0)}
    }
    @Test fun defaultsAndOldSettingsAreExplicitAndPrivacyChoicesIndependent() {
        val old=Json.decodeFromString<UserSettings>("""{"historyEnabled":true,"historyDays":90,"historyLimit":500}""")
        assertEquals(UserSettings(),old);assertFalse(old.historyDeleteOnExit);assertTrue(old.continuousParts)
        assertFalse(Json.decodeFromString<UserSettings>(Json.encodeToString(old.copy(continuousParts=false))).continuousParts)
        assertFalse(old.historyPolicy().keepAll);assertTrue(old.copy(historyKeepAll=true).historyPolicy().keepAll)
        val preferences=old.copy(musicBoost=false)
        assertFalse(preferences.copy(historyEnabled=false).musicBoost)
        assertEquals(old.historyPolicy(),preferences.copy(musicBoost=true).historyPolicy())
    }
}
