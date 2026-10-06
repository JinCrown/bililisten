package app.bililisten.shared

import kotlin.test.*
import kotlinx.serialization.json.Json

class LibraryPreferencesTest {
    private val mine=SourceRef(SourceKind.OWN_FAVORITES,1,7)
    private val folder=SourceRef(SourceKind.PUBLIC_FAVORITES,1,8)
    private val season=SourceRef(SourceKind.UP_COLLECTION,1,8,CollectionKind.SEASON)
    @Test fun identityAndHiddenOrderSurviveRefreshNewSourcesAndRestore() {
        val prefs=LibraryPreferences().edit(LibrarySection.FOLLOWED,10){it.copy(hidden=setOf(folder),order=listOf(season,folder))}.checked("7")
        val next=folder.copy(id=2)
        assertEquals(listOf(season,next),prefs.apply(LibrarySection.FOLLOWED,listOf(folder,next,season)){it})
        assertEquals(listOf(season,folder,next),prefs.apply(LibrarySection.FOLLOWED,listOf(folder,next,season),true){it})
        val visible=prefs.edit(LibrarySection.FOLLOWED,10){it.copy(hidden=emptySet())}
        assertEquals(11,visible.layout(LibrarySection.FOLLOWED)!!.updatedAt)
        assertEquals(listOf(season,folder,next),visible.apply(LibrarySection.FOLLOWED,listOf(folder,next,season)){it})
        assertEquals(listOf(mine),prefs.apply(LibrarySection.MINE,listOf(mine)){it})
    }
    @Test fun newerImportWinsOnlyItsCategoryAndUnhideTombstoneWinsOlderHide() {
        val a=LibraryPreferences().edit(LibrarySection.FOLLOWED,20){it.copy(hidden=setOf(folder))}.edit(LibrarySection.MINE,50){it.copy(order=listOf(mine))}
        val b=LibraryPreferences().edit(LibrarySection.FOLLOWED,30){it.copy(hidden=emptySet(),order=listOf(season,folder))}.edit(LibrarySection.MINE,40){it.copy(hidden=setOf(mine))}
        val merged=a.merge(b);assertTrue(merged.layout(LibrarySection.FOLLOWED)!!.hidden.isEmpty());assertTrue(merged.layout(LibrarySection.MINE)!!.hidden.isEmpty())
        assertEquals(merged,merged.merge(b));assertEquals(merged,merged.merge(LibraryPreferences()))
        assertEquals(merged,Json.decodeFromString<LibraryPreferences>(Json.encodeToString(merged)))
    }
    @Test fun invalidOwnersTypesDuplicatesAndDatesAreRejected() {
        val invalid=listOf(LibraryLayout(LibrarySection.MINE,hidden=setOf(mine.copy(owner=8)),updatedAt=1),LibraryLayout(LibrarySection.MINE,order=listOf(folder),updatedAt=1),LibraryLayout(LibrarySection.FOLLOWED,order=listOf(folder,folder),updatedAt=1),LibraryLayout(LibrarySection.UP,hidden=setOf(folder),updatedAt=1),LibraryLayout(LibrarySection.MINE,updatedAt=0))
        invalid.forEach{assertFailsWith<IllegalArgumentException>{LibraryPreferences(listOf(it)).checked("7")}}
        assertFailsWith<IllegalArgumentException>{LibraryPreferences(listOf(LibraryLayout(LibrarySection.MINE,updatedAt=1),LibraryLayout(LibrarySection.MINE,updatedAt=2))).checked("7")}
    }
    @Test fun portableV2AndLegacyV1RespectAccountAndTimeBoundaries() {
        val prefs=LibraryPreferences().edit(LibrarySection.MINE,20){it.copy(hidden=setOf(mine))}
        val old=Json.decodeFromString<LocalTransfer>("""{"version":1,"createdAt":100,"account":"7"}""").checked()
        assertTrue(old.library.layouts.isEmpty())
        val data=LocalTransfer(createdAt=100,account="7",library=prefs).checked();assertEquals(data,Json.decodeFromString<LocalTransfer>(Json.encodeToString(data)).checked())
        assertFailsWith<IllegalArgumentException>{data.copy(account="8").checked()}
        assertFailsWith<IllegalArgumentException>{data.copy(createdAt=19).checked()}
        assertFailsWith<IllegalArgumentException>{data.copy(version=1).checked()}
        assertFailsWith<IllegalArgumentException>{data.copy(version=3).checked()}
        assertTrue(Json.decodeFromString<OrganizerData>("{}").library.layouts.isEmpty())
    }
}
