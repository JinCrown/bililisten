package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class M0CapabilitiesTest {
    private val searchKeys="""{"code":-101,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png"}}}"""
    private fun searchApi(client:HttpClient)=BiliApi(client,searchClock=object:Clock{override fun nowMs()=100000L},searchMd5={"a".repeat(32)}){null}
    @Test fun searchUsesRealEndpointPaginationAndFiltersInvalidIdentity() = runTest {
        val client = HttpClient(MockEngine { request ->
            if(request.url.encodedPath.endsWith("/nav"))return@MockEngine respond(searchKeys)
            assertEquals("/x/web-interface/wbi/search/type", request.url.encodedPath)
            assertEquals("a".repeat(32),request.url.parameters["w_rid"])
            assertEquals("video", request.url.parameters["search_type"])
            assertEquals("科普", request.url.parameters["keyword"])
            assertEquals("2", request.url.parameters["page"])
            respond("""{"code":0,"data":{"page":2,"numPages":3,"numResults":42,"result":[{"bvid":"BV1xx411c7mD","title":"<em class=\"keyword\">科普</em>&amp;知识"},{"bvid":"","title":"推广"}]}}""")
        })
        try { val result = searchApi(client).search("科普", 2)
            assertEquals("科普&知识", result.items.single().title); assertTrue(result.hasMore); assertEquals(42, result.total)
        } finally { client.close() }
    }
    @Test fun emptySearchAndRateLimitRemainDistinct() = runTest {
        val client = HttpClient(MockEngine { request ->
            if(request.url.encodedPath.endsWith("/nav"))return@MockEngine respond(searchKeys)
            if (request.url.parameters["keyword"] == "empty") respond("""{"code":0,"data":{"page":1,"numPages":0,"numResults":0,"result":[]}}""")
            else respond("limited", HttpStatusCode.TooManyRequests)
        })
        try {
            assertFalse(searchApi(client).search("empty").hasMore)
            assertEquals(429, assertFailsWith<PlatformFailure> { searchApi(client).search("limit") }.code)
        } finally { client.close() }
    }
    @Test fun uncertainWriteIsNotReplayedAndOnlyTouchesSelectedFolder() = runTest {
        var posts = 0; var reads = 0
        val client = HttpClient(MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/nav") -> respond("""{"code":0,"data":{"isLogin":true,"mid":7,"uname":"fixture"}}""")
                request.method == HttpMethod.Post -> {
                    posts++
                    val body = request.body.toByteArray().decodeToString()
                    assertTrue(body.contains("add_media_ids=42")); assertTrue(body.contains("del_media_ids="))
                    assertTrue(body.contains("csrf=test-csrf")); assertTrue(body.contains("rid=100"))
                    respond("uncertain", HttpStatusCode.BadGateway)
                }
                else -> { reads++; respond("""{"code":0,"data":{"list":[{"id":42,"title":"测试","media_count":1,"fav_state":${if(reads == 1) 0 else 1}}]}}""") }
            }
        })
        try {
            assertEquals(MutationOutcome.CONFIRMED, BiliApi(client) { "SESSDATA=fixture; bili_jct=test-csrf" }.changeFavorite(7, 100, 42, true))
            assertEquals(1, posts); assertEquals(2, reads)
        } finally { client.close() }
    }
    @Test fun alreadyPresentFavoriteAndUnownedFolderDoNotWrite() = runTest {
        var posts = 0
        val client = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Post) posts++
            if (request.url.encodedPath.endsWith("/nav")) respond("""{"code":0,"data":{"isLogin":true,"mid":7,"uname":"fixture"}}""")
            else respond("""{"code":0,"data":{"list":[{"id":42,"title":"测试","media_count":1,"fav_state":1}]}}""")
        })
        try { val api = BiliApi(client) { "SESSDATA=fixture; bili_jct=test-csrf" }
            assertEquals(MutationOutcome.UNCHANGED, api.changeFavorite(7, 100, 42, true))
            assertFailsWith<PlatformFailure> { api.changeFavorite(7, 100, 99, true) }
            assertEquals(0, posts)
        } finally { client.close() }
    }
    @Test fun recommendationMissingTagsDoesNotBecomeMusicAndInputOrderIsUnchanged() {
        val input = listOf(Recommendation("a","未知",null), Recommendation("b","音乐教学讨论",listOf("知识")), Recommendation("c","曲目",listOf("音乐")))
        assertEquals(listOf("c","a","b"), RecommendationPolicy.rank(input).map { it.bvid })
        assertEquals(listOf("a","b","c"), input.map { it.bvid })
    }
    @Test fun jumpCarriesCorrectPartAndReliableLocalTime() {
        val entry = QueueEntry("q", "BV1xx411c7mD", 99, 3, "第三 P")
        assertEquals("https://www.bilibili.com/video/BV1xx411c7mD/?p=3&t=76", VideoJump.url(entry, 76543))
    }
}
