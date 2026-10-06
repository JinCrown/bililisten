package app.bililisten.shared

internal expect fun readLyricsYaml(text:String):Map<*,*>

data class LyricWord(val fromMs:Long,val toMs:Long?,val text:String)
data class LyricsTiming(val cues:List<SubtitleCue>,val words:Map<Int,List<LyricWord>>,val plain:String)

/** Only source timestamps are used; missing word timestamps never receive evenly divided times. */
object LyricsfileParser {
    fun parse(text:String,durationMs:Long):LyricsTiming {
        require(text.length<=512000)
        val root=readLyricsYaml(text)
        require(root["version"]=="1.0")
        val metadata=root["metadata"] as? Map<*,*> ?: error("Missing lyric metadata")
        require(!metadata.containsKey("offset_ms") || metadata["offset_ms"]?.toString()?.toLongOrNull()==0L)
        fun time(value:Any?):Long?=value?.toString()?.toLongOrNull()?.also{require(it in 0..86400000)}
        val duration=time(metadata["duration_ms"]) ?: durationMs.coerceIn(0,86400000)
        val lines=root["lines"] as? List<*> ?: emptyList<Any>()
        require(lines.size<=20000)
        val parsed=lines.map { value->
            val line=value as? Map<*,*> ?: error("Invalid lyric line")
            val content=line["text"] as? String ?: error("Missing lyric text")
            val start=time(line["start_ms"]) ?: error("Missing lyric start")
            Triple(start,content,line)
        }.sortedBy{it.first}
        val cues=mutableListOf<SubtitleCue>()
        val words=mutableMapOf<Int,List<LyricWord>>()
        var wordCount=0
        for((index,line) in parsed.withIndex()) {
            val (start,content,raw)=line
            val end=time(raw["end_ms"]) ?: parsed.getOrNull(index+1)?.first ?: duration
            require(end>=start)
            if(content.isBlank() || end==start)continue
            val cueIndex=cues.size
            cues+=SubtitleCue(start,end,content)
            val entries=raw["words"] as? List<*> ?: continue
            wordCount+=entries.size
            require(wordCount<=60000)
            val timed=entries.map { value->
                val word=value as? Map<*,*> ?: error("Invalid lyric word")
                val at=time(word["start_ms"]) ?: error("Missing word time")
                val until=time(word["end_ms"])
                require(at>=start && at<end && (until==null || until in at..end))
                LyricWord(at,until,word["text"] as? String ?: error("Missing word text"))
            }
            require(timed.zipWithNext().all{(a,b)->a.fromMs<=b.fromMs})
            require(timed.joinToString(""){it.text}==content)
            if(timed.isNotEmpty())words[cueIndex]=timed
        }
        val plain=(root["plain"] as? String).orEmpty().ifBlank{cues.joinToString("\n"){it.content}}
        return LyricsTiming(cues,words,plain)
    }
}
