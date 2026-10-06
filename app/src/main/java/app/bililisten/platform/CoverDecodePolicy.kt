package app.bililisten.platform

internal object CoverDecodePolicy {
    const val MAX_EDGE = 1024

    fun sampleSize(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0) return null
        val edge = maxOf(width, height).toLong()
        var sample = 1
        // BitmapFactory rounds non-power-of-two samples down on supported formats.
        while ((edge + sample - 1) / sample > MAX_EDGE) sample *= 2
        return sample
    }
}
