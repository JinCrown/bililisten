package app.bililisten.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class UpLibraryTest {
    private val mid=3493093607213343L
    private val keys="""{"code":-101,"data":{"isLogin":false,"wbi_img":{"img_url":"https://i0.hdslb.com/0123456789abcdef0123456789abcdef.png","sub_url":"https://i0.hdslb.com/fedcba9876543210fedcba9876543210.png"}}}"""
    @Test fun guestSigningAndEncodedCreatorSearchNeverFollowReturnedNavigation()=runTest {
        val paths=mutableListOf<String>()
        val client=HttpClient(MockEngine {req->
            paths+=req.url.encodedPath;assertEquals("https",req.url.protocol.name);assertEquals("api.bilibili.com",req.url.host)
            if(req.url.encodedPath.endsWith("nav")){assertNull(req.headers["Cookie"]);respond(keys,headers=headersOf(HttpHeaders.ContentType,"application/json"))}
            else {
                assertEquals("bili_user",req.url.parameters["search_type"]);assertEquals("名字 & 空格",req.url.parameters["keyword"])
                assertEquals(32,req.url.parameters["w_rid"]!!.length)
                respond("""{"code":0,"data":{"page":1,"numResults":1,"numPages":1,"result":[{"mid":$mid,"uname":"<em>JLRS</em>-LeoFM","upic":"//i0.hdslb.com/avatar.jpg","usign":"音乐 &amp; 现场","videos":1887,"fans":4500000,"goto":"https://evil.example"}]}}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
            }
        })
        try {
            val api=BiliApi(client){null};val result=api.upSearch("名字 & 空格",1,100000){"a".repeat(32)}
            assertEquals("JLRS-LeoFM",result.items.single().name);assertEquals("音乐 & 现场",result.items.single().signature)
            assertEquals(1887,result.items.single().videos);assertFalse(result.hasMore)
            assertEquals(listOf("/x/web-interface/nav","/x/web-interface/wbi/search/type"),paths)
        }finally{client.close()}
    }
    @Test fun uploadPaginationValidatesOwnerAndParsesMetadataWithoutAnyAudioRequest()=runTest {
        var badOwner=false;var badPage=false;var restriction=false;var union=false
        val client=HttpClient(MockEngine {req->
            val body=if(req.url.encodedPath.endsWith("nav"))keys else {
                assertEquals("/x/space/wbi/arc/search",req.url.encodedPath);assertEquals("$mid",req.url.parameters["mid"])
                assertEquals("30",req.url.parameters["ps"]);assertEquals("2",req.url.parameters["pn"])
                assertEquals("pubdate",req.url.parameters["order"]);assertEquals("现场",req.url.parameters["keyword"])
                if(restriction)"""{"code":-352}""" else """{"code":0,"data":{"page":{"pn":${if(badPage)3 else 2},"ps":30,"count":1887},"list":{"vlist":[{"mid":${if(badOwner)8 else mid},"is_union_video":${if(union)1 else 0},"bvid":"BV1xx411c7mD","title":"<em>现场</em>","author":"JLRS-LeoFM","pic":"//i0.hdslb.com/cover.jpg","length":"04:01"}]}}}"""
            }
            respond(body,headers=headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val api=BiliApi(client){null}
            suspend fun read()=api.upUploads(mid,2,UploadOrder.OLDEST,"现场",100000){"a".repeat(32)}
            val page=read();assertEquals(1887,page.source.count);assertTrue(page.hasMore);assertEquals(241L,page.items.single().duration)
            assertEquals("现场",page.items.single().title);assertEquals(SourceKind.UP_UPLOADS,page.source.ref.kind)
            badOwner=true;assertFailsWith<PlatformFailure>{read()};union=true
            assertEquals(mid,read().source.ref.owner);assertEquals("",read().source.ownerName)
            badOwner=false;union=false
            badPage=true;assertFailsWith<PlatformFailure>{read()};badPage=false
            restriction=true;assertEquals(FailureKind.PLATFORM_BLOCKED,assertFailsWith<PlatformFailure>{read()}.kind())
        }finally{client.close()}
    }
    @Test fun upLinksSourceIdentityAndTransferRemainCompatibleWithEarlierRecords() {
        assertEquals(mid,UpLinks.mid("https://space.bilibili.com/$mid/video"));assertEquals(mid,UpLinks.mid("$mid"))
        for(bad in listOf("https://space.bilibili.com.evil.example/$mid","https://user@space.bilibili.com/$mid","https://space.bilibili.com:444/$mid","0","https://space.bilibili.com/$mid/favlist"))assertNull(UpLinks.mid(bad))
        val source=SourceRef(SourceKind.UP_UPLOADS,mid,mid)
        assertEquals(source,SourceCodec.decode(SourceCodec.encode(source)))
        assertFailsWith<IllegalArgumentException>{source.copy(id=8).checked()}
        val data=LocalTransfer(createdAt=100000,account="7",bookmarks=listOf(TransferBookmark(source,"音乐投稿","UP",1887,"//i0.hdslb.com/avatar.jpg")),heard=listOf(TransferHeard(source,setOf("BV1xx411c7mD"))))
        assertEquals(data,Json.decodeFromString<LocalTransfer>(Json.encodeToString(LocalTransfer.serializer(),data)).checked())
        val legacy=Json.decodeFromString<TransferBookmark>("""{"source":{"kind":"PUBLIC_FAVORITES","id":9,"owner":8},"title":"旧收藏","ownerName":"UP","total":2}""")
        assertEquals("",legacy.cover)
    }
    @Test fun videoDetailVerifiesCollaboratorsAndLegacyMetadataDefaultsToNoCollaborators()=runTest {
        val client=HttpClient(MockEngine {r->respond(if(r.url.encodedPath.endsWith("nav"))keys else
            """{"code":0,"data":{"View":{"bvid":"BV1xx411c7mD","aid":1,"title":"联合投稿","owner":{"mid":8,"name":"主投稿人"},"pages":[{"cid":11,"page":1,"part":"P1"}],"staff":[{"mid":$mid},{"mid":8},{"mid":0}]}}}""")})
        try {
            val video=BiliApi(client,searchClock=object:Clock{override fun nowMs()=100000L},searchMd5={"a".repeat(32)},credentials={null}).video("BV1xx411c7mD")
            assertTrue(video.hasCreator(mid));assertTrue(video.hasCreator(8));assertFalse(video.hasCreator(9));assertFalse(video.hasCreator(0))
            assertEquals(setOf(mid,8L),video.collaborators)
            val legacy=Json.decodeFromString<Video>("""{"bvid":"BV1xx411c7mD","aid":1,"title":"legacy","parts":[],"owner":8}""")
            assertFalse(legacy.hasCreator(mid));assertTrue(legacy.hasCreator(8))
        }finally{client.close()}
    }
    @Test fun all1887EntriesAndLazyP1RoundTripButZeroCidCannotEnterOtherSources() {
        val source=SourceRef(SourceKind.UP_UPLOADS,mid,mid)
        val rows=List(1887){QueueEntry("$it","BV"+(it+1).toString().padStart(10,'0'),0,1,"song $it",source=source)}
        val queue=ResumeSnapshot(account="7",entries=rows,order=rows.reversed().map{it.id},currentId=rows.last().id,positionMs=0)
        val restored=SnapshotCodec.decode(SnapshotCodec.encode(queue))
        assertEquals(queue,restored);assertEquals(rows.reversed(),restored.playbackEntries())
        assertEquals(HistoryOrigin.UP_UPLOADS,rows.first().resolvedOrigin())
        for(bad in listOf(rows.first().copy(source=null),rows.first().copy(offline=true),rows.first().copy(part=2),rows.first().copy(cid=-1))) {
            assertFailsWith<IllegalArgumentException>{queue.copy(entries=listOf(bad),order=listOf(bad.id),currentId=bad.id).checked()}
        }
    }
}
