package app.bililisten.platform

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.*

@RunWith(AndroidJUnit4::class)
class DataTransferTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val bvid="BV1Yt411u7UD"
    private fun database()=Room.inMemoryDatabaseBuilder(context,ListenDatabase::class.java).build()
    private val source=SourceRef(SourceKind.PUBLIC_FAVORITES,8,9)
    private fun data()=LocalTransfer(createdAt=3000,account="7",history=listOf(LocalHistoryEntry("7",VideoRef(bvid,1,1),"示例",source,50,2000)),liveHistory=listOf(LiveHistoryEntry("7",33,"示例直播",2200)),bookmarks=listOf(TransferBookmark(source,"示例来源","作者",2)),heard=listOf(TransferHeard(source,setOf(bvid))),aliases=mapOf(bvid to "别名"),orders=mapOf(2L to listOf(bvid)))
    private fun store(db:ListenDatabase)=RoomOrganizerStore(db.listenDao(),object:OrganizerStore{override suspend fun read(account:String)=OrganizerData();override suspend fun write(account:String,value:OrganizerData){}})
    @Test fun emptyNewPhoneRoundTripAndRepeatedImportNeverChangeOtherAccountsOrQueue()=runBlocking {
        val db=database();try{
            val repository=LocalTransferRepository(db,store(db),{SessionStamp("7",1)},{3000})
            db.listenDao().played(HistoryRow("8",bvid,1,"other",1,null,90,2500))
            db.listenDao().save(ResumeRow("7","keep-original-queue"))
            val result=repository.import(TransferCodec.decode(TransferCodec.encode(data())),SessionStamp("7",1),HistoryRetentionPolicy(keepAll=true))
            assertEquals(2,result.histories);assertEquals(1,result.bookmarks);assertEquals(data(),repository.export(SessionStamp("7",1)))
            val again=repository.import(data(),SessionStamp("7",1),HistoryRetentionPolicy(keepAll=true));assertEquals(0,again.histories);assertEquals(0,again.bookmarks)
            assertEquals(90,db.listenDao().allHistory("8").single().positionMs);assertEquals("keep-original-queue",db.listenDao().resume("7")!!.payload)
        }finally{db.close()}
    }
    @Test fun newerLocalHistoryAliasesAndDeletionFencesWin()=runBlocking {
        val db=database();try{
            val store=store(db);store.write("7",OrganizerData(aliases=mapOf(bvid to "本机别名"),orders=mapOf(2L to emptyList())))
            db.listenDao().played(HistoryRow("7",bvid,1,"newer",1,null,99,2500))
            db.listenDao().deleteLive("7",33,2600)
            val repository=LocalTransferRepository(db,store,{SessionStamp("7",1)},{3000})
            repository.import(data(),SessionStamp("7",1),HistoryRetentionPolicy(keepAll=true))
            assertEquals(99,db.listenDao().allHistory("7").single().positionMs);assertTrue(db.listenDao().allLiveHistory("7").isEmpty())
            assertEquals("本机别名",store.read("7").aliases[bvid]);assertEquals(emptyList<String>(),store.read("7").orders[2])
        }finally{db.close()}
    }
    @Test fun accountChangeAtCommitRollsBackAllPortableRecords()=runBlocking {
        val db=database();try{
            val store=store(db);store.read("7");var checks=0
            val repository=LocalTransferRepository(db,store,{checks++;if(checks>=4)SessionStamp("8",2) else SessionStamp("7",1)},{3000})
            try{repository.import(data(),SessionStamp("7",1),HistoryRetentionPolicy(keepAll=true));fail("Identity change accepted")}catch(_:IllegalArgumentException){}
            assertTrue(db.listenDao().allHistory("7").isEmpty());assertTrue(db.listenDao().collections("7").isEmpty());assertTrue(store.read("7").aliases.isEmpty())
        }finally{db.close()}
    }
    @Test fun legacyOrganizerPreservesPendingAndBlocksImport()=runBlocking {
        val db=database();val dir=File(context.cacheDir,"transfer-test-${java.util.UUID.randomUUID()}");try{
            val old=OrganizerData(pending=FolderPending(FolderDraft(FolderAction.CREATE,title="fixture"),emptyList()),aliases=mapOf(bvid to "旧别名"))
            val file=OrganizerFileStore(dir);file.write("7",old);val store=RoomOrganizerStore(db.listenDao(),file)
            assertEquals(old,store.read("7"));assertEquals(old,RoomOrganizerStore(db.listenDao(),file).read("7"))
            try{LocalTransferRepository(db,store,{SessionStamp("7",1)},{3000}).import(data(),SessionStamp("7",1),HistoryRetentionPolicy(keepAll=true));fail("Pending operation ignored")}catch(_:IllegalArgumentException){}
            assertTrue(db.listenDao().allHistory("7").isEmpty());assertEquals(old,store.read("7"))
        }finally{db.close();dir.deleteRecursively()}
    }
    @Test fun actualTcpPairingEncryptedRecordsAndWrongCode()=runBlocking {
        val bytes=TransferCodec.encode(data());val code=WifiTransfer.randomCode()
        for(correct in listOf(true,false)){
            val server=ServerSocket(0,2,InetAddress.getLoopbackAddress())
            try{
                val sending=async(Dispatchers.IO){server.accept().use{socket->runCatching{WifiTransfer.serve(socket,bytes,code)}.isSuccess}}
                val result=withContext(Dispatchers.IO){Socket(InetAddress.getLoopbackAddress(),server.localPort).use{runCatching{WifiTransfer.receiveSocket(it,if(correct)code else if(code[0]=='A')"B"+code.drop(1) else "A"+code.drop(1))}}}
                if(correct){assertArrayEquals(bytes,result.getOrThrow());assertTrue(sending.await())}else{assertTrue(result.isFailure);assertFalse(sending.await())}
            }finally{server.close()}
        }
    }
    @Test fun stoppingSenderClosesConnectionAlreadyWaitingForPairing()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main);val sender=WifiTransfer(context,scope)
        try{
            withContext(Dispatchers.Main){sender.startSending(TransferCodec.encode(data()))}
            val address=sender.sending.value.address
            withContext(Dispatchers.IO){Socket(address.substringBefore(':'),address.substringAfter(':').toInt()).use{client->
                client.soTimeout=2000;client.getOutputStream().apply{write("GET /backup HTTP/1.1\r\n".toByteArray());flush()}
                delay(300);withContext(Dispatchers.Main){sender.stopSending()}
                val closed=try{client.getInputStream().read()==-1}catch(_:SocketException){true}
                assertTrue("Stopping sender closes the accepted connection",closed)
            }}
            assertFalse(sender.sending.value.active)
        }finally{withContext(Dispatchers.Main){sender.close()};scope.cancel()}
    }
    @Test fun installedOfficialAppHandlesBothExplicitRoutes(){
        for(page in OfficialAccountPage.entries){val intent=OfficialAccountLinks.intent(page);assertEquals("tv.danmaku.bili",intent.`package`);assertNotNull(intent.resolveActivity(context.packageManager))}
    }
    @Test fun wifiDiscoveryAndEncryptedConnectionUseActualWifiInterface()=runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main);val sender=WifiTransfer(context,scope);val receiver=WifiTransfer(context,scope)
        try{
            val payload=TransferCodec.encode(data())
            withContext(Dispatchers.Main){sender.startSending(payload);receiver.discover()}
            val state=sender.sending.value;assertTrue(state.active)
            val host=state.address.substringBefore(':');val port=state.address.substringAfter(':').toInt()
            val end=System.currentTimeMillis()+20000
            while(System.currentTimeMillis()<end&&receiver.peers.value.none{it.address==host&&it.port==port})delay(100)
            val peer=receiver.peers.value.firstOrNull{it.address==host&&it.port==port} ?: error("Wi-Fi NSD discovery did not find fixture sender")
            assertArrayEquals(payload,receiver.receive(peer,state.code))
        }finally{withContext(Dispatchers.Main){sender.close();receiver.close()};scope.cancel()}
    }
    @Test fun oldDatabaseMigrationPreservesHistoryAndOrganizerJournal()=runBlocking {
        val name="transfer-migration-${java.util.UUID.randomUUID()}.db"
        val schema=Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets.open("transfer-schema4.json").bufferedReader().use{it.readText()}).jsonObject["database"]!!.jsonObject
        val raw=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        raw.use{sql->
            schema["entities"]!!.jsonArray.forEach{entry->val entity=entry.jsonObject;val table=entity["tableName"]!!.jsonPrimitive.content
                sql.execSQL(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}",table))
                entity["indices"]?.jsonArray?.forEach{sql.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}",table))}
            }
            schema["setupQueries"]?.jsonArray?.forEach{sql.execSQL(it.jsonPrimitive.content)}
            sql.execSQL("INSERT INTO history (account,bvid,cid,title,part,sourceFolder,positionMs,playedAt,sourceJson,origin) VALUES ('7',?,1,'fixture',1,NULL,55,2000,NULL,'UNKNOWN')",arrayOf(bvid));sql.version=4
        }
        val db=Room.databaseBuilder(context,ListenDatabase::class.java,name).addMigrations(ListenDatabase.MIGRATION_4_5,ListenDatabase.MIGRATION_5_6).build()
        try{assertEquals(55,db.listenDao().allHistory("7").single().positionMs);store(db).write("7",OrganizerData(aliases=mapOf(bvid to "保留")));assertEquals("保留",store(db).read("7").aliases[bvid])}
        finally{db.close();context.deleteDatabase(name)}
    }
}
