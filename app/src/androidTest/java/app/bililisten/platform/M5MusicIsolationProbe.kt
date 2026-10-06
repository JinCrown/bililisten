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

/** Explicit opt-in. Only the user-authorized 音乐 folder can be written; all others read-only. */
@RunWith(AndroidJUnit4::class)
class M5MusicIsolationProbe {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ListenApplication
    private val phase=InstrumentationRegistry.getArguments().getString("m5music")
    private val journal get()=File(app.noBackupFilesDir,"m5-music-isolation.json")
    private fun save(data:JsonObject){journal.writeText(data.toString())}
    private fun number(data:JsonObject,key:String)=data.getValue(key).jsonPrimitive.long
    private suspend fun memberships(data:JsonObject):List<FavoriteFolder> {
        val account=app.accounts.verify() ?: error("Account required")
        check(account.id==number(data,"account"))
        return app.favorites.folders(account.id,number(data,"aid")).also { folders ->
            check(folders.single{it.id==number(data,"music")}.title=="音乐")
        }
    }
    private suspend fun waitForMembership(data:JsonObject,present:Boolean):List<FavoriteFolder> {
        repeat(8) {
            val folders=memberships(data)
            if(folders.single{it.id==number(data,"music")}.contains==present)return folders
            delay(1500)
        }
        error("Membership remains unsettled; keep journal and use read-only reconciliation")
    }
    private suspend fun restore(data:JsonObject) {
        val folders=memberships(data)
        val current=folders.single{it.id==number(data,"music")}.contains ?: error("Membership unknown")
        // An uncertain removal is reconciled, never re-posted.
        if(data["removeAttempted"]?.jsonPrimitive?.boolean==true) {
            val final=waitForMembership(data,false)
            app.favorites.reconcile(number(data,"account"))
            assertEquals(true,final.single{it.id==number(data,"other")}.contains)
            check(journal.delete());return
        }
        if(current){
            app.favorites.reconcile(number(data,"account"))
            save(JsonObject(data+mapOf("removeAttempted" to JsonPrimitive(true))))
            app.favorites.change(number(data,"account"),number(data,"aid"),number(data,"music"),false)
            val final=waitForMembership(data,false)
            app.favorites.reconcile(number(data,"account"))
            assertEquals(true,final.single{it.id==number(data,"other")}.contains)
            check(journal.delete())
        } else {
            // A timed-out add that was never observed may still be settling. Do not clear its journal.
            check(data["addedObserved"]?.jsonPrimitive?.boolean==true) { "Add not observed; retain recovery journal" }
            assertEquals(true,folders.single{it.id==number(data,"other")}.contains)
            app.favorites.reconcile(number(data,"account"));check(journal.delete())
        }
    }
    @Test fun removingOnlyMusicPreservesTheExistingOtherFolderMembership()=runBlocking {
        assumeTrue(phase=="exercise")
        check(!journal.exists()){"Finish previous journal recovery before another test"}
        val account=app.accounts.verify() ?: error("Existing test account required")
        check(app.favorites.pending(account.id.toString())==null){"Existing user operation must not be touched"}
        val folders=app.favorites.folders(account.id)
        val music=folders.single{it.title=="音乐"}
        var data:JsonObject?=null
        outer@ for(other in folders.filter{it.id!=music.id&&it.count>0}.take(3)) {
            for(item in app.favorites.page(other.id,1).items.take(3)) {
                val aid=app.content.video(item.bvid).aid
                val before=app.favorites.folders(account.id,aid)
                if(before.single{it.id==music.id}.contains==false&&before.single{it.id==other.id}.contains==true){
                    data=buildJsonObject{put("account",account.id);put("aid",aid);put("music",music.id);put("other",other.id);put("addedObserved",false)}
                    break@outer
                }
            }
        }
        var record=checkNotNull(data){"No suitable existing other-folder sample within bounded read scope"}
        save(record)
        try {
            app.favorites.change(account.id,number(record,"aid"),music.id,true)
            val both=waitForMembership(record,true)
            record=JsonObject(record+mapOf("addedObserved" to JsonPrimitive(true)));save(record)
            assertEquals(true,both.single{it.id==number(record,"other")}.contains)
        } finally {
            restore(record)
        }
        File(app.filesDir,"m5-evidence").mkdirs()
        File(app.filesDir,"m5-evidence/music-isolation.json").writeText(buildJsonObject{
            put("originalMusicAbsent",true);put("simultaneousMembershipObserved",true)
            put("onlyMusicWasWritten",true);put("otherMembershipPreserved",true);put("musicRestoredAbsent",true)
            put("privateIdentifiersExported",false)
        }.toString())
    }
    @Test fun recoverOnlyThePreviouslyJournaledMusicRelation()=runBlocking {
        assumeTrue(phase=="recover")
        check(journal.exists())
        restore(Json.parseToJsonElement(journal.readText()).jsonObject)
    }
}
