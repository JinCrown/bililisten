package app.bililisten.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** This cache owns only cover files; clearing it cannot touch credentials, Room or downloads. */
class CoverStore internal constructor(private val directory: File, private val http: OkHttpClient) {
    constructor(context: Context):this(File(context.cacheDir,"covers-v1"),OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).cache(null)
        .connectTimeout(8,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).build())
    private val lock = Mutex()
    private val stripes = Array(16) { Mutex() }
    private val downloads = Semaphore(3)
    private var generation = 0L
    private val memory = object : LruCache<String,Bitmap>(12*1024*1024) { override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount }
    companion object {
        fun safeUrl(raw:String):String? = runCatching {
            val value=if(raw.startsWith("//"))"https:$raw" else raw.replaceFirst("http://","https://")
            val u=URI(value);val host=u.host?.lowercase() ?: return null
            val imageHost=listOf("hdslb.com","biliimg.com").any{host==it||host.endsWith(".$it")}
            if(u.scheme!="https" || u.userInfo!=null || u.port !in listOf(-1,443) || !imageHost) return null
            value
        }.getOrNull()
    }
    suspend fun load(raw:String):Bitmap? = withContext(Dispatchers.IO) {
        val url=safeUrl(raw) ?: return@withContext null
        memory.get(url)?.let{return@withContext it}
        // Coalesce identical URLs without making unrelated covers wait for their network I/O.
        stripes[(url.hashCode() and Int.MAX_VALUE)%stripes.size].withLock {
            memory.get(url)?.let{return@withLock it}
            try {
                val key=MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString(""){"%02x".format(it)}
                val file=File(directory,key)
                val cached=lock.withLock { generation to if(file.isFile && file.length()<=4*1024*1024)file.readBytes() else null }
                val bytes=cached.second ?: downloads.withPermit {
                    http.newCall(Request.Builder().url(url).header("Referer","https://www.bilibili.com/").build()).execute().use { response ->
                        check(response.isSuccessful);val body=checkNotNull(response.body);check(body.contentLength()<=4*1024*1024)
                        body.byteStream().use{input->
                            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                            while(true){val n=input.read(buffer);if(n<0)break;check(out.size()+n<=4*1024*1024);out.write(buffer,0,n)}
                            out.toByteArray()
                        }
                    }
                }
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                val sample=CoverDecodePolicy.sampleSize(bounds.outWidth,bounds.outHeight)
                val bitmap=sample?.let { BitmapFactory.decodeByteArray(bytes,0,bytes.size,
                    BitmapFactory.Options().apply{inSampleSize=it}) }
                lock.withLock publish@{
                    // A clear during an in-flight download must stay cleared.
                    if(cached.first!=generation)return@publish null
                    if(bitmap==null){file.delete();return@publish null}
                    directory.mkdirs()
                    if(cached.second==null){file.writeBytes(bytes);trimDisk()}
                    memory.put(url,bitmap)
                    bitmap
                }
            } catch(e:CancellationException){throw e} catch(_:Exception){null}
        }
    }
    private fun trimDisk() {
        val files=directory.listFiles().orEmpty().filter{it.isFile}.map{Triple(it,it.length(),it.lastModified())}
        var total=files.sumOf{it.second}
        if(total<=32L*1024*1024)return
        files.sortedBy{it.third}.forEach{if(total>32L*1024*1024 && it.first.delete())total-=it.second}
    }
    suspend fun bytes()=withContext(Dispatchers.IO){lock.withLock{directory.listFiles().orEmpty().sumOf{it.length()}}}
    suspend fun clear()=withContext(Dispatchers.IO){lock.withLock{generation++;memory.evictAll();directory.listFiles().orEmpty().forEach{check(it.delete())};Unit}}
}
