package app.bililisten.platform

import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.bililisten.*
import app.bililisten.shared.Theme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Controlled response-to-first-row-draw benchmark using the production row component. */
@androidx.media3.common.util.UnstableApi @RunWith(AndroidJUnit4::class)
class M5ListRenderTest {
    @Test fun fiftyItemResponseDrawsFirstRowWithinOneSecond() {
        val i=InstrumentationRegistry.getInstrumentation()
        val app=i.targetContext.applicationContext as ListenApplication
        assumeTrue(app.vault.read()==null) // emulator fixture only
        app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        var activity:MainActivity?=null
        val until=System.currentTimeMillis()+10000
        while(activity==null&&System.currentTimeMillis()<until){
            i.runOnMainSync{activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()}
            Thread.sleep(50)
        }
        assertNotNull(activity)
        val rows=mutableStateOf(emptyList<Int>());val drawn=AtomicLong(0)
        i.runOnMainSync {activity!!.setContent {
            ListenTheme(Theme.LIGHT){LazyColumn{items(rows.value,key={it}){number->
                Box(Modifier.drawWithContent {drawContent();if(number==rows.value.firstOrNull())drawn.compareAndSet(0,SystemClock.elapsedRealtimeNanos())}) {
                    VideoRowCard("fixture $number: 列表响应绘制测试","","fixture author",180,12000,"fixture folder",{}, {})
                }
            }}}
        }}
        val samples=mutableListOf<Double>()
        repeat(25){round->
            i.runOnMainSync{rows.value=emptyList()};i.waitForIdleSync();Thread.sleep(80)
            var start=0L
            i.runOnMainSync{drawn.set(0);start=SystemClock.elapsedRealtimeNanos();rows.value=(round*50 until round*50+50).toList()}
            val deadline=System.currentTimeMillis()+3000
            while(drawn.get()==0L&&System.currentTimeMillis()<deadline)Thread.sleep(5)
            assertTrue("No row was drawn",drawn.get()>0)
            samples+=(drawn.get()-start)/1e6
        }
        File(app.filesDir,"m5-evidence").mkdirs()
        File(app.filesDir,"m5-evidence/list-render.json").writeText(buildJsonObject {
            put("method","controlled 50-item response to first production VideoRowCard draw; excludes network/cover fetch/GPU presentation")
            put("build","debug-instrumented");put("samples",JsonArray(samples.map{JsonPrimitive(it)}))
        }.toString())
        assertTrue("Response-to-first-row draw exceeded 1 second",samples.all{it<1000})
        i.runOnMainSync{activity!!.finish()}
    }
}
