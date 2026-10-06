package app.bililisten.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class M0SourceProbe {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
    private val args = InstrumentationRegistry.getArguments()
    private fun save(name: String, data: JsonElement) {
        File(app.filesDir, "m0-evidence").mkdirs()
        File(app.filesDir, "m0-evidence/$name.json").writeText(data.toString())
    }
    @Test fun scopedFollowAndRollback() = runBlocking {
        val phase = args.getString("m0phase")
        assumeTrue(phase in setOf("source-follow", "source-reconcile", "source-unfollow"))
        val account = app.api.account()
        val source = SourceRef(SourceKind.UP_COLLECTION, 57445, 2142762, CollectionKind.SEASON)
        val journal = File(app.noBackupFilesDir, "m0-source-follow.json")
        if (phase == "source-follow") {
            require(!journal.exists()) { "Existing intent requires reconciliation, never replay" }
            require(!app.api.seasonFollowed(account.id, source)) { "Already followed; original relation must not be changed" }
            journal.writeText(buildJsonObject { put("account", account.id); put("source", SourceCodec.encode(source)); put("before", false) }.toString())
            val outcome = app.api.changeSeasonFollow(account.id, source, true)
            save("source-follow", buildJsonObject { put("outcome", outcome.name); put("before", false); put("season", source.id) })
        } else {
            val proof = Json.parseToJsonElement(journal.readText()).jsonObject
            require(proof["account"]!!.jsonPrimitive.long == account.id && !proof["before"]!!.jsonPrimitive.boolean)
            require(SourceCodec.decode(proof["source"]!!.jsonPrimitive.content) == source)
            if (phase == "source-reconcile") {
                save("source-reconcile", buildJsonObject { put("followed", app.api.seasonFollowed(account.id, source)); put("readOnly", true) })
            } else {
                val outcome = app.api.changeSeasonFollow(account.id, source, false)
                save("source-unfollow", buildJsonObject { put("outcome", outcome.name); put("restored", outcome != MutationOutcome.UNKNOWN) })
                if (outcome != MutationOutcome.UNKNOWN) journal.delete()
            }
        }
    }
    @Test fun readRealSources() = runBlocking {
        assumeTrue(args.getString("m0phase") == "sources-read")
        val account = app.api.account()
        val results = mutableListOf<JsonElement>()
        suspend fun probe(name: String, action: suspend () -> JsonObject) {
            try { results += buildJsonObject { put("name", name); put("result", action()) } }
            catch (e: PlatformFailure) { results += buildJsonObject { put("name", name); put("code", e.code); put("failure", e.category) } }
            finally { save("source-reads", JsonArray(results)) }
        }
        probe("public-favorites") {
            val folders = app.sources.publicFolders(17340771)
            val first = app.sources.resolve(requireNotNull(SourceLinks.parse("https://space.bilibili.com/17340771/favlist?fid=2578744971")), account.id)
            val second = app.sources.content(first.source.ref, account.id, 2)
            assertEquals(SourceKind.PUBLIC_FAVORITES, first.source.ref.kind)
            assertTrue(first.hasMore); assertFalse(second.hasMore)
            assertTrue(first.items.map { it.bvid }.intersect(second.items.map { it.bvid }.toSet()).isEmpty())
            save("source-public-sample", buildJsonObject { put("source", SourceCodec.encode(first.source.ref)); put("bvid", first.items.first().bvid) })
            buildJsonObject { put("folderCount", folders.sources.size); put("page1", first.items.size); put("page2", second.items.size); put("total", first.source.count); put("identityVerified", true) }
        }
        probe("followed-sources") {
            val page = app.sources.followed(account.id)
            // Only a selected public collection identity is recorded; never dump the user's library.
            val selected = page.sources.firstOrNull { it.ref.kind == SourceKind.UP_COLLECTION }
            selected?.let { save("source-followed-sample", buildJsonObject { put("source", SourceCodec.encode(it.ref)) }) }
            buildJsonObject { put("pageCount", page.sources.size); put("hasMore", page.hasMore); put("seasons", page.sources.count { it.ref.kind == SourceKind.UP_COLLECTION }); put("favorites", page.sources.count { it.ref.kind == SourceKind.PUBLIC_FAVORITES }); put("unsupported", JsonArray(page.unsupportedTypes.map { JsonPrimitive(it) })) }
        }
        probe("public-season") {
            val first = app.sources.resolve(requireNotNull(SourceLinks.parse("https://space.bilibili.com/2142762/lists/57445?type=season")), account.id)
            val second = if (first.hasMore) app.sources.content(first.source.ref, account.id, 2) else null
            second?.let { assertTrue(first.items.map { it.bvid }.intersect(it.items.map { row -> row.bvid }.toSet()).isEmpty()) }
            save("source-season-sample", buildJsonObject { put("source", SourceCodec.encode(first.source.ref)); put("bvid", first.items.first().bvid) })
            buildJsonObject { put("page1", first.items.size); put("page2", second?.items?.size); put("total", first.source.count); put("hasMore", second?.hasMore ?: first.hasMore); put("identityVerified", true) }
        }
        probe("public-series") {
            val first = app.api.seriesSource(547718, 1958703906, 1)
            val second = app.api.seriesSource(547718, 1958703906, 2)
            assertTrue(first.items.map { it.bvid }.intersect(second.items.map { it.bvid }.toSet()).isEmpty())
            buildJsonObject { put("page1", first.items.size); put("page2", second.items.size); put("total", first.source.count); put("distinctFromSeason", first.source.ref.collectionKind == CollectionKind.SERIES) }
        }
    }
}
