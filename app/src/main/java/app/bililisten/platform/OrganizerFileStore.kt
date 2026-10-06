package app.bililisten.platform

import android.util.AtomicFile
import app.bililisten.shared.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/** One atomic document per identity. Corruption is surfaced instead of discarding pending writes. */
class OrganizerFileStore(private val directory: File) : OrganizerStore {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(account: String): AtomicFile { AccountRef(account); return AtomicFile(File(directory, "$account.json")) }
    override suspend fun read(account: String): OrganizerData = withContext(Dispatchers.IO) { lock.withLock {
        val atomic = file(account)
        if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) OrganizerData()
        else try { json.decodeFromString<OrganizerData>(atomic.readFully().decodeToString()) } catch (_: Exception) { throw PlatformFailure("整理记录无法读取；请保留数据，不要重复提交") }
    } }
    override suspend fun write(account: String, value: OrganizerData) = withContext(Dispatchers.IO) { lock.withLock {
        check(directory.isDirectory || directory.mkdirs())
        val atomic = file(account); val stream = atomic.startWrite()
        try { stream.write(json.encodeToString(value).encodeToByteArray()); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    } }
}
