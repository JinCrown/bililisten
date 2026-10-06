package app.bililisten.playback

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@UnstableApi
@RunWith(AndroidJUnit4::class)
class PlaybackPersistenceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as ListenApplication
    private fun <T> main(action: () -> T): T {
        val value = AtomicReference<T>()
        val error = AtomicReference<Throwable>()
        instrumentation.runOnMainSync { try { value.set(action()) } catch (caught: Throwable) { error.set(caught) } }
        error.get()?.let { throw it }
        return value.get()
    }
    private fun connect(): ListenableFuture<MediaController> = main {
        MediaController.Builder(app, SessionToken(app, ComponentName(app, ListenService::class.java))).buildAsync()
    }
    private fun command(controller: MediaController, action: String, args: Bundle = Bundle.EMPTY) {
        val result = main { controller.sendCustomCommand(PlaybackCommands.command(action), args) }.get(10, TimeUnit.SECONDS)
        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
    }
    private fun load(account: String) = runBlocking { SnapshotCodec.decode(requireNotNull(app.database.listenDao().resume(account)).payload) }

    @Test fun servicePersistsShuffleAndRestoresSilentlyWithoutCreatingHistory() {
        val previousAccount = app.accountKey
        val account = "instrumented-queue"
        main { app.accountKey = account }
        val entries = listOf(
            QueueEntry("first", "BV1xx411c7mD", 62131, 1, "重复条目一", 42),
            QueueEntry("second", "BV1xx411c7mD", 62131, 1, "重复条目二", 42),
            QueueEntry("third", "BV1xx411c7mD", 62131, 1, "重复条目三", 42),
        )
        val original = ResumeSnapshot(account = account, entries = entries, order = entries.map { it.id }, currentId = "second", positionMs = 12345)
        var future = connect()
        var controller = future.get(10, TimeUnit.SECONDS)
        try {
            command(controller, PlaybackCommands.REPLACE_QUEUE, Bundle().apply { putString("snapshot", SnapshotCodec.encode(original)); putBoolean("play", false) })
            assertEquals(original, load(account))
            command(controller, PlaybackCommands.SET_MODE, Bundle().apply { putString("mode", PlayMode.SHUFFLE.name) })
            val shuffled = load(account)
            assertEquals(PlayMode.SHUFFLE, shuffled.mode)
            assertEquals(entries, shuffled.entries)
            assertEquals("second", shuffled.order.first())
            assertEquals(12345L, shuffled.positionMs)
            assertEquals(shuffled.order, main { (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId } })
            command(controller, PlaybackCommands.SET_MODE, Bundle().apply { putString("mode", PlayMode.SHUFFLE.name) })
            assertEquals(shuffled, load(account))
            assertTrue(runBlocking { app.database.listenDao().history(account).first().isEmpty() })

            main { MediaController.releaseFuture(future); app.stopService(Intent(app, ListenService::class.java)) }
            instrumentation.waitForIdleSync()
            future = connect()
            controller = future.get(10, TimeUnit.SECONDS)
            assertFalse(main { controller.playWhenReady })
            assertEquals(0, main { controller.mediaItemCount })
            command(controller, PlaybackCommands.REPLACE_QUEUE, Bundle().apply { putString("snapshot", SnapshotCodec.encode(load(account))); putBoolean("play", false) })
            assertEquals(shuffled, load(account))
            main { controller.seekToNextMediaItem() }
            command(controller, PlaybackCommands.FLUSH)
            assertEquals(shuffled.order[1], load(account).currentId)
            main { controller.seekToPreviousMediaItem() }
            command(controller, PlaybackCommands.FLUSH)
            assertEquals(shuffled.order[0], load(account).currentId)
            command(controller, PlaybackCommands.SET_MODE, Bundle().apply { putString("mode", PlayMode.SEQUENTIAL.name) })
            assertEquals(original.order, load(account).order)
            assertEquals("second", load(account).currentId)
            for ((mode, repeat) in listOf(PlayMode.REPEAT_ALL to Player.REPEAT_MODE_ALL, PlayMode.REPEAT_ONE to Player.REPEAT_MODE_ONE)) {
                command(controller, PlaybackCommands.SET_MODE, Bundle().apply { putString("mode", mode.name) })
                assertEquals(mode, load(account).mode)
                assertEquals(repeat, main { controller.repeatMode })
                assertFalse(main { controller.playWhenReady })
            }
            val beforeRejectedCommand = load(account)
            val mismatch = beforeRejectedCommand.copy(account = "different-account")
            val rejected = main { controller.sendCustomCommand(PlaybackCommands.command(PlaybackCommands.REPLACE_QUEUE), Bundle().apply {
                putString("snapshot", SnapshotCodec.encode(mismatch)); putBoolean("play", false)
            }) }.get(10, TimeUnit.SECONDS)
            assertEquals(SessionError.ERROR_BAD_VALUE, rejected.resultCode)
            assertEquals(beforeRejectedCommand, load(account))
            assertTrue(runBlocking { app.database.listenDao().history(account).first().isEmpty() })
        } finally {
            command(controller, PlaybackCommands.CLEAR)
            main { MediaController.releaseFuture(future); app.stopService(Intent(app, ListenService::class.java)); app.accountKey = previousAccount }
        }
    }
}
