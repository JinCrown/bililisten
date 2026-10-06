package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlinx.serialization.json.Json
import io.ktor.http.*

class SourcesTest {
    private val source = SourceRef(SourceKind.UP_COLLECTION, 42, 7, CollectionKind.SEASON)
    @Test fun strictLinksKeepOwnerAndRemoteType() {
        assertEquals(SourceLink(7, 42), SourceLinks.parse("https://space.bilibili.com/7/favlist?fid=42"))
        assertEquals(SourceLink(null, 42), SourceLinks.parse("https://www.bilibili.com/list/ml42"))
        assertEquals(SourceLink(7, 42, CollectionKind.SERIES), SourceLinks.parse("https://space.bilibili.com/7/lists/42?type=series"))
        assertEquals(SourceLink(7, 42, CollectionKind.SEASON), SourceLinks.parse("https://space.bilibili.com/7/channel/collectiondetail?sid=42"))
        assertNull(SourceLinks.parse("https://space.bilibili.com.evil.test/7/favlist?fid=42"))
        assertNull(SourceLinks.parse("https://www.bilibili.com/bangumi/play/ss42"))
        assertNull(SourceLinks.parse("https://space.bilibili.com/7/lists/42?type=unknown"))
        assertNull(SourceLinks.parse("https://space.bilibili.com/7/favlist?fid=-1"))
    }
    @Test fun partialRefreshNeverReplacesBaselineAndReorderIsNotAnUpdate() {
        val first = SourceUpdates.compare(null, "7", source, listOf("a", "b"), true)
        assertTrue(first.added.isEmpty())
        val checkpoint = first.checkpoint!!.copy(heard = setOf("a"))
        val partial = SourceUpdates.compare(checkpoint, "7", source, listOf("c"), false)
        assertEquals(checkpoint, partial.checkpoint); assertTrue(partial.added.isEmpty()); assertTrue(partial.removed.isEmpty())
        val reordered = SourceUpdates.compare(checkpoint, "7", source, listOf("b", "a", "a"), true)
        assertTrue(reordered.added.isEmpty()); assertTrue(reordered.removed.isEmpty())
        val replaced = SourceUpdates.compare(checkpoint, "7", source, listOf("b", "c"), true)
        assertEquals(setOf("c"), replaced.added); assertEquals(setOf("a"), replaced.removed)
        assertEquals(setOf("a"), replaced.checkpoint!!.heard)
        val restored = Json.decodeFromString<SourceCheckpoint>(Json.encodeToString(replaced.checkpoint))
        assertEquals(replaced.checkpoint, restored)
        assertTrue(SourceUpdates.compare(replaced.checkpoint, "7", source, listOf("c", "b"), true).added.isEmpty())
        assertFailsWith<IllegalArgumentException> { SourceUpdates.compare(checkpoint, "8", source, emptyList(), true) }
    }
    @Test fun sameNumericIdsNeverMergeAndLegacySourceIsPreserved() {
        val own = SourceRef(SourceKind.OWN_FAVORITES, 42, 7)
        val other = SourceRef(SourceKind.PUBLIC_FAVORITES, 42, 8)
        assertEquals(4, setOf(own, other, source, source.copy(collectionKind = CollectionKind.SERIES)).size)
        val entries = listOf(own, other, source).mapIndexed { i, s -> QueueEntry("$i", "BV1xx411c7mD", 3, 2, "fixture", source = s) }
        val snapshot = ResumeSnapshot(account = "7", entries = entries, order = listOf("2", "0", "1"), currentId = "2", positionMs = 43210, mode = PlayMode.SHUFFLE)
        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot)))
        val legacy = QueueEntry("old", "BV1xx411c7mD", 3, 2, "fixture", 42)
        assertEquals(own, legacy.resolvedSource("7")); assertNull(legacy.resolvedSource("guest"))
    }
    @Test fun privateAndOwnerMismatchAreFailuresNotEmptyContent() = runTest {
        val client = HttpClient(MockEngine { respond("""{"code":-403}""") })
        try { val e = assertFailsWith<PlatformFailure> { BiliApi(client) { null }.favoriteSource(42) }; assertEquals("收藏夹无权访问或已转为私有", e.category) } finally { client.close() }
        val mismatch = HttpClient(MockEngine { respond("""{"code":0,"data":{"info":{"id":42,"upper":{"mid":8,"name":"fixture"}},"has_more":false}}""") })
        try { assertFailsWith<PlatformFailure> { BiliApi(mismatch) { null }.favoriteSource(42, expectedOwner = 7) } } finally { mismatch.close() }
    }
    @Test fun seasonUsesServerPageAndRejectsRepeatedWrongPage() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/x/polymer/web-space/seasons_archives_list", request.url.encodedPath)
            assertEquals("2", request.url.parameters["page_num"])
            respond("""{"code":0,"data":{"meta":{"mid":7,"season_id":42,"name":"fixture"},"page":{"page_num":1,"page_size":20,"total":31},"archives":[]}}""")
        })
        try { assertFailsWith<PlatformFailure> { BiliApi(client) { null }.seasonSource(42, 2, 7) } } finally { client.close() }
    }
    @Test fun mixedSubscriptionsAreTypedAndUnknownTypesNeverPretendToBeSeasons() = runTest {
        val client = HttpClient(MockEngine { respond("""{"code":0,"data":{"count":3,"list":[{"type":11,"id":42,"title":"folder","cover":"https://i0.hdslb.com/bfs/archive/folder.jpg","media_count":2,"upper":{"mid":8,"name":"up"}},{"type":21,"id":42,"title":"season","cover":"https://i0.hdslb.com/bfs/archive/fixture.jpg","media_count":3,"upper":{"mid":8,"name":"up"}},{"type":99}]}}""") })
        try {
            val page = BiliApi(client) { null }.followedSources(7)
            assertEquals(listOf(SourceKind.PUBLIC_FAVORITES, SourceKind.UP_COLLECTION), page.sources.map { it.ref.kind })
            assertEquals("https://i0.hdslb.com/bfs/archive/fixture.jpg",page.sources.last().cover)
            assertEquals("https://i0.hdslb.com/bfs/archive/folder.jpg",page.sources.first().cover)
            assertEquals(setOf(99), page.unsupportedTypes); assertFalse(page.hasMore)
        } finally { client.close() }
    }
    @Test fun unavailableSeasonCannotBeTreatedAsNotFollowed() = runTest {
        val client = HttpClient(MockEngine { respond("""{"code":0,"data":{"count":1,"list":[{"type":21,"id":42,"upper":{"mid":0}}]}}""") })
        try {
            val api = BiliApi(client) { null }
            val page = api.followedSources(7)
            assertTrue(page.sources.isEmpty()); assertEquals(setOf(42L), page.unavailableSeasonIds)
            assertFailsWith<PlatformFailure> { api.seasonFollowed(7, source) }
        } finally { client.close() }
    }
    @Test fun sourceRepositoryRejectsWrongAccountBeforeNetwork() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine { requests++; error("Unexpected network") })
        try {
            val repository: SourceRepository = BiliSourceRepository(BiliApi(client) { null })
            assertFailsWith<PlatformFailure> { repository.content(SourceRef(SourceKind.OWN_FAVORITES, 42, 7), 8) }
            assertEquals(0, requests)
        } finally { client.close() }
    }
    @Test fun ambiguousFollowResponseUsesReadbackWithoutPostingAgain() = runTest {
        var posts = 0
        val client = HttpClient(MockEngine { r ->
            when {
                r.url.encodedPath == "/x/web-interface/nav" -> respond("""{"code":0,"data":{"isLogin":true,"mid":7,"uname":"fixture"}}""")
                r.method == HttpMethod.Post -> {
                    posts++; assertEquals("/x/v3/fav/season/fav", r.url.encodedPath)
                    respond("malformed-after-write")
                }
                r.url.encodedPath.endsWith("seasons_archives_list") -> respond("""{"code":0,"data":{"meta":{"mid":7,"season_id":42,"name":"fixture"},"page":{"page_num":1,"page_size":20,"total":0},"archives":[]}}""")
                else -> respond(if (posts == 0) """{"code":0,"data":{"count":0,"list":[]}}""" else """{"code":0,"data":{"count":1,"list":[{"type":21,"id":42,"title":"fixture","media_count":0,"upper":{"mid":7,"name":"fixture"}}]}}""")
            }
        })
        try {
            assertEquals(MutationOutcome.CONFIRMED, BiliApi(client) { "bili_jct=fixture" }.changeSeasonFollow(7, source, true))
            assertEquals(1, posts)
        } finally { client.close() }
    }
    @Test fun collectionShareTextAndOldPlayerLinksPreserveOwnerAndType() {
        val url="https://www.bilibili.com/medialist/play/7?business=space_collection&business_id=42&desc=0"
        assertEquals(SourceLink(7,42,CollectionKind.SEASON),SourceLinks.parse(SourceLinks.inputUrl("来听这个合集：$url）")))
        assertEquals(SourceLink(7,42,CollectionKind.SERIES),SourceLinks.parse(url.replace("space_collection","space_series")))
        assertNull(SourceLinks.parse(url.replace("business_id=42","business_id=0")))
        assertNull(SourceLinks.parse(url.replace("https://www.bilibili.com/","https://www.bilibili.com:444/")))
        assertNull(SourceLinks.parse(url.replace("space_collection","unknown")))
    }
    @Test fun collectionShortLinkReadsAnonymousAndRejectsUntrustedRedirectOrLoop()=runTest {
        var reads=0
        val client=HttpClient(MockEngine{request->reads++;assertEquals("b23.tv",request.url.host);assertNull(request.headers["Cookie"])
            respond("",HttpStatusCode.Found,headersOf("Location","https://space.bilibili.com/7/lists/42?type=season"))}){followRedirects=false}
        try {
            val api=BiliApi(client){"secret"}
            assertEquals(SourceLink(7,42,CollectionKind.SEASON),api.resolveCollectionInput("分享合集 https://b23.tv/example"))
            assertEquals(1,reads)
            assertEquals(SourceLink(7,42,CollectionKind.SEASON),api.resolveCollectionInput("https://space.bilibili.com/7/lists/42"));assertEquals(1,reads)
        }finally{client.close()}
        for(target in listOf("https://evil.example/test","http://b23.tv/test","https://b23.tv:444/test","https://space.bilibili.com/7/lists/42?type=series","https://www.bilibili.com/list/ml42","https://b23.tv/loop")) {
            var calls=0;val redirect=HttpClient(MockEngine{calls++;respond("",HttpStatusCode.Found,headersOf("Location",target))}){followRedirects=false}
            try{assertFailsWith<PlatformFailure>{BiliApi(redirect){"secret"}.resolveCollectionInput("https://b23.tv/test")};assertEquals(if(target.endsWith("/loop"))3 else 1,calls)}finally{redirect.close()}
        }
    }

}
