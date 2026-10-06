package app.bililisten.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessorChain
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Streaming PCM only. SoundTouch is dynamically linked; see third_party/soundtouch/UPSTREAM.md. */
internal object SoundTouchNative {
    init { System.loadLibrary("bilitempo") }
    external fun create(rate:Int,channels:Int,speed:Float,pitch:Float):Long
    external fun put(handle:Long,buffer:ByteBuffer,offset:Int,frames:Int)
    external fun receive(handle:Long,buffer:ByteBuffer):Int
    external fun finish(handle:Long)
    external fun available(handle:Long):Int
    external fun release(handle:Long)
}

@UnstableApi
internal class MusicTempoProcessor : AudioProcessor {
    private var format=AudioProcessor.AudioFormat.NOT_SET
    private var activeFormat=AudioProcessor.AudioFormat.NOT_SET
    private var speed=1f
    private var pitch=1f
    private var activeSpeed=1f
    private var handle=0L
    private var ended=false
    private var output=AudioProcessor.EMPTY_BUFFER
    private var batch=AudioProcessor.EMPTY_BUFFER

    // Media3 drains the previous speed's data before applying parameters and flushing.
    fun parameters(value:PlaybackParameters) { speed=value.speed.coerceIn(.1f,8f);pitch=value.pitch.coerceIn(.1f,8f) }
    fun mediaDuration(playoutUs:Long)=(playoutUs.toDouble()*activeSpeed).toLong()
    override fun getDurationAfterProcessorApplied(durationUs:Long)=(durationUs.toDouble()/speed).toLong()
    override fun configure(inputAudioFormat:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat {
        if(inputAudioFormat.encoding!=C.ENCODING_PCM_16BIT||inputAudioFormat.channelCount !in 1..8||inputAudioFormat.sampleRate !in 8000..192000)
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        format=inputAudioFormat
        return inputAudioFormat
    }
    override fun isActive()=format!=AudioProcessor.AudioFormat.NOT_SET&&(speed!=1f||pitch!=1f)
    override fun flush() {
        closeHandle();activeFormat=format;activeSpeed=speed;ended=false
        if(isActive())handle=SoundTouchNative.create(format.sampleRate,format.channelCount,speed,pitch)
        output=AudioProcessor.EMPTY_BUFFER
    }
    override fun queueInput(inputBuffer:ByteBuffer) {
        // Media3 polls output by queueing EMPTY_BUFFER, including after EOS while draining.
        if(!inputBuffer.hasRemaining())return
        check(!ended&&handle!=0L)
        require(inputBuffer.isDirect&&inputBuffer.remaining()%activeFormat.bytesPerFrame==0)
        val frames=minOf(inputBuffer.remaining()/activeFormat.bytesPerFrame,2048)
        if(frames==0)return
        SoundTouchNative.put(handle,inputBuffer,inputBuffer.position(),frames)
        inputBuffer.position(inputBuffer.position()+frames*activeFormat.bytesPerFrame)
    }
    override fun getOutput():ByteBuffer {
        if(handle==0L)return AudioProcessor.EMPTY_BUFFER
        val frames=SoundTouchNative.available(handle)
        if(frames==0)return AudioProcessor.EMPTY_BUFFER
        val size=Math.multiplyExact(frames,activeFormat.bytesPerFrame)
        if(output.capacity()<size)output=ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        else output.clear()
        // One Media3 output must include all currently ready frames, especially at slow speeds.
        // JNI uses a small reusable batch; never leave an accumulating native output backlog.
        val batchSize=2048*activeFormat.bytesPerFrame
        if(batch.capacity()<batchSize)batch=ByteBuffer.allocateDirect(batchSize).order(ByteOrder.nativeOrder())
        while(output.position()<size){
            batch.clear();val count=SoundTouchNative.receive(handle,batch);check(count>0)
            batch.limit(count);output.put(batch)
        }
        output.flip()
        return output
    }
    override fun queueEndOfStream() { if(!ended&&handle!=0L)SoundTouchNative.finish(handle);ended=true }
    override fun isEnded()=ended&&(handle==0L||SoundTouchNative.available(handle)==0)
    override fun reset() {
        closeHandle();format=AudioProcessor.AudioFormat.NOT_SET;activeFormat=format
        speed=1f;pitch=1f;activeSpeed=1f;ended=false;output=AudioProcessor.EMPTY_BUFFER;batch=AudioProcessor.EMPTY_BUFFER
    }
    private fun closeHandle(){if(handle!=0L){SoundTouchNative.release(handle);handle=0L}}
}

@UnstableApi
internal class MusicTempoChain : AudioProcessorChain {
    private val tempo=MusicTempoProcessor()
    override fun getAudioProcessors():Array<AudioProcessor> = arrayOf(tempo)
    override fun applyPlaybackParameters(playbackParameters:PlaybackParameters):PlaybackParameters {
        val supported=PlaybackParameters(playbackParameters.speed.coerceIn(.1f,8f),playbackParameters.pitch.coerceIn(.1f,8f))
        tempo.parameters(supported);return supported
    }
    // The product never skips silence. Do not add a second tempo processor or change elapsed time.
    override fun applySkipSilenceEnabled(skipSilenceEnabled:Boolean)=false
    override fun getMediaDuration(playoutDuration:Long)=tempo.mediaDuration(playoutDuration)
    override fun getSkippedOutputFrameCount()=0L
}
