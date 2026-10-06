package app.bililisten.shared

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class OfflineAudioTest {
    private val ref=VideoRef("BV1xx411c7mD",7,1)
    private val track=AudioTrack(30280,"https://sample.bilivideo.com/audio","audio/mp4","mp4a.40.2",192000)
    private val video=Video(ref.bvid,1,"audio",listOf(VideoPart(7,1,"part",1)),downloadAllowed=true)
    private val probe=AudioProbe(listOf(track),false,1000,true)
    @Test fun defaultStorageDoesNotEnableWritesOrMeteredTransfers(){val s=Json.decodeFromString<UserSettings>("{}");assertEquals(StorageSettings(),s.storage);assertFalse(s.storage.automaticAudio);assertFalse(s.storage.downloadOnMetered)}
    @Test fun playbackConsentDoesNotAuthorizeCachingOrDownloading(){val s=UserSettings(mobilePlayback=true);assertFalse(OfflineAudioRules.networkAllowed(NetworkKind.METERED,s.storage.downloadOnMetered));assertFalse(s.storage.cacheOnMetered)}
    @Test fun networkOptionsAreIndependent(){val s=StorageSettings(downloadOnMetered=true);assertTrue(OfflineAudioRules.networkAllowed(NetworkKind.METERED,s.downloadOnMetered));assertFalse(OfflineAudioRules.networkAllowed(NetworkKind.METERED,s.cacheOnMetered));assertFalse(OfflineAudioRules.networkAllowed(NetworkKind.OFFLINE,true))}
    @Test fun capacityIsBounded(){assertFails{StorageSettings(cacheMiB=0).checked()};assertFails{StorageSettings(cacheMiB=2049).checked()};StorageSettings(cacheMiB=32).checked()}
    @Test fun ordinaryExplicitFullAudioIsAdmitted(){assertTrue(OfflineAudioRules.admitted(video,ref,probe,track))}
    @Test fun unknownOrDeniedDownloadFlagIsRejected(){listOf(null,false).forEach{assertFalse(OfflineAudioRules.admitted(video.copy(downloadAllowed=it),ref,probe,track))}}
    @Test fun paidPreviewOrDrmAudioIsRejected(){assertFalse(OfflineAudioRules.admitted(video.copy(paidContent=true),ref,probe,track));assertFalse(OfflineAudioRules.admitted(video,ref,probe.copy(offlineFull=false),track))}
    @Test fun noMixedOrHighTierFallback(){assertFalse(OfflineAudioRules.admitted(video,ref,probe.copy(tracks=emptyList(),mixedAvailable=true),track));val flac=track.copy(id=30251,codec="flac");assertFalse(OfflineAudioRules.admitted(video,ref,probe.copy(tracks=listOf(flac)),flac))}
    @Test fun partIdentityMustMatch(){assertFalse(OfflineAudioRules.admitted(video,ref.copy(cid=8),probe,track));assertFalse(OfflineAudioRules.admitted(video,ref.copy(part=2),probe,track))}
    @Test fun fullDurationMustMatchEvenWhenPreviewFlagIsMissing(){assertFalse(OfflineAudioRules.admitted(video,ref,probe.copy(durationMs=30000),track));assertFalse(OfflineAudioRules.admitted(video.copy(parts=listOf(VideoPart(7,1,"unknown"))),ref,probe,track));assertFalse(OfflineAudioRules.admitted(video,ref,probe.copy(preview=true),track));assertFalse(OfflineAudioRules.admitted(video,ref,probe.copy(drm=true),track))}
    @Test fun completeAudioHasNoClockOrLoginGenerationExpiry(){val r=record();assertTrue(OfflineAudioRules.usable(r,"123"));assertTrue(r.entry().offline);assertFalse(OfflineAudioRules.usable(r,"guest"));assertFalse(OfflineAudioRules.usable(r,"456"));assertFalse(OfflineAudioRules.usable(r.copy(phase=DownloadPhase.PAUSED),"123"))}
    private fun record()=AudioDownload("a","123",ref,"audio",phase=DownloadPhase.COMPLETE,bytes=10,digest="a".repeat(64))
    @Test fun metadataCannotSupplyPathsOrUncheckedCompleteFiles(){assertFails{record().copy(id="../private").checked()};assertFails{record().copy(digest="").checked()};assertFails{record().copy(bytes=OfflineAudioRules.MAX_BYTES+1).checked()}}
    private class Disk(initial:String="",var space:Long=Long.MAX_VALUE):AudioTransferDisk {
        var data=initial.encodeToByteArray();var resets=0
        override fun size()=data.size.toLong();override fun reset(){data=byteArrayOf();resets++}
        override fun append(buffer:ByteArray,count:Int){data+=buffer.copyOf(count)};override fun freeBytes()=space
    }
    private class Body(val text:String,override val status:Int=200,override val start:Long?=null,override val total:Long?=text.length.toLong(),override val etag:String?="\"v1\"",val chunk:Int=3):AudioResponse {
        var at=0;var closed=false
        override suspend fun read(buffer:ByteArray):Int {if(at==text.length)return -1;val b=text.substring(at,minOf(text.length,at+chunk)).encodeToByteArray();b.copyInto(buffer);at+=b.size;return b.size}
        override fun close(){closed=true}
    }
    @Test fun fullCopyTracksUnknownLengthWithoutFalsePercentage()=runTest {val d=Disk();val b=Body("abcdef",total=null);val p=AudioTransfer().copy(d,null,{_,_->b},{true}){};assertNull(p.total);assertEquals("abcdef",d.data.decodeToString());assertTrue(b.closed)}
    @Test fun matching206ResumesExactly()=runTest {val d=Disk("abc");val b=Body("def",206,3,6);AudioTransfer().copy(d,"\"v1\"",{offset,_->assertEquals(3L,offset);b},{true}){};assertEquals("abcdef",d.data.decodeToString());assertEquals(0,d.resets)}
    @Test fun ignoredRangeRestartsInsteadOfAppending()=runTest {val d=Disk("abc");val first=Body("abcdef");val offsets=mutableListOf<Long>();AudioTransfer().copy(d,"\"v1\"",{offset,_->offsets+=offset;if(offset>0)first else Body("abcdef")},{true}){};assertEquals(listOf(3L,0L),offsets);assertEquals("abcdef",d.data.decodeToString());assertTrue(first.closed)}
    @Test fun changedEtagOrWrongRangeCannotResume(){assertFalse(OfflineAudioRules.resumeResponse(3,206,3,"a","b"));assertFalse(OfflineAudioRules.resumeResponse(3,206,2,"a","a"));assertFalse(OfflineAudioRules.resumeResponse(3,206,3,"W/a","W/a"));assertFalse(OfflineAudioRules.resumeResponse(3,206,3,null,null))}
    @Test fun truncatedAudioIsNeverComplete()=runTest {val d=Disk();val b=Body("abc",total=6);assertFailsWith<TransferFailure>{AudioTransfer().copy(d,null,{_,_->b},{true}){}};assertEquals(3L,d.size());assertTrue(b.closed)}
    @Test fun noSpaceStopsBeforeWritingAndPreservesPartial()=runTest {val d=Disk("abc",OfflineAudioRules.RESERVE_BYTES);val b=Body("def",206,3,6);assertFailsWith<TransferFailure>{AudioTransfer().copy(d,"\"v1\"",{_,_->b},{true}){}};assertEquals("abc",d.data.decodeToString());assertTrue(b.closed)}
    @Test fun networkOrIdentityChangeStopsNewWrites()=runTest {val d=Disk();val b=Body("abcdef");var allowed=true;assertFailsWith<TransferFailure>{AudioTransfer().copy(d,null,{_,_->b},{allowed}){if(it.bytes>0)allowed=false}};assertEquals(3L,d.size());assertTrue(b.closed)}
    @Test fun oversizeIsRejectedBeforeAnyBytes()=runTest {val d=Disk();val b=Body("abc",total=OfflineAudioRules.MAX_BYTES+1);assertFailsWith<TransferFailure>{AudioTransfer().copy(d,null,{_,_->b},{true}){}};assertEquals(0L,d.size());assertTrue(b.closed)}
}
