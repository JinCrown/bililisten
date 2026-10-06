package app.bililisten.platform

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class M0StorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun versionOneHistoryAndResumeSurviveTypedSourceMigration() = runBlocking {
        val name = "source-migration-test.db"
        context.deleteDatabase(name)
        val legacy = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        legacy.execSQL("CREATE TABLE resume (account TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
        legacy.execSQL("CREATE TABLE history (account TEXT NOT NULL, bvid TEXT NOT NULL, cid INTEGER NOT NULL, title TEXT NOT NULL, part INTEGER NOT NULL, sourceFolder INTEGER, positionMs INTEGER NOT NULL, playedAt INTEGER NOT NULL, PRIMARY KEY(account,bvid,cid))")
        legacy.execSQL("INSERT INTO resume VALUES ('7','legacy-snapshot')")
        legacy.execSQL("INSERT INTO history VALUES ('7','BV1xx411c7mD',3,'fixture',2,42,43210,1)")
        legacy.version = 1; legacy.close()
        val db = Room.databaseBuilder(context, ListenDatabase::class.java, name).addMigrations(ListenDatabase.MIGRATION_1_2, ListenDatabase.MIGRATION_2_3, ListenDatabase.MIGRATION_3_4,ListenDatabase.MIGRATION_4_5,ListenDatabase.MIGRATION_5_6).build()
        try {
            val dao = db.listenDao()
            assertEquals("legacy-snapshot", dao.resume("7")!!.payload)
            val row = dao.history("7").first().single()
            assertEquals(43210L, row.positionMs)
            assertEquals(app.bililisten.shared.SourceRef(app.bililisten.shared.SourceKind.OWN_FAVORITES,42,7), row.entry("restored").source)
            val source = app.bililisten.shared.SourceRef(app.bililisten.shared.SourceKind.UP_COLLECTION,42,8,app.bililisten.shared.CollectionKind.SEASON)
            dao.played(row.copy(sourceFolder = null, sourceJson = app.bililisten.shared.SourceCodec.encode(source)))
            assertEquals(source, dao.history("7").first().single().entry("new").source)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun vaultEncryptsRestoresRejectsCorruptionAndClearsKey() {
        // Dedicated namespace: never reads or changes the user's real session.
        val vault = CredentialVault(context, "storage-test")
        val file = File(context.noBackupFilesDir, "storage-test.enc")
        try {
            vault.save("SESSDATA=non-secret-fixture; bili_jct=fixture")
            assertFalse(file.readText().contains("SESSDATA"))
            val restored = CredentialVault(context, "storage-test").apply { restore() }
            assertEquals(vault.read(), restored.read())
            vault.save("SESSDATA=replaced-fixture")
            assertEquals(vault.read(), CredentialVault(context, "storage-test").apply { restore() }.read())
            file.writeText("corrupt")
            val invalid = CredentialVault(context, "storage-test").apply { restore() }
            assertNull(invalid.read()); assertFalse(file.exists())
            vault.clear()
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias("bili-listen-m0-storage-test"))
        } finally { vault.clear() }
    }
    @Test fun deletingLocalHistoryPreservesOtherAccountAndResume() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ListenDatabase::class.java).build()
        try { val dao = db.listenDao()
            val a = HistoryRow("test-a", "BV1xx411c7mD", 62131, "fixture", 1, 42, 12345, 1)
            dao.played(a); dao.played(a.copy(account="test-b")); dao.save(ResumeRow("test-a","snapshot-fixture"))
            dao.deleteHistory("test-a",a.bvid,a.cid)
            assertTrue(dao.history("test-a").first().isEmpty()); assertEquals(1,dao.history("test-b").first().size)
            dao.played(a); dao.clearHistory("test-a")
            assertTrue(dao.history("test-a").first().isEmpty()); assertEquals("snapshot-fixture",dao.resume("test-a")!!.payload)
        } finally { db.close() }
    }
}
