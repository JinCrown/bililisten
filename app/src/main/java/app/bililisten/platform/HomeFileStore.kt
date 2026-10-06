package app.bililisten.platform

import android.util.AtomicFile
import app.bililisten.*
import app.bililisten.shared.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Display-only, account-scoped preview. It never supplies playback or authorization. */
class HomeFileStore(private val directory:File, private val covers:CoverStore, private val clock:Clock):HomeStore {
    private val gate=Mutex()
    private val json=Json { ignoreUnknownKeys=true }
    private fun file(account:String)=AtomicFile(File(directory,MessageDigest.getInstance("SHA-256").digest(account.toByteArray()).joinToString(""){"%02x".format(it)}+".json"))
    private fun readNow(account:String):HomePreview? = runCatching {
        val f=file(account)
        if(!f.baseFile.isFile || f.baseFile.length()>512*1024)return null
        json.decodeFromString<HomePreview>(f.openRead().use{it.readBytes().decodeToString()}).takeIf {
            it.account==account && it.savedAt<=clock.nowMs()+60000 && clock.nowMs()-it.savedAt<=7L*86400000
        }
    }.getOrNull()
    override suspend fun read(account:String)=withContext(Dispatchers.IO){gate.withLock{readNow(account)}}
    private suspend fun change(account:String,edit:(HomePreview)->HomePreview)=withContext(Dispatchers.IO){gate.withLock{
        directory.mkdirs()
        val value=edit(readNow(account) ?: HomePreview(account,clock.nowMs())).copy(savedAt=clock.nowMs())
        val bytes=json.encodeToString(value).toByteArray()
        if(bytes.size>512*1024)return@withLock
        val f=file(account);val output=f.startWrite()
        try { output.write(bytes);f.finishWrite(output) }
        catch(e:Exception){f.failWrite(output);throw e}
        // At most four account previews; cache eviction cannot affect the listening database.
        directory.listFiles().orEmpty().filter{it.extension=="json"}.sortedByDescending{it.lastModified()}.drop(4).forEach{it.delete()}
    }}
    override suspend fun recommendations(account:String,rows:List<Recommendation>){change(account){it.copy(recommendations=rows.take(20))}}
    override suspend fun popularMusic(account:String,rows:List<PopularMusic>){change(account){it.copy(popularMusic=PopularMusic.valid(rows).take(12))}}
    override suspend fun metadata(account:String,video:Video){change(account){it.copy(metadata=(it.metadata+(video.bvid to video.copy(parts=video.parts.take(20)))).entries.toList().takeLast(20).associate{entry->entry.toPair()})}}
    override suspend fun clearMetadata(account:String){change(account){it.copy(metadata=emptyMap())}}
    override suspend fun warm(urls:List<String>)=coroutineScope { urls.filter{it.isNotBlank()}.distinct().take(5).map { async { covers.load(it);Unit } }.awaitAll();Unit }
}
