package app.bililisten.platform

import android.content.Context
import android.net.*
import android.net.nsd.*
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.*
import java.security.*
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.*

data class TransferPeer(val name:String,val address:String,val port:Int)
data class WifiSendState(val code:String="",val address:String="",val active:Boolean=false,val message:String="")

/** Ephemeral, Wi-Fi-bound server. No credentials or plaintext records travel on the wire. */
class WifiTransfer(private val context:Context,private val scope:CoroutineScope) {
    private val nsd=context.getSystemService(NsdManager::class.java)
    private val connectivity=context.getSystemService(ConnectivityManager::class.java)
    private val mutableSend=MutableStateFlow(WifiSendState());val sending=mutableSend.asStateFlow()
    private val mutablePeers=MutableStateFlow<List<TransferPeer>>(emptyList());val peers=mutablePeers.asStateFlow()
    private var server:ServerSocket?=null;private var sendJob:Job?=null;private var registration:NsdManager.RegistrationListener?=null
    private var discovery:NsdManager.DiscoveryListener?=null
    private var discoveryToken=0L
    @Volatile private var receivingSocket:Socket?=null
    @Volatile private var sendingSocket:Socket?=null
    private fun wifi():Pair<Network,Inet4Address> {
        for(n in connectivity.allNetworks){
            val capabilities=connectivity.getNetworkCapabilities(n) ?: continue
            if(!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue
            val address=connectivity.getLinkProperties(n)?.linkAddresses?.map{it.address}?.filterIsInstance<Inet4Address>()?.firstOrNull{localAddress(it.hostAddress!!)} ?: continue
            return n to address
        }
        error("请连接 Wi-Fi，并让两台手机使用同一网络")
    }
    fun startSending(bytes:ByteArray){
        stopSending();stopDiscovery();require(bytes.size<=TransferCodec.MAX_BYTES)
        val (network,address)=wifi();val socket=ServerSocket().apply{reuseAddress=true;bind(InetSocketAddress(address,0),4);soTimeout=1000};server=socket
        val code=randomCode();mutableSend.value=WifiSendState(code,"${address.hostAddress}:${socket.localPort}",true,"等待新手机连接 · 配对码 5 分钟内有效")
        val info=NsdServiceInfo().apply{serviceName="哔哩听视频-${Build.MODEL.take(24)}";serviceType=SERVICE;port=socket.localPort;if(Build.VERSION.SDK_INT>=33)this.network=network}
        val listener=object:NsdManager.RegistrationListener {
            override fun onServiceRegistered(info:NsdServiceInfo){}
            override fun onRegistrationFailed(info:NsdServiceInfo,error:Int){if(registration===this)mutableSend.value=mutableSend.value.copy(message="自动发现不可用；可在新手机手动输入连接地址")}
            override fun onServiceUnregistered(info:NsdServiceInfo){}
            override fun onUnregistrationFailed(info:NsdServiceInfo,error:Int){}
        };registration=listener
        try{nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,listener)}catch(_:Exception){mutableSend.value=mutableSend.value.copy(message="可在新手机手动输入连接地址")}
        sendJob=scope.launch(Dispatchers.IO){
            val deadline=android.os.SystemClock.elapsedRealtime()+300000;var failures=0
            try {
                while(isActive&&android.os.SystemClock.elapsedRealtime()<deadline&&failures<5){
                    val client=try{socket.accept()}catch(_:SocketTimeoutException){continue}
                    sendingSocket=client
                    if(!isActive){client.close();if(sendingSocket===client)sendingSocket=null;return@launch}
                    client.use{
                        try{serve(it,bytes,code);withContext(Dispatchers.Main){stopSending("发送完成，请在新手机预览并确认导入")};return@launch}
                        catch(_:Exception){failures++}
                        finally{if(sendingSocket===client)sendingSocket=null}
                    }
                }
                withContext(Dispatchers.Main){stopSending(if(failures>=5)"配对失败次数过多，请重新发送" else "配对码已过期，请重新发送")}
            }catch(_:Exception){if(isActive)withContext(Dispatchers.Main){stopSending("连接中断，请重新发送")}}
        }
    }
    fun stopSending(message:String="已停止发送"){
        sendJob?.cancel();sendJob=null;runCatching{server?.close()};server=null
        runCatching{sendingSocket?.close()};sendingSocket=null
        registration?.let{runCatching{nsd.unregisterService(it)}};registration=null
        mutableSend.value=WifiSendState(message=message)
    }
    fun discover(){
        stopDiscovery();wifi();mutablePeers.value=emptyList();val token=discoveryToken
        val listener=object:NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type:String){}
            override fun onDiscoveryStopped(type:String){}
            override fun onStartDiscoveryFailed(type:String,error:Int){if(discoveryToken==token)stopDiscovery()}
            override fun onStopDiscoveryFailed(type:String,error:Int){}
            override fun onServiceLost(info:NsdServiceInfo){if(discoveryToken==token)mutablePeers.update{list->list.filter{it.name!=info.serviceName}}}
            @Suppress("DEPRECATION") override fun onServiceFound(info:NsdServiceInfo){
                if(!info.serviceType.startsWith(SERVICE.trimEnd('.')))return
                nsd.resolveService(info,object:NsdManager.ResolveListener {
                    override fun onResolveFailed(info:NsdServiceInfo,error:Int){}
                    override fun onServiceResolved(info:NsdServiceInfo){
                        val host=info.host?.hostAddress ?: return
                        if(discoveryToken!=token||!localAddress(host)||info.port !in 1024..65535)return
                        mutablePeers.update{list->(list.filter{it.name!=info.serviceName}+TransferPeer(info.serviceName,host,info.port)).take(20)}
                    }
                })
            }
        }
        discovery=listener;nsd.discoverServices(SERVICE,NsdManager.PROTOCOL_DNS_SD,listener)
    }
    fun stopDiscovery(){discoveryToken++;discovery?.let{runCatching{nsd.stopServiceDiscovery(it)}};discovery=null;mutablePeers.value=emptyList()}
    suspend fun receive(peer:TransferPeer,rawCode:String):ByteArray=withContext(Dispatchers.IO){
        require(localAddress(peer.address)&&peer.port in 1024..65535){"请输入旧手机显示的局域网地址"}
        val network=wifi().first
        network.socketFactory.createSocket().use { socket->
            receivingSocket=socket
            try{currentCoroutineContext().ensureActive();socket.connect(InetSocketAddress(peer.address,peer.port),8000);receiveSocket(socket,rawCode)}finally{if(receivingSocket===socket)receivingSocket=null}
        }
    }
    fun close(){runCatching{receivingSocket?.close()};receivingSocket=null;stopSending("");stopDiscovery()}
    companion object {
        const val SERVICE="_bililisten._tcp."
        private const val ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        fun randomCode():String=SecureRandom().let{r->(1..12).map{ALPHABET[r.nextInt(ALPHABET.length)]}.joinToString("")}
        fun normalize(code:String)=code.filterNot{it=='-'||it.isWhitespace()}.uppercase().also{require(it.length==12&&it.all{c->c in ALPHABET}){"请输入旧手机显示的 12 位配对码"}}
        fun localAddress(address:String):Boolean {
            val n=address.split('.').map{it.toIntOrNull() ?: return false};if(n.size!=4||n.any{it !in 0..255})return false
            return n[0]==10||n[0]==192&&n[1]==168||n[0]==172&&n[1] in 16..31||n[0]==169&&n[1]==254
        }
        private fun key(code:String,label:String)=MessageDigest.getInstance("SHA-256").digest((label+normalize(code)).toByteArray())
        private fun proof(code:String,nonce:String)=Mac.getInstance("HmacSHA256").run{init(SecretKeySpec(key(code,"pair:"),"HmacSHA256"));doFinal(nonce.toByteArray()).joinToString(""){"%02x".format(it)}}
        private fun readHeaders(input:java.io.InputStream):List<String>{
            val out=java.io.ByteArrayOutputStream();var tail=""
            while(out.size()<8192){val b=input.read();require(b>=0);out.write(b);tail=(tail+b.toChar()).takeLast(4);if(tail=="\r\n\r\n")return out.toString("US-ASCII").split("\r\n").filter{it.isNotEmpty()}}
            error("连接数据格式不正确")
        }
        private fun encrypt(bytes:ByteArray,code:String,requestNonce:String):ByteArray {
            val nonce=ByteArray(12).also{SecureRandom().nextBytes(it)}
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key(code,"data:$requestNonce:"),"AES"),GCMParameterSpec(128,nonce))
            return nonce+cipher.doFinal(bytes)
        }
        internal fun serve(socket:Socket,bytes:ByteArray,code:String){
            socket.soTimeout=10000
            val headers=readHeaders(socket.getInputStream());require(headers.first()=="GET /backup HTTP/1.1")
            val map=headers.drop(1).associate{it.substringBefore(':').lowercase() to it.substringAfter(':').trim()}
            val nonce=map["x-nonce"].orEmpty();require(nonce.matches(Regex("[0-9a-f]{32}")))
            require(MessageDigest.isEqual(proof(code,nonce).toByteArray(),map["x-proof"].orEmpty().toByteArray())){"配对码不正确"}
            val payload=encrypt(bytes,code,nonce);socket.getOutputStream().apply{
                write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray());write(payload);flush()
            }
        }
        internal fun receiveSocket(socket:Socket,rawCode:String):ByteArray {
            socket.soTimeout=20000;val code=normalize(rawCode);val nonce=ByteArray(16).also{SecureRandom().nextBytes(it)}.joinToString(""){"%02x".format(it)}
            socket.getOutputStream().apply{write("GET /backup HTTP/1.1\r\nX-Nonce: $nonce\r\nX-Proof: ${proof(code,nonce)}\r\n\r\n".toByteArray());flush()}
            val input=socket.getInputStream();val headers=readHeaders(input);require(headers.first()=="HTTP/1.1 200 OK")
            val length=headers.firstOrNull{it.startsWith("Content-Length:") }?.substringAfter(':')?.trim()?.toIntOrNull() ?: error("连接数据格式不正确")
            require(length in 28..TransferCodec.MAX_BYTES+28);val payload=ByteArray(length);var read=0
            while(read<length){val n=input.read(payload,read,length-read);require(n>0){"连接中断，数据未接收完整"};read+=n}
            val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key(code,"data:$nonce:"),"AES"),GCMParameterSpec(128,payload.copyOfRange(0,12)))
            return cipher.doFinal(payload,12,payload.size-12)
        }
    }
}
