package app.bililisten.platform

import app.bililisten.shared.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Import the old atomic document once, including its unresolved remote journal. */
class RoomOrganizerStore(private val dao:ListenDao,private val legacy:OrganizerStore):OrganizerStore {
    private val gate=Mutex()
    override suspend fun read(account:String):OrganizerData=gate.withLock {
        AccountRef(account)
        dao.organizer(account)?.let{Json.decodeFromString<OrganizerData>(it.payload)} ?: legacy.read(account).also {
            dao.organizer(OrganizerRow(account,Json.encodeToString(it)))
        }
    }
    override suspend fun write(account:String,value:OrganizerData)=gate.withLock{
        AccountRef(account);dao.organizer(OrganizerRow(account,Json.encodeToString(value)))
    }
}
