package app.bililisten.shared

import kotlin.test.*

class VideoContinuationTest {
    private val bv="BV1BDk2YCEHF"
    private val video=Video(bv,1,"150 首歌",List(150){VideoPart(it+101L,it+1,"歌曲 ${it+1}",200)})
    @Test fun resume150PartsAt42KeepsEveryPartInOrderAndExactMilliseconds() {
        val plan=VideoContinuation.prepare(video,VideoRef(bv,142,42),95123,true)
        assertEquals(video.parts,plan.parts);assertEquals(41,plan.currentIndex);assertEquals(95123L,plan.positionMs);assertTrue(plan.resumed)
    }
    @Test fun reorderedPartMatchesCidAndNeverGuessesByOldNumber() {
        val reordered=video.copy(parts=video.parts.reversed().mapIndexed{i,p->p.copy(number=i+1)})
        val plan=VideoContinuation.prepare(reordered,VideoRef(bv,142,42),1234,true)
        assertEquals(142L,plan.parts[plan.currentIndex].cid);assertEquals(109,plan.parts[plan.currentIndex].number)
        val removed=video.copy(parts=video.parts.filterNot{it.cid==142L})
        val fresh=VideoContinuation.prepare(removed,VideoRef(bv,142,42),99999,true)
        assertTrue(fresh.missingPart);assertEquals(0,fresh.currentIndex);assertEquals(0L,fresh.positionMs)
    }
    @Test fun finishedPartAdvancesAndFinishedVideoRestartsWithoutTrimmingShortPauses() {
        val next=VideoContinuation.prepare(video,VideoRef(bv,142,42),200000,true)
        assertEquals(42,next.currentIndex);assertEquals(0L,next.positionMs)
        val last=VideoContinuation.prepare(video,VideoRef(bv,250,150),200000,true)
        assertEquals(0,last.currentIndex);assertEquals(0L,last.positionMs);assertFalse(last.resumed)
        assertEquals(199900L,VideoContinuation.prepare(video,VideoRef(bv,142,42),199900,true).positionMs)
    }
    @Test fun disablingContinuousStillResumesTheSavedPartAndItsOwnStart() {
        val plan=VideoContinuation.prepare(video,VideoRef(bv,142,42),90000,false)
        assertEquals(listOf(video.parts[41]),plan.parts);assertEquals(0,plan.currentIndex);assertEquals(90000L,plan.positionMs)
        val completed=VideoContinuation.prepare(video,VideoRef(bv,142,42),200000,false)
        assertEquals(listOf(video.parts[41]),completed.parts);assertEquals(0L,completed.positionMs)
    }
    @Test fun noHistoryAndDifferentVideoStartFromP1AndUnknownDurationKeepsProgress() {
        assertEquals(0,VideoContinuation.prepare(video,null,0,true).currentIndex)
        assertFalse(VideoContinuation.prepare(video,VideoRef("BV1xx411c7mD",142,42),90000,true).resumed)
        val unknown=video.copy(parts=video.parts.map{it.copy(durationSeconds=0)})
        assertEquals(999999L,VideoContinuation.prepare(unknown,VideoRef(bv,142,42),999999,true).positionMs)
    }
}
