package app.bililisten.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

object PlaybackCommands {
    const val REPLACE_QUEUE = "app.bililisten.REPLACE_QUEUE"
    const val SET_MODE = "app.bililisten.SET_MODE"
    const val FLUSH = "app.bililisten.FLUSH"
    const val CLEAR = "app.bililisten.CLEAR"
    const val PLAY_LIVE = "app.bililisten.PLAY_LIVE"
    const val LIVE_EDGE = "app.bililisten.LIVE_EDGE"
    const val FORGET_HISTORY = "app.bililisten.FORGET_HISTORY"
    const val PAUSE_REASON = "app.bililisten.PAUSE_REASON"
    const val EDIT_QUEUE = "app.bililisten.EDIT_QUEUE"
    const val SET_SPEED = "app.bililisten.SET_SPEED"
    const val AUDIO_QUALITY = "app.bililisten.AUDIO_QUALITY"
    const val AUDIO_OUTPUT = "app.bililisten.AUDIO_OUTPUT"
    const val AUDIO_EFFECTS = "app.bililisten.AUDIO_EFFECTS"
    const val SLEEP_TIMER = "app.bililisten.SLEEP_TIMER"
    const val EXIT_LISTENING = "app.bililisten.EXIT_LISTENING"
    const val WIDGET_CONTROL = "app.bililisten.WIDGET_CONTROL"
    fun command(action: String) = SessionCommand(action, Bundle.EMPTY)
    val all = listOf(REPLACE_QUEUE, SET_MODE, FLUSH, CLEAR, PLAY_LIVE, LIVE_EDGE, FORGET_HISTORY, PAUSE_REASON, EDIT_QUEUE, SET_SPEED, SLEEP_TIMER, EXIT_LISTENING, AUDIO_QUALITY, AUDIO_OUTPUT, AUDIO_EFFECTS, WIDGET_CONTROL)
}
