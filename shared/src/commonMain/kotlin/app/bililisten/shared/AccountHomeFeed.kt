package app.bililisten.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AccountHomePage(val rows:List<PopularMusic>,val next:Long?)

/** Account-scoped display metadata only; the server owns recommendation order. */
class AccountHomeFeed(private val read:suspend (Long)->AccountHomePage,private val generation:()->Long) {
    private val gate=Mutex()
    private var session:Long?=null
    private var cursor=0L
    private var ended=false
    private var pool=emptyList<PopularMusic>()
    private fun identity() {
        val stamp=generation()
        if(session!=stamp){session=stamp;cursor=0;ended=false;pool=emptyList()}
    }
    suspend fun load(previous:Set<String>,ready:suspend (List<PopularMusic>)->Unit):List<PopularMusic> = gate.withLock {
        identity();val stamp=session
        var rows=pool.filter{it.bvid !in previous&&it.key !in previous}.take(PopularMusic.BATCH_SIZE)
        if(rows.isNotEmpty())ready(rows)
        var pages=0
        while(rows.size<PopularMusic.BATCH_SIZE&&!ended&&pages++<3) {
            val page=read(cursor)
            if(generation()!=stamp)throw CancellationException("Account changed")
            val fresh=PopularMusic.valid(page.rows,false)
            pool=(pool+fresh).distinctBy{it.bvid}.takeLast(72)
            rows=(rows+fresh.filter{it.bvid !in previous&&it.key !in previous}).distinctBy{it.bvid}.take(PopularMusic.BATCH_SIZE)
            ended=page.next==null||page.next==cursor
            page.next?.let{cursor=it}
            if(rows.isNotEmpty())ready(rows)
        }
        rows
    }
    suspend fun creators(previous:Set<Long>):List<UpProfile> {
        val excluded=gate.withLock{identity();pool.filter{it.authorId in previous}.map{it.bvid}.toSet()}
        load(excluded){}
        return gate.withLock {
            identity()
            pool.filter{it.authorId>0&&it.author.isNotBlank()&&it.authorId !in previous}
                .distinctBy{it.authorId}.take(10).map{UpProfile(it.authorId,it.author,it.avatar)}
        }
    }
}
