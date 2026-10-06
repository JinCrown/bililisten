package app.bililisten.playback

import app.bililisten.shared.ResumeSnapshot
import app.bililisten.shared.SnapshotCodec
import java.io.File
import java.util.UUID

/** Private metadata handoff: large playlists never travel as one Binder string. */
class QueueHandoff(private val directory:File) {
    private val namePattern=Regex("queue-[a-f0-9-]{36}\\.json")
    private val limit=16*1024*1024
    private fun file(name:String):File { require(namePattern.matches(name));return File(directory,name) }
    fun write(snapshot:ResumeSnapshot):String {
        val bytes=SnapshotCodec.encode(snapshot).toByteArray(Charsets.UTF_8)
        require(bytes.size<=limit){"队列元信息超过本机交接容量"}
        directory.mkdirs()
        val name="queue-${UUID.randomUUID()}.json"
        file(name).outputStream().use{it.write(bytes)}
        return name
    }
    fun read(name:String):ResumeSnapshot {
        val target=file(name);require(target.isFile&&target.length() in 1..limit.toLong())
        return SnapshotCodec.decode(target.readText(Charsets.UTF_8))
    }
    fun discard(name:String) { file(name).delete() }
}
