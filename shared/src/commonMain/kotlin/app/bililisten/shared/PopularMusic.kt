package app.bililisten.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class PopularMusic(
    val bvid: String, val title: String, val cover: String, val author: String,
    val plays: Long, val parts: Int = 1, val collection: ContentSource? = null, val authorId:Long=0,val avatar:String="",
) {
    val key get() = collection?.ref?.let { "season-${it.owner}-${it.id}" } ?: bvid
    val typeLabel get() = collection?.let { "合集 · ${it.count} 个视频" }
        ?: if (parts > 1) "多分 P · 共 $parts P" else "单视频"
    val playLabel get() = if (plays >= 100000000) "${plays / 1000000 / 100.0} 亿播放"
        else "${plays / 1000 / 10.0} 万播放"
    companion object {
        const val MIN_PLAYS = 2000000L
        const val BATCH_SIZE = 12
        fun valid(rows: List<PopularMusic>,musicOnly:Boolean=true) = rows.filter {
            Bvid.parse(it.bvid) == it.bvid && it.title.isNotBlank() && it.plays >= (if(musicOnly)MIN_PLAYS else 0) && it.parts > 0 &&
                (it.collection == null || (it.collection.ref.kind == SourceKind.UP_COLLECTION &&
                    it.collection.ref.collectionKind == CollectionKind.SEASON && it.collection.ref.id > 0 &&
                    it.collection.ref.owner > 0 && it.collection.count > 0))
        }.distinctBy { it.key }
        fun select(rows: List<PopularMusic>, random: Random = Random.Default) = valid(rows).shuffled(random).take(BATCH_SIZE)
    }
}

/** Only verified display metadata is reused. Playback still resolves the actual source. */
class PopularMusicFeed(
    private val read: suspend (Int, Set<String>, suspend (List<PopularMusic>)->Unit)->List<PopularMusic>,
    private val now: ()->Long, private val generation: ()->Long, private val random: Random = Random.Default,
) {
    private val gate=Mutex()
    private var session:Long?=null
    private var savedAt=0L
    private var pool=emptyList<PopularMusic>()
    private var round=0
    suspend fun load(previous:Set<String>,ready:suspend (List<PopularMusic>)->Unit):List<PopularMusic> = gate.withLock {
        val stamp=generation();val time=now()
        if(session!=stamp){pool=emptyList();round=0;session=stamp}
        if(time-savedAt !in 0..6L*3600000)pool=emptyList()
        val available=pool.filter{it.key !in previous && it.bvid !in previous}.shuffled(random).take(PopularMusic.BATCH_SIZE)
        if(available.size>=PopularMusic.BATCH_SIZE)return@withLock available
        if(available.isNotEmpty())ready(available)
        var published=available
        val requestRound=round++
        val fresh=read(requestRound,previous+pool.map{it.key}+pool.map{it.bvid}) { rows->
                if(generation()!=stamp)throw CancellationException("Account changed")
                val additions=PopularMusic.valid(rows).filter{it.key !in previous && it.bvid !in previous && published.none{p->p.key==it.key}}
                published=(published+additions).take(PopularMusic.BATCH_SIZE)
                if(published.isNotEmpty())ready(published)
        }
        if(generation()!=stamp)throw CancellationException("Account changed")
        pool=PopularMusic.valid(fresh+pool).take(72);savedAt=time
        val additions=pool.filter{it.key !in previous && it.bvid !in previous && published.none{p->p.key==it.key}}.shuffled(random)
        (published+additions).take(PopularMusic.BATCH_SIZE)
    }
}
