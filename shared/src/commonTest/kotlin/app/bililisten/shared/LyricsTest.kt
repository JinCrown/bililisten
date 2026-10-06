package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsTest {
    private val video=VideoRef("BV1xx411c7mD",1,1)
    private val candidate=LyricsCandidate(1,"fixture song","fixture artist","fixture album",5000)
    private val document=LyricsDocument(candidate,"测试文本",listOf(SubtitleCue(1000,3000,"测试文本")))
    private class Accounts:AccountRepository {
        override val session=MutableStateFlow(AccountSession(SessionStamp("7",1),SessionStatus.AUTHENTICATED,Account(7,"fixture",Membership.VIP)))
        override suspend fun verify()=session.value.account
        override suspend fun accept(cookie:String)=error("unused")
        override suspend fun logout()=error("unused")
        override fun requireCurrent(stamp:SessionStamp){require(stamp==session.value.stamp)}
    }
    private open inner class Repository:LyricsRepository {
        var requests=0
        override suspend fun search(name:String,artist:String):List<LyricsCandidate>{requests++;return listOf(candidate)}
        override suspend fun get(id:Long):LyricsDocument{requests++;return document}
    }
    @Test fun lrcKeepsFractionMultipleStampsAndTranslationWithoutWordTiming() {
        val cues=LrcParser.parse("[00:01.25][00:03.500]测试甲\n[00:01.250]translation\n[00:04.00]<00:04.20>测试乙",6000)
        assertEquals(listOf(1250L,3500L,4000L),cues.map{it.fromMs})
        assertEquals("测试甲\ntranslation",cues[0].content);assertEquals(6000,cues.last().toMs);assertEquals("测试乙",cues.last().content)
    }
    @Test fun blankTimestampEndsPriorLineAndStaticTextDoesNotInventTimes() {
        val timeline=SubtitleTimeline(LrcParser.parse("[00:01.00]测试\n[00:02.00]\n[00:03.00]下一句",4000))
        assertEquals(listOf(0),timeline.active(1500));assertTrue(timeline.active(2500).isEmpty());assertTrue(timeline.active(4000).isEmpty())
        assertTrue(LrcParser.parse("没有时间的文本",5000).isEmpty());assertTrue(LrcParser.parse("[00:06.00]源时长外",5000).isEmpty())
    }
    @Test fun sourceOffsetAndUserDelayHaveSeparateSigns() {
        val cue=LrcParser.parse("[offset:500]\n[00:01.00]fixture",2000).single()
        assertEquals(500,cue.fromMs)
        val delay=1000L;val timeline=SubtitleTimeline(listOf(cue))
        assertTrue(timeline.active(1000-delay).isEmpty());assertEquals(listOf(0),timeline.active(1500-delay))
    }
    @Test fun musicTeachingOrInstrumentalIsNotAutomaticallySong() {
        fun withZone(zone:String)=Video(video.bvid,1,"包含歌曲名的普通视频",emptyList(),zoneName=zone)
        assertTrue(MusicContent.songCandidate(withZone("翻唱")));assertTrue(MusicContent.songCandidate(withZone("MV")))
        for(zone in listOf("音乐教学","演奏","乐评","生活",""))assertFalse(MusicContent.songCandidate(withZone(zone)))
        assertFalse(MusicContent.songCandidate(null))
        assertTrue(MusicContent.songCandidate(withZone("").copy(zoneId=31)))
        assertFalse(MusicContent.songCandidate(withZone("").copy(zoneId=2020)))
    }
    @Test fun publicSearchSendsOnlyExplicitQueryWithoutCookieAndGetsExactChosenId()=runTest {
        var count=0
        val client=HttpClient(MockEngine {r ->
            count++;assertEquals("lrclib.net",r.url.host);assertNull(r.headers["Cookie"]);assertNotNull(r.headers["Lrclib-Client"])
            val item="""{"id":1,"trackName":"fixture","artistName":"artist","albumName":"album","duration":4,"instrumental":false,"plainLyrics":"fixture","syncedLyrics":"[00:01.00]fixture"}"""
            if(r.url.encodedPath=="/api/search") {assertEquals("explicit name",r.url.parameters["track_name"]);assertEquals("artist",r.url.parameters["artist_name"]);respond("[$item]")}
            else {assertEquals("/api/get/1",r.url.encodedPath);respond(item)}
        })
        try {val repo=LrclibRepository(client);assertEquals(1,repo.search("explicit name","artist").single().id);assertEquals(4000,repo.get(1).cues.single().toMs);assertEquals(2,count)}finally{client.close()}
    }
    @Test fun foreignRecordCannotReplaceChosenVersion()=runTest {
        val client=HttpClient(MockEngine{respond("""{"id":2,"trackName":"fixture","duration":3,"plainLyrics":"fixture"}""")})
        try{assertFailsWith<PlatformFailure>{LrclibRepository(client).get(1)}}finally{client.close()}
    }
    @Test fun busyAndOversizeAreExplicitFailuresWithoutAutomaticRetry()=runTest {
        for(status in listOf(HttpStatusCode.TooManyRequests,HttpStatusCode.ServiceUnavailable)) {
            var requests=0;val client=HttpClient(MockEngine{requests++;respond("",status)})
            try{assertFailsWith<PlatformFailure>{LrclibRepository(client).search("fixture","")};assertEquals(1,requests)}finally{client.close()}
        }
        val client=HttpClient(MockEngine{respond("{}",headers=headersOf("Content-Length","5000000"))})
        try{assertFailsWith<PlatformFailure>{LrclibRepository(client).get(1)}}finally{client.close()}
    }
    @Test fun plainLyricsRemainStaticAndInstrumentalLabelIsPreserved()=runTest {
        val client=HttpClient(MockEngine{respond("""{"id":1,"duration":3,"instrumental":true,"plainLyrics":"fixture","syncedLyrics":null}""")})
        try{val doc=LrclibRepository(client).get(1);assertTrue(doc.cues.isEmpty());assertEquals("fixture",doc.plain);assertTrue(doc.candidate.instrumental)}finally{client.close()}
    }
    @Test fun noAutomaticSearchAcceptanceOrSyncUntilManualConfirmation()=runTest {
        val accounts=Accounts();val repo=Repository();val controller=LyricsController(backgroundScope,repo,accounts)
        controller.show(video,accounts.session.value.stamp);runCurrent();assertEquals(0,repo.requests)
        controller.search("fixture","");runCurrent();assertEquals(LyricsStatus.CANDIDATES,controller.state.value.status);assertNull(controller.state.value.document)
        controller.select(999);runCurrent();assertEquals(1,repo.requests)
        controller.select(1);runCurrent();assertEquals(LyricsStatus.PREVIEW,controller.state.value.status);assertFalse(controller.state.value.confirmed)
        controller.confirm();assertTrue(controller.state.value.confirmed)
        controller.offset(500);assertFalse(controller.state.value.confirmed);assertEquals(500,controller.state.value.offsetMs)
        controller.confirm();controller.hide();controller.show(video,accounts.session.value.stamp);assertTrue(controller.state.value.confirmed)
        controller.show(video.copy(cid=2,part=2),accounts.session.value.stamp);assertNull(controller.state.value.document);assertFalse(controller.state.value.confirmed)
    }
    @Test fun oldVideoAndAccountResponsesCannotRestoreLyrics()=runTest {
        val accounts=Accounts();val repo=object:Repository(){override suspend fun search(name:String,artist:String):List<LyricsCandidate>{withContext(NonCancellable){delay(100)};return listOf(candidate)}}
        val controller=LyricsController(backgroundScope,repo,accounts);controller.show(video,accounts.session.value.stamp);controller.search("fixture","");runCurrent()
        controller.show(video.copy(cid=2),accounts.session.value.stamp);advanceTimeBy(150);runCurrent();assertTrue(controller.state.value.candidates.isEmpty())
        controller.search("fixture","");runCurrent();accounts.session.value=accounts.session.value.copy(stamp=SessionStamp("guest",2));controller.identityChanged(accounts.session.value.stamp)
        advanceTimeBy(150);runCurrent();assertNull(controller.state.value.video);assertTrue(controller.state.value.candidates.isEmpty())
    }
    @Test fun staticLyricsCannotEnableSync()=runTest {
        val accounts=Accounts();val repo=object:Repository(){override suspend fun get(id:Long)=document.copy(cues=emptyList())}
        val controller=LyricsController(backgroundScope,repo,accounts);controller.show(video,accounts.session.value.stamp);controller.search("fixture","");runCurrent();controller.select(1);runCurrent();controller.confirm();assertFalse(controller.state.value.confirmed)
    }
}
