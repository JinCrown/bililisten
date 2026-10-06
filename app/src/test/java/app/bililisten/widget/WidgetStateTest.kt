package app.bililisten.widget

import app.bililisten.shared.PlaybackIssue
import org.junit.Assert.*
import org.junit.Test

class WidgetStateTest {
    private val vod = WidgetState(account = "7", mediaId = "part-2", version = 4, title = "音乐合集", part = 2,
        positionMs = 12345, durationMs = 60000, canPrevious = true, canNext = true, canSeek = true)
    @Test fun emptyHasNoMediaCommands() {
        listOf("play", "pause", "previous", "next", "back", "forward", "other").forEach { assertFalse(WidgetState().allows(it)) }
        assertFalse(WidgetState().hasContent)
    }
    @Test fun vodUsesActualCapabilitiesAndPart() {
        listOf("play", "pause", "previous", "next", "back", "forward").forEach { assertTrue(vod.allows(it)) }
        assertTrue(vod.status.contains("P2")); assertTrue(vod.status.contains("已暂停"))
        assertFalse(vod.copy(canNext = false).allows("next")); assertFalse(vod.copy(canSeek = false).allows("back"))
        assertFalse(vod.allows("arbitrary"))
    }
    @Test fun liveNeverExposesVodControlsEvenWithBogusCapabilities() {
        val live = vod.copy(live = true)
        listOf("previous", "next", "back", "forward").forEach { assertFalse(live.allows(it)) }
        assertTrue(live.allows("play")); assertTrue(live.status.startsWith("直播")); assertFalse(live.status.contains("P2"))
    }
    @Test fun restoreKeepsPausedAndRequiresExplicitPlay() {
        val restored = vod.copy(restored = true)
        assertFalse(restored.requested); assertTrue(restored.allows("play")); assertTrue(restored.status.startsWith("继续收听"))
        listOf("previous", "next", "back", "forward").forEach { assertFalse(restored.allows(it)) }
    }
    @Test fun staleAccountItemAndVersionCannotMatch() {
        assertTrue(vod.matches("7", "part-2", 4))
        assertFalse(vod.matches("8", "part-2", 4)); assertFalse(vod.matches("7", "part-3", 4)); assertFalse(vod.matches("7", "part-2", 5))
    }
    @Test fun bufferingAndPauseReflectRequestedNotDecoderState() {
        assertTrue(vod.copy(requested = true, buffering = true).status.contains("正在缓冲"))
        assertTrue(vod.copy(requested = false, buffering = true).status.contains("已暂停"))
        assertTrue(vod.copy(requested = true).status.contains("正在播放"))
    }
    @Test fun restrictionsAndConnectionFailuresAreNotPresentedAsPlaying() {
        val denied = vod.copy(requested = true, issue = PlaybackIssue.CONTENT_UNAVAILABLE)
        assertEquals(PlaybackIssue.CONTENT_UNAVAILABLE.message, denied.status)
        assertEquals("打开应用重试", WidgetState(notice = "打开应用重试").status)
    }
    @Test fun timeSupportsLongContentAndUnknownNegativeValues() {
        assertEquals("0:00", widgetTime(-1)); assertEquals("0:15", widgetTime(15000))
        assertEquals("59:59", widgetTime(3599000)); assertEquals("25:02:03", widgetTime(90123000))
    }
}
