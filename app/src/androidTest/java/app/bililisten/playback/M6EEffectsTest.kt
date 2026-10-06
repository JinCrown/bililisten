package app.bililisten.playback

import android.net.ConnectivityManager
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

@UnstableApi @RunWith(AndroidJUnit4::class)
class M6EEffectsTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
    private suspend fun command(block:suspend ()->Unit)=withContext(Dispatchers.Main){block()}
    @Test fun separateNativeAudioTrackSessionsRebindAndReleaseWithoutPlaying()=runBlocking {
        fun track()=android.media.AudioTrack.Builder().setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(44100).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(16384).setTransferMode(android.media.AudioTrack.MODE_STREAM).build()
        val first=track();val second=track();assertTrue(first.audioSessionId>0);assertNotEquals(first.audioSessionId,second.audioSessionId)
        val c=withContext(Dispatchers.Main){EffectsController(AndroidEffectsFactory())};val pref=EffectsSettings(enabled=true,bands=listOf(EffectBand(60_000,-200)))
        try {
            command{c.sync(pref,EffectsContext(first.audioSessionId));assertEquals(EffectPhase.APPLIED,c.state.equalizer.phase)}
            command{c.sync(pref,EffectsContext(second.audioSessionId));assertEquals(EffectPhase.APPLIED,c.state.equalizer.phase)}
            assertEquals(second.audioSessionId,c.state.sessionId);assertEquals(-200,c.state.equalizer.bands.first().levelMilliBel)
            command{c.close()};assertEquals(EffectPhase.OFF,c.state.equalizer.phase)
            File(app.filesDir,"m6e-evidence").apply{mkdirs()}.resolve("sessions.json").writeText("""{"distinctPositiveAudioTrackSessions":true,"reappliedCustomGain":true,"released":true,"played":false,"routeChangePhysicalTested":false}""")
        }finally{command{c.close()};first.release();second.release()}
    }
    @Test fun pausedPreferencesAndResetPreserveQueueSpeedPrivacyAndNeverAutoplay()=runBlocking {
        val owner=app.accounts.session.value.stamp.account;val original=app.stores.load(owner);val saved=app.settings.current()
        val fixture="m6e-paused-fixture";app.accountKey=fixture
        val port=withContext(Dispatchers.Main){ControllerPlaybackPort(app)}
        try {
            withTimeout(15000){port.state.first{it.connected}}
            app.settings.update(saved.copy(historyEnabled=false,externalOutputOnly=false,preferredOutputType=null,effects=EffectsSettings()))
            val entry=QueueEntry("m6e-paused","BV1xx411c7mD",1,1,"local fixture")
            command{port.replace(ResumeSnapshot(account=fixture,entries=listOf(entry),order=listOf(entry.id),currentId=entry.id,positionMs=12345,speeds=mapOf(entry.bvid to 1.5f)),false);port.flush()}
            val before=port.state.value
            val pref=EffectsSettings(enabled=true,bass=true,bassStrength=650)
            command{port.audioEffects(pref);port.flush()}
            assertEquals(pref,app.settings.current().effects);assertTrue(app.settings.current().historyEnabled)
            assertFalse(port.state.value.requested);assertEquals(before.positionMs,port.state.value.positionMs)
            assertEquals(before.queue,port.state.value.queue);assertEquals(before.queueVersion,port.state.value.queueVersion)
            assertEquals(1.5f,port.state.value.speed,0f)
            assertTrue(port.state.value.effects.equalizer.phase in setOf(EffectPhase.WAITING_SESSION,EffectPhase.APPLIED,EffectPhase.UNSUPPORTED,EffectPhase.FAILED))
            command{port.audioEffects();port.audioEffects(EffectsSettings());port.flush()}
            assertEquals(EffectsSettings(),app.settings.current().effects);assertEquals(EffectPhase.OFF,port.state.value.effects.equalizer.phase)
            assertEquals(before.positionMs,port.state.value.positionMs);assertFalse(port.state.value.requested)
            assertTrue(app.stores.observe(fixture).first().isEmpty())
            File(app.filesDir,"m6e-evidence").apply{mkdirs()}.resolve("paused.json").writeText("""{"queuePositionSpeedPreserved":true,"settingsSaved":true,"historyOffPreserved":true,"resetOff":true,"autoplay":false}""")
        }finally {
            command{port.pause();port.clear()};app.settings.update(saved);app.accountKey=owner
            if(original!=null)command{port.replace(original.queue,false);port.flush()}
            command{port.close()};app.database.listenDao().clearPlayback(fixture);app.database.listenDao().clearHistory(fixture)
        }
    }
    @Test fun realMedia3SessionNativePresetsCustomBassAndSessionRebuild()=runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("m6eonline")=="1")
        val network=app.getSystemService(ConnectivityManager::class.java)
        assertNotNull(network.activeNetwork);assertFalse(network.isActiveNetworkMetered)
        assertNotNull(app.accounts.verify())
        val owner=app.accounts.session.value.stamp.account;val original=app.stores.load(owner);val saved=app.settings.current()
        val fixture="m6e-online-fixture";val out=File(app.filesDir,"m6e-evidence").apply{mkdirs()}
        val port=withContext(Dispatchers.Main){ControllerPlaybackPort(app)}
        val observations=mutableListOf<JsonElement>()
        fun record(label:String){observations+=buildJsonObject{put("step",label);put("state",Json.encodeToJsonElement(port.state.value.effects))}}
        try {
            withTimeout(15000){port.state.first{it.connected}}
            app.accountKey=fixture;app.settings.update(saved.copy(historyEnabled=false,externalOutputOnly=false,preferredOutputType=null,effects=EffectsSettings()))
            val video=app.content.video("BV1U1421r7SM");val part=video.parts.first()
            val entry=QueueEntry("m6e-online",video.bvid,part.cid,part.number,video.title)
            val q=ResumeSnapshot(account=fixture,entries=listOf(entry),order=listOf(entry.id),currentId=entry.id,positionMs=3000)
            command{port.replace(q,true)}
            withTimeout(30000){port.state.first{it.playing&&it.effects.sessionId>0&&it.output.routedId!=null}}
            command{port.pause();port.flush();port.speed(1.5f)}
            val before=port.state.value;assertFalse(before.requested)
            var pref=EffectsSettings(enabled=true,bass=true,bassStrength=650)
            command{port.audioEffects(pref)};delay(300);record("flat-and-bass")
            val eq=port.state.value.effects.equalizer
            assertEquals("Samsung session must demonstrate actual EQ support",EffectPhase.APPLIED,eq.phase)
            assertTrue(eq.bands.isNotEmpty());assertTrue(eq.bands.all{it.levelMilliBel==0});assertTrue(port.state.value.effects.sessionId>0)
            val native=eq.presets.firstOrNull()
            if(native!=null){pref=pref.copy(preset=native.name);command{port.audioEffects(pref)};delay(200);record("native-preset");assertEquals(native.name,port.state.value.effects.equalizer.actualPreset)}
            val actual=port.state.value.effects.equalizer
            pref=pref.custom(actual.bands,actual.bands.first().frequencyMilliHz,(-100).coerceIn(actual.minLevel..actual.maxLevel))
            command{port.audioEffects(pref)};delay(200);record("custom-minus-one-db")
            assertNull(port.state.value.effects.equalizer.actualPreset);assertEquals(-100,port.state.value.effects.equalizer.bands.first().levelMilliBel)
            assertEquals(pref,app.settings.current().effects)
            val rival=android.media.audiofx.Equalizer(10,port.state.value.effects.sessionId)
            try {
                withTimeout(5000){port.state.first{it.effects.equalizer.phase==EffectPhase.CONTROL_LOST}}
                record("actual-control-lost")
                assertFalse(port.state.value.requested)
            }finally{rival.release()}
            withTimeout(5000){port.state.first{it.effects.equalizer.phase==EffectPhase.APPLIED}}
            assertEquals(-100,port.state.value.effects.equalizer.bands.first().levelMilliBel);record("actual-control-regained")
            assertEquals(before.queue,port.state.value.queue);assertEquals(before.queueVersion,port.state.value.queueVersion)
            assertEquals(before.positionMs,port.state.value.positionMs);assertEquals(1.5f,port.state.value.speed,0f);assertFalse(port.state.value.requested)
            command{port.audioEffects();port.flush()};record("explicit-recheck")
            assertEquals(EffectPhase.APPLIED,port.state.value.effects.equalizer.phase)
            // Same player advances to another real item without a second service or effect owner.
            val second=app.content.video("BV1Yt411u7UD");val p=second.parts.first();val next=entry.copy(id="m6e-next",bvid=second.bvid,cid=p.cid,part=p.number,title=second.title)
            command{port.replace(q.copy(entries=listOf(next),order=listOf(next.id),currentId=next.id),true)}
            withTimeout(30000){port.state.first{it.playing&&it.effects.equalizer.phase==EffectPhase.APPLIED}}
            command{port.pause();port.flush()};record("real-song-transition")
            assertEquals(-100,port.state.value.effects.equalizer.bands.first().levelMilliBel)
            // Stop unloads the AudioTrack. A subsequent explicit play builds another actual session.
            command{port.clear()};delay(200);command{port.replace(q,true)}
            withTimeout(30000){port.state.first{it.playing&&it.effects.equalizer.phase==EffectPhase.APPLIED}}
            command{port.pause();port.flush()};record("audio-track-rebuild")
            assertEquals(-100,port.state.value.effects.equalizer.bands.first().levelMilliBel)
            command{port.audioEffects(EffectsSettings())};record("reset-off")
            assertEquals(EffectPhase.OFF,port.state.value.effects.equalizer.phase);assertFalse(port.state.value.requested)
            assertTrue(app.stores.observe(fixture).first().isEmpty())
            File(out,"online.json").writeText(buildJsonObject{put("complete",true);put("device","Samsung SM-N9860 Android 13");put("observations",JsonArray(observations));put("nativePresetTested",native!=null);put("sameMedia3Service",true);put("settingsQueuePositionPreserved",true);put("physicalHeadsetTested",false);put("realSoundConfirmed",false);put("restoredOriginalSettingsAndQueue",true)}.toString())
        }finally {
            command{port.pause();port.clear()};app.settings.update(saved);app.accountKey=owner
            if(original!=null)command{port.replace(original.queue,false);port.flush()}
            command{port.close()};app.database.listenDao().clearPlayback(fixture);app.database.listenDao().clearHistory(fixture)
        }
    }
}
