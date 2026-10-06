package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SearchAssistTest {
    @Test fun officialPathsUseEncodedTermsWithoutAccountCredentialsOrRemoteNavigation()=runTest {
        val requests=mutableListOf<String>()
        val client=HttpClient(wbiEngine { req ->
            assertEquals("api.bilibili.com",req.url.host);assertEquals(URLProtocol.HTTPS,req.url.protocol)
            assertNull(req.headers[HttpHeaders.Cookie]);assertNull(req.headers[HttpHeaders.Authorization]);assertNull(req.url.parameters["userid"])
            requests+=req.url.encodedPath
            if(req.url.encodedPath.endsWith("suggest")) {
                assertEquals("周 深&live",req.url.parameters["term"])
                assertEquals("0",req.url.parameters["highlight"])
                respond("""{"code":0,"data":{"result":{"tag":[{"value":"周深","name":"<em>unsafe</em>","url":"https://foreign.example"}]}}}""")
            } else {
                assertEquals("10",req.url.parameters["limit"]);assertNotNull(req.url.parameters["w_rid"])
                respond("""{"code":0,"data":{"trending":{"list":[{"keyword":"音乐现场","show_name":"音乐现场","goto_value":"javascript:bad"}]}}}""")
            }
        }){followRedirects=false}
        try {
            val repo=BiliSearchAssistRepository(wbiApi(client){"secret"})
            assertEquals(listOf(SearchWord("周深")),repo.suggest("周 深&live"))
            assertEquals(listOf(SearchWord("音乐现场")),repo.hot())
            assertEquals(listOf("/x/web-interface/suggest","/x/web-interface/wbi/search/square"),requests)
        } finally {client.close()}
    }
    @Test fun wordsKeepSourceOrderDeduplicateStripMarkupAndBoundCounts()=runTest {
        val client=HttpClient(wbiEngine {respond("""{"code":0,"data":{"trending":{"list":[{"keyword":"<em>音乐</em>&amp;现场","show_name":"<b>音乐 &amp; 现场</b>"},{"keyword":"<em>音乐</em>&amp;现场"},{"keyword":"下一首"},{"keyword":""}]}}}""")})
        try{assertEquals(listOf(SearchWord("音乐&现场","音乐 & 现场"),SearchWord("下一首")),BiliSearchAssistRepository(wbiApi(client){null}).hot())}finally{client.close()}
        val many=HttpClient(wbiEngine{respond("""{"code":0,"data":{"trending":{"list":[${(1..20).joinToString{"{\"keyword\":\"word$it\"}"}}]}}}""")})
        try{assertEquals((1..10).map{SearchWord("word$it")},BiliSearchAssistRepository(wbiApi(many){null}).hot())}finally{many.close()}
    }
    @Test fun malformedBlockedAndOversizedResponsesNeverMasqueradeAsEmptyWords()=runTest {
        for((body,status) in listOf("{}" to HttpStatusCode.OK,"<html>challenge</html>" to HttpStatusCode.OK,
            """{"code":-352}""" to HttpStatusCode.OK,"blocked" to HttpStatusCode.PreconditionFailed,
            " ".repeat(512*1024+1) to HttpStatusCode.OK)) {
            val client=HttpClient(wbiEngine{respond(body,status)})
            try{assertFailsWith<PlatformFailure>{BiliSearchAssistRepository(wbiApi(client){null}).hot()}}finally{client.close()}
        }
        val empty=HttpClient(MockEngine{respond("""{"code":0,"data":{"result":{"tag":[]}}}""")})
        try{assertTrue(BiliSearchAssistRepository(wbiApi(empty){null}).suggest("暂无").isEmpty())}finally{empty.close()}
    }
    private fun clock(scope:TestScope)=object:Clock {override fun nowMs()=scope.currentTime}
    @Test fun rapidTypingDebouncesAndClearingCancelsBeforeRequest()=runTest {
        val queries=mutableListOf<String>()
        val repo=object:SearchAssistRepository {
            override suspend fun hot()=listOf(SearchWord("热门"))
            override suspend fun suggest(query:String):List<SearchWord>{queries+=query;return listOf(SearchWord(query+"现场"))}
        }
        val c=SearchAssistController(backgroundScope,repo,clock(this))
        c.show("周");runCurrent();advanceTimeBy(299);runCurrent();assertTrue(queries.isEmpty())
        c.show("周深");runCurrent();advanceTimeBy(300);runCurrent()
        assertEquals(listOf("周深"),queries);assertEquals("周深现场",c.state.value.suggestions.single().keyword)
        c.show("陈");runCurrent();c.show("");advanceTimeBy(1000);runCurrent()
        assertEquals(listOf("周深"),queries);assertTrue(c.state.value.suggestions.isEmpty());assertFalse(c.state.value.suggestionsBusy)
    }
    @Test fun lateUncancellableResponseCannotReplaceNewQueryOrAppearAfterLeaving()=runTest {
        val old=CompletableDeferred<List<SearchWord>>()
        val repo=object:SearchAssistRepository {
            override suspend fun hot()=emptyList<SearchWord>()
            override suspend fun suggest(query:String)=if(query=="旧")withContext(NonCancellable){old.await()} else listOf(SearchWord("新结果"))
        }
        val c=SearchAssistController(backgroundScope,repo,clock(this))
        c.show("旧");runCurrent();advanceTimeBy(300);runCurrent()
        c.show("新");runCurrent();advanceTimeBy(300);runCurrent();assertEquals("新结果",c.state.value.suggestions.single().keyword)
        old.complete(listOf(SearchWord("迟到旧结果")));runCurrent();assertEquals("新结果",c.state.value.suggestions.single().keyword)
        c.hide();runCurrent();assertTrue(c.state.value.suggestions.isEmpty());assertFalse(c.state.value.suggestionsBusy)
        c.show("新");runCurrent();assertEquals("新结果",c.state.value.suggestions.single().keyword)
    }
    @Test fun emptySuggestionResultsAreCachedAndHotErrorsDoNotRetryOnEveryKeystroke()=runTest {
        var hotCalls=0;var suggestCalls=0
        val repo=object:SearchAssistRepository {
            override suspend fun hot():List<SearchWord>{hotCalls++;if(hotCalls==1)throw PlatformFailure("网络请求失败");return listOf(SearchWord("恢复热搜"))}
            override suspend fun suggest(query:String):List<SearchWord>{suggestCalls++;return emptyList()}
        }
        val c=SearchAssistController(backgroundScope,repo,clock(this))
        c.show("无");runCurrent();advanceTimeBy(300);runCurrent();assertNotNull(c.state.value.hotError)
        c.show("无");runCurrent();advanceTimeBy(300);runCurrent();assertEquals(1,suggestCalls);assertEquals(1,hotCalls)
        c.show("其他");runCurrent();advanceTimeBy(300);runCurrent();assertEquals(1,hotCalls)
        c.retryHot();runCurrent();assertEquals(2,hotCalls);assertEquals("恢复热搜",c.state.value.hot.single().keyword)
        c.hide();c.show("");runCurrent();assertEquals(2,hotCalls)
        advanceTimeBy(600001);c.show("");runCurrent();assertEquals(3,hotCalls)
    }
    @Test fun closingDuringHotReadAllowsReopeningAndDiscardsOldHotResponse()=runTest {
        var calls=0;val pending=CompletableDeferred<List<SearchWord>>()
        val repo=object:SearchAssistRepository {
            override suspend fun hot()=if(++calls==1)withContext(NonCancellable){pending.await()} else listOf(SearchWord("当前热搜"))
            override suspend fun suggest(query:String)=emptyList<SearchWord>()
        }
        val c=SearchAssistController(backgroundScope,repo,clock(this))
        c.show("");runCurrent();c.hide();c.show("");runCurrent();assertEquals("当前热搜",c.state.value.hot.single().keyword)
        pending.complete(listOf(SearchWord("过期热搜")));runCurrent();assertEquals("当前热搜",c.state.value.hot.single().keyword)
    }
    @Test fun externallyCancelledReadDoesNotLeaveTheSpinnerRunning()=runTest {
        val repo=object:SearchAssistRepository {
            override suspend fun hot():List<SearchWord> = throw CancellationException("generation changed")
            override suspend fun suggest(query:String):List<SearchWord> = throw CancellationException("generation changed")
        }
        val c=SearchAssistController(backgroundScope,repo,clock(this))
        c.show("周深");runCurrent();advanceTimeBy(300);runCurrent()
        assertFalse(c.state.value.hotBusy);assertFalse(c.state.value.suggestionsBusy)
        assertTrue(c.state.value.suggestions.isEmpty());assertNull(c.state.value.suggestionsError)
    }
}
