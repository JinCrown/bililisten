package app.bililisten.playback

import kotlinx.coroutines.*

/** Samples the player's own media clock; never estimates position or drives playback. */
internal class PlaybackPositionTicker(private val scope: CoroutineScope, private val sample: () -> Unit) {
    private var playing = false
    private var precise = false
    private var job: Job? = null

    fun update(playing: Boolean, precise: Boolean) {
        if (this.playing == playing && this.precise == precise) return
        this.playing = playing
        this.precise = precise
        job?.cancel()
        job = if (playing) scope.launch {
            while (isActive) {
                delay(if (precise) 50L else 1000L)
                sample()
            }
        } else null
    }
}
