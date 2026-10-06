package app.bililisten.platform

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.shared.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M3StorageTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val source=ContentSource(SourceRef(SourceKind.PUBLIC_FAVORITES,42,8),"fixture","owner",2)
    @Test fun bookmarksAndFollowIntentSurviveReopenAndStayAccountIsolated()=runBlocking {
        val name="m3-local-fixture.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,ListenDatabase::class.java,name).build()
        try {
            val store=RoomStores(db.listenDao())
            store.saveCollection(CollectionSnapshot("7",source.ref,listOf(ContentItem("BV1xx411c7mD","fixture")),false,100,100,source.title,source.ownerName,2))
            store.bookmark("7",source,true)
            val follow=SourceRef(SourceKind.UP_COLLECTION,51,8,CollectionKind.SEASON)
            store.saveMutation(MutationRecord("op","7",0,0,true,100,followSource=follow))
            db.close();db=Room.databaseBuilder(context,ListenDatabase::class.java,name).build()
            val reopened=RoomStores(db.listenDao())
            assertEquals("fixture",reopened.bookmarks("7").single().title)
            assertEquals(1,reopened.bookmarks("7").single().items.size)
            assertTrue(reopened.bookmarks("8").isEmpty());assertNull(reopened.mutation("8"))
            assertEquals(follow,reopened.mutation("7")!!.followSource)
            reopened.bookmark("7",source,false);assertTrue(reopened.bookmarks("7").isEmpty())
            assertEquals(1,reopened.collection("7",source.ref)!!.items.size)
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun oldCollectionPayloadLoadsAndLateRevisionCannotOverwriteNewData()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ListenDatabase::class.java).build()
        try {
            val dao=db.listenDao();val key=SourceCodec.encode(source.ref)
            dao.collection(CollectionRow("7",key,"""{"account":"7","source":$key,"items":[],"complete":true,"refreshedAt":100,"revision":5}""",5))
            val store=RoomStores(dao);assertEquals("",store.collection("7",source.ref)!!.title)
            store.saveCollection(CollectionSnapshot("7",source.ref,listOf(ContentItem("BV1xx411c7mD","new")),true,200,10))
            store.saveCollection(CollectionSnapshot("7",source.ref,emptyList(),false,150,9))
            assertEquals("new",store.collection("7",source.ref)!!.items.single().title)
        }finally{db.close()}
    }
}
