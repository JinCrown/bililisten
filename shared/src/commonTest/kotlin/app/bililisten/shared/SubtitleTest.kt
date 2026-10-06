package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleTest {
    private val video=VideoRef("BV1xx411c7mD",1,1)
    private val other=video.copy(cid=2,part=2)
    private val chinese=SubtitleTrack(SubtitleOption("zh","zh-CN","中文",SubtitleKind.HUMAN),"https://i0.hdslb.com/bfs/subtitle/zh.json")
    private val english=SubtitleTrack(SubtitleOption("en","en","英文",SubtitleKind.AI),"https://i0.hdslb.com/bfs/subtitle/en.json")
    private val sentence=listOf(SubtitleCue(1000,2000,"测试句子"),SubtitleCue(3000,4000,"第二句"))
    private fun obj(value: String)=Json.parseToJsonElement(value).jsonObject
    private class Accounts:AccountRepository {
        override val session=MutableStateFlow(AccountSession(SessionStamp("7",1),SessionStatus.AUTHENTICATED,Account(7,"fixture",Membership.VIP)))
        override suspend fun verify()=session.value.account
        override suspend fun accept(cookie:String)=error("unused")
        override suspend fun logout()=error("unused")
        override fun requireCurrent(stamp:SessionStamp){require(stamp==session.value.stamp)}
    }
    private class Settings:SettingsRepository {
        override val settings=MutableStateFlow(UserSettings(audioChoice=AudioChoice(30280,"mp4a.40.2"),historyKeepAll=true))
        override suspend fun update(value:UserSettings){settings.value=value.checked()}
    }
    @Test fun originalSentenceTextAndFractionalTimeRemainIntact() {
        val cues=SubtitleParser.cues("""{"body":[{"from":1.125,"to":2.8,"content":"<原文>\n第二行"},{"from":0,"to":0.5,"content":"开头"}]}""")
        assertEquals(0,cues.first().fromMs);assertEquals(1125,cues[1].fromMs);assertEquals(2800,cues[1].toMs);assertEquals("<原文>\n第二行",cues[1].content)
    }
    @Test fun missingAndMalformedFilesCannotMasqueradeAsEmptySubtitles() {
        assertTrue(SubtitleParser.cues("""{"body":[]}""").isEmpty())
        for(file in listOf("{}","null","<html>blocked</html>","""{"body":[{"from":2,"to":1,"content":"x"}]}""","""{"body":[{"from":-1,"to":1,"content":"x"}]}"""))
            assertEquals(SubtitleStatus.FAILED,assertFailsWith<SubtitleFailure>{SubtitleParser.cues(file)}.status)
    }
    @Test fun timelineNeverHoldsPreviousSentenceAcrossGapOrEndAndSupportsOverlap() {
        val timeline=SubtitleTimeline(listOf(SubtitleCue(0,1200,"a"),SubtitleCue(800,1500,"b"),SubtitleCue(2000,2200,"c")))
        assertEquals(listOf(0),timeline.active(0));assertEquals(listOf(0,1),timeline.active(800))
        assertEquals(listOf(1),timeline.active(1200));assertTrue(timeline.active(1500).isEmpty());assertTrue(timeline.active(2300).isEmpty())
        assertEquals(listOf(2),timeline.active(2100));assertEquals(1,timeline.nearby(1800));assertEquals(0,timeline.nearby(-5))
    }
    @Test fun timelineUsesMediaPositionDirectlyForSeekAndSpeed() {
        val timeline=SubtitleTimeline(sentence)
        assertEquals(listOf(1),timeline.active(3500));assertEquals(listOf(0),timeline.active(1500));assertTrue(timeline.active(2500).isEmpty())
        assertEquals(listOf(1),timeline.active((1750*2).toLong()))
    }
    @Test fun metadataDistinguishesArtificialHumanUnknownAndUsesStringId() {
        val data=obj("""{"cid":1,"subtitle":{"subtitles":[{"id_str":"9007199254740993","lan":"zh-CN","lan_doc":"中文","ai_type":0,"subtitle_url":"//i0.hdslb.com/1.json"},{"id":2,"lan":"ai-zh","ai_type":0,"subtitle_url":"//i0.hdslb.com/2.json"},{"id":3,"lan":"en","subtitle_url":"//i0.hdslb.com/3.json"}]}}""")
        val tracks=SubtitleParser.tracks(video,data).tracks
        assertEquals("9007199254740993",tracks[0].option.id)
        assertEquals(listOf(SubtitleKind.HUMAN,SubtitleKind.AI,SubtitleKind.UNKNOWN),tracks.map{it.option.kind})
        val public=Json.encodeToString(tracks.map{it.option});assertFalse(public.contains("hdslb"));assertFalse(public.contains("https"))
    }
    @Test fun loginEmptyInvalidStructureAndPartMismatchAreDistinct() {
        assertTrue(SubtitleParser.tracks(video,obj("""{"need_login_subtitle":true}""")).needsLogin)
        assertTrue(SubtitleParser.tracks(video,obj("""{"subtitle":{"subtitles":[]}}""")).tracks.isEmpty())
        assertFailsWith<SubtitleFailure>{SubtitleParser.tracks(video,obj("{}"))}
        assertFailsWith<SubtitleFailure>{SubtitleParser.tracks(video,obj("""{"cid":2,"subtitle":{"subtitles":[]}}"""))}
    }
    @Test fun oldSettingsDefaultAndPreferencesRoundTripPreserveAudioAndHistory() {
        val old=Json.decodeFromString<UserSettings>("""{"historyKeepAll":true}""")
        assertEquals("",old.subtitleLanguage);assertFalse(old.subtitleLineMode)
        val saved=old.copy(subtitleLanguage="ai-zh",subtitleLineMode=true,audioChoice=AudioChoice(30216,"mp4a.40.5"))
        assertEquals(saved,Json.decodeFromString<UserSettings>(Json.encodeToString(saved)).checked())
        assertFailsWith<IllegalArgumentException>{saved.copy(subtitleLanguage="https://foreign.example").checked()}
    }
    @Test fun apiCookieIsOnlySentToMetadataAndNotSubtitleCdn()=runTest {
        var metadata=0;var files=0
        val client=HttpClient(wbiEngine { request ->
            when(request.url.host) {
                "api.bilibili.com" -> {metadata++;assertEquals("/x/player/wbi/v2",request.url.encodedPath);assertEquals("session",request.headers["Cookie"]);assertEquals("1",request.url.parameters["cid"]);assertEquals("a".repeat(32),request.url.parameters["w_rid"])
                    respond("""{"code":0,"data":{"subtitle":{"subtitles":[{"id":1,"lan":"zh-CN","ai_type":0,"subtitle_url":"//i0.hdslb.com/sub.json"}]}}}""")}
                "i0.hdslb.com" -> {files++;assertNull(request.headers["Cookie"]);respond("""{"body":[{"from":0,"to":1,"content":"fixture"}]}""")}
                else -> error("unexpected host")
            }
        })
        try {val api=wbiApi(client){"session"};val result=api.subtitleTracks(video);assertEquals(1,api.subtitleCues(result.tracks.single()).size);assertEquals(1,metadata);assertEquals(1,files)}finally{client.close()}
    }
    @Test fun foreignInsecureOrCredentialBearingAddressesAreRejectedBeforeHttp()=runTest {
        val client=HttpClient(MockEngine{error("No external request allowed")})
        try {for(url in listOf("http://i0.hdslb.com/a","https://hdslb.com.foreign.example/a","https://user@i0.hdslb.com/a","https://i0.hdslb.com:444/a"))
            assertEquals(SubtitleStatus.RESTRICTED,assertFailsWith<SubtitleFailure>{BiliApi(client){null}.subtitleCues(chinese.copy(url=url))}.status)
        }finally{client.close()}
    }
    @Test fun expiredRestrictedAndOversizeFilesHaveExplicitFailures()=runTest {
        for((status,expected) in listOf(403 to SubtitleStatus.EXPIRED,429 to SubtitleStatus.RESTRICTED)) {
            val client=HttpClient(MockEngine{respond("",HttpStatusCode.fromValue(status))})
            try {assertEquals(expected,assertFailsWith<SubtitleFailure>{BiliApi(client){null}.subtitleCues(chinese)}.status)}finally{client.close()}
        }
        val big=HttpClient(MockEngine{respond("{}",headers=headersOf("Content-Length","5000000"))})
        try {assertFailsWith<SubtitleFailure>{BiliApi(big){null}.subtitleCues(chinese)}}finally{big.close()}
    }
    @Test fun languageSwitchClearsOldTextPreservesOtherPreferencesAndDoesNotRequirePlayback()=runTest {
        val accounts=Accounts();val settings=Settings();val repo=object:SubtitleRepository {
            override suspend fun tracks(video:VideoRef)=SubtitleTracks(video,listOf(chinese,english),false)
            override suspend fun cues(track:SubtitleTrack):List<SubtitleCue>{delay(50);return if(track==english)sentence.map{it.copy(content="English")}else sentence}
        }
        val controller=SubtitleController(backgroundScope,repo,accounts,settings,object:Clock{override fun nowMs()=testScheduler.currentTime})
        controller.show(video,accounts.session.value.stamp);runCurrent();assertEquals(SubtitleStatus.LOADING,controller.state.value.status)
        advanceTimeBy(51);runCurrent();assertEquals(SubtitleStatus.READY,controller.state.value.status)
        controller.select("en");assertTrue(controller.state.value.cues.isEmpty());advanceTimeBy(51);runCurrent()
        assertEquals("English",controller.state.value.cues.first().content);assertEquals("en",settings.settings.value.subtitleLanguage)
        assertTrue(settings.settings.value.historyKeepAll);assertEquals(30280,settings.settings.value.audioChoice.id)
        controller.hide();assertTrue(controller.state.value.cues.isEmpty())
    }
    @Test fun lateNonCooperativeResponseCannotCrossPartOrAccount()=runTest {
        val accounts=Accounts();val settings=Settings()
        val repo=object:SubtitleRepository {
            override suspend fun tracks(video:VideoRef):SubtitleTracks {withContext(NonCancellable){delay(if(video.cid==1L)100 else 20)};return SubtitleTracks(video,listOf(chinese),false)}
            override suspend fun cues(track:SubtitleTrack)=sentence
        }
        val controller=SubtitleController(backgroundScope,repo,accounts,settings,object:Clock{override fun nowMs()=testScheduler.currentTime})
        controller.show(video,accounts.session.value.stamp);runCurrent();controller.show(other,accounts.session.value.stamp)
        advanceTimeBy(110);runCurrent();assertEquals(other,controller.state.value.video);assertEquals(SubtitleStatus.READY,controller.state.value.status)
        controller.show(video,accounts.session.value.stamp);runCurrent()
        accounts.session.value=AccountSession(SessionStamp("guest",2),SessionStatus.GUEST)
        controller.identityChanged(accounts.session.value.stamp);advanceTimeBy(110);runCurrent();assertTrue(controller.state.value.cues.isEmpty())
    }
    @Test fun expiredTrackSelectionRefreshesMetadataBeforeFileAndKeepsLanguagePreference()=runTest {
        val accounts=Accounts();val settings=Settings();var now=1L;var reads=0;val urls=mutableListOf<String>()
        val repo=object:SubtitleRepository {
            override suspend fun tracks(video:VideoRef):SubtitleTracks {reads++;return SubtitleTracks(video,listOf(chinese,english.copy(url="https://i0.hdslb.com/$reads.json")),false)}
            override suspend fun cues(track:SubtitleTrack):List<SubtitleCue>{urls+=track.url;return sentence}
        }
        val controller=SubtitleController(backgroundScope,repo,accounts,settings,object:Clock{override fun nowMs()=now})
        controller.show(video,accounts.session.value.stamp);runCurrent();now=400000;controller.select("en");runCurrent()
        assertEquals(2,reads);assertEquals("https://i0.hdslb.com/2.json",urls.last());assertEquals("en",settings.settings.value.subtitleLanguage)
    }
    @Test fun retryFetchesFreshTrackAndEmptyLoginChallengeNetworkStayDistinct()=runTest {
        val accounts=Accounts();val settings=Settings();var outcome=0
        val repo=object:SubtitleRepository {
            override suspend fun tracks(video:VideoRef):SubtitleTracks=when(outcome) {
                0 -> SubtitleTracks(video,emptyList(),true)
                1 -> SubtitleTracks(video,emptyList(),false)
                2 -> throw PlatformFailure("平台限制",412)
                3 -> throw PlatformFailure("网络请求失败")
                else -> SubtitleTracks(video,listOf(chinese),false)
            }
            override suspend fun cues(track:SubtitleTrack)=sentence
        }
        val controller=SubtitleController(backgroundScope,repo,accounts,settings,object:Clock{override fun nowMs()=1L})
        controller.show(video,accounts.session.value.stamp);runCurrent();assertEquals(SubtitleStatus.LOGIN_REQUIRED,controller.state.value.status)
        outcome=1;controller.retry();runCurrent();assertEquals(SubtitleStatus.EMPTY,controller.state.value.status)
        outcome=2;controller.retry();runCurrent();assertEquals(SubtitleStatus.RESTRICTED,controller.state.value.status)
        outcome=3;controller.retry();runCurrent();assertEquals(SubtitleStatus.FAILED,controller.state.value.status)
        outcome=4;controller.retry();runCurrent();assertEquals(SubtitleStatus.READY,controller.state.value.status)
    }
}
