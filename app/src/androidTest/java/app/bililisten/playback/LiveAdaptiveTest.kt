package app.bililisten.playback

import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

@UnstableApi @RunWith(AndroidJUnit4::class)
class LiveAdaptiveTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block:()->T):T {val result=AtomicReference<T>();val failure=AtomicReference<Throwable>();i.runOnMainSync{try{result.set(block())}catch(e:Throwable){failure.set(e)}};failure.get()?.let{throw it};return result.get()}
    private fun waitFor(check:()->Boolean){val until=System.currentTimeMillis()+15000;while(System.currentTimeMillis()<until){if(check())return;Thread.sleep(40)};error("Adaptive live condition timed out")}
    private fun silentAac():ByteArray {
        val codec=MediaCodec.createEncoderByType("audio/mp4a-latm")
        val output=ByteArrayOutputStream()
        try {
            codec.configure(MediaFormat.createAudioFormat("audio/mp4a-latm",16000,1).apply{setInteger(MediaFormat.KEY_AAC_PROFILE,2);setInteger(MediaFormat.KEY_BIT_RATE,32000)},null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start();val info=MediaCodec.BufferInfo();var inputs=0;var ended=false
            val until=System.currentTimeMillis()+12000
            while(!ended && System.currentTimeMillis()<until) {
                if(inputs<=64) {
                    val index=codec.dequeueInputBuffer(1000)
                    if(index>=0) {
                        val buffer=requireNotNull(codec.getInputBuffer(index));buffer.clear()
                        val size=if(inputs==64)0 else 2048
                        if(size>0)buffer.put(ByteArray(size))
                        codec.queueInputBuffer(index,0,size,inputs*1024L*1000000/16000,if(size==0)MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0);inputs++
                    }
                }
                val index=codec.dequeueOutputBuffer(info,1000)
                if(index>=0) {
                    if(info.size>0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG==0) {
                        val length=info.size+7
                        output.write(byteArrayOf(0xff.toByte(),0xf1.toByte(),0x60,0x40.toByte().let{(it.toInt() or (length shr 11)).toByte()},(length shr 3).toByte(),((length and 7) shl 5 or 0x1f).toByte(),0xfc.toByte()))
                        val buffer=requireNotNull(codec.getOutputBuffer(index));buffer.position(info.offset);buffer.limit(info.offset+info.size);val bytes=ByteArray(info.size);buffer.get(bytes);output.write(bytes)
                    }
                    ended=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0;codec.releaseOutputBuffer(index,false)
                }
            }
            check(ended && output.size()>0)
        }finally{runCatching{codec.stop()};codec.release()}
        return output.toByteArray()
    }
    @Test fun inMemorySilentHlsExposesARealSeekableLiveWindowAndEdgeWithoutDiskOrNetwork() {
        val bytes=silentAac()
        val manifest="#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:100\n"+(100..105).joinToString(""){"#EXTINF:4.0,\n$it.aac\n"}
        val factory=DataSource.Factory{object:DataSource {
            var source:ByteArrayDataSource?=null
            override fun addTransferListener(listener:TransferListener){}
            override fun open(spec:DataSpec):Long {val data=if(spec.uri.path.orEmpty().endsWith("m3u8"))manifest.toByteArray() else bytes;return ByteArrayDataSource(data).also{source=it}.open(spec)}
            override fun read(buffer:ByteArray,offset:Int,length:Int)=requireNotNull(source).read(buffer,offset,length)
            override fun getUri():Uri?=source?.uri
            override fun close(){source?.close();source=null}
        }}
        val player=main{ExoPlayer.Builder(i.targetContext).build().apply{volume=0f;setMediaSource(HlsMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri("https://fixture.invalid/live.m3u8")));prepare()}}
        try {
            waitFor{main{player.playbackState==Player.STATE_READY}}
            assertTrue(main{player.isCurrentMediaItemLive});assertTrue(main{player.isCurrentMediaItemSeekable});assertEquals(24000L,main{player.duration});assertFalse(main{player.playWhenReady})
            main{player.seekTo(1000)};waitFor{main{player.currentPosition==1000L}}
            main{player.seekToDefaultPosition()};waitFor{main{player.currentPosition>1000L}}
            assertTrue(main{player.currentPosition<=player.duration});assertEquals(1f,main{player.playbackParameters.speed},0f)
            main{player.stop()};assertFalse(main{player.isLoading})
        }finally{main{player.release()}}
    }
}
