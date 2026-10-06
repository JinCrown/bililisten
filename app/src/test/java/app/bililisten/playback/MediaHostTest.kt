package app.bililisten.playback

import org.junit.Assert.*
import org.junit.Test

class MediaHostTest {
    @Test fun mediaAllowlistRejectsSuffixSpoofingAndAccountApiHost() {
        assertTrue(isMediaHost("cn-example.bilivideo.com"))
        assertTrue(isMediaHost("upos-sz-mirror.bilivideo.cn"))
        assertFalse(isMediaHost("bilivideo.com.evil.example"))
        assertFalse(isMediaHost("evilbilivideo.com"))
        assertFalse(isMediaHost("api.bilibili.com"))
        assertFalse(isMediaHost("127.0.0.1"))
    }
}
