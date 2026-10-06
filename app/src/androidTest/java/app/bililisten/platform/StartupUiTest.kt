package app.bililisten.platform

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Read-only UI probe: uses the already signed-in identity, never plays or changes the library. */
@RunWith(AndroidJUnit4::class)
class StartupUiTest {
    @Test fun firstVisibleHomeHasCachedRecommendationsAndHistoryAndNeverAutoplays()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("startupui")=="1")
        val i=InstrumentationRegistry.getInstrumentation()
        val app=i.targetContext.applicationContext as ListenApplication
        val account=app.accounts.session.value.stamp.account
        check(!app.home.read(account)?.recommendations.isNullOrEmpty()){ "Warm the homepage once before running this probe" }
        val done=CountDownLatch(1)
        var first:ScreenState?=null
        var elapsed=0L
        val started=SystemClock.elapsedRealtime()
        val callbacks=object:Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity:Activity) {
                if(activity !is MainActivity)return
                val view=activity.window.decorView
                val listener=object:ViewTreeObserver.OnDrawListener {
                    override fun onDraw() {
                        if(first!=null)return
                        val field=MainActivity::class.java.getDeclaredField("playbackVm").apply{isAccessible=true}
                        first=(field.get(activity) as MainViewModel).state.value
                        elapsed=SystemClock.elapsedRealtime()-started
                        done.countDown()
                        view.post { view.viewTreeObserver.removeOnDrawListener(this) }
                    }
                }
                view.viewTreeObserver.addOnDrawListener(listener)
            }
            override fun onActivityCreated(a:Activity,b:Bundle?){}
            override fun onActivityStarted(a:Activity){}
            override fun onActivityPaused(a:Activity){}
            override fun onActivityStopped(a:Activity){}
            override fun onActivitySaveInstanceState(a:Activity,b:Bundle){}
            override fun onActivityDestroyed(a:Activity){}
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue("First visible draw timed out",done.await(8,TimeUnit.SECONDS))
            val state=checkNotNull(first)
            assertTrue(state.recommendations.isNotEmpty())
            assertTrue(state.startupReady)
            assertFalse(state.playRequested);assertFalse(state.playing)
            assertEquals(app.stores.observe(account).first().size,state.history.size)
            val out=File(app.filesDir,"startup-evidence").apply{mkdirs()}
            File(out,"first-draw.json").writeText("""{"firstDrawElapsedMs":$elapsed,"recommendations":${state.recommendations.size},"history":${state.history.size},"cached":${state.recommendationsCached},"startupReady":${state.startupReady},"autoplay":${state.playRequested}}""")
        } finally {app.unregisterActivityLifecycleCallbacks(callbacks)}
    }
}
