package app.bililisten.playback

import androidx.media3.common.util.UnstableApi
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
import kotlin.system.measureNanoTime

/** Opt-in read-only requests and ten brief VOD starts; always restores local settings/snapshot. */
@UnstableApi @RunWith(AndroidJUnit4::class)
class M5TimingProbe {
    @Test fun measuredReadAndPlayerReadinessLatency()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m5timing")=="1")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
        val account=app.accounts.verify() ?: error("Existing signed-in test account required")
        app.accountKey=account.id.toString()
        val original=app.stores.load(app.accountKey)!!
        val settings=app.settings.settings.first()
        val player=withContext(Dispatchers.Main){ControllerPlaybackPort(app)}
        val series=linkedMapOf<String,List<Double>>()
        try {
            app.settings.update(settings.copy(historyEnabled=false,mobilePlayback=false))
            withTimeout(5000){app.settings.settings.first{it.historyEnabled&&it.mobilePlayback==false}}
            suspend fun timings(name:String,action:suspend()->Unit) {
                val samples=mutableListOf<Double>()
                repeat(10){samples+=measureNanoTime{action()}/1e6;delay(1000)}
                series[name]=samples
            }
            timings("accountVerifyMs"){assertNotNull(app.accounts.verify())}
            val video=app.content.video("BV1c4411d7jb");val part=video.parts.single{it.number==2}
            timings("videoDetailsMs"){assertEquals(video.bvid,app.content.video(video.bvid).bvid)}
            timings("audioResolveMs"){assertTrue(app.content.audio(video.bvid,part.cid).startsWith("https://"))}
            timings("localSnapshotReadMs"){assertNotNull(app.stores.load(app.accountKey))}
            withTimeout(15000){player.state.first{it.connected}}
            var n=0
            val readiness=mutableListOf<Double>()
            repeat(10){
                val id="m5-timing-${n++}"
                val queue=ResumeSnapshot(account=app.accountKey,entries=listOf(QueueEntry(id,video.bvid,part.cid,2,"timing fixture")),order=listOf(id),currentId=id,positionMs=0)
                val start=System.nanoTime()
                withContext(Dispatchers.Main){player.replace(queue,true)}
                withTimeout(30000){player.state.first{it.currentId==id&&it.playing}}
                readiness+=(System.nanoTime()-start)/1e6
                withContext(Dispatchers.Main){player.pause();player.flush()}
                delay(1000)
            }
            series["playRequestToIsPlayingMs"]=readiness
            val data=buildJsonObject {
                put("samplesPerSeries",10);put("build", "debug-instrumented");put("network","Wi-Fi")
                put("firstSoundMethod","MediaController isPlaying proxy; not acoustic output measurement")
                put("series",buildJsonObject{series.forEach{(key,values)->put(key,JsonArray(values.map{JsonPrimitive(it)}))}})
            }
            File(app.filesDir,"m5-evidence").mkdirs();File(app.filesDir,"m5-evidence/timing.json").writeText(data.toString())
        } finally {
            withContext(Dispatchers.Main){player.pause();player.clear();player.close()}
            app.stores.checkpoint(original,null);app.settings.update(settings)
            assertEquals(original,app.stores.load(app.accountKey))
        }
    }
}
