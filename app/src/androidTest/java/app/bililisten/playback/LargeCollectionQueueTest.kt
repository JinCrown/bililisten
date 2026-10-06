package app.bililisten.playback

import android.os.Bundle
import android.os.Parcel
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.shared.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@UnstableApi @RunWith(AndroidJUnit4::class)
class LargeCollectionQueueTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private fun <T> main(block:()->T):T {
        val value=AtomicReference<T>();val failure=AtomicReference<Throwable>()
        i.runOnMainSync{try{value.set(block())}catch(e:Throwable){failure.set(e)}}
        failure.get()?.let{throw it};return value.get()
    }
    @Test fun twoHundredMetadataEntriesRoundTripAndEditWithoutOpeningAnyAudio() {
        val source=SourceRef(SourceKind.OWN_FAVORITES,9,7)
        val entries=List(200){QueueEntry("song-$it","BV"+(it+1).toString().padStart(10,'0'),it+1L,1,"示例歌曲 $it",source=source)}
        val snapshot=ResumeSnapshot(account="7",entries=entries,order=entries.map{it.id},currentId=entries.first().id,positionMs=0)
        val parcel=Parcel.obtain()
        val restored:ResumeSnapshot
        val bytes:Int
        try {
            val args=Bundle().apply{putString("snapshot",SnapshotCodec.encode(snapshot));putBoolean("play",false)}
            parcel.writeBundle(args);bytes=parcel.dataSize();assertTrue(bytes<512*1024)
            parcel.setDataPosition(0)
            restored=SnapshotCodec.decode(parcel.readBundle(javaClass.classLoader)!!.getString("snapshot")!!)
            assertEquals(snapshot,restored)
        }finally{parcel.recycle()}
        val opened=AtomicInteger()
        val factory=DataSource.Factory {opened.incrementAndGet();DefaultDataSource.Factory(i.targetContext).createDataSource()}
        val player=main{ExoPlayer.Builder(i.targetContext).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()}
        val queue=PlaybackQueue(player)
        try {
            main {
                queue.replace(restored,false)
                assertEquals(200,player.mediaItemCount);assertFalse(player.playWhenReady)
                assertEquals(Player.STATE_IDLE,player.playbackState)
                assertEquals(entries.map{it.id},queue.snapshot()!!.order)
                queue.edit(QueueEdit.Move(entries.last().id,0),queue.snapshot()!!.queueVersion)
                assertEquals(entries.last().id,queue.snapshot()!!.order.first())
                assertEquals(entries.first().id,player.currentMediaItem!!.mediaId)
                queue.edit(QueueEdit.Remove(entries[100].id),queue.snapshot()!!.queueVersion)
                assertEquals(199,player.mediaItemCount)
                assertFalse(queue.snapshot()!!.entries.any{it.id==entries[100].id})
            }
            Thread.sleep(200);assertEquals(0,opened.get())
            val folder=File(i.targetContext.filesDir,"large-queue-evidence").apply{mkdirs()}
            File(folder,"native-summary.json").writeText("""{"entries":200,"parcelBytes":$bytes,"metadataRoundTrip":true,"nativeReorderAndRemove":true,"audioDataSourcesCreated":0,"userPlaybackTouched":false}""")
        }finally{main{player.release()}}
    }
}
