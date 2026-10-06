package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlin.test.*

class ApiMigrationTest {
    @Test fun concurrentSignedReadsShareOnePublicKeyFetch()=runTest {
        var keyReads=0;var audioReads=0
        val client=HttpClient(MockEngine {r->
            if(r.url.encodedPath.endsWith("nav")) {
                keyReads++;delay(10)
                respond("""{"code":-101,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/${"0".repeat(32)}.png","sub_url":"https://i0.hdslb.com/${"1".repeat(32)}.png"}}}""")
            }else{audioReads++;respond("""{"code":0,"data":{"dash":{"audio":[]}}}""")}
        })
        try{val api=wbiApi(client){null};(1L..3L).map{cid->async{api.audioProbe("BV1xx411c7mD",cid)}}.awaitAll();assertEquals(1,keyReads);assertEquals(3,audioReads)}finally{client.close()}
    }
    @Test fun publicKeyChallengeKeepsItsCodeAndDoesNotReachThePlayer()=runTest {
        var calls=0
        val client=HttpClient(MockEngine {r->calls++;assertEquals("/x/web-interface/nav",r.url.encodedPath);respond("""{"code":-352}""")})
        try{assertEquals(-352,assertFailsWith<PlatformFailure>{wbiApi(client){null}.audioProbe("BV1xx411c7mD",1)}.code);assertEquals(1,calls)}finally{client.close()}
    }
    @Test fun playerUsesCurrentSignedEndpointWithoutSendingCookiesToPublicKeys()=runTest {
        val paths=mutableListOf<String>()
        val client=HttpClient(MockEngine { r ->
            paths+=r.url.encodedPath
            if(r.url.encodedPath.endsWith("nav")) {
                assertNull(r.headers["Cookie"])
                respond("""{"code":-101,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/${"0".repeat(32)}.png","sub_url":"https://i0.hdslb.com/${"1".repeat(32)}.png"}}}""")
            } else {
                assertEquals("/x/player/wbi/playurl",r.url.encodedPath)
                assertEquals("fixture",r.headers["Cookie"])
                assertEquals("4048",r.url.parameters["fnval"])
                assertEquals("BROWSER",r.url.parameters["from_client"])
                assertEquals("1315873",r.url.parameters["web_location"])
                assertEquals("true",r.url.parameters["support_multi_audio"])
                assertEquals("a".repeat(32),r.url.parameters["w_rid"])
                respond("""{"code":0,"data":{"timelength":1000,"dash":{"audio":[]}}}""")
            }
        })
        try {wbiApi(client){"fixture"}.audioProbe("BV1xx411c7mD",1,true);assertEquals(2,paths.size)}finally{client.close()}
    }
    @Test fun signedDetailReadsViewRatherThanTheOldFlatEnvelope()=runTest {
        val client=HttpClient(wbiEngine { r ->
            assertEquals("/x/web-interface/wbi/view/detail",r.url.encodedPath)
            assertNotNull(r.url.parameters["w_rid"])
            respond("""{"code":0,"data":{"View":{"aid":1,"bvid":"BV1xx411c7mD","title":"fixture","pages":[{"cid":1,"page":1,"part":"P1"}],"owner":{"mid":7,"name":"fixture"}},"Card":{},"Related":[]}}""")
        })
        try {assertEquals(1,wbiApi(client){null}.video("BV1xx411c7mD").parts.single().cid)}finally{client.close()}
    }
    @Test fun missingViewIsAnErrorNotAnEmptyVideoOrLegacyFallback()=runTest {
        var calls=0
        val client=HttpClient(wbiEngine { calls++;respond("""{"code":0,"data":{"pages":[]}}""") })
        try {assertEquals("缺少视频详情",assertFailsWith<PlatformFailure>{wbiApi(client){null}.video("BV1xx411c7mD")}.category);assertEquals(1,calls)}finally{client.close()}
    }
    @Test fun signingConfigurationMissingFailsBeforeAnyRequest()=runTest {
        var calls=0
        val client=HttpClient(MockEngine { calls++;error("must not request") })
        try {assertEquals("请求签名暂不可用",assertFailsWith<PlatformFailure>{BiliApi(client){null}.audioProbe("BV1xx411c7mD",1)}.category);assertEquals(0,calls)}finally{client.close()}
    }
    @Test fun challengeNeverFallsBackToAnOldEndpoint()=runTest {
        val paths=mutableListOf<String>()
        val client=HttpClient(wbiEngine { r -> paths+=r.url.encodedPath;respond("""{"code":-352}""") })
        try {assertEquals(-352,assertFailsWith<PlatformFailure>{wbiApi(client){null}.audioProbe("BV1xx411c7mD",1)}.code);assertEquals(listOf("/x/player/wbi/playurl"),paths)}finally{client.close()}
    }
    @Test fun sessionChangeDuringKeyReadCannotSendTheOldCookieAfterSigning()=runTest {
        var generation=1L;var calls=0
        val client=HttpClient(MockEngine { r ->
            calls++;assertEquals("/x/web-interface/nav",r.url.encodedPath);assertNull(r.headers["Cookie"]);generation++
            respond("""{"code":-101,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/${"0".repeat(32)}.png","sub_url":"https://i0.hdslb.com/${"1".repeat(32)}.png"}}}""")
        })
        try {assertFailsWith<PlatformFailure>{wbiApi(client,generation={generation}){"old-cookie"}.audioProbe("BV1xx411c7mD",1)};assertEquals(1,calls)}finally{client.close()}
    }
    @Test fun currentImHistorySignsDeviceAndPagingParamsWithoutAcknowledging()=runTest {
        val client=HttpClient(wbiEngine { r ->
            assertEquals("api.vc.bilibili.com",r.url.host)
            assertEquals("/svr_sync/v1/svr_sync/fetch_session_msgs",r.url.encodedPath)
            assertEquals("1",r.url.parameters["sender_device_id"])
            assertEquals("web",r.url.parameters["mobi_app"])
            assertEquals("0",r.url.parameters["build"])
            assertEquals("9007199254740993",r.url.parameters["end_seqno"])
            assertEquals("a".repeat(32),r.url.parameters["w_rid"])
            respond("""{"code":0,"data":{}}""")
        })
        try {wbiApi(client){"fixture"}.messageRead("/svr_sync/v1/svr_sync/fetch_session_msgs",mapOf("end_seqno" to "9007199254740993"))}finally{client.close()}
    }
    @Test fun currentRoomCannotMapToAnUnrelatedLongId()=runTest {
        val client=HttpClient(MockEngine { respond("""{"code":0,"data":{"room_info":{"room_id":77,"short_id":8,"uid":9},"anchor_info":{"base_info":{"uname":"fixture"}}}}""") })
        try {assertEquals("房间身份不匹配",assertFailsWith<PlatformFailure>{BiliApi(client){null}.liveRoom(6)}.category)}finally{client.close()}
    }
}
