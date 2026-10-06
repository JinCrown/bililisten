package app.bililisten.platform

import app.bililisten.shared.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class RoomRemoteHistoryStore(private val dao:ListenDao):RemoteHistoryStore {
    private val json=Json{ignoreUnknownKeys=true}
    override fun observe(account:String)=dao.remoteHistoryFlow(account).map{it?.let{json.decodeFromString<RemoteHistoryData>(it.payload).checked()} ?: RemoteHistoryData()}
    override suspend fun read(account:String)=dao.remoteHistory(account)?.let{json.decodeFromString<RemoteHistoryData>(it.payload).checked()} ?: RemoteHistoryData()
    override suspend fun changeMode(account:String,data:RemoteHistoryData,cutoff:Long) {
        require(account.toLongOrNull()?.let{it>0}==true)
        dao.changeHistoryMode(RemoteHistoryRow(account,data.enabled,data.epoch,json.encodeToString(data.checked())),cutoff)
    }
    override suspend fun save(account:String,data:RemoteHistoryData,expectedEpoch:Long) {
        dao.saveRemoteHistory(RemoteHistoryRow(account,data.enabled,data.epoch,json.encodeToString(data.checked())),expectedEpoch)
    }
}
