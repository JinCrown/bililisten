package app.bililisten.shared

import io.ktor.http.encodeURLParameter

enum class HomeCategory(val label:String,val rid:Int?=null,val keyword:String?=null,val related:Set<String> = emptySet()) {
    ALL("推荐",0),MUSIC("音乐",3),STUDY("学习",36),EMOTION("情感",keyword="情感",related=setOf("情感","情感故事","情感电台")),
    AUDIOBOOK("有声书",keyword="有声书",related=setOf("有声书","有声小说","小说朗读")),GAME("游戏",4),LIFE("生活",160),LIVE("直播");
    val sourceLabel get()=when(this){ALL->"全站排行榜 · 前 5";STUDY->"知识排行榜 · 前 5";LIVE->"直播人气排行 · 前 5";EMOTION,AUDIOBOOK->"近 30 天相关投稿 · 播放热度前 5";else->"${label}排行榜 · 前 5"}
}
data class RecentRecommendationWindow(val nowMs:Long){
    init{require(nowMs>0)}
    val cutoffSeconds get()=(nowMs-30L*86400000)/1000
}

/** The public Bilibili web client's WBI query format; MD5 is supplied by the platform. */
object WbiQuery {
    private val positions=listOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52)
    fun sign(params:Map<String,String>,imageUrl:String,subUrl:String,nowMs:Long,md5:(ByteArray)->String):Map<String,String>{
        fun key(url:String)=url.substringAfterLast('/').substringBefore('.')
        val keys=key(imageUrl)+key(subUrl);require(keys.length==64&&keys.all{it in "0123456789abcdef"}){"平台搜索签名信息无效"}
        val mix=positions.map{keys[it]}.joinToString("").take(32)
        val clean=params.mapValues{(_,v)->v.filterNot{it in "!'()*"}}+mapOf("wts" to ((nowMs+500)/1000).toString())
        val query=clean.keys.sorted().joinToString("&"){it.encodeURLParameter()+"="+clean.getValue(it).encodeURLParameter()}
        return clean+mapOf("w_rid" to md5((query+mix).encodeToByteArray()))
    }
}
