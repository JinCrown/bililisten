package app.bililisten.platform

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.*

@RunWith(AndroidJUnit4::class)
class LibraryManagementStoreTest {
    @Test fun persistedLayoutsMigrateOverEncryptedTcpKeepJournalQueueAndNewerLocalSettings()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="library-management-fixture-${java.util.UUID.randomUUID()}.db"
        var db=Room.databaseBuilder(context,ListenDatabase::class.java,name).build()
        val dest=Room.inMemoryDatabaseBuilder(context,ListenDatabase::class.java).build()
        val stamp=SessionStamp("7",1)
        val accounts=object:AccountRepository {
            override val session=MutableStateFlow(AccountSession(stamp,SessionStatus.AUTHENTICATED,Account(7,"fixture")))
            override suspend fun verify()=session.value.account
            override suspend fun accept(cookie:String)=error("unused")
            override suspend fun logout()=error("unused")
            override fun requireCurrent(stamp:SessionStamp){require(stamp==session.value.stamp)}
        }
        var remoteCalls=0
        val remote=object:OrganizerRemote {
            override suspend fun folders(account:Long):List<FavoriteFolder>{remoteCalls++;error("unused")}
            override suspend fun folder(account:Long,id:Long):FavoriteFolder{remoteCalls++;error("unused")}
            override suspend fun writeFolder(account:Long,draft:FolderDraft):Long?{remoteCalls++;error("unused")}
            override suspend fun video(bvid:String):Video{remoteCalls++;error("unused")}
            override suspend fun membership(account:Long,aid:Long):List<FavoriteFolder>{remoteCalls++;error("unused")}
            override suspend fun change(account:Long,aid:Long,folder:Long,add:Boolean):MutationOutcome{remoteCalls++;error("unused")}
        }
        fun store(database:ListenDatabase)=RoomOrganizerStore(database.listenDao(),object:OrganizerStore{override suspend fun read(account:String)=OrganizerData();override suspend fun write(account:String,value:OrganizerData){}})
        val mine=SourceRef(SourceKind.OWN_FAVORITES,1,7)
        val folder=SourceRef(SourceKind.PUBLIC_FAVORITES,1,8)
        val season=SourceRef(SourceKind.UP_COLLECTION,1,8,CollectionKind.SEASON)
        val up=SourceRef(SourceKind.UP_UPLOADS,8,8)
        try {
            var store=store(db)
            val journal=FolderPending(FolderDraft(FolderAction.CREATE,title="未确认示例"),emptyList())
            store.write("7",OrganizerData(pending=journal,aliases=mapOf("BV1Yt411u7UD" to "别名")))
            val local=RoomLibraryPreferences(store,Organizer(remote,accounts,store),accounts)
            val prefs=local.update(stamp){it.edit(LibrarySection.MINE,2000){l->l.copy(hidden=setOf(mine),order=listOf(mine))}.edit(LibrarySection.FOLLOWED,2100){l->l.copy(hidden=setOf(folder),order=listOf(season,folder))}.edit(LibrarySection.UP,2200){l->l.copy(hidden=setOf(up),order=listOf(up))}}
            assertEquals(journal,store.read("7").pending);assertEquals("别名",store.read("7").aliases["BV1Yt411u7UD"])
            assertTrue(local.read(SessionStamp("7",1)).hidden(folder));assertEquals(0,remoteCalls)
            accounts.session.value=AccountSession(SessionStamp("8",2),SessionStatus.AUTHENTICATED,Account(8,"other"))
            try{local.update(stamp){LibraryPreferences()};fail("Stale account accepted")}catch(_:IllegalArgumentException){}
            assertTrue(local.read(accounts.session.value.stamp).layouts.isEmpty());accounts.session.value=AccountSession(stamp,SessionStatus.AUTHENTICATED,Account(7,"fixture"))
            store.write("7",store.read("7").copy(pending=null));db.close()
            db=Room.databaseBuilder(context,ListenDatabase::class.java,name).build();store=store(db)
            assertEquals(prefs,store.read("7").library)
            val sourceRepo=LocalTransferRepository(db,store,{stamp},{3000})
            val exported=sourceRepo.export(stamp);val bytes=TransferCodec.encode(exported);val code=WifiTransfer.randomCode()
            val socket=ServerSocket(0,2,InetAddress.getLoopbackAddress())
            val received=try{
                val sending=async(Dispatchers.IO){socket.accept().use{WifiTransfer.serve(it,bytes,code)}}
                val result=withContext(Dispatchers.IO){Socket(InetAddress.getLoopbackAddress(),socket.localPort).use{WifiTransfer.receiveSocket(it,code)}}
                sending.await();TransferCodec.decode(result)
            }finally{socket.close()}
            val destStore=store(dest);val target=LocalTransferRepository(dest,destStore,{stamp},{3000})
            dest.listenDao().save(ResumeRow("7","keep-queue"));destStore.write("8",OrganizerData(aliases=mapOf("BV1Yt411u7UD" to "other")))
            target.import(received,stamp,HistoryRetentionPolicy(keepAll=true))
            assertEquals(prefs,destStore.read("7").library);assertEquals("keep-queue",dest.listenDao().resume("7")!!.payload)
            val restored=prefs.edit(LibrarySection.MINE,2500){it.copy(hidden=emptySet())}
            destStore.write("7",destStore.read("7").copy(library=restored));target.import(received,stamp,HistoryRetentionPolicy(keepAll=true))
            assertEquals(restored,destStore.read("7").library)
            target.import(LocalTransfer(version=1,createdAt=3000,account="7"),stamp,HistoryRetentionPolicy(keepAll=true))
            assertEquals(restored,destStore.read("7").library);assertEquals("other",destStore.read("8").aliases["BV1Yt411u7UD"]);assertEquals(0,remoteCalls)
            java.io.File(context.filesDir,"up-library-evidence").mkdirs()
            java.io.File(context.filesDir,"up-library-evidence/management-store.json").writeText("""{"roomReopenRetained":true,"threeCategoryMigration":true,"encryptedTcpRoundTrip":true,"newerLocalAndLegacyImportKept":true,"otherAccountAndQueueKept":true,"pendingJournalKept":true,"staleAccountRejected":true,"remoteCalls":0,"realUserStoreTouched":false}""")
        }finally{db.close();dest.close();context.deleteDatabase(name)}
    }
    @Test fun realCreationAdapterJournalsOneFakeWriteAndRecoversLostResponseWithoutRepeatingIt()=runBlocking {
        val stamp=SessionStamp("7",1)
        val accounts=object:AccountRepository {
            override val session=MutableStateFlow(AccountSession(stamp,SessionStatus.AUTHENTICATED,Account(7,"fixture")))
            override suspend fun verify()=session.value.account
            override suspend fun accept(cookie:String)=error("unused")
            override suspend fun logout()=error("unused")
            override fun requireCurrent(stamp:SessionStamp){require(stamp==session.value.stamp)}
        }
        val prefs=LibraryPreferences().edit(LibrarySection.MINE,2000){it.copy(hidden=setOf(SourceRef(SourceKind.OWN_FAVORITES,1,7)))}
        var saved=OrganizerData(library=prefs,aliases=mapOf("BV1Yt411u7UD" to "别名"))
        val store=object:OrganizerStore {
            override suspend fun read(account:String)=saved.also{require(account=="7")}
            override suspend fun write(account:String,value:OrganizerData){require(account=="7");saved=value}
        }
        val server=mutableListOf(FavoriteFolder(1,"已有",0,attr=0));var writes=0;var failReads=false
        val remote=object:OrganizerRemote {
            override suspend fun folders(account:Long):List<FavoriteFolder>{if(failReads)throw PlatformFailure("read failed");return server.toList()}
            override suspend fun folder(account:Long,id:Long)=server.first{it.id==id}
            override suspend fun writeFolder(account:Long,draft:FolderDraft):Long? {
                assertNotNull(saved.pending);assertEquals(draft,saved.pending!!.draft)
                writes++;server+=FavoriteFolder(10,"夜晚听歌",0,attr=1);failReads=true
                throw PlatformFailure("response lost")
            }
            override suspend fun video(bvid:String):Video=error("unused")
            override suspend fun membership(account:Long,aid:Long):List<FavoriteFolder> = error("unused")
            override suspend fun change(account:Long,aid:Long,folder:Long,add:Boolean):MutationOutcome=error("unused")
        }
        var port=OrganizerFolderCreation(Organizer(remote,accounts,store),store,accounts)
        assertFalse(port.inspect(stamp).pending)
        val outcome=port.create(stamp,"夜晚听歌",true);assertTrue(outcome.pending);assertEquals(1,writes)
        port=OrganizerFolderCreation(Organizer(remote,accounts,store),store,accounts)
        assertTrue(port.inspect(stamp).pending)
        try{port.create(stamp,"夜晚听歌",true);fail("Repeated uncertain create")}catch(_:PlatformFailure){}
        assertEquals(1,writes);failReads=false
        val confirmed=port.reconcile(stamp);assertFalse(confirmed.pending);assertEquals("已回读确认创建成功",confirmed.message)
        assertEquals(1,writes);assertEquals(prefs,saved.library);assertEquals("别名",saved.aliases["BV1Yt411u7UD"])
        assertEquals(2,server.size)
        java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"up-library-evidence/creation-journal.json").writeText("""{"durableIntentBeforeWrite":true,"lostResponsePending":true,"recreatedAdapterBlocksDuplicate":true,"readOnlyReconcileConfirmed":true,"fakeWrites":1,"localPreferencesPreserved":true,"realRemoteWrites":0}""")
    }

}
