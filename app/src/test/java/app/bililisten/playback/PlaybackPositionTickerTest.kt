package app.bililisten.playback

import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import app.bililisten.shared.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackPositionTickerTest {
    @Test fun readerSeesCueBoundaryWithinOneSampleAtDifferentSpeeds() = runTest {
        for (speed in listOf(.5f,1f,1.5f,3f)) {
            val origin=currentTime
            val timeline=SubtitleTimeline(listOf(SubtitleCue(1000,1500,"first"),SubtitleCue(2000,3000,"next")))
            var observed=emptyList<Int>()
            val ticker=PlaybackPositionTicker(backgroundScope) {
                observed=timeline.active(1990+((currentTime-origin)*speed).toLong())
            }
            ticker.update(true,true);runCurrent()
            advanceTimeBy(49);runCurrent();assertTrue(observed.isEmpty())
            advanceTimeBy(1);runCurrent();assertEquals("reader must not wait for the old one-second poll at $speed",listOf(1),observed)
            ticker.update(false,true)
        }
    }
    @Test fun switchingOutOfReaderAndStoppingPlaybackCancelFastTicks() = runTest {
        val samples=mutableListOf<Long>()
        val ticker=PlaybackPositionTicker(backgroundScope){samples+=currentTime}
        ticker.update(true,false);runCurrent();advanceTimeBy(300);runCurrent();assertTrue(samples.isEmpty())
        ticker.update(true,true);runCurrent();advanceTimeBy(50);runCurrent();assertEquals(listOf(350L),samples)
        ticker.update(true,true);advanceTimeBy(50);runCurrent();assertEquals(listOf(350L,400L),samples)
        ticker.update(true,false);runCurrent();advanceTimeBy(999);runCurrent();assertEquals(2,samples.size)
        advanceTimeBy(1);runCurrent();assertEquals(1400L,samples.last())
        ticker.update(false,false);advanceTimeBy(2000);runCurrent();assertEquals(3,samples.size)
    }
    @Test fun seekingAndBufferRecoveryReadFreshMediaClockInsteadOfExtrapolating() = runTest {
        var mediaPosition=8000L;var displayed=0L
        val ticker=PlaybackPositionTicker(backgroundScope){displayed=mediaPosition}
        ticker.update(true,true);runCurrent();advanceTimeBy(50);runCurrent();assertEquals(8000L,displayed)
        mediaPosition=1500;advanceTimeBy(50);runCurrent();assertEquals(1500L,displayed)
        ticker.update(false,true);mediaPosition=1800;advanceTimeBy(1000);runCurrent();assertEquals(1500L,displayed)
        ticker.update(true,true);runCurrent();advanceTimeBy(50);runCurrent();assertEquals(1800L,displayed)
    }
}
