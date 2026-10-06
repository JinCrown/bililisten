package app.bililisten.storage

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.playback.ControllerPlaybackPort
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in one ordinary audio download. Original queue/settings stay private and are restored by cleanup. */
@UnstableApi @RunWith(AndroidJUnit4::class) class M6FOnlineProbe {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
    private val json=Json{ignoreUnknownKeys=true;encodeDefaults=true}
    private val directory get()=File(app.filesDir,"m6f-evidence").apply{mkdirs()}
    private val preserve get()=File(app.noBackupFilesDir,"m6f-preserve.json")
    private fun enabled(){assumeTrue(InstrumentationRegistry.getArguments().getString("m6fonline")=="1")}
    private suspend fun command(block:suspend ()->Unit)=withContext(Dispatchers.Main){block()}
    @Test fun readScopeAndDownload()=runBlocking {
        enabled();assertEquals(NetworkKind.UNMETERED,AudioFiles.network(app))
        val owner=app.accounts.session.value.stamp.account;val settings=app.settings.current();val snapshot=app.stores.load(owner)
        check(!preserve.exists()){"Previous download test must be cleaned up first"}
        val beforeIds=app.downloads.records.value.map{it.id}.toSet()
        preserve.writeText(buildJsonObject{put("owner",owner);put("settings",json.encodeToJsonElement(settings));put("snapshot",snapshot?.let{json.encodeToJsonElement(it)} ?: JsonNull);put("beforeIds",JsonArray(beforeIds.map(::JsonPrimitive)))}.toString())
        app.settings.update(settings.copy(historyEnabled=false,externalOutputOnly=false,preferredOutputType=null,storage=StorageSettings()))
        val observations=mutableListOf<JsonObject>()
        var candidate:QueueEntry?=null
        for(bvid in listOf("BV1Yt411u7UD","BV1U1421r7SM")) {
            val video=app.content.video(bvid);val part=video.parts.first();val ref=VideoRef(bvid,part.cid,part.number)
            val probe=app.entitlements.probe(bvid,part.cid,false)
            val admitted=probe.tracks.any{OfflineAudioRules.admitted(video,ref,probe,it)}
            observations+=buildJsonObject{put("bvid",bvid);put("downloadFlag",video.downloadAllowed?.let(::JsonPrimitive) ?: JsonNull);put("paid",video.paidContent);put("fullDurationCandidate",probe.offlineFull);put("previewFlag",probe.preview?.let(::JsonPrimitive) ?: JsonNull);put("drmFlag",probe.drm?.let(::JsonPrimitive) ?: JsonNull);put("ordinaryAudioAdmitted",admitted);put("durationMs",probe.durationMs?.let(::JsonPrimitive) ?: JsonNull)}
            if(admitted&&candidate==null)candidate=QueueEntry("download-probe",bvid,part.cid,part.number,video.title)
            if(candidate!=null)break
        }
        File(directory,"scope.json").writeText(buildJsonObject{put("observations",JsonArray(observations));put("signedUrlsExported",false)}.toString())
        val entry=requireNotNull(candidate){"No eligible ordinary audio observed; do not bypass admission"}
        check(app.downloads.records.value.none{it.account==owner&&it.video==VideoRef(entry.bvid,entry.cid,entry.part)}){"Sample already belongs to user"}
        app.downloads.enqueue(listOf(entry))
        val row=withTimeout(120000){app.downloads.records.first{rows->rows.any{it.id !in beforeIds && it.phase in setOf(DownloadPhase.COMPLETE,DownloadPhase.FAILED,DownloadPhase.BLOCKED)}}}.first{it.id !in beforeIds}
        assertEquals(row.message,DownloadPhase.COMPLETE,row.phase);assertTrue(app.downloads.playable(row.id).isFile)
        File(directory,"download.json").writeText(buildJsonObject{put("complete",true);put("bytes",row.bytes);put("trackId",row.trackId);put("codec",row.codec);put("foregroundServiceUsed",true);put("audioOnly",true);put("syntheticExpiry",false);put("historyGeneratedByDownload",false)}.toString())
    }
    @Test fun offlineReadAndRealMedia3Playback()=runBlocking {
        enabled();assertEquals(NetworkKind.OFFLINE,AudioFiles.network(app));assertTrue(preserve.exists())
        val saved=json.parseToJsonElement(preserve.readText()).jsonObject
        val previous=saved["beforeIds"]!!.jsonArray.map{it.jsonPrimitive.content}.toSet()
        val row=app.downloads.records.value.single{it.id !in previous&&it.phase==DownloadPhase.COMPLETE}
        val file=app.downloads.playable(row.id)
        val source=StorageDataSource(app,DataSource.Factory{throw AssertionError("Offline must not create network source")})
        source.open(DataSpec.Builder().setUri(android.net.Uri.fromFile(file)).setCustomData(OfflineFilePermit(row.id)).build())
        var bytes=0L;val buf=ByteArray(65536)
        try{while(true){val n=source.read(buf,0,buf.size);if(n<0)break;bytes+=n}}finally{source.close()}
        assertEquals(row.bytes,bytes)
        val port=withContext(Dispatchers.Main){ControllerPlaybackPort(app)}
        try {
            withTimeout(15000){port.state.first{it.connected}}
            val entry=row.entry();command{port.replace(ResumeSnapshot(account=row.account,entries=listOf(entry),order=listOf(entry.id),currentId=entry.id,positionMs=0),true)}
            withTimeout(25000){port.state.first{it.playing}}
            delay(1500);command{port.pause();port.flush()};assertFalse(port.state.value.requested);assertTrue(port.state.value.positionMs>0)
            assertTrue(port.state.value.audio.decoded)
            File(directory,"offline.json").writeText(buildJsonObject{put("actualNetwork","OFFLINE");put("completeLocalReadBytes",bytes);put("networkDataSourceCreated",false);put("realMedia3Playing",true);put("decoded",true);put("positionAdvanced",true);put("accountSessionInitially",app.accounts.session.value.status.name);put("pausePreserved",true);put("heardConfirmed",false)}.toString())
        }finally{command{port.pause();port.flush();port.close()}}
    }
    @Test fun cleanup()=runBlocking {
        enabled();if(!preserve.exists())return@runBlocking
        val saved=json.parseToJsonElement(preserve.readText()).jsonObject
        val owner=saved["owner"]!!.jsonPrimitive.content;assertEquals(owner,app.accounts.session.value.stamp.account)
        val previous=saved["beforeIds"]!!.jsonArray.map{it.jsonPrimitive.content}.toSet()
        val port=withContext(Dispatchers.Main){ControllerPlaybackPort(app)}
        try {
            withTimeout(15000){port.state.first{it.connected}};command{port.pause();port.flush()}
            app.downloads.records.value.filter{it.id !in previous}.forEach{app.downloads.remove(it.id)}
            val snapshot=saved["snapshot"]?.takeUnless{it==JsonNull}?.let{json.decodeFromJsonElement<PlaybackSnapshot>(it)}
            app.accountKey=owner
            if(snapshot!=null)command{port.replace(snapshot.queue,false);port.flush()}else{command{port.forget(null)};app.database.listenDao().clearPlayback(owner)}
            app.settings.update(json.decodeFromJsonElement<UserSettings>(saved["settings"]!!))
            assertFalse(port.state.value.requested)
            File(directory,"cleanup.json").writeText(buildJsonObject{put("originalSettingsRestored",true);put("originalQueueRestoredPaused",true);put("testDownloadRemoved",true);put("credentialsChanged",false);put("remoteWrites",0);put("positionRestoredMs",snapshot?.queue?.positionMs?.let(::JsonPrimitive) ?: JsonNull)}.toString())
            preserve.delete()
        }finally{command{port.close()}}
    }
}
