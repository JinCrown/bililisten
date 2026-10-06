package app.bililisten.storage

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import app.bililisten.ListenApplication
import app.bililisten.playback.PlaybackNetworkException
import app.bililisten.shared.PlaybackIssue

data class OfflineFilePermit(val id:String)

@UnstableApi class StorageDataSource(private val app:ListenApplication,private val remote:DataSource.Factory):DataSource {
    private var delegate:DataSource?=null
    private var local:OfflineFilePermit?=null
    private val listeners=mutableListOf<TransferListener>()
    override fun addTransferListener(listener:TransferListener){listeners+=listener;delegate?.addTransferListener(listener)}
    private fun checkLocal(){local?.let{try{app.downloads.playable(it.id)}catch(_:Exception){throw PlaybackNetworkException(PlaybackIssue.LOCAL_FILE)}}}
    override fun open(spec:DataSpec):Long {
        local=spec.customData as? OfflineFilePermit
        if(spec.uri.scheme=="file" && local==null)throw PlaybackNetworkException(PlaybackIssue.LOCAL_FILE)
        checkLocal()
        local?.let { if(spec.uri!=android.net.Uri.fromFile(app.downloads.playable(it.id)))throw PlaybackNetworkException(PlaybackIssue.LOCAL_FILE) }
        val data=if(local!=null)FileDataSource() else remote.createDataSource()
        delegate=data;listeners.forEach(data::addTransferListener);return data.open(spec)
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {checkLocal();return requireNotNull(delegate).read(buffer,offset,length)}
    override fun getUri()=delegate?.uri
    override fun getResponseHeaders()=delegate?.responseHeaders ?: emptyMap()
    override fun close(){try{delegate?.close()}finally{delegate=null;local=null}}
}
