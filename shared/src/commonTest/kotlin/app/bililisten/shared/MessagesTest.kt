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
class MessagesTest {
    private val alice=MessageSession(8,1,"示例音乐作者")
    private fun root(data:String)="""{"code":0,"data":$data}"""
    private fun msg(content:String="""{"content":"你好"}""",type:Int=1,status:Int=0,key:String="7354295169819585966",seq:String="1285290404823041")=buildJsonObject {
        put("msg_key",JsonPrimitive(key.toLong()));put("msg_seqno",JsonPrimitive(seq.toLong()));put("sender_uid",8);put("receiver_id",7);put("receiver_type",1);put("timestamp",1712305278);put("msg_type",type);put("msg_status",status);put("content",content)
    }
    @Test fun withdrawnMessagesNeverExposeTheirRawBody() {
        assertEquals("消息已撤回",messageBody(msg("""{"content":"private secret"}""",status=1)).text)
        assertEquals("消息已撤回",messageBody(msg("""{"content":"private secret"}""",status=2)).text)
        assertEquals("消息已失效",messageBody(msg(status=50)).text)
        assertEquals("7354295169819585966",messageBody(msg("7354295169819585966",type=5)).withdrawnTarget)
    }
    @Test fun bodiesHandleTextRichNotificationsImagesSharesAndUnknownsSafely() {
        assertEquals("你好",messageBody(msg()).text)
        assertEquals("活动通知\n已开始\n歌曲：示例歌",messageBody(msg("""{"title":"活动通知","text":"已开始","modules":[{"title":"歌曲","detail":"示例歌"}]}""",10)).text)
        assertTrue(messageBody(msg("""{"url":"javascript:danger()"}""",2)).text.contains("图片消息"))
        assertFalse(messageBody(msg("""{"url":"javascript:danger()"}""",2)).text.contains("danger"))
        assertEquals("示例歌曲",messageBody(msg("""{"title":"示例歌曲"}""",7)).text)
        assertTrue(messageBody(msg(type=999)).text.contains("暂不支持"))
        assertTrue(messageBody(msg("not json")).text.contains("暂不支持"))
    }
    @Test fun readsUseOnlyTheAllowlistedHostsAndNeverAcknowledgeOrSend()=runTest {
        val requests=mutableListOf<Pair<String,String>>()
        val api=wbiApi(HttpClient(wbiEngine{r->
            requests+=r.url.host to r.url.encodedPath
            assertEquals(HttpMethod.Get,r.method);assertEquals("SESSDATA=fixture",r.headers[HttpHeaders.Cookie])
            assertFalse(r.url.parameters.contains("csrf"));assertFalse(r.url.encodedPath.contains("update"))
            assertEquals("0",r.url.parameters["build"]);assertEquals("web",r.url.parameters["mobi_app"])
            if(r.url.host=="api.vc.bilibili.com")assertEquals("a".repeat(32),r.url.parameters["w_rid"])
            respond(root("{}"),headers=headersOf(HttpHeaders.ContentType,"application/json"))
        })){"SESSDATA=fixture"}
        api.messageRead("/x/msgfeed/unread")
        api.messageRead("/session_svr/v1/session_svr/get_sessions")
        api.messageRead("/x/sys-msg/query_user_notify")
        assertEquals(listOf("api.bilibili.com","api.vc.bilibili.com","message.bilibili.com"),requests.map{it.first})
        assertFailsWith<IllegalArgumentException>{api.messageRead("/session_svr/v1/session_svr/update_ack")}
        assertFailsWith<IllegalArgumentException>{api.messageRead("/x/sys-msg/update_cursor")}
        assertEquals(3,requests.size)
    }
    @Test fun guestMakesNoNetworkRequestAndStaleGenerationCannotReturnMessages()=runTest {
        var calls=0;var generation=1L
        val client=HttpClient(MockEngine{calls++;generation++;respond(root("{}"))})
        val guest=BiliApi(client){null}
        assertEquals(-101,assertFailsWith<PlatformFailure>{guest.messageRead("/x/msgfeed/unread")}.code)
        assertEquals(0,calls)
        val api=BiliApi(client,generation={generation}){"SESSDATA=fixture"}
        assertFailsWith<PlatformFailure>{api.messageRead("/x/msgfeed/unread")}
    }
    @Test fun sessionsUseMicrosecondCursorAndExactProfileIdentity()=runTest {
        val paths=mutableListOf<String>()
        val repo=BiliMessageRepository(wbiApi(HttpClient(wbiEngine{r->
            paths+=r.url.encodedPath
            val d=if(r.url.encodedPath.endsWith("cards"))"""{"8":{"name":"示例音乐作者","face":"https://i0.hdslb.com/bfs/face/fixture.jpg"}}""" else {
                assertEquals("1",r.url.parameters["session_type"]);assertEquals("9999999",r.url.parameters["end_ts"])
                """{"session_list":[{"talker_id":8,"session_type":1,"session_ts":1234567,"unread_count":2,"last_msg":${msg()}}],"has_more":1}"""
            }
            respond(root(d))
        })){"SESSDATA=fixture"})
        val page=repo.sessions(MessageCursor("9999999"));assertEquals("1234567",page.next?.id)
        assertEquals(alice.name,page.items.single().name);assertEquals(2,page.items.single().unread)
        assertEquals(2,paths.size)
    }
    @Test fun privatePagingPreserves64BitKeysAndDoesNotShowWrongSession()=runTest {
        var wrong=false
        val repo=BiliMessageRepository(wbiApi(HttpClient(wbiEngine{r->
            assertEquals("30",r.url.parameters["size"]);assertEquals("2000000000000000",r.url.parameters["end_seqno"])
            val row=if(wrong)JsonObject(msg().toMutableMap().apply{put("sender_uid",JsonPrimitive(9))}) else msg()
            respond(root("""{"messages":[$row],"has_more":1,"min_seqno":1285290404823041}"""))
        })){"SESSDATA=fixture"})
        val page=repo.conversation(alice,MessageCursor("2000000000000000"));assertEquals("7354295169819585966",page.items.single().key)
        assertEquals("1285290404823041",page.next?.id)
        wrong=true;assertFailsWith<PlatformFailure>{repo.conversation(alice,MessageCursor("2000000000000000"))}
    }
    @Test fun nullInboxIsEmptyButMissingListsAreErrors()=runTest {
        var d="""{"session_list":null,"has_more":0}"""
        val repo=BiliMessageRepository(wbiApi(HttpClient(wbiEngine{respond(root(d))})){"SESSDATA=fixture"})
        assertTrue(repo.sessions().items.isEmpty());d="{}";assertFailsWith<PlatformFailure>{repo.sessions()}
    }
    @Test fun notificationCategoriesParseLatestLikesAndExactCursors()=runTest {
        val item="""{"id":123,"user":{"nickname":"示例作者"},"users":[{"nickname":"示例作者"}],"reply_time":1234567,"at_time":1234567,"like_time":1234567,"counts":1,"item":{"business":"评论","title":"歌曲讨论","source_content":"好听","target_reply_content":"原评论"}}"""
        val feed="""{"cursor":{"is_end":false,"id":123,"time":1234567},"items":[$item]}"""
        val repo=BiliMessageRepository(BiliApi(HttpClient(MockEngine{r->
            val d=if(r.url.encodedPath.endsWith("like"))"""{"latest":{"items":[$item]},"total":$feed}""" else feed
            if(r.url.parameters.contains("id"))assertEquals("1234567",r.url.parameters["${r.url.encodedPath.substringAfterLast('/')}_time"])
            respond(root(d))
        })){"SESSDATA=fixture"})
        for(category in listOf(MessageCategory.REPLY,MessageCategory.AT,MessageCategory.LIKE)) {
            val p=repo.notices(category);assertEquals(1,p.items.size);assertEquals(MessageCursor("123","1234567"),p.next)
            assertTrue(p.items.single().body.contains("原评论"));repo.notices(category,p.next)
        }
    }
    @Test fun systemMergesTwoSourcesAndUsesCursorWithoutMarkingRead()=runTest {
        val paths=mutableListOf<String>()
        val row="""{"id":123,"cursor":1712305278000000,"title":"通知","content":"<p>示例正文</p>","time_at":"2024-04-05 12:00:00"}"""
        val repo=BiliMessageRepository(BiliApi(HttpClient(MockEngine{r->
            paths+=r.url.encodedPath
            val d=if(r.url.encodedPath.endsWith("query_notify_list")){assertEquals("1712305278000000",r.url.parameters["cursor"]);"[]"}else """{"system_notify_list":[$row]}"""
            respond(root(d))
        })){"SESSDATA=fixture"})
        val p=repo.notices(MessageCategory.SYSTEM);assertEquals(1,p.items.size);assertTrue(p.items.single().html)
        assertTrue(repo.notices(MessageCategory.SYSTEM,p.next).items.isEmpty());assertEquals(3,paths.size)
    }
    private class Accounts:AccountRepository {
        override val session=MutableStateFlow(AccountSession(SessionStamp("7",1),SessionStatus.AUTHENTICATED,Account(7,"示例账号")))
        override suspend fun verify()=session.value.account
        override suspend fun accept(cookie:String)=error("unused")
        override suspend fun logout(){session.value=AccountSession(SessionStamp("guest",2),SessionStatus.GUEST)}
        override fun requireCurrent(stamp:SessionStamp){if(stamp!=session.value.stamp||session.value.status!=SessionStatus.AUTHENTICATED)throw PlatformFailure("账号已变化")}
    }
    private open inner class Repo:MessageRepository {
        var calls=0;var fails=false;var gate:CompletableDeferred<Unit>?=null
        override suspend fun sessions(cursor:MessageCursor?):MessagePage<MessageSession>{calls++;gate?.await();if(fails)throw PlatformFailure("网络请求失败");return if(cursor==null)MessagePage(listOf(alice),MessageCursor("100")) else MessagePage(listOf(alice.copy(talker=9)),null)}
        override suspend fun conversation(session:MessageSession,cursor:MessageCursor?)=MessagePage(listOf(PrivateMessage("1","1",8,1000,MessageBody("你好"))),null)
        override suspend fun notices(category:MessageCategory,cursor:MessageCursor?)=MessagePage(listOf(MessageNotice("2","示例通知","全文")),null)
        override suspend fun unread()=mapOf(MessageCategory.REPLY to 3)
    }
    @Test fun controllerLoadsOnlyWhenVisibleAndCachesVisitedTabsWithoutPolling()=runTest {
        val accounts=Accounts();val repo=Repo();val c=MessagesController(backgroundScope,repo,accounts);runCurrent();assertEquals(0,repo.calls)
        c.show();runCurrent();assertEquals(1,repo.calls);assertEquals(3,c.state.value.unread[MessageCategory.REPLY])
        c.select(MessageCategory.REPLY);runCurrent();assertEquals(1,c.state.value.notifications[MessageCategory.REPLY]?.items?.size)
        c.select(MessageCategory.PRIVATE);runCurrent();assertEquals(1,repo.calls)
        advanceTimeBy(60000);runCurrent();assertEquals(1,repo.calls);c.hide();assertTrue(c.state.value.sessions.items.isEmpty())
    }
    @Test fun refreshRetainsRowsUntilReplacementAndKeepsErrorsVisible()=runTest {
        val repo=Repo();val c=MessagesController(backgroundScope,repo,Accounts());c.show();runCurrent()
        repo.gate=CompletableDeferred();c.refresh();runCurrent();assertTrue(c.state.value.loading);assertEquals(listOf(alice),c.state.value.sessions.items)
        repo.fails=true;repo.gate!!.complete(Unit);runCurrent();assertFalse(c.state.value.loading)
        assertEquals("网络请求失败",c.state.value.sessions.error);assertEquals(listOf(alice),c.state.value.sessions.items);c.hide()
    }
    @Test fun conversationStopsAtEndRatherThanReusingSessionCursor()=runTest {
        var calls=0;val repo=object:Repo(){override suspend fun conversation(session:MessageSession,cursor:MessageCursor?):MessagePage<PrivateMessage>{calls++;return super.conversation(session,cursor)}}
        val c=MessagesController(backgroundScope,repo,Accounts());c.show();runCurrent();c.open(alice);runCurrent();c.more();runCurrent();assertEquals(1,calls);c.back();c.more();runCurrent();assertEquals(2,c.state.value.sessions.items.size);c.hide()
    }
    @Test fun accountChangePurgesPrivateMemoryAndIgnoresUncancellableOldResults()=runTest {
        val accounts=Accounts();val gate=CompletableDeferred<Unit>()
        val repo=object:Repo(){override suspend fun sessions(cursor:MessageCursor?):MessagePage<MessageSession>{withContext(NonCancellable){gate.await()};return MessagePage(listOf(alice))}}
        val c=MessagesController(backgroundScope,repo,accounts);c.show();runCurrent();accounts.logout();runCurrent()
        assertFalse(c.state.value.authenticated);assertTrue(c.state.value.sessions.items.isEmpty());gate.complete(Unit);runCurrent();assertTrue(c.state.value.sessions.items.isEmpty())
        c.refresh();runCurrent();assertTrue(c.state.value.sessions.items.isEmpty());c.hide()
    }
    @Test fun expiryErasesAlreadyLoadedPrivateMessages()=runTest {
        val repo=object:Repo(){var expired=false;override suspend fun sessions(cursor:MessageCursor?):MessagePage<MessageSession>{if(expired)throw PlatformFailure("登录已失效",-101);return super.sessions(cursor)}}
        val c=MessagesController(backgroundScope,repo,Accounts());c.show();runCurrent();assertTrue(c.state.value.sessions.items.isNotEmpty());repo.expired=true;c.refresh();runCurrent()
        assertFalse(c.state.value.authenticated);assertTrue(c.state.value.sessions.items.isEmpty());c.hide()
    }
    @Test fun withdrawalEventsRedactPreviouslyLoadedTargetsAcrossPages()=runTest {
        val repo=object:Repo(){override suspend fun conversation(session:MessageSession,cursor:MessageCursor?)=if(cursor==null)MessagePage(listOf(PrivateMessage("1","2",8,1000,MessageBody("私密原文"))),MessageCursor("2"))else MessagePage(listOf(PrivateMessage("3","1",8,500,MessageBody("消息已撤回","撤回","1"))),null)}
        val c=MessagesController(backgroundScope,repo,Accounts());c.show();runCurrent();c.open(alice);runCurrent();c.more();runCurrent()
        assertEquals("消息已撤回",c.state.value.conversation.items.first{it.key=="1"}.body.text);c.hide()
    }
}
