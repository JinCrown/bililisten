package app.bililisten.storage

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.database.StandaloneDatabaseProvider
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class AudioCachePermit(val account:String,val session:SessionStamp,val generation:Long)

/** Constructed lazily on the first explicitly enabled, eligible ordinary audio stream. */
@UnstableApi class AutomaticAudioCache(private val app:ListenApplication) {
    private val directory=File(app.cacheDir,"audio-automatic-v1")
    @Volatile private var cache:SimpleCache?=null
    @Volatile private var generation=0L
    private val writeGate=Any()
    private val writers=mutableSetOf<GuardedAudioSink>()
    private val evictor=MutableLruEvictor(app.settings.settings.value.storage.cacheMiB.toLong()*1048576)
    @Synchronized private fun get():SimpleCache = cache ?: SimpleCache(directory,evictor,StandaloneDatabaseProvider(app)).also{cache=it}
    fun key(account:String,bvid:String,cid:Long,track:AudioTrack):String = MessageDigest.getInstance("SHA-256")
        .digest("$account/$bvid/$cid/${track.id}/${track.codec}".toByteArray()).joinToString(""){"%02x".format(it)}
    fun permit():AudioCachePermit = AudioCachePermit(app.accounts.session.value.stamp.account,app.accounts.session.value.stamp,generation)
    fun allowed(permit:AudioCachePermit):Boolean {
        val settings=app.settings.settings.value.storage
        return settings.automaticAudio && permit.generation==generation && permit.session==app.accounts.session.value.stamp &&
            OfflineAudioRules.networkAllowed(AudioFiles.network(app),settings.cacheOnMetered)
    }
    fun refreshLimit() {
        synchronized(writeGate){if(!app.settings.settings.value.storage.automaticAudio)writers.toList().forEach{it.close()}}
        cache?.let { synchronized(it){evictor.limit(it,app.settings.settings.value.storage.cacheMiB.toLong()*1048576)} }
    }
    fun wrap(upstream:DataSource.Factory):DataSource.Factory = DataSource.Factory {
        object:DataSource {
            private var delegate:DataSource?=null
            private val listeners=mutableListOf<TransferListener>()
            override fun addTransferListener(listener:TransferListener){listeners+=listener;delegate?.addTransferListener(listener)}
            override fun open(spec:DataSpec):Long {
                val permit=spec.customData as? AudioCachePermit
                val eligible=permit!=null && spec.key!=null && allowed(permit)
                val data=if(eligible) {
                    val current=get();refreshLimit()
                    CacheDataSource.Factory().setCache(current).setUpstreamDataSourceFactory(upstream)
                        .setCacheKeyFactory{requireNotNull(it.key)}.setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                        .setCacheWriteDataSinkFactory { GuardedAudioSink(CacheDataSink.Factory().setCache(current).setFragmentSize(512*1024).createDataSink(),{allowed(requireNotNull(permit))},writeGate,{writers+=it},{writers-=it}) }
                        .createDataSource()
                } else upstream.createDataSource()
                delegate=data;listeners.forEach(data::addTransferListener);return data.open(spec)
            }
            override fun read(buffer:ByteArray,offset:Int,length:Int)=requireNotNull(delegate).read(buffer,offset,length)
            override fun getUri()=delegate?.uri
            override fun getResponseHeaders()=delegate?.responseHeaders ?: emptyMap()
            override fun close(){delegate?.close();delegate=null}
        }
    }
    suspend fun bytes()=withContext(Dispatchers.IO){cache?.cacheSpace ?: directory.walkTopDown().filter{it.isFile}.sumOf{it.length()}}
    suspend fun clear()=withContext(Dispatchers.IO) {
        synchronized(writeGate) {
            generation++
            writers.toList().forEach{it.close()}
            val current=cache
            if(current!=null) synchronized(current){current.keys.toList().forEach(current::removeResource)}
            else if(directory.exists()) {directory.listFiles().orEmpty().forEach {check(!it.exists()||it.delete())}}
        }
    }
}

/** Closing the real sink commits only bytes already written; later bytes are never represented as cached. */
@UnstableApi internal class GuardedAudioSink(private val real:DataSink,private val allowed:()->Boolean,
    private val gate:Any=Any(),private val register:(GuardedAudioSink)->Unit={},private val release:(GuardedAudioSink)->Unit={}):DataSink {
    private var opened=false
    override fun open(spec:DataSpec)=synchronized(gate){if(allowed()){real.open(spec);opened=true;register(this)}}
    override fun write(buffer:ByteArray,offset:Int,length:Int)=synchronized(gate){if(opened&&!allowed())close();if(opened)real.write(buffer,offset,length)}
    override fun close()=synchronized(gate){if(opened){opened=false;try{real.close()}finally{release(this)}}}
}

@UnstableApi internal class MutableLruEvictor(private var maximum:Long):CacheEvictor {
    private val spans=java.util.TreeSet<CacheSpan>(compareBy<CacheSpan>{it.lastTouchTimestamp}.thenBy{it.key}.thenBy{it.position})
    private var bytes=0L
    override fun requiresCacheSpanTouches()=true
    override fun onCacheInitialized()=Unit
    fun limit(cache:Cache,maximum:Long){this.maximum=maximum;trim(cache,0)}
    override fun onStartFile(cache:Cache,key:String,position:Long,length:Long){trim(cache,if(length>=0)length else 0)}
    override fun onSpanAdded(cache:Cache,span:CacheSpan){spans+=span;bytes+=span.length;trim(cache,0)}
    override fun onSpanRemoved(cache:Cache,span:CacheSpan){if(spans.remove(span))bytes-=span.length}
    override fun onSpanTouched(cache:Cache,oldSpan:CacheSpan,newSpan:CacheSpan){onSpanRemoved(cache,oldSpan);spans+=newSpan;bytes+=newSpan.length}
    private fun trim(cache:Cache,incoming:Long){while(bytes+incoming>maximum&&spans.isNotEmpty())cache.removeSpan(spans.first())}
}
