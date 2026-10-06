package app.bililisten.playback

import android.content.Context
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** A separate, muted local player, never ListenService or the user's account/queue. */
@UnstableApi @RunWith(AndroidJUnit4::class)
class MusicPlaybackTest {
    @Test fun realAudioSinkChangesTempoKeepsClockSeeksAndReturnsToOriginal()=runBlocking<Unit> {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val rate=48000;val frames=rate*30;val size=frames*4
        val wave=ByteBuffer.allocate(44+size).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(s:String){wave.put(s.toByteArray(Charsets.US_ASCII))}
        tag("RIFF");wave.putInt(36+size);tag("WAVE");tag("fmt ");wave.putInt(16);wave.putShort(1);wave.putShort(2)
        wave.putInt(rate);wave.putInt(rate*4);wave.putShort(4);wave.putShort(16);tag("data");wave.putInt(size)
        repeat(frames){val v=(sin(it.toDouble()/rate*440*2*PI)*3000).toInt().toShort();wave.putShort(v);wave.putShort(v)}
        val file=File(app.cacheDir,"tempo-own-fixture.wav").apply{writeBytes(wave.array())}
        val error=CompletableDeferred<PlaybackException>()
        val rates=mutableListOf<Pair<Float,Long>>()
        val player=withContext(Dispatchers.Main){
            val factory=object:DefaultRenderersFactory(app){
                override fun buildAudioSink(context:Context,enableFloatOutput:Boolean,enableAudioTrackPlaybackParams:Boolean):AudioSink =
                    DefaultAudioSink.Builder(context).setEnableFloatOutput(false).setEnableAudioTrackPlaybackParams(false).setAudioProcessorChain(MusicTempoChain()).build()
            }
            ExoPlayer.Builder(app,factory).build().apply{
                volume=0f;setAudioAttributes(AudioAttributes.DEFAULT,false)
                addListener(object:Player.Listener{override fun onPlayerError(e:PlaybackException){error.complete(e)}})
                setMediaItem(MediaItem.fromUri(file.toURI().toString()));prepare();play()
            }
        }
        try {
            withTimeout(10000){while(!withContext(Dispatchers.Main){player.isPlaying}){assertFalse(error.isCompleted);delay(50)}}
            for(speed in listOf(.5f,1.5f,.75f,2f,1f)){
                withContext(Dispatchers.Main){player.setPlaybackSpeed(speed)}
                delay(1300)
                val before=withContext(Dispatchers.Main){assertEquals(speed,player.playbackParameters.speed,0f);assertEquals(1f,player.playbackParameters.pitch,0f);player.currentPosition}
                delay(1000)
                val delta=withContext(Dispatchers.Main){player.currentPosition-before}
                if(error.isCompleted)throw error.await()
                assertEquals("actual position progression $speed",1000.0*speed,delta.toDouble(),150.0)
                rates+=speed to delta;assertFalse(error.isCompleted)
            }
            withContext(Dispatchers.Main){player.pause();player.seekTo(8000);player.setPlaybackSpeed(1.5f)}
            delay(300);assertEquals(8000L,withContext(Dispatchers.Main){player.currentPosition});assertFalse(withContext(Dispatchers.Main){player.playWhenReady})
            withContext(Dispatchers.Main){player.play()};delay(500);assertTrue(withContext(Dispatchers.Main){player.currentPosition}>8000)
            assertFalse(error.isCompleted)
            File(app.filesDir,"tempo-evidence/player.json").apply{parentFile!!.mkdirs();writeText("""{"muted":true,"localGeneratedAudio":true,"userQueueAccessed":false,"error":false,"pausedSeek":true,"rates":${rates.map{"{\"speed\":${it.first},\"positionDeltaMs\":${it.second}}"}.joinToString(prefix="[",postfix="]")}}""")}
        }finally{withContext(Dispatchers.Main){player.release()};file.delete()}
    }
}
