package app.bililisten.shared

data class FolderCreationOutcome(val pending:Boolean,val message:String)
interface FolderCreationRepository {
    suspend fun inspect(stamp:SessionStamp):FolderCreationOutcome
    suspend fun create(stamp:SessionStamp,title:String,private:Boolean):FolderCreationOutcome
    suspend fun reconcile(stamp:SessionStamp):FolderCreationOutcome
}
