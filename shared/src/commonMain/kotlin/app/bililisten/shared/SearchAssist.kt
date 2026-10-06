package app.bililisten.shared

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class SearchWord(val keyword: String, val label: String = keyword)
data class SearchAssistView(val query: String = "", val suggestions: List<SearchWord> = emptyList(),
    val hot: List<SearchWord> = emptyList(), val suggestionsBusy: Boolean = false,
    val hotBusy: Boolean = false, val suggestionsError: String? = null, val hotError: String? = null)
interface SearchAssistRepository {
    suspend fun suggest(query: String): List<SearchWord>
    suspend fun hot(): List<SearchWord>
}

/** Official public search words only; no cookie, user ID or returned navigation URL is used. */
class BiliSearchAssistRepository(private val api:BiliApi) : SearchAssistRepository {
    private fun plain(value: String): String = value.replace(Regex("<[^>]*>"),"")
        .replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"")
        .trim().takeIf{it.isNotEmpty() && it.length<=100 && it.none{c->c.isISOControl()}}.orEmpty()
    private fun words(rows: JsonArray, keyword: String, label: String = keyword): List<SearchWord> {
        if(rows.size>200)throw PlatformFailure("搜索提示数量异常")
        return rows.mapNotNull { item ->
            val row=item as? JsonObject ?: throw PlatformFailure("平台响应格式变化")
            val word=plain(row[keyword]?.jsonPrimitive?.contentOrNull.orEmpty())
            if(word.isBlank())null else SearchWord(word,plain(row[label]?.jsonPrimitive?.contentOrNull.orEmpty()).ifBlank{word})
        }.distinctBy{it.keyword}.take(10)
    }
    override suspend fun suggest(query: String): List<SearchWord> {
        require(query.isNotBlank() && query.length<=100)
        val root=api.searchAssistRead("/x/web-interface/suggest",mapOf("term" to query,"highlight" to "0"))
        val tags=(root["result"] as? JsonObject)?.get("tag") as? JsonArray
            ?: throw PlatformFailure("平台响应格式变化")
        return words(tags,"value")
    }
    override suspend fun hot(): List<SearchWord> {
        val root=api.searchAssistRead("/x/web-interface/wbi/search/square",mapOf("limit" to "10","platform" to "web"))
        val rows=(root["trending"] as? JsonObject)?.get("list") as? JsonArray
            ?: throw PlatformFailure("平台响应格式变化")
        return words(rows,"keyword","show_name")
    }
}

/** Debounce typing, cancel obsolete requests, and never publish a late result for another query. */
class SearchAssistController(private val scope: CoroutineScope,private val repository: SearchAssistRepository,
    private val clock: Clock) {
    private val mutable=MutableStateFlow(SearchAssistView())
    val state=mutable.asStateFlow()
    private var visible=false
    private var revision=0L
    private var suggestionJob:Job?=null
    private var hotJob:Job?=null
    private var hotAttemptAt:Long?=null
    private var hotRevision=0L
    private val cachedSuggestions=linkedMapOf<String,List<SearchWord>>()
    fun show(query:String) {
        val wasVisible=visible
        visible=true
        if(hotJob?.isActive!=true && (hotAttemptAt==null || clock.nowMs()-hotAttemptAt!!>=600000))loadHot()
        val input=query.trim().take(100)
        if(wasVisible && input==state.value.query && (suggestionJob?.isActive==true || input in cachedSuggestions || state.value.suggestionsError!=null))return
        suggestionJob?.cancel();val ticket=++revision
        val cached=cachedSuggestions[input]
        mutable.value=mutable.value.copy(query=input,suggestions=cached.orEmpty(),suggestionsBusy=input.isNotEmpty() && cached==null,suggestionsError=null)
        if(input.isBlank() || cached!=null)return
        suggestionJob=scope.launch {
            try {
                delay(300)
                val words=repository.suggest(input);ensureActive()
                if(!visible || ticket!=revision)return@launch
                cachedSuggestions[input]=words
                if(cachedSuggestions.size>20)cachedSuggestions.remove(cachedSuggestions.keys.first())
                mutable.value=mutable.value.copy(suggestions=words,suggestionsBusy=false)
            } catch(e:CancellationException){throw e} catch(_:Exception) {
                if(visible && ticket==revision)mutable.value=mutable.value.copy(suggestionsBusy=false,suggestionsError="联想词暂时不可用，可直接搜索")
            } finally {if(visible && ticket==revision)mutable.value=mutable.value.copy(suggestionsBusy=false)}
        }
    }
    fun hide(){visible=false;revision++;hotRevision++;suggestionJob?.cancel();if(hotJob?.isActive==true)hotAttemptAt=null;hotJob?.cancel();hotJob=null;mutable.value=mutable.value.copy(suggestions=emptyList(),suggestionsBusy=false,hotBusy=false)}
    fun retryHot(){if(visible){hotJob?.cancel();hotJob=null;loadHot()}}
    private fun loadHot() {
        val ticket=++hotRevision
        hotAttemptAt=clock.nowMs()
        mutable.value=mutable.value.copy(hotBusy=true,hotError=null)
        hotJob=scope.launch {
            try {
                val words=repository.hot();ensureActive()
                if(visible && ticket==hotRevision)mutable.value=mutable.value.copy(hot=words,hotBusy=false)
            } catch(e:CancellationException){throw e} catch(_:Exception) {
                if(visible && ticket==hotRevision)mutable.value=mutable.value.copy(hotBusy=false,hotError="热搜暂时不可用")
            } finally {
                if(ticket==hotRevision){hotJob=null;if(visible)mutable.value=mutable.value.copy(hotBusy=false)}
            }
        }
    }
}
