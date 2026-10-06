package app.bililisten.playback

import android.media.AudioDeviceInfo
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@UnstableApi @RunWith(AndroidJUnit4::class)
class M6CAudioServiceTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
    @Test fun pausedQualitySwitchStaleResponsesOutputSelectionAndExternalGuardPreserveUserData()=runBlocking {
        val owner=app.accounts.session.value.stamp.account
        val saved=app.stores.load(owner);val settings=app.settings.current()
        val repo=app.entitlements
        val field=ListenApplication::class.java.getDeclaredField("entitlements").apply{isAccessible=true}
        var gate:CompletableDeferred<Unit>?=null
        val high=AudioTrack(30280,"https://example.bilivideo.com/high","audio/mp4","mp4a.40.2",192000)
        val low=high.copy(id=30216,codec="mp4a.40.5",bandwidth=64000)
        field.set(app,object:EntitlementRepository {
            override suspend fun inspect(video:VideoRef)=error("unused")
            override suspend fun probe(bvid:String,cid:Long,extended:Boolean):AudioProbe {gate?.await();return AudioProbe(listOf(high,low),false,60000)}
        })
        val account="m6c-fixture";app.accountKey=account
        val port=withContext(Dispatchers.Main){ControllerPlaybackPort(app)}
        suspend fun command(block:suspend ()->Unit)=withContext(Dispatchers.Main){block()}
        val a=QueueEntry("m6c-a","BV1xx411c7mD",1,1,"fixture")
        val q=ResumeSnapshot(account=account,entries=listOf(a),order=listOf(a.id),currentId=a.id,positionMs=12345,speeds=mapOf(a.bvid to 1.5f))
        try {
            withTimeout(15000){port.state.first{it.connected}}
            app.settings.update(settings.copy(historyEnabled=false,preferredOutputType=null,externalOutputOnly=false,audioChoice=AudioChoice()))
            delay(300)
            command{port.replace(q,false);port.audioQuality()}
            assertEquals(2,port.state.value.audio.options.size)
            command{port.audioQuality(AudioChoice(low.id,low.codec));port.flush()}
            assertFalse(port.state.value.requested);assertEquals(12345L,port.state.value.positionMs)
            assertEquals(a.id,port.state.value.currentId);assertEquals(1.5f,port.state.value.speed,0f)
            assertEquals(low.id,port.state.value.audio.selected?.id)
            assertEquals(AudioChoice(low.id,low.codec),app.settings.current().audioChoice)
            assertTrue(app.stores.observe(account).first().isEmpty())
            gate=CompletableDeferred()
            val changing=async{runCatching{command{port.audioQuality(AudioChoice())}}}
            withTimeout(5000){port.state.first{it.audio.checking}}
            command{port.replace(q.copy(entries=listOf(a.copy(cid=2,part=2)),positionMs=4567),false)}
            gate!!.complete(Unit)
            assertTrue(changing.await().isFailure)
            assertEquals(4567L,port.state.value.positionMs);assertNull(port.state.value.audio.selected)
            gate=null
            val speaker=port.state.value.output.devices.first{it.type==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER}
            command{port.audioOutput(speaker.id);port.flush()}
            assertEquals(speaker.type,app.settings.current().preferredOutputType)
            assertFalse(port.state.value.requested);assertEquals(4567L,port.state.value.positionMs)
            assertTrue(runCatching{command{port.audioOutput(-999)}}.isFailure)
            assertEquals(speaker.type,app.settings.current().preferredOutputType)
            if(port.state.value.output.devices.none{AudioOutputMonitor.external(it.type)}) {
                app.settings.update(app.settings.current().copy(externalOutputOnly=true));delay(300)
                command{port.toggle()};delay(300)
                assertFalse(port.state.value.requested);assertEquals(PauseReason.NOISY,port.state.value.pauseReason)
            }
            File(app.filesDir,"m6c-evidence").apply{mkdirs()}.resolve("service.json").writeText("""{"pausedSwitchPreservesPosition":true,"staleResponseRejected":true,"outputPreferenceSaved":true,"invalidOutputRejected":true,"externalGuardTested":${port.state.value.output.devices.none{AudioOutputMonitor.external(it.type)}},"physicalHeadsetTested":false}""")
        }finally {
            gate?.complete(Unit)
            command{port.pause();port.flush();port.clear()}
            field.set(app,repo);app.settings.update(settings);app.accountKey=owner
            if(saved!=null)command{port.replace(saved.queue,false)}
            command{port.close()}
            app.database.listenDao().clearPlayback(account);app.database.listenDao().clearHistory(account)
        }
    }
}
