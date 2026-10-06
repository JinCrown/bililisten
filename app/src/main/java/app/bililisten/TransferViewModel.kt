package app.bililisten

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bililisten.platform.*
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TransferUiState(val busy:Boolean=false,val preview:LocalTransfer?=null,val message:String="",val receiveMode:Boolean=false)
class TransferViewModel(application:Application):AndroidViewModel(application){
    private val app=application as ListenApplication
    private val mutable=MutableStateFlow(TransferUiState());val state=mutable.asStateFlow()
    private val repository=LocalTransferRepository(app.database,app.organizerStore,{app.accounts.session.value.stamp},app.clock::nowMs)
    val wifi=WifiTransfer(application,viewModelScope)
    private var preparedStamp:SessionStamp?=null
    private var exportStamp:SessionStamp?=null
    private var operation:Job?=null
    init{viewModelScope.launch{app.accounts.session.map{it.stamp}.distinctUntilChanged().drop(1).collect{
        val active=state.value.busy||state.value.preview!=null||state.value.receiveMode||wifi.sending.value.active||exportStamp!=null
        leave();if(active)mutable.value=TransferUiState(message="账号已变化，请重新开始迁移")
    }}}
    private fun run(action:suspend ()->Unit){
        if(state.value.busy)return
        mutable.update{it.copy(busy=true,message="")}
        operation=viewModelScope.launch{
            try{action()}
            catch(e:CancellationException){throw e}
            catch(e:Exception){mutable.update{it.copy(message=if(e is IllegalArgumentException)e.message ?: "迁移文件格式不正确" else "迁移未完成，请检查文件、Wi-Fi 或配对码；本机数据保留")}}
            finally{mutable.update{it.copy(busy=false)}}
        }
    }
    private suspend fun bytes(stamp:SessionStamp)=withContext(Dispatchers.IO){app.organizer.coordinateLocalData{TransferCodec.encode(repository.export(stamp))}}
    fun prepareExport(){exportStamp=app.accounts.session.value.stamp}
    fun export(uri:Uri)=run{
        val stamp=exportStamp ?: error("请重新选择导出位置")
        val bytes=bytes(stamp)
        withContext(Dispatchers.IO){require(uri.scheme=="content");app.contentResolver.openOutputStream(uri,"wt")?.use{it.write(bytes)} ?: error("文件无法保存")}
        mutable.update{it.copy(message="导出完成，把迁移文件带到新手机后导入")};exportStamp=null
    }
    private fun preview(data:LocalTransfer,stamp:SessionStamp){
        require(app.accounts.session.value.stamp==stamp){"账号已变化，请重新开始迁移"}
        require(data.account==stamp.account){"请先登录文件对应的 B 站账号；游客数据请在游客模式接收"}
        preparedStamp=stamp;mutable.update{it.copy(preview=data,message="请核对内容，再确认合并")}
    }
    fun read(uri:Uri)=run{
        preparedStamp=null;mutable.update{it.copy(preview=null)}
        val stamp=app.accounts.session.value.stamp
        val data=withContext(Dispatchers.IO){require(uri.scheme=="content");app.contentResolver.openInputStream(uri)?.use(TransferCodec::read) ?: error("文件无法打开")}
        preview(data,stamp)
    }
    fun startSending()=run{
        val stamp=app.accounts.session.value.stamp;val bytes=bytes(stamp)
        require(app.accounts.session.value.stamp==stamp){"账号已变化，请重新发送"};wifi.startSending(bytes)
        mutable.update{it.copy(receiveMode=false,preview=null)}
    }
    fun discover(){try{wifi.stopSending("");wifi.discover();mutable.update{it.copy(receiveMode=true,message="选择旧手机，输入其显示的配对码")}}catch(e:Exception){mutable.update{it.copy(message=e.message ?: "请连接同一 Wi-Fi")}}}
    fun receive(peer:TransferPeer,code:String)=run{
        preparedStamp=null;mutable.update{it.copy(preview=null)}
        val stamp=app.accounts.session.value.stamp
        val bytes=wifi.receive(peer,code);val data=withContext(Dispatchers.IO){TransferCodec.decode(bytes)}
        wifi.stopDiscovery();preview(data,stamp)
    }
    fun merge(done:()->Unit)=run{
        val data=state.value.preview ?: return@run;val stamp=preparedStamp ?: return@run
        val result=withContext(Dispatchers.IO){app.organizer.coordinateLocalData{repository.import(data,stamp,app.settings.current().historyPolicy())}}
        preparedStamp=null;mutable.update{it.copy(preview=null,message="合并完成：处理 ${result.histories} 条历史，新增 ${result.bookmarks} 个来源入口；收藏管理按较新的设置合并，已有别名保留")};done()
    }
    fun cancelPreview(){preparedStamp=null;mutable.update{it.copy(preview=null,message="已取消导入，本机数据保留")}}
    fun leave(){operation?.cancel();operation=null;wifi.close();preparedStamp=null;exportStamp=null;mutable.value=TransferUiState()}
    override fun onCleared(){wifi.close();super.onCleared()}
}
