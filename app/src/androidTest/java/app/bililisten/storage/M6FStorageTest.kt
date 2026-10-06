package app.bililisten.storage

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.SimpleCache
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

@UnstableApi @RunWith(AndroidJUnit4::class) class M6FStorageTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
    private val entry=QueueEntry("fixture","BV1xx411c7mD",7,1,"private fixture")
    private val track=AudioTrack(30280,"https://fixture.bilivideo.com/audio","audio/mp4","mp4a.40.2",128000)
    private fun directory()=File(app.cacheDir,"m6f-fixture-${java.util.UUID.randomUUID()}").apply{mkdirs()}
    private class Body(val data:ByteArray,offset:Long=0):AudioResponse {
        var at=offset.toInt();var closed=false
        override val status=if(offset>0)206 else 200;override val start=offset;override val total=data.size.toLong();override val etag="\"fixture-v1\""
        override suspend fun read(buffer:ByteArray):Int {delay(5);if(at==data.size)return -1;val n=minOf(4096,data.size-at);data.copyInto(buffer,0,at,at+n);at+=n;return n}
        override fun close(){closed=true}
    }
    @Test fun tasksPauseResumeRestartDeleteAndNeverStoreUrls()=runBlocking {
        val dir=directory();val data=ByteArray(1024*1024){(it%128).toByte()};val opened=mutableListOf<Long>()
        val files=AudioFiles(app,dir,{DownloadAudioSource(track,1000)},{NetworkKind.UNMETERED},{_,offset,_->opened+=offset;Body(data,offset)},{SessionStamp("101",1)},{_,_->})
        try {
            files.enqueue(listOf(entry,entry),false);assertEquals(1,files.records.value.size)
            val id=files.records.value.single().id
            val job=launch(Dispatchers.IO){files.drain()}
            withTimeout(15000){files.records.first{it.single().bytes>0}}
            files.pause(id);job.join();assertEquals(DownloadPhase.PAUSED,files.records.value.single().phase)
            val partial=files.records.value.single().bytes;assertTrue(partial in 1 until data.size.toLong())
            val restart=AudioFiles(app,dir,{DownloadAudioSource(track,1000)},{NetworkKind.UNMETERED},{_,offset,_->opened+=offset;Body(data,offset)},{SessionStamp("101",99)},{_,_->})
            assertEquals(DownloadPhase.PAUSED,restart.records.value.single().phase)
            restart.resume(id,false);restart.drain();assertEquals(DownloadPhase.COMPLETE,restart.records.value.single().phase)
            assertTrue(opened.last()>0);assertArrayEquals(data,restart.playable(id).readBytes())
            assertFalse(File(dir,"index.json").readText().contains("https://"))
            val accountChanged=AudioFiles(app,dir,{DownloadAudioSource(track,1000)},{NetworkKind.UNMETERED},{_,offset,_->Body(data,offset)},{SessionStamp("202",1)},{_,_->})
            try{accountChanged.playable(id);fail("cross-account playback")}catch(_:java.io.IOException){}
            assertTrue(restart.playable(id).exists());restart.remove(id);assertTrue(restart.records.value.isEmpty());assertFalse(File(dir,"$id.m4a").exists())
        }finally{dir.deleteRecursively()}
    }
    @Test fun identityChangeStopsTransferWithoutDeletingOtherFiles()=runBlocking {
        val dir=directory();val data=ByteArray(1024*1024);var identity=SessionStamp("101",1)
        val files=AudioFiles(app,dir,{DownloadAudioSource(track,1000)},{NetworkKind.UNMETERED},{_,offset,_->Body(data,offset)},{identity},{_,_->})
        try {
            files.enqueue(listOf(entry),false);val job=launch(Dispatchers.IO){files.drain()}
            withTimeout(15000){files.records.first{it.single().bytes>0}}
            identity=SessionStamp("202",2);job.join()
            val row=files.records.value.single();assertEquals(DownloadPhase.FAILED,row.phase);assertTrue(File(dir,"${row.id}.part").exists());assertFalse(File(dir,"${row.id}.m4a").exists())
        }finally{dir.deleteRecursively()}
    }
    @Test fun closingOrClearingCacheStopsOldWritersAndPreservesManualFiles()=runBlocking {
        val saved=app.settings.current();val marker=File(app.noBackupFilesDir,"audio-downloads-v1/m6f-manual-marker-${java.util.UUID.randomUUID()}.m4a").apply{parentFile?.mkdirs();writeText("keep")}
        try {
            app.settings.update(saved.copy(storage=StorageSettings(automaticAudio=true)))
            val cache=app.automaticAudio;cache.clear()
            val spec=DataSpec.Builder().setUri("https://fixture.bilivideo.com/audio").setKey("m6f-fixture-key").setCustomData(cache.permit()).build()
            val upstream=DataSource.Factory{ByteArrayDataSource(ByteArray(2*1024*1024))}
            val source=cache.wrap(upstream).createDataSource();source.open(spec)
            val buf=ByteArray(65536);repeat(12){assertTrue(source.read(buf,0,buf.size)>0)}
            app.settings.update(saved.copy(storage=StorageSettings(automaticAudio=false)));cache.refreshLimit()
            val stopped=cache.bytes();assertTrue(stopped>0)
            repeat(4){assertTrue(source.read(buf,0,buf.size)>0)};assertEquals(stopped,cache.bytes())
            cache.clear();assertEquals(0L,cache.bytes());repeat(4){assertTrue(source.read(buf,0,buf.size)>0)};source.close();assertEquals(0L,cache.bytes());assertEquals("keep",marker.readText())
        }finally{app.settings.update(saved);app.automaticAudio.clear();marker.delete()}
    }
    @Test fun lruEvictsOldSpansAndShrinkingCapacityAppliesImmediately() {
        val dir=directory();val evictor=MutableLruEvictor(1024);val cache=SimpleCache(dir,evictor)
        fun add(key:String,size:Int){val hole=requireNotNull(cache.startReadWriteNonBlocking(key,0,size.toLong()));try{val f=cache.startFile(key,0,size.toLong());f.writeBytes(ByteArray(size));cache.commitFile(f,size.toLong())}finally{cache.releaseHoleSpan(hole)}}
        try{add("a",600);add("b",600);assertTrue(cache.getCachedSpans("a").isEmpty());assertEquals(600L,cache.cacheSpace);evictor.limit(cache,100);assertEquals(0L,cache.cacheSpace)}finally{cache.release();dir.deleteRecursively()}
    }
    @Test fun corruptIndexCannotOverwriteOriginalFiles()=runBlocking {
        val dir=directory();val index=File(dir,"index.json").apply{writeText("{broken")};val original=File(dir,"saved.m4a").apply{writeText("keep")}
        try{val files=AudioFiles(app,dir);try{files.enqueue(listOf(entry),false);fail("must retain damaged index")}catch(_:IllegalStateException){};assertEquals("{broken",index.readText());assertEquals("keep",original.readText())}finally{dir.deleteRecursively()}
    }
    @Test fun unexpectedNonAudioBytesCannotBecomeCompletedDownload()=runBlocking {
        val dir=directory()
        val files=AudioFiles(app,dir,{DownloadAudioSource(track,1000)},{NetworkKind.UNMETERED},{_,offset,_->Body("not audio".toByteArray(),offset)},{SessionStamp("101",1)})
        try{files.enqueue(listOf(entry),false);files.drain();assertEquals(DownloadPhase.FAILED,files.records.value.single().phase);assertFalse(File(dir,"${files.records.value.single().id}.m4a").exists())}finally{dir.deleteRecursively()}
    }
}
