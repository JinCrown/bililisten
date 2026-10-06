package app.bililisten.platform

import org.junit.Assert.*
import org.junit.Test

class CoverDecodePolicyTest {
    @Test fun smallCoversKeepTheirOriginalResolution() {
        assertEquals(1, CoverDecodePolicy.sampleSize(640, 360))
        assertEquals(1, CoverDecodePolicy.sampleSize(1024, 1024))
    }
    @Test fun largeAndPortraitCoversUseBoundedPowerOfTwoSampling() {
        assertEquals(2, CoverDecodePolicy.sampleSize(1920, 1080))
        assertEquals(4, CoverDecodePolicy.sampleSize(1800, 3000))
        assertEquals(4, CoverDecodePolicy.sampleSize(4096, 4096))
        assertEquals(8, CoverDecodePolicy.sampleSize(4097, 1024))
        assertEquals(2, CoverDecodePolicy.sampleSize(1025, 1))
    }
    @Test fun invalidOrExtremeBoundsCannotOverflow() {
        assertNull(CoverDecodePolicy.sampleSize(0, 100))
        assertNull(CoverDecodePolicy.sampleSize(100, -1))
        val sample = requireNotNull(CoverDecodePolicy.sampleSize(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(0, sample and (sample - 1))
        assertTrue((Int.MAX_VALUE.toLong() + sample - 1) / sample <= CoverDecodePolicy.MAX_EDGE)
    }
}
