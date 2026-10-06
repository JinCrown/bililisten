package app.bililisten.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Pure generated audio: no account, service, history, network, speaker or user queue access. */
@UnstableApi @RunWith(AndroidJUnit4::class)
class MusicTempoTest {
    private val dir=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"tempo-evidence").apply{mkdirs()}
    private fun input(rate:Int,seconds:Int,channels:Int,inverted:Boolean=false):ShortArray = ShortArray(rate*seconds*channels){ n ->
        val t=(n/channels).toDouble()/rate
        val a=(sin(2*PI*173*t)*5000+sin(2*PI*433*t)*4500+sin(2*PI*857*t)*4000).roundToInt()
        (if(inverted&&n%channels==1)-a else a).toShort()
    }
    private fun render(p:AudioProcessor,samples:ShortArray,rate:Int,channels:Int,chunk:Int=1024):ShortArray {
        p.configure(AudioProcessor.AudioFormat(rate,channels,C.ENCODING_PCM_16BIT));p.flush()
        val out=ByteArrayOutputStream()
        fun drain(){val b=p.output;val bytes=ByteArray(b.remaining());b.get(bytes);out.write(bytes);assertFalse("all ready frames drained in one Media3 output",p.output.hasRemaining())}
        var position=0
        while(position<samples.size){
            val size=minOf(chunk*channels,samples.size-position)
            val b=ByteBuffer.allocateDirect(size*2).order(ByteOrder.nativeOrder())
            repeat(size){b.putShort(samples[position+it])};b.flip()
            while(b.hasRemaining()){val at=b.position();p.queueInput(b);drain();assertTrue(b.position()>at)}
            position+=size
        }
        p.queueEndOfStream();p.queueInput(AudioProcessor.EMPTY_BUFFER);drain();assertTrue(p.isEnded)
        val bytes=out.toByteArray();val short=ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer()
        return ShortArray(short.remaining()).also{short.get(it)}
    }
    private fun save(name:String,data:ShortArray){val b=ByteBuffer.allocate(data.size*2).order(ByteOrder.LITTLE_ENDIAN);data.forEach(b::putShort);File(dir,"$name.pcm").writeBytes(b.array())}
    // Independent signal-quality check. Parseval + Goertzel bins around the known input tones,
    // rather than asserting this implementation's chosen overlap offsets or parameter values.
    private fun toneEnergy(data:ShortArray,rate:Int,channels:Int):Double {
        val n=8192;val bins=listOf(173,433,857).flatMap{hz->val center=(hz.toDouble()*n/rate).roundToInt();(center-2..center+2).toList()}
        var energy=0.0;var tones=0.0
        for(start in rate/4 until data.size/channels-rate/4-n step n){
            val x=DoubleArray(n){i->data[(start+i)*channels].toDouble()*(.5-.5*cos(2*PI*i/(n-1)))}
            energy+=x.sumOf{it*it}*n/2
            for(bin in bins){
                val c=2*cos(2*PI*bin/n);var previous=0.0;var older=0.0
                for(sample in x){val now=sample+c*previous-older;older=previous;previous=now}
                tones+=previous*previous+older*older-c*previous*older
            }
        }
        return tones/energy
    }
    @Test fun stereoMusicFastAndSlowCompareWithPreviousSonic() {
        val rate=48000;val audio=input(rate,6,2,true);save("input",audio)
        for(speed in listOf(.5f,.75f,1.25f,1.5f,1.75f,2f)){
            val p=MusicTempoProcessor();p.parameters(PlaybackParameters(speed,1f))
            try {
                val result=render(p,audio,rate,2)
                assertEquals("duration $speed",(audio.size/2/speed).toDouble(),result.size/2.0,2.0)
                assertTrue(result.indices.step(2).all{abs(result[it].toInt()+result[it+1].toInt())<=1})
                assertTrue(result.any{abs(it.toInt())>1000});assertTrue(result.all{abs(it.toInt())<16000})
                save("soundtouch-$speed",result)
                val sonic=SonicAudioProcessor();sonic.setSpeed(speed)
                try{
                    val old=render(sonic,audio,rate,2);save("sonic-$speed",old)
                    assertTrue("pitch/sideband quality $speed",toneEnergy(result,rate,2)>.95)
                    assertTrue("regression over prior stereo cancellation $speed",toneEnergy(result,rate,2)>toneEnergy(old,rate,2)+.1)
                }finally{sonic.reset()}
            }finally{p.reset()}
        }
    }
    @Test fun seekFlushRateChangesEosShortClipsAndChunkingStayIsolated() {
        val p=MusicTempoProcessor()
        try {
            for(rate in listOf(44100,48000,96000))for(channels in listOf(1,2,6))for(speed in listOf(.5f,.75f,1.5f,2f)){
                p.parameters(PlaybackParameters(speed,1f))
                val audio=input(rate,1,channels)
                val first=render(p,audio,rate,channels,257)
                val second=render(p,audio,rate,channels,2048)
                assertArrayEquals("buffer boundaries $rate $channels $speed",first,second)
                assertEquals((rate/speed).toDouble(),first.size.toDouble()/channels,2.0)
                assertEquals((1_000_000L*speed).toLong(),p.mediaDuration(1_000_000L))
                p.configure(AudioProcessor.AudioFormat(rate,channels,C.ENCODING_PCM_16BIT));p.flush();p.queueEndOfStream()
                assertFalse(p.output.hasRemaining());assertTrue(p.isEnded)
                val short=render(p,audio.copyOf(channels*137),rate,channels)
                assertEquals((137/speed).toDouble(),short.size.toDouble()/channels,2.0)
            }
            p.parameters(PlaybackParameters.DEFAULT);p.configure(AudioProcessor.AudioFormat(48000,2,C.ENCODING_PCM_16BIT));p.flush()
            assertFalse(p.isActive);assertFalse(p.output.hasRemaining())
            p.reset();assertFalse(p.isActive)
        }finally{p.reset()}
    }
}
