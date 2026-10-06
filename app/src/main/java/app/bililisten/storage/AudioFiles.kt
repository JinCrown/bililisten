package app.bililisten.storage

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import app.bililisten.ListenApplication
import app.bililisten.playback.isMediaHost
import app.bililisten.playback.DeviceAudioSupport
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Private audio files only. Download URLs remain in memory; completed files never acquire a synthetic expiry. */
class AudioFiles(val app: ListenApplication, private val directory: File = File(app.noBackupFilesDir,"audio-downloads-v1"),
    private val source: suspend (VideoRef) -> DownloadAudioSource = { ref ->
        val video = app.content.video(ref.bvid)
        val probe = app.entitlements.probe(ref.bvid,ref.cid,false)
        val track=probe.tracks.filter { OfflineAudioRules.admitted(video,ref,probe,it) && DeviceAudioSupport.supports(it) }
            .maxByOrNull { it.bandwidth } ?: throw PlatformFailure("该内容未确认允许下载完整普通音轨；付费、试听、高阶音轨和混流不下载")
        DownloadAudioSource(track,requireNotNull(probe.durationMs))
    }, private val networkKind: () -> NetworkKind = { network(app) },
    private val transport: suspend (String,Long,String?) -> AudioResponse = { url, offset, etag -> httpOpen(url,offset,etag) },
    private val identity: () -> SessionStamp = { app.accounts.session.value.stamp },
    private val validate:(File,Long)->Unit = LocalAudioVerifier::check,
) {
    private val json=Json { ignoreUnknownKeys=true;encodeDefaults=true }
    private val index=AtomicFile(File(directory,"index.json"))
    private var damagedIndex=false
    private val lock=Mutex();private val pump=Mutex()
    @Volatile private var active:Pair<String,Job>?=null
    private val mutable=MutableStateFlow(load())
    val records=mutable.asStateFlow()
    private val verified=java.util.concurrent.ConcurrentHashMap<String,Pair<Long,Long>>()
    private fun load():List<AudioDownload> = try {
        if(!index.baseFile.exists() && !File(directory,"index.json.bak").exists())emptyList() else {
            check(index.baseFile.length()<=2*1024*1024)
            val loaded=json.decodeFromString<List<AudioDownload>>(index.openRead().use{it.readBytes().decodeToString()})
            check(loaded.size<=500 && loaded.map{it.id}.distinct().size==loaded.size)
            loaded.map { it.checked().let { r -> if(r.phase in setOf(DownloadPhase.QUEUED,DownloadPhase.CHECKING,DownloadPhase.RUNNING))
                r.copy(phase=DownloadPhase.PAUSED,message="上次任务已停止，请手动继续") else r } }
        }
    } catch(_:Exception) { damagedIndex=true;emptyList() }
    private suspend fun edit(block:(List<AudioDownload>)->List<AudioDownload>)=withContext(Dispatchers.IO) {lock.withLock {
        check(!damagedIndex){"下载索引无法读取，原文件已保留"}
        val rows=block(mutable.value);require(rows.size<=500);rows.forEach{it.checked()};directory.mkdirs()
        val output=index.startWrite()
        try { output.write(json.encodeToString(rows).toByteArray());index.finishWrite(output);mutable.value=rows }
        catch(e:Exception){index.failWrite(output);throw e}
    }}
    private suspend fun update(id:String,block:(AudioDownload)->AudioDownload)=edit { rows->rows.map {if(it.id==id)block(it) else it} }
    private fun part(id:String)=file(id,"part")
    private fun final(id:String)=file(id,"m4a")
    private fun file(id:String,extension:String):File {require(Regex("[a-zA-Z0-9-]{1,80}").matches(id));return File(directory,"$id.$extension")}
    private fun owner()=identity().account
    private fun allowed(stamp:SessionStamp)=identity()==stamp && OfflineAudioRules.networkAllowed(networkKind(),app.settings.settings.value.storage.downloadOnMetered)
    suspend fun enqueue(entries:List<QueueEntry>, startService:Boolean=true) {
        require(entries.isNotEmpty() && entries.size<=500)
        val account=owner();AccountRef(account)
        edit { old ->
            val result=old.toMutableList()
            entries.distinctBy{it.bvid to it.cid}.forEach { e ->
                val ref=VideoRef(e.bvid,e.cid,e.part)
                if(result.none{it.account==account && it.video==ref}) result+=AudioDownload(java.util.UUID.randomUUID().toString(),account,ref,e.title.take(1024),createdAt=app.clock.nowMs())
            };result
        }
        if(startService)start()
    }
    fun start() { ContextCompat.startForegroundService(app,Intent(app,AudioDownloadService::class.java)) }
    suspend fun resume(id:String,startService:Boolean=true) {
        val row=records.value.firstOrNull{it.id==id&&it.account==owner()} ?: throw PlatformFailure("下载任务不属于当前账号")
        if(row.phase==DownloadPhase.COMPLETE)return
        if(active?.first==id)return
        update(id){it.copy(phase=DownloadPhase.QUEUED,message="")};if(startService)start()
    }
    suspend fun pause(id:String) {
        require(records.value.any{it.id==id&&it.account==owner()})
        update(id){if(it.phase!=DownloadPhase.COMPLETE)it.copy(phase=DownloadPhase.PAUSED) else it}
        active?.takeIf{it.first==id}?.second?.cancelAndJoin()
        update(id){if(it.phase!=DownloadPhase.COMPLETE)it.copy(phase=DownloadPhase.PAUSED,bytes=part(id).length(),message="请手动继续") else it}
    }
    suspend fun remove(id:String) {
        require(records.value.any{it.id==id&&it.account==owner()})
        update(id){if(it.phase!=DownloadPhase.COMPLETE)it.copy(phase=DownloadPhase.PAUSED) else it}
        active?.takeIf{it.first==id}?.second?.cancelAndJoin()
        edit {it.filterNot{r->r.id==id}}
        withContext(Dispatchers.IO) {check(!part(id).exists() || part(id).delete());check(!final(id).exists() || final(id).delete());verified.remove(id)}
    }
    suspend fun drain()=pump.withLock {supervisorScope {
        while(true) {
            val task=lock.withLock {
                val row=records.value.firstOrNull{it.account==owner()&&it.phase==DownloadPhase.QUEUED} ?: return@withLock null
                launch(start=CoroutineStart.LAZY) {transfer(row)}.also{active=row.id to it}
            } ?: break
            task.start();task.join();active=null
        }
    }}
    private suspend fun transfer(row:AudioDownload) {
        val stamp=identity()
        try {
            if(!allowed(stamp))throw TransferFailure("下载仅允许非计费网络；请联网或修改下载网络设置")
            update(row.id){it.copy(phase=DownloadPhase.CHECKING,message="")}
            val source=source(row.video);val track=source.track
            if(!allowed(stamp)||row.account!=stamp.account)throw TransferFailure("账号或网络已变化，请手动继续")
            val target=part(row.id);directory.mkdirs()
            var previous=row.etag
            if(row.trackId!=0 && (row.trackId!=track.id || row.codec!=track.codec)) {target.writeBytes(byteArrayOf());previous=null}
            update(row.id){it.copy(phase=DownloadPhase.RUNNING,trackId=track.id,codec=track.codec,bytes=target.length())}
            val disk=object:AudioTransferDisk {
                override fun size()=target.length()
                override fun reset(){target.writeBytes(byteArrayOf())}
                override fun append(buffer:ByteArray,count:Int){java.io.FileOutputStream(target,true).use{it.write(buffer,0,count)}}
                override fun freeBytes()=directory.usableSpace
            }
            var last=0L
            val result=AudioTransfer().copy(disk,previous,{offset,tag->transport(track.url,offset,tag)},{allowed(stamp)}) { p ->
                val now=android.os.SystemClock.elapsedRealtime()
                if(now-last>=400 || p.bytes==0L || p.bytes==p.total) {last=now;update(row.id){it.copy(bytes=p.bytes,total=p.total,etag=p.etag)}}
            }
            currentCoroutineContext().ensureActive()
            if(!allowed(stamp))throw TransferFailure("账号或网络已变化，请手动继续")
            runInterruptible(Dispatchers.IO){validate(target,source.durationMs)}
            val digest=sha(target)
            currentCoroutineContext().ensureActive()
            if(!allowed(stamp))throw TransferFailure("账号或网络已变化，请手动继续")
            // Finalize before publishing. An interrupted publication never makes an unchecked file playable.
            check(target.renameTo(final(row.id)))
            update(row.id){it.copy(phase=DownloadPhase.COMPLETE,bytes=result.bytes,total=result.total,etag=null,digest=digest,message="独立普通音轨 · 本机离线收听")}
        } catch(e:CancellationException) {
            withContext(NonCancellable){update(row.id){if(it.phase==DownloadPhase.COMPLETE)it else it.copy(phase=DownloadPhase.PAUSED,bytes=part(row.id).length(),message="已停止，请手动继续")}};throw e
        } catch(e:Exception) {
            update(row.id){it.copy(phase=if(e is PlatformFailure)DownloadPhase.BLOCKED else DownloadPhase.FAILED,bytes=part(row.id).length(),message=when(e){is PlatformFailure->e.category;is TransferFailure->e.explanation;else->"下载读取失败，未完成文件不可播放，请手动重试"}.take(512))}
        }
    }
    fun playable(id:String):File {
        val row=records.value.firstOrNull{it.id==id} ?: throw java.io.IOException("下载记录不存在")
        if(!OfflineAudioRules.usable(row,owner()))throw java.io.IOException("请选择当前账号已完成的下载")
        val file=final(id)
        if(!file.isFile||file.length()!=row.bytes)throw java.io.IOException("音频文件缺失或不完整，请重新下载")
        val signature=file.length() to file.lastModified()
        if(verified[id]!=signature) {if(sha(file)!=row.digest)throw java.io.IOException("音频文件校验失败，请重新下载");verified[id]=signature}
        return file
    }
    suspend fun bytes()=withContext(Dispatchers.IO){directory.listFiles().orEmpty().filter{it.extension in setOf("part","m4a")}.sumOf{it.length()}}
    companion object {
        private val http=OkHttpClient.Builder().cache(null).followRedirects(false).followSslRedirects(false)
            .connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
        fun network(app:ListenApplication):NetworkKind {
            val cm=app.getSystemService(ConnectivityManager::class.java)
            val cap=cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkKind.OFFLINE
            if(!cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))return NetworkKind.OFFLINE
            return if(cm.isActiveNetworkMetered)NetworkKind.METERED else NetworkKind.UNMETERED
        }
        private fun sha(file:File):String {val hash=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;hash.update(b,0,n)}};return hash.digest().joinToString(""){"%02x".format(it)}}
        private suspend fun httpOpen(url:String,offset:Long,etag:String?):AudioResponse {
            val request=Request.Builder().url(url).header("Referer","https://www.bilibili.com/").header("User-Agent","BiliListen/0.7.0").header("Accept-Encoding","identity")
            val u=request.build().url
            if(u.scheme!="https"||u.port!=443||u.username.isNotEmpty()||u.password.isNotEmpty()||!isMediaHost(u.host))throw TransferFailure("音频地址需要核对")
            if(offset>0){request.header("Range","bytes=$offset-");etag?.let{request.header("If-Range",it)}}
            val call=http.newCall(request.build())
            val response=suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation{call.cancel()}
                call.enqueue(object:Callback {
                    override fun onFailure(call:Call,e:java.io.IOException){if(continuation.isActive)continuation.resumeWithException(e)}
                    override fun onResponse(call:Call,response:Response){continuation.resume(response){_,value,_->value.close()}}
                })
            }
            val body=response.body ?: run{response.close();throw TransferFailure("音频响应为空")}
            val range=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
            val start=range?.groupValues?.get(1)?.toLongOrNull()
            val end=range?.groupValues?.get(2)?.toLongOrNull()
            val total=if(response.code==206)range?.groupValues?.get(3)?.toLongOrNull() else body.contentLength().takeIf{it>=0}
            if(response.code !in setOf(200,206,416) || response.code==416&&offset==0L || response.code==206 && (start==null||end==null||total==null||end!=total-1||end<start)) {
                response.close();throw TransferFailure("音频服务器拒绝请求或范围无效，请手动重试")
            }
            val input=body.byteStream()
            return object:AudioResponse {
                override val status=response.code;override val start=start;override val total=total;override val etag=response.header("ETag")?.takeIf{it.length<=512}
                override suspend fun read(buffer:ByteArray):Int = runInterruptible(Dispatchers.IO){input.read(buffer)}
                override fun close(){call.cancel();response.close()}
            }
        }
    }
}
