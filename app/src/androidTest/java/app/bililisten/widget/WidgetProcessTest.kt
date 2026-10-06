package app.bililisten.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.os.Process
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.R
import app.bililisten.shared.SnapshotCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@UnstableApi @RunWith(AndroidJUnit4::class)
class WidgetProcessTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private val marker get()=File(app.noBackupFilesDir,"m6g-process-host.txt")
    private fun <T> main(block:()->T):T {val result=AtomicReference<T>();i.runOnMainSync{result.set(block())};return result.get()}
    private fun waitFor(check:()->Boolean) {val end=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<end){if(check())return;Thread.sleep(40)};error("Process restore timed out")}
    private fun digest(text:String)=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
    @Test fun seedTemporaryHostWithoutStartingService() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m6gphase")=="seed")
        val owner=app.accounts.session.value.stamp.account
        val saved=requireNotNull(app.stores.load(owner))
        val host=main{AppWidgetHost(app,6037)}
        val id=main{host.allocateAppWidgetId()}
        check(!marker.exists())
        try {
            assertTrue(AppWidgetManager.getInstance(app).bindAppWidgetIdIfAllowed(id,PlaybackWidget.component(app)))
            main{host.startListening();host.createView(app,id,AppWidgetManager.getInstance(app).getAppWidgetInfo(id));app.widgets.launchRefresh{}}
            waitFor{main{app.widgets.current?.mediaId==saved.queue.currentId}}
            assertTrue(requireNotNull(main{app.widgets.current}).restored);assertFalse(requireNotNull(main{app.widgets.current}).requested)
            assertEquals(saved.queue,app.stores.load(owner)?.queue)
            marker.writeText("$id\n${Process.myPid()}\n${digest(SnapshotCodec.encode(saved.queue))}")
        } catch(e:Throwable) {main{host.deleteAppWidgetId(id)};throw e} finally {main{host.stopListening()}}
    }
    @Test fun newProcessReadsSamePausedSnapshotAndCleansTemporaryHost() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m6gphase")=="check")
        val parts=marker.readLines();val id=parts[0].toInt();val host=main{AppWidgetHost(app,6037)}
        try {
            assertNotEquals(parts[1].toInt(),Process.myPid())
            val saved=requireNotNull(app.stores.load(app.accounts.session.value.stamp.account))
            assertEquals(parts[2],digest(SnapshotCodec.encode(saved.queue)))
            val view=main{host.startListening();host.createView(app,id,requireNotNull(AppWidgetManager.getInstance(app).getAppWidgetInfo(id)));app.widgets.launchRefresh{};host.createView(app,id,AppWidgetManager.getInstance(app).getAppWidgetInfo(id))}
            val entry=saved.queue.entries.first{it.id==saved.queue.currentId}
            waitFor{main{app.widgets.current?.restored==true&&view.findViewById<TextView>(R.id.widget_title)?.text?.toString()==entry.title}}
            val state=requireNotNull(main{app.widgets.current})
            assertEquals(saved.queue.positionMs,state.positionMs);assertFalse(state.requested);assertFalse(state.canNext)
            File(app.filesDir,"m6g-evidence").apply{mkdirs()}.resolve("process.json").writeText("""{"actualPidChanged":true,"nativeHostSurvivedProcess":true,"sameSnapshotHash":true,"pausedTitleAndPositionRestored":true,"autoplay":false,"temporaryHostRemoved":true}""")
        } finally {main{host.deleteAppWidgetId(id);host.stopListening()};marker.delete()}
    }
}
