package app.bililisten.playback

import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.shared.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi @RunWith(AndroidJUnit4::class)
class M2QueueTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block:()->T):T {val out=AtomicReference<T>();val err=AtomicReference<Throwable>();i.runOnMainSync{try{out.set(block())}catch(e:Throwable){err.set(e)}};err.get()?.let{throw it};return out.get()}
    private fun entry(id:String)=QueueEntry(id,"BV1xx411c7mD",1,2,"fixture $id")
    private fun snapshot()=ResumeSnapshot(account="fixture-m2",entries=listOf(entry("a"),entry("b"),entry("c")),order=listOf("a","b","c"),currentId="b",positionMs=12345)
    @Test fun nativeQueueEditsPreservePositionAndRejectLateMediaFromPriorGeneration() {
        val player=main{ExoPlayer.Builder(i.targetContext).build()};val queue=PlaybackQueue(player)
        try { main {
            queue.replace(snapshot(),false);val oldItem=player.currentMediaItem!!
            queue.edit(QueueEdit.Add(listOf(entry("d")),true),1)
            assertEquals(12345L,player.currentPosition);assertEquals("b",player.currentMediaItem!!.mediaId)
            queue.edit(QueueEdit.Move("d",0),2)
            assertEquals(listOf("d","a","b","c"),queue.snapshot()!!.order);assertEquals(12345L,player.currentPosition)
            try{queue.edit(QueueEdit.Remove("a"),1);fail("stale edit")}catch(_:IllegalArgumentException){}
            assertEquals(4,player.mediaItemCount)
            queue.edit(QueueEdit.Remove("b"),3);assertEquals("c",player.currentMediaItem!!.mediaId);assertEquals(0L,player.currentPosition)
            queue.replace(snapshot(),false);assertFalse(queue.accepts(oldItem))
            try{queue.edit(QueueEdit.Add(listOf(entry("late"))),1);fail("old queue generation")}catch(_:IllegalArgumentException){}
            assertTrue(queue.edit(QueueEdit.Clear,queue.snapshot()!!.queueVersion));assertNull(queue.snapshot());assertEquals(0,player.mediaItemCount)
        }} finally{main{player.release()}}
    }
    @Test fun everyModePreservesPositionAndShuffleDoesNotRestartOrReshuffle() {
        val player=main{ExoPlayer.Builder(i.targetContext).build()};val queue=PlaybackQueue(player)
        try{main{
            queue.replace(snapshot(),false)
            for(mode in PlayMode.entries){queue.changeMode(mode);assertEquals(12345L,player.currentPosition);assertEquals("b",player.currentMediaItem!!.mediaId);assertFalse(player.playWhenReady)}
            val saved=queue.snapshot()!!;queue.changeMode(PlayMode.SHUFFLE);assertEquals(saved,queue.snapshot())
            queue.replace(SnapshotCodec.decode(SnapshotCodec.encode(saved)),false);assertEquals(saved.order,queue.snapshot()!!.order);assertTrue(queue.snapshot()!!.queueVersion>saved.queueVersion)
            player.seekToNextMediaItem();assertEquals(saved.order[1],player.currentMediaItem!!.mediaId)
            player.seekToPreviousMediaItem();assertEquals(saved.order[0],player.currentMediaItem!!.mediaId)
        }}finally{main{player.release()}}
    }
    @Test fun realEndEventsAdvanceExactlyOnceAndNoHistoryIsCreatedByQueueClass() {
        val file=File(i.targetContext.cacheDir,"m2-silent-fixture.wav")
        val bytes=ByteBuffer.allocate(44+16000).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36+16000).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        bytes.putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(16000)
        file.writeBytes(bytes.array())
        val seen=mutableListOf<String>();val player=main{
            val factory=ResolvingDataSource.Factory(DefaultDataSource.Factory(i.targetContext)){spec->spec.withUri(android.net.Uri.fromFile(file))}
            ExoPlayer.Builder(i.targetContext).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply{
                volume=0f;addListener(object:Player.Listener{override fun onMediaItemTransition(item:MediaItem?,reason:Int){item?.mediaId?.let(seen::add)}})
            }
        }
        try {
            main{PlaybackQueue(player).replace(snapshot().copy(currentId="a",positionMs=0),true)}
            val end=System.currentTimeMillis()+10000
            while(System.currentTimeMillis()<end && main{player.playbackState!=Player.STATE_ENDED})Thread.sleep(30)
            main{assertNull(player.playerError);assertEquals(Player.STATE_ENDED,player.playbackState);assertEquals(listOf("a","b","c"),seen)}
        }finally{main{player.release()};file.delete()}
    }
    @Test fun unavailableSourceStopsWithoutSkippingWholeQueue() {
        val player=main{ExoPlayer.Builder(i.targetContext).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(i.targetContext)).setLoadErrorHandlingPolicy(BoundedMediaRetry{false})).build()}
        try{
            main{PlaybackQueue(player).replace(snapshot().copy(currentId="a",positionMs=0),true)}
            val end=System.currentTimeMillis()+10000
            while(System.currentTimeMillis()<end && main{player.playerError==null})Thread.sleep(30)
            main{assertNotNull(player.playerError);assertEquals("a",player.currentMediaItem!!.mediaId);assertEquals(3,player.mediaItemCount)}
            Thread.sleep(500);main{assertEquals("a",player.currentMediaItem!!.mediaId)}
        }finally{main{player.release()}}
    }
    @Test fun transientOpenRetriesOnceAndResolvesAgain() {
        var attempts=0;var resolutions=0
        val upstream=DataSource.Factory {object:DataSource {
            override fun addTransferListener(listener:TransferListener){}
            override fun open(spec:DataSpec):Long {attempts++;throw java.net.SocketTimeoutException("fixture")}
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int=-1
            override fun getUri():android.net.Uri?=null
            override fun close(){}
        }}
        val resolver=ResolvingDataSource.Factory(upstream){spec->resolutions++;spec}
        val player=main{ExoPlayer.Builder(i.targetContext).setMediaSourceFactory(DefaultMediaSourceFactory(resolver).setLoadErrorHandlingPolicy(BoundedMediaRetry{true})).build()}
        try{
            main{PlaybackQueue(player).replace(snapshot().copy(currentId="a",positionMs=0),true)}
            val end=System.currentTimeMillis()+10000
            while(System.currentTimeMillis()<end && main{player.playerError==null})Thread.sleep(30)
            main{assertNotNull(player.playerError);assertEquals("a",player.currentMediaItem!!.mediaId)}
            assertEquals(2,attempts);assertEquals(2,resolutions)
        }finally{main{player.release()}}
    }
}
