package app.bililisten

import app.bililisten.shared.*
import org.junit.Assert.*
import org.junit.Test

class PlayerTitlesTest {
    private val video=Video("BV1xx411c7mD",1,"长视频合集",listOf(VideoPart(101,1,"第一首"),VideoPart(202,2,"第二首")))
    private val row=QueueEntry("second",video.bvid,202,2,"长视频合集 · 第二首")
    @Test fun sameResolverSelectsTheCidTitleInsteadOfLongVideoOrPosition(){
        assertEquals("第二首",playbackEntryTitle(video,row,"fallback"))
        assertEquals("第二首",playbackEntryTitle(video.copy(parts=video.parts.reversed()),row.copy(part=99),"fallback"))
    }
    @Test fun missingOrBlankPartUsesExactStoredPrefixWithoutGuessingAtSeparators(){
        assertEquals("第二首",playbackEntryTitle(video,row.copy(cid=999),"fallback"))
        assertEquals("第二首",playbackEntryTitle(video.copy(parts=video.parts.map{it.copy(title="")}),row,"fallback"))
        assertEquals("另一个标题 · 原有文字",playbackEntryTitle(video,row.copy(cid=999,title="另一个标题 · 原有文字"),"fallback"))
    }
    @Test fun singleVideoAndUnavailableOrMismatchedMetadataRetainValidFallback(){
        assertEquals(video.title,playbackEntryTitle(video.copy(parts=video.parts.take(1)),row,"fallback"))
        assertEquals(row.title,playbackEntryTitle(null,row,"fallback"))
        assertEquals(row.title,playbackEntryTitle(video.copy(bvid="BV1xx411c7mE"),row,"fallback"))
        assertEquals("fallback",playbackEntryTitle(null,null,"fallback"))
    }
}
