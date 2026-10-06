package app.bililisten

import app.bililisten.shared.*
import org.junit.Assert.*
import org.junit.Test

class LyricHighlightTest {
    private val cue=SubtitleCue(1000,3000,"你好")
    private val words=listOf(LyricWord(1000,1800,"你"),LyricWord(1800,3000,"好"))
    @Test fun highlightsOnlySourceTimedWordsAndRewindsOnSeek() {
        val first=lyricLineText(cue,words,1799,true,true)
        assertEquals("你好",first.text);assertEquals(ImmersivePink,first.spanStyles[0].item.color)
        assertNotEquals(ImmersivePink,first.spanStyles[1].item.color)
        assertEquals(ImmersivePink,lyricLineText(cue,words,1800,true,true).spanStyles[1].item.color)
        assertNotEquals(ImmersivePink,lyricLineText(cue,words,1000,true,true).spanStyles[1].item.color)
    }
    @Test fun unconfirmedInactiveAndUntimedLinesNeverReceiveWordHighlight() {
        assertTrue(lyricLineText(cue,words,2000,false,true).spanStyles.isEmpty())
        assertTrue(lyricLineText(cue,words,2000,true,false).spanStyles.isEmpty())
        assertTrue(lyricLineText(cue,emptyList(),2000,true,true).spanStyles.isEmpty())
    }
}
