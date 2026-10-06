package app.bililisten.platform

import app.bililisten.shared.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TransferCodecTest {
    private val data=LocalTransfer(createdAt=2000,account="7",history=listOf(LocalHistoryEntry("7",VideoRef("BV1Yt411u7UD",1,1),"示例",null,33,1000)),bookmarks=listOf(TransferBookmark(SourceRef(SourceKind.PUBLIC_FAVORITES,1,9),"收藏","作者",2)))
    @Test fun roundTripHasPortableRecordsAndNoLoginOrPendingOperationFields(){
        val bytes=TransferCodec.encode(data);assertEquals(data,TransferCodec.read(bytes.inputStream()))
        val text=bytes.decodeToString();for(field in listOf("SESSDATA","bili_jct","password","pending","mediaUrl"))assertFalse(text.contains(field))
    }
    @Test fun damagedChecksumAndUnsupportedVersionAreRejected(){
        val root=Json.parseToJsonElement(TransferCodec.encode(data).decodeToString()).jsonObject
        for(modified in listOf(root+mapOf("sha256" to JsonPrimitive("0".repeat(64))),root+mapOf("version" to JsonPrimitive(99)))){
            try{TransferCodec.decode(JsonObject(modified).toString().toByteArray());fail("Invalid envelope accepted")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun wrongAccountDuplicatesAndInvalidReferencesAreRejected(){
        for(bad in listOf(data.copy(account="8"),data.copy(history=data.history+data.history),data.copy(aliases=mapOf("not-bvid" to "bad")),data.copy(orders=mapOf(-1L to emptyList())))){
            try{TransferCodec.encode(bad);fail("Invalid records accepted")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun oversizedAndDeeplyNestedInputIsBounded(){
        for(bytes in listOf(ByteArray(TransferCodec.MAX_BYTES+1),(("[".repeat(100))+("]".repeat(100))).toByteArray())){
            try{TransferCodec.read(bytes.inputStream());fail("Unbounded input accepted")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun wifiPairingNormalizesGroupsAndRestrictsAddresses(){
        val code=WifiTransfer.randomCode();assertEquals(12,code.length);assertEquals(code,WifiTransfer.normalize(code.lowercase().chunked(4).joinToString("-")))
        assertTrue(WifiTransfer.localAddress("192.168.1.1"));assertTrue(WifiTransfer.localAddress("172.16.0.1"))
        for(host in listOf("8.8.8.8","127.0.0.1","192.168.1.999","localhost","192.168.1.2/evil"))assertFalse(WifiTransfer.localAddress(host))
    }
    @Test fun managementPayloadRoundTripAndLegacyFileDecode() {
        val source=data.bookmarks.single().source
        val prefs=LibraryPreferences().edit(LibrarySection.FOLLOWED,1900){it.copy(hidden=setOf(source),order=listOf(source))}
        val managed=data.copy(library=prefs);assertEquals(managed,TransferCodec.decode(TransferCodec.encode(managed)))
        val legacy=data.copy(version=1);assertEquals(legacy,TransferCodec.decode(TransferCodec.encode(legacy)))
    }

}
