package app.bililisten

import app.bililisten.shared.*
import kotlinx.serialization.Serializable

@Serializable data class HomePreview(val account:String, val savedAt:Long, val recommendations:List<Recommendation> = emptyList(), val metadata:Map<String,Video> = emptyMap(),val popularMusic:List<PopularMusic> = emptyList())
interface HomeStore {
    suspend fun read(account:String):HomePreview?
    suspend fun recommendations(account:String, rows:List<Recommendation>)
    suspend fun popularMusic(account:String,rows:List<PopularMusic>) {}
    suspend fun metadata(account:String, video:Video)
    suspend fun clearMetadata(account:String)
    suspend fun warm(urls:List<String>)
}
