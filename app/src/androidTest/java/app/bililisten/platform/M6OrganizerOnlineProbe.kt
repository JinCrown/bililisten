package app.bililisten.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.ListenApplication
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Existing folders are read-only. Writes need explicit opt-in and only newly journaled IDs. */
@RunWith(AndroidJUnit4::class)
class M6OrganizerOnlineProbe {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
    private val phase=InstrumentationRegistry.getArguments().getString("m6a")
    private val journal get()=File(app.noBackupFilesDir,"m6a-test-folders.json")
    private fun evidence(name:String,data:JsonObject){File(app.filesDir,"m6a-evidence").apply{mkdirs()}.resolve("$name.json").writeText(data.toString())}
    @Test fun remoteSearchIncludesPreviouslyUnloadedPageAndOtherFolder()=runBlocking {
        assumeTrue(phase=="read")
        val account=app.accounts.verify() ?: error("Test login required")
        val folders=app.api.folders(account.id)
        val source=folders.first{it.count>20}
        val second=app.api.favorites(source.id,2)
        val sample=second.items.first{it.title.length>=6}
        val query=sample.title.take(12)
        val scoped=app.api.favorites(source.id,1,query)
        val other=folders.first{it.id!=source.id}
        val cross=app.api.favorites(other.id,1,query,true)
        val scopedFound=scoped.items.any{it.bvid==sample.bvid};val crossFound=cross.items.any{it.bvid==sample.bvid}
        evidence("remote-search",buildJsonObject{put("searchedUnloadedPage2",true);put("folderQueryMatched",scopedFound);put("crossFolderQueryMatched",crossFound);put("crossAnchorWasDifferentFolder",true);put("scopedHasMore",scoped.hasMore);put("crossHasMore",cross.hasMore)})
        assertTrue("Search must cover beyond initial page",scopedFound)
        assertTrue("type=1 must cover another folder",crossFound)
        val filtered=app.api.favorites(source.id,1,"",false,"view",3)
        evidence("search-filter",buildJsonObject{put("musicPartitionAndViewSortAccepted",true);put("returnedCount",filtered.items.size);put("hasMore",filtered.hasMore)})
    }
    private suspend fun cleanup(account:Long,before:Set<Long>,names:Set<String>) {
        if(app.favorites.pending(account.toString())!=null)app.favorites.reconcile(account)
        var data=app.organizer.reconcile()
        check(data.pending==null && data.batch?.rows?.none{it.outcome in setOf(RowOutcome.RUNNING,RowOutcome.UNKNOWN)}!=false){"Unsettled write: preserve journal and stop"}
        for(folder in app.api.folders(account).filter{it.id !in before && it.title in names}) {
            data=app.organizer.folder(FolderDraft(FolderAction.DELETE,folder.id))
            check(data.pending==null){"Deletion unsettled; retain journal"}
        }
        val remaining=app.api.folders(account)
        check(remaining.none{it.id !in before && it.title in names})
        check(journal.delete())
    }
    @Test fun temporaryFoldersCreateRenameMoveCopyRemoveAndDelete()=runBlocking {
        assumeTrue(phase=="exercise")
        check(!journal.exists()){"Recover earlier test first"}
        val account=app.accounts.verify() ?: error("Test account required")
        val original=app.api.folders(account.id)
        val before=original.map{it.id}.toSet()
        val names=setOf("听视频验收A","听视频验收A改","听视频验收B")
        check(original.none{it.title in names}){"Never reuse existing folders"}
        check(app.favorites.pending(account.id.toString())==null)
        val previous=app.organizerStore.read(account.id.toString())
        check(previous.pending==null&&previous.batch==null){"Preserve user organizer work"}
        journal.writeText(buildJsonObject{put("account",account.id);put("before",JsonArray(before.map{JsonPrimitive(it)}));put("names",JsonArray(names.map{JsonPrimitive(it)}))}.toString())
        var passed=false
        try {
            check(app.organizer.folder(FolderDraft(FolderAction.CREATE,title="听视频验收A",private=true)).pending==null)
            val a=app.api.folders(account.id).single{it.id !in before&&it.title=="听视频验收A"}
            assertEquals(1,a.attr!!.and(1))
            check(app.organizer.folder(FolderDraft(FolderAction.EDIT,a.id,"听视频验收A改",false)).pending==null)
            val renamed=app.api.ownFolder(account.id,a.id);assertEquals("听视频验收A改",renamed.title);assertEquals(0,renamed.attr!!.and(1))
            check(app.organizer.folder(FolderDraft(FolderAction.CREATE,title="听视频验收B",private=true)).pending==null)
            val b=app.api.folders(account.id).single{it.id !in before&&it.title=="听视频验收B"}
            val videos=listOf(app.api.video("BV1xx411c7mD"),app.api.video("BV1rHh16CESS"))
            val originalMembership=videos.associate{v->v.aid to app.api.folders(account.id,v.aid).filter{it.id in before}.associate{it.id to it.contains}}
            for((index,v) in videos.withIndex()) {
                try {
                    val outcome=app.favorites.change(account.id,v.aid,a.id,true)
                    evidence("seed-$index",buildJsonObject{put("outcome",outcome.name)})
                    assertTrue("Seed favorite must confirm",outcome!=MutationOutcome.UNKNOWN)
                } catch(e:PlatformFailure) { evidence("seed-$index",buildJsonObject{put("error",e.category);put("code",e.code)});throw e }
            }
            val items=videos.map{FavoriteItem(it.bvid,it.title)}
            var result=app.organizer.batch(a.id,b.id,OrganizeAction.COPY,items)
            assertTrue(result.batch!!.rows.all{it.outcome==RowOutcome.CONFIRMED})
            for(v in videos){val member=app.api.folders(account.id,v.aid);assertTrue(member.single{it.id==a.id}.contains==true);assertTrue(member.single{it.id==b.id}.contains==true)}
            result=app.organizer.batch(a.id,b.id,OrganizeAction.MOVE,items)
            assertTrue(result.batch!!.rows.all{it.outcome==RowOutcome.CONFIRMED})
            result=app.organizer.batch(b.id,0,OrganizeAction.REMOVE,items)
            assertTrue(result.batch!!.rows.all{it.outcome==RowOutcome.CONFIRMED})
            for(v in videos)assertEquals(originalMembership[v.aid],app.api.folders(account.id,v.aid).filter{it.id in before}.associate{it.id to it.contains})
            passed=true
        } finally {
            cleanup(account.id,before,names)
            app.organizerStore.write(account.id.toString(),previous)
        }
        val after=app.api.folders(account.id)
        assertEquals(original.map{Triple(it.id,it.title,it.count)}.toSet(),after.map{Triple(it.id,it.title,it.count)}.toSet())
        evidence("folder-lifecycle",buildJsonObject{put("passed",passed);put("createPrivate",true);put("renameAndPublicPermission",true);put("copyTwoVideos",true);put("moveTwoVideos",true);put("removeTwoVideos",true);put("originalFolderMetadataUnchanged",true);put("existingVideoMembershipUnchanged",true);put("temporaryFoldersDeleted",true);put("userOrganizerDataRestored",true)})
    }
    @Test fun recoverOnlyNewlyJournaledTemporaryFolders()=runBlocking {
        assumeTrue(phase=="recover")
        val data=Json.parseToJsonElement(journal.readText()).jsonObject
        val account=app.accounts.verify() ?: error("Test login required")
        check(account.id==data.getValue("account").jsonPrimitive.long)
        cleanup(account.id,data.getValue("before").jsonArray.map{it.jsonPrimitive.long}.toSet(),data.getValue("names").jsonArray.map{it.jsonPrimitive.content}.toSet())
    }
}
