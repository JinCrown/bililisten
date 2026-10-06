package app.bililisten.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.shared.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class M6OrganizerStorageTest {
    private val dir get()=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"m6-organizer-fixture")
    @Test fun interruptedIntentAndPreferencesReopenWithAccountIsolation()=runBlocking {
        val data=OrganizerData(FolderPending(FolderDraft(FolderAction.CREATE,title="fixture"),listOf(10)),
            OrganizeBatch(10,20,OrganizeAction.MOVE,listOf(OrganizeRow("BV1xx411c7mD","original",1,RowOutcome.RUNNING))),
            mapOf("BV1xx411c7mD" to "local"),mapOf(10L to listOf("BV1xx411c7mD")))
        try {
            OrganizerFileStore(dir).write("7",data)
            assertEquals(data,OrganizerFileStore(dir).read("7"))
            assertEquals(OrganizerData(),OrganizerFileStore(dir).read("8"))
            assertEquals(OrganizerData(),OrganizerFileStore(dir).read("guest"))
        }finally{dir.deleteRecursively()}
    }
    @Test fun corruptJournalBlocksInsteadOfPretendingNoPendingWrite()=runBlocking {
        try {
            dir.mkdirs();File(dir,"7.json").writeText("broken")
            try{OrganizerFileStore(dir).read("7");fail("Must not discard journal")}catch(_:PlatformFailure){}
        }finally{dir.deleteRecursively()}
    }
}
