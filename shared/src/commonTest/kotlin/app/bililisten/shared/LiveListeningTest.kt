package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LiveListeningTest {
    @Test fun roomInputUsesLiveIdentityAndCanonicalPaths() {
        for(text in listOf("6"," https://live.bilibili.com/6?from=share ","直播分享 https://live.bilibili.com/blanc/6","https://live.bilibili.com/h5/6"))assertEquals(6,LiveInput.room(text))
        for(text in listOf("0","-1","https://live.bilibili.com.evil/6","http://live.bilibili.com/6","https://user@live.bilibili.com/6","https://live.bilibili.com:444/6","https://www.bilibili.com/video/BV1xx411c7mD"))assertNull(LiveInput.room(text))
        assertEquals(SharedTarget.Live(6),SharedInput.direct("https://live.bilibili.com/blanc/6"))
        assertEquals("https://live.bilibili.com/77",LiveInput.official(77))
    }
    @Test fun statesNeverTreatUnknownOrReplayAsBroadcasting() {
        assertEquals(LiveRoomStatus.LIVE,LiveRoomStatus.from(1));assertEquals(LiveRoomStatus.OFFLINE,LiveRoomStatus.from(0))
        assertEquals(LiveRoomStatus.REPLAY,LiveRoomStatus.from(2));assertEquals(LiveRoomStatus.UNKNOWN,LiveRoomStatus.from(-1))
    }
    @Test fun pureAudioWinsWithoutMixedConsentAndUnknownFormatsAreRejected() {
        val mixed=LiveStream("https://a.bilivideo.com/live.flv","flv","avc",80)
        val audio=LiveStream("https://a.bilivideo.com/live.aac","aac","aac",0,StreamKind.AUDIO_ONLY,"audio/aac")
        assertEquals(audio,LiveStreams.choose(listOf(mixed,audio),false))
        assertFailsWith<PlatformFailure>{LiveStreams.choose(listOf(mixed),false)}
        assertEquals(mixed,LiveStreams.choose(listOf(mixed),true))
        assertFailsWith<PlatformFailure>{LiveStreams.choose(listOf(mixed.copy(codec="unknown")),true)}
    }
    @Test fun reconnectBudgetStopsAndNeverResumesAnExplicitPauseOrRestriction() {
        assertEquals(listOf(1000L,3000L,8000L,null),(1..4).map{LiveReconnect.delayMs(it,LiveFailure.NETWORK,true,true)})
        for(failure in listOf(LiveFailure.PLATFORM,LiveFailure.SESSION,LiveFailure.OFFLINE_ROOM,LiveFailure.UNSUPPORTED))assertNull(LiveReconnect.delayMs(1,failure,true,true))
        assertNull(LiveReconnect.delayMs(1,LiveFailure.NETWORK,false,true));assertNull(LiveReconnect.delayMs(1,LiveFailure.NETWORK,true,false))
    }
    @Test fun localRoomsAreAccountScopedBoundedAndAfterLifeInCategoryOrder() {
        UserSettings(localLiveRooms=mapOf("guest" to listOf(LiveBookmark(6,"房间")),"7" to listOf(LiveBookmark(6,"另一账号")))).checked()
        assertFailsWith<IllegalArgumentException>{UserSettings(localLiveRooms=mapOf("guest" to listOf(LiveBookmark(6,"x"),LiveBookmark(6,"y")))).checked()}
        assertEquals(HomeCategory.LIVE,HomeCategory.entries[HomeCategory.LIFE.ordinal+1])
    }
    @Test fun roomMappingAndUnknownStatusRemainExact()=runTest {
        val client=HttpClient(MockEngine { request ->
            assertEquals("https://live.bilibili.com/",request.headers["Referer"])
            assertEquals("/xlive/web-room/v1/index/getInfoByRoom",request.url.encodedPath)
            respond("""{"code":0,"data":{"room_info":{"room_id":77,"short_id":6,"uid":9,"title":"示例直播","cover":"https://i0.hdslb.com/a.jpg","area_name":"音乐"},"anchor_info":{"base_info":{"uname":"示例主播"}}}}""")
        })
        try {val room=BiliApi(client){null}.liveRoom(6);assertEquals(77,room.roomId);assertEquals(6,room.requestedId);assertEquals(LiveRoomStatus.UNKNOWN,room.state);assertEquals("音乐",room.area);assertEquals("https://i0.hdslb.com/a.jpg",room.cover)}finally{client.close()}
    }
    @Test fun lockedRoomStopsBeforeMetadataOrStreams()=runTest {
        var calls=0
        val client=HttpClient(MockEngine{calls++;respond("""{"code":0,"data":{"room_info":{"room_id":77,"short_id":6,"uid":9,"lock_status":1}}}""")})
        try {assertEquals(-403,assertFailsWith<PlatformFailure>{BiliApi(client){null}.liveRoom(6)}.code);assertEquals(1,calls)}finally{client.close()}
    }
    @Test fun liveRankingUsesOfficialHeatSourceAndRoomIdsWithoutVodFallback()=runTest {
        val client=HttpClient(MockEngine{request->
            assertEquals("/xlive/web-interface/v1/index/getHotRankList",request.url.encodedPath);assertTrue(request.url.parameters.isEmpty())
            assertEquals("https://live.bilibili.com/",request.headers["Referer"])
            respond("""{"code":0,"data":{"list":[{"roomid":77,"uid":9,"title":"真实热度来源","uname":"主播","online":0,"score":123,"face":"https://i0.hdslb.com/avatar.jpg","area_v2_name":"音乐"},{"roomid":77,"title":"重复"},{"roomid":0,"title":"无效"},{"room_id":88,"title":"未知热度","online":999}]}}""")
        })
        try {val rows=BiliApi(client){null}.liveRanking();assertEquals(listOf(77L,88L),rows.map{it.roomId});assertEquals(123,rows.first().heat);assertEquals("音乐",rows.first().area);assertEquals("https://i0.hdslb.com/avatar.jpg",rows.first().cover);assertNull(rows.last().heat)}finally{client.close()}
    }
    @Test fun hotRankingPreservesServerOrderRatherThanSortingOtherCounters()=runTest {
        val client=HttpClient(MockEngine{respond("""{"code":0,"data":{"list":[{"roomid":11,"score":10,"online":0},{"roomid":12,"score":9,"online":999},{"roomid":13,"score":8},{"roomid":14,"score":7},{"roomid":15,"score":6},{"roomid":16,"score":5}]}}""")})
        try {assertEquals(listOf(11L,12L,13L,14L,15L),BiliApi(client){null}.liveRanking().map{it.roomId})}finally{client.close()}
    }
    @Test fun hotRankingRejectsMissingListInsteadOfInventingRecommendations()=runTest {
        val client=HttpClient(MockEngine{respond("""{"code":0,"data":{"room_list":[]}}""")})
        try {assertFailsWith<PlatformFailure>{BiliApi(client){null}.liveRanking()}}finally{client.close()}
    }
    @Test fun rankRestrictionIsNotRetriedAsAnotherSource()=runTest {
        var calls=0
        val client=HttpClient(MockEngine{calls++;respond("""{"code":-352,"message":"restricted"}""")})
        try {assertEquals(-352,assertFailsWith<PlatformFailure>{BiliApi(client){null}.liveRanking()}.code);assertEquals(1,calls)}finally{client.close()}
    }
    @Test fun liveStreamsSeparateAudioAndHlsAndFilterUnsafeHosts()=runTest {
        val client=HttpClient(MockEngine{respond("""{"code":0,"data":{"room_id":77,"playurl_info":{"playurl":{"stream":[{"protocol_name":"http_stream","format":[{"format_name":"aac","codec":[{"codec_name":"aac","base_url":"/live.aac","url_info":[{"host":"https://a.bilivideo.com","extra":""},{"host":"https://evil.example","extra":""}]}]}]},{"protocol_name":"http_hls","format":[{"format_name":"fmp4","codec":[{"codec_name":"avc","current_qn":80,"base_url":"/live.m3u8","url_info":[{"host":"https://a.bilivideo.com","extra":""}]}]}]}]}}}}""")})
        try {val rows=BiliApi(client){null}.liveStreams(77);assertEquals(2,rows.size);assertEquals(StreamKind.AUDIO_ONLY,rows[0].kind);assertEquals("application/x-mpegURL",rows[1].mime);assertTrue(rows.all(LiveStreams::supported))}finally{client.close()}
    }
}
