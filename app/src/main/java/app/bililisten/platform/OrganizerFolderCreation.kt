package app.bililisten.platform

import app.bililisten.shared.*

/** Reuses the single-write journal and read-only reconciliation of B站 folder creation. */
class OrganizerFolderCreation(private val organizer:Organizer,private val store:OrganizerStore,
    private val accounts:AccountRepository):FolderCreationRepository {
    private fun outcome(data:OrganizerData)=FolderCreationOutcome(data.pending!=null||data.batch?.rows?.any{it.outcome in setOf(RowOutcome.RUNNING,RowOutcome.UNKNOWN)}==true,data.result)
    override suspend fun inspect(stamp:SessionStamp):FolderCreationOutcome {
        accounts.requireCurrent(stamp);val data=store.read(stamp.account);accounts.requireCurrent(stamp)
        return outcome(data).let{if(it.pending)it else it.copy(message="")}
    }
    override suspend fun create(stamp:SessionStamp,title:String,private:Boolean):FolderCreationOutcome {
        accounts.requireCurrent(stamp)
        return outcome(organizer.folder(FolderDraft(FolderAction.CREATE,title=title,private=private))).also{accounts.requireCurrent(stamp)}
    }
    override suspend fun reconcile(stamp:SessionStamp):FolderCreationOutcome {
        accounts.requireCurrent(stamp);return outcome(organizer.reconcile()).also{accounts.requireCurrent(stamp)}
    }
}
