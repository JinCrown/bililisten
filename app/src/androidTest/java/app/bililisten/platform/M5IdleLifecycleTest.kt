package app.bililisten.platform

import android.app.ActivityManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.bililisten.ListenApplication
import app.bililisten.MainActivity
import app.bililisten.playback.ListenService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@androidx.media3.common.util.UnstableApi @RunWith(AndroidJUnit4::class)
class M5IdleLifecycleTest {
    @Test fun removingIdleTaskReleasesBoundPlaybackService() {
        val i=InstrumentationRegistry.getInstrumentation();val app=i.targetContext.applicationContext as ListenApplication
        assumeTrue(app.vault.read()==null)
        @Suppress("DEPRECATION")
        fun bound()=app.getSystemService(ActivityManager::class.java).getRunningServices(100).any{it.service.className==ListenService::class.java.name}
        repeat(3) {
            app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            var until=System.currentTimeMillis()+10000
            while(!bound()&&System.currentTimeMillis()<until)Thread.sleep(50)
            assertTrue("UI controller did not connect",bound())
            Thread.sleep(1000)
            i.runOnMainSync{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList().forEach{it.finishAndRemoveTask()}}
            until=System.currentTimeMillis()+10000
            while(bound()&&System.currentTimeMillis()<until)Thread.sleep(100)
            assertFalse("Idle service retained after task removal",bound())
        }
    }
}
