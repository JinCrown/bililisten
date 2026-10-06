package app.bililisten.shared

import kotlinx.serialization.Serializable

@Serializable data class TransferBookmark(val source:SourceRef,val title:String,val ownerName:String,val total:Int,val cover:String="")
@Serializable data class TransferHeard(val source:SourceRef,val videos:Set<String>)
/** Portable records only: never credentials, remote write journals, media URLs or files. */
@Serializable data class LocalTransfer(val format:String="bili-listen-local",val version:Int=2,val createdAt:Long,val account:String,
    val history:List<LocalHistoryEntry> = emptyList(),val liveHistory:List<LiveHistoryEntry> = emptyList(),
    val bookmarks:List<TransferBookmark> = emptyList(),val heard:List<TransferHeard> = emptyList(),
    val aliases:Map<String,String> = emptyMap(),val orders:Map<Long,List<String>> = emptyMap(),val library:LibraryPreferences=LibraryPreferences()) {
    fun checked():LocalTransfer {
        require(format=="bili-listen-local"&&version in 1..2){"迁移文件版本不受支持"};AccountRef(account)
        library.checked(account);require(library.layouts.all{it.updatedAt<=createdAt});require(version==2||library.layouts.isEmpty())
        require(createdAt>0&&history.size+liveHistory.size<=50000&&bookmarks.size<=10000&&heard.size<=10000&&aliases.size<=10000&&orders.size<=10000)
        fun source(s:SourceRef){s.checked();require(s.kind!=SourceKind.OWN_FAVORITES || s.owner.toString()==account)}
        require(history.distinctBy{it.video.bvid to it.video.cid}.size==history.size)
        require(liveHistory.distinctBy{it.roomId}.size==liveHistory.size)
        history.forEach{require(it.account==account&&it.positionMs>=0&&it.playedAt in 1..createdAt&&it.title.length<=2000);it.source?.let(::source)}
        liveHistory.forEach{require(it.account==account&&it.roomId>0&&it.playedAt in 1..createdAt&&it.title.length<=2000)}
        require(bookmarks.distinctBy{it.source}.size==bookmarks.size&&heard.distinctBy{it.source}.size==heard.size)
        bookmarks.forEach{source(it.source);require(it.source.kind!=SourceKind.OWN_FAVORITES&&it.title.length<=2000&&it.ownerName.length<=300&&it.total>=0&&it.cover.length<=2048)}
        require(heard.sumOf{it.videos.size}<=100000)
        heard.forEach{source(it.source);require(it.videos.all{b->Bvid.parse(b)==b})}
        require(aliases.all{(b,t)->Bvid.parse(b)==b&&t.isNotBlank()&&t.length<=80})
        require(orders.values.sumOf{it.size}<=100000)
        orders.forEach{(folder,ids)->require(folder>0&&ids.size<=10000&&ids.distinct().size==ids.size&&ids.all{Bvid.parse(it)==it})}
        return this
    }
}
