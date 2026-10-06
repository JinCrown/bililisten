package app.bililisten.playback

import android.content.ComponentName
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Host marks the emulator's test Wi-Fi metered and restores the override afterwards. No media is fetched. */
@UnstableApi @RunWith(AndroidJUnit4::class)
class M2MeteredProbe {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private fun <T> main(f:()->T):T{val out=AtomicReference<T>();val err=AtomicReference<Throwable>();i.runOnMainSync{try{out.set(f())}catch(e:Throwable){err.set(e)}};err.get()?.let{throw it};return out.get()}
    @Test fun meteredNetworkHonorsSavedChoiceAndLiveControlsAreUnavailable()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m2phase")=="metered")
        val original=app.settings.settings.first()
        val network=PlaybackNetwork(app,{app.settings.settings.value},{})
        assertEquals(NetworkKind.METERED,network.kind())
        val future=main{MediaController.Builder(app,SessionToken(app,ComponentName(app,ListenService::class.java))).buildAsync()}
        val c=future.get(15,TimeUnit.SECONDS)
        fun command(name:String,b:Bundle=Bundle.EMPTY)=main{c.sendCustomCommand(PlaybackCommands.command(name),b)}.get(15,TimeUnit.SECONDS).resultCode
        suspend fun choice(value:Boolean?){app.settings.update(original.copy(mobilePlayback=value));withTimeout(5000){app.settings.settings.first{it.mobilePlayback==value}}}
        try {
            choice(null);assertEquals(PlaybackIssue.MOBILE_CHOICE,network.blocked())
            choice(false);assertEquals(PlaybackIssue.MOBILE_DISABLED,network.blocked())
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackCommands.PLAY_LIVE,Bundle().apply{putLong("room",1);putString("title","M2 network fixture");putBoolean("mixedConsent",true)}))
            assertFalse(main{c.playWhenReady});assertEquals(PlaybackIssue.MOBILE_DISABLED.name,main{c.sessionExtras.getString("issue")})
            assertFalse(main{c.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)})
            assertEquals(SessionError.ERROR_BAD_VALUE,command(PlaybackCommands.SET_MODE,Bundle().apply{putString("mode",PlayMode.SHUFFLE.name)}))
            assertEquals(SessionResult.RESULT_SUCCESS,command(PlaybackCommands.CLEAR))
            choice(true);assertNull(network.blocked());assertFalse(main{c.playWhenReady})
            File(app.filesDir,"m2-evidence").mkdirs()
            File(app.filesDir,"m2-evidence/metered.json").writeText(buildJsonObject{put("osMeteredNetwork",true);put("firstChoiceRequired",true);put("denyBlockedLoading",true);put("allowRemembered",true);put("didNotAutoplay",true);put("liveSeekAndModeDisabled",true);put("mediaTransferred",false);put("realCellularTested",false)}.toString())
        }finally{command(PlaybackCommands.CLEAR);main{MediaController.releaseFuture(future)};app.settings.update(original)}
    }
}
