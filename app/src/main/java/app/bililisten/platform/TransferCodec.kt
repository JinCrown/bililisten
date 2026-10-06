package app.bililisten.platform

import app.bililisten.shared.LocalTransfer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.security.MessageDigest

object TransferCodec {
    const val MAX_BYTES=16*1024*1024
    @Serializable private data class Envelope(val format:String="bili-listen-transfer",val version:Int=1,val payload:String,val sha256:String)
    private val json=Json { encodeDefaults=true }
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    fun encode(data:LocalTransfer):ByteArray {
        val payload=json.encodeToString(data.checked())
        return json.encodeToString(Envelope(payload=payload,sha256=hash(payload.toByteArray(Charsets.UTF_8)))).toByteArray(Charsets.UTF_8).also{require(it.size<=MAX_BYTES){"记录过多，迁移文件超过 16MB"}}
    }
    fun read(input:InputStream):LocalTransfer {
        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=MAX_BYTES){"迁移文件超过 16MB"};out.write(buffer,0,n)}
        return decode(out.toByteArray())
    }
    private fun boundedJson(text:String){
        var depth=0;var quoted=false;var escaped=false
        for(c in text){if(quoted){if(escaped)escaped=false else if(c=='\\')escaped=true else if(c=='"')quoted=false}
            else when(c){'"'->quoted=true;'[','{'->{depth++;require(depth<=32){"迁移文件格式不正确"}};']','}'->depth--}}
        require(depth==0&&!quoted){"迁移文件不完整"}
    }
    fun decode(bytes:ByteArray):LocalTransfer {
        require(bytes.size<=MAX_BYTES);val text=bytes.toString(Charsets.UTF_8);boundedJson(text)
        val envelope=json.decodeFromString<Envelope>(text)
        require(envelope.format=="bili-listen-transfer"&&envelope.version==1){"迁移文件版本不受支持"}
        require(envelope.sha256.matches(Regex("[0-9a-f]{64}"))&&hash(envelope.payload.toByteArray(Charsets.UTF_8))==envelope.sha256){"迁移文件已损坏或被修改"}
        boundedJson(envelope.payload)
        return json.decodeFromString<LocalTransfer>(envelope.payload).checked()
    }
}
