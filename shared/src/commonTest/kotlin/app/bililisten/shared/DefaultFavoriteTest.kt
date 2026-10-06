package app.bililisten.shared

import kotlin.test.*
import kotlinx.serialization.json.Json

class DefaultFavoriteTest {
    @Test fun oldSettingsAndAccountPreferencesRoundTrip() {
        val old=Json.decodeFromString<UserSettings>("""{"theme":"LIGHT"}""").checked()
        assertTrue(old.defaultFavoriteFolders.isEmpty())
        val next=old.copy(defaultFavoriteFolders=mapOf("7" to DefaultFavoriteFolder(9,"音乐"),"8" to DefaultFavoriteFolder(10,"随手收藏"))).checked()
        assertEquals(next,Json.decodeFromString<UserSettings>(Json.encodeToString(next)).checked())
    }
    @Test fun guestAndInvalidFolderCannotBecomeAccountDefault() {
        for((account,folder) in listOf("guest" to DefaultFavoriteFolder(9,"音乐"),"7" to DefaultFavoriteFolder(0,"音乐"),"7" to DefaultFavoriteFolder(9," "))) {
            assertFailsWith<IllegalArgumentException>{UserSettings(defaultFavoriteFolders=mapOf(account to folder)).checked()}
        }
    }
}
