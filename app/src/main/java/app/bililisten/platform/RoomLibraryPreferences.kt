package app.bililisten.platform

import app.bililisten.shared.*

/** Shares the organizing/migration lock; writes only the captured account's local payload. */
class RoomLibraryPreferences(private val store:OrganizerStore,private val organizer:Organizer,
    private val accounts:AccountRepository):LibraryPreferenceRepository {
    override suspend fun read(stamp:SessionStamp):LibraryPreferences {
        accounts.requireCurrent(stamp)
        return store.read(stamp.account).library.checked(stamp.account).also{accounts.requireCurrent(stamp)}
    }
    override suspend fun update(stamp:SessionStamp,change:(LibraryPreferences)->LibraryPreferences)=organizer.coordinateLocalData {
        accounts.requireCurrent(stamp);val data=store.read(stamp.account)
        val preferences=change(data.library).checked(stamp.account);accounts.requireCurrent(stamp)
        store.write(stamp.account,data.copy(library=preferences));accounts.requireCurrent(stamp);preferences
    }
}
