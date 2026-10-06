package app.bililisten.platform

import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@UnstableApi @RunWith(AndroidJUnit4::class)
class M3OnlineProbe {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private val phase=InstrumentationRegistry.getArguments().getString("m3phase")
    private suspend fun await(vm:MainViewModel) {withTimeout(45000){while(vm.state.value.busy||!vm.state.value.accountChecked)delay(50)}}
    private fun evidence(name:String,value:JsonObject){File(app.filesDir,"m3-evidence").mkdirs();File(app.filesDir,"m3-evidence/$name.json").writeText(value.toString())}
    @Test fun authenticatedBrowseMembershipAndPaging()=runBlocking {
        assumeTrue(phase=="read")
        val account=app.accounts.verify()!!;app.accountKey=account.id.toString()
        val vm=withContext(Dispatchers.Main){MainViewModel(app)}
        val owner=androidx.lifecycle.ViewModelStore();withContext(Dispatchers.Main){owner.put("test",vm)}
        try {
            await(vm);assertEquals(account.id,vm.state.value.account!!.id)
            withContext(Dispatchers.Main){vm.loadFolders()};await(vm)
            val music=vm.state.value.folders.single{it.title=="音乐"}
            withContext(Dispatchers.Main){vm.openFolder(music)};await(vm)
            assertEquals(music.id,vm.state.value.folder!!.id);assertFalse(vm.state.value.sourceCached)
            val public=SourceRef(SourceKind.PUBLIC_FAVORITES,2578744971,17340771)
            withContext(Dispatchers.Main){vm.openSource(public)};await(vm)
            assertEquals(public,vm.state.value.sourceContent!!.source.ref)
            val first=vm.state.value.sourceContent!!.items.size
            if(vm.state.value.sourceContent!!.hasMore){withContext(Dispatchers.Main){vm.openSource(public,true)};await(vm)}
            assertTrue(vm.state.value.sourceContent!!.items.size>=first)
            val season=SourceRef(SourceKind.UP_COLLECTION,57445,2142762,CollectionKind.SEASON)
            withContext(Dispatchers.Main){vm.openSource(season)};await(vm);assertEquals(season,vm.state.value.sourceContent!!.source.ref)
            withContext(Dispatchers.Main){vm.loadFollowedSources()};await(vm);assertNotNull(vm.state.value.sources)
            evidence("read",buildJsonObject{put("accountVerified",true);put("membershipKnown",account.membership!=Membership.UNKNOWN);put("musicRead",true);put("publicPagination",true);put("seasonRead",true);put("followedRead",true);put("remoteWrites",0)})
        }finally{withContext(Dispatchers.Main){owner.clear()}}
    }
    @Test fun guestSearchRecommendationPlaybackHistoryAndResume()=runBlocking {
        assumeTrue(phase in setOf("guest","guest-playback"))
        require(app.vault.read()==null){"Guest test only runs on an unsigned-in device"}
        require(android.os.Build.HARDWARE == "ranchu"){"Guest test is restricted to the emulator"}
        // Emulator's virtual mobile interface uses the host connection, not a physical SIM.
        val originalSettings=app.settings.settings.value
        app.settings.update(originalSettings.copy(mobilePlayback=true,historyEnabled=true))
        val vm=withContext(Dispatchers.Main){MainViewModel(app)}
        val owner=androidx.lifecycle.ViewModelStore();withContext(Dispatchers.Main){owner.put("test",vm)}
        try {
            await(vm);assertNull(vm.state.value.account);assertEquals(LoginPhase.GUEST,vm.state.value.loginPhase)
            val searchOk = if(phase=="guest") {
                withContext(Dispatchers.Main){vm.loadRecommendations()};withTimeout(45000){while(vm.state.value.recommendationBusy)delay(50)};assertTrue(vm.state.value.recommendations.isNotEmpty())
                withContext(Dispatchers.Main){vm.searchVideos("科普")}
                withTimeout(30000){while(vm.state.value.searchBusy)delay(100)}
                val ok=vm.state.value.search?.items?.isNotEmpty()==true
                evidence("guest-search",buildJsonObject{put("recommendations",true);put("search",ok);put("failure",if(ok)"" else vm.state.value.message)})
                ok
            } else false
            // Still verify independent guest playback when platform search is restricted.
            withContext(Dispatchers.Main){vm.inspect("BV1U1421r7SM")};await(vm);assertNotNull(vm.state.value.video)
            withTimeout(15000){while(!vm.state.value.connected)delay(100)}
            withContext(Dispatchers.Main){vm.playPart(vm.state.value.video!!.parts.first())};await(vm)
            withTimeout(30000){while(!vm.state.value.playing){assertNull(vm.state.value.playbackIssue);delay(100)}}
            delay(2500);withContext(Dispatchers.Main){vm.prepareLogin();vm.loginCancelled();vm.refreshResume()};await(vm)
            assertFalse(vm.state.value.playRequested)
            withTimeout(5000){while(vm.state.value.history.isEmpty())delay(100)}
            val saved=app.stores.load("guest")!!;assertTrue(saved.queue.positionMs>1000)
            val resume=ResumeCoordinator(app.stores,app.accounts,app.sources,app.clock).prepare()!!;assertFalse(resume.autoplay)
            withContext(Dispatchers.Main){vm.requestFavorite()};assertTrue(vm.state.value.pendingFavorite)
            evidence(if(phase=="guest")"guest" else "guest-playback",buildJsonObject{put("noCredential",true);put("searchExercised",phase=="guest");put("search",searchOk);put("played",true);put("history",true);put("resumeSilent",true);put("favoriteRequiresLogin",true);put("remoteWrites",0)})
            if(phase=="guest")assertTrue("Guest search failed; inspect guest-search evidence",searchOk)
        }finally{withContext(Dispatchers.Main){vm.prepareLogin();owner.clear()};app.settings.update(originalSettings)}
    }
    @Test fun prepareOrRemoveOnlyNewMusicTestFavorite()=runBlocking {
        assumeTrue(phase in setOf("favorite-prepare","favorite-read","favorite-remove"))
        val account=app.accounts.verify()!!
        val video=app.content.video("BV1rHh16CESS")
        val folder=app.favorites.folders(account.id,video.aid).single{it.title=="音乐"}
        val journal=File(app.noBackupFilesDir,"m3-music-test.json")
        if(phase=="favorite-prepare") {
            require(!journal.exists());require(folder.contains==false){"Existing user favorite must not be modified"}
            journal.writeText(buildJsonObject{put("account",account.id);put("folder",folder.id);put("aid",video.aid);put("before",false)}.toString())
            evidence("favorite-prepare",buildJsonObject{put("before",false);put("prepared",true);put("remoteWrites",0)})
        } else {
            val record=Json.parseToJsonElement(journal.readText()).jsonObject
            require(record["account"]!!.jsonPrimitive.long==account.id && record["folder"]!!.jsonPrimitive.long==folder.id && record["aid"]!!.jsonPrimitive.long==video.aid && !record["before"]!!.jsonPrimitive.boolean)
            if(phase=="favorite-read") {
                val vm=withContext(Dispatchers.Main){MainViewModel(app)};val owner=androidx.lifecycle.ViewModelStore()
                withContext(Dispatchers.Main){owner.put("test",vm)}
                try {await(vm);withContext(Dispatchers.Main){vm.loadFolders()};await(vm);withContext(Dispatchers.Main){vm.openFolder(folder)};await(vm)
                    assertTrue(vm.state.value.favorites.any{it.bvid==video.bvid});assertTrue(folder.contains==true)
                    evidence("favorite-read",buildJsonObject{put("officialAdditionVisible",true);put("readOnly",true)})
                }finally{withContext(Dispatchers.Main){owner.clear()}}
            } else {
                val result=app.favorites.change(account.id,video.aid,folder.id,false)
                assertTrue(result!=MutationOutcome.UNKNOWN)
                assertEquals(false,app.favorites.folders(account.id,video.aid).single{it.id==folder.id}.contains)
                evidence("favorite-remove",buildJsonObject{put("restoredOriginalAbsence",true);put("confirmed",true)})
                assertTrue(journal.delete())
            }
        }
    }
}
