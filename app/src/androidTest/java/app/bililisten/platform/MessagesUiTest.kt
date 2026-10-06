package app.bililisten.platform

import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.bililisten.*
import app.bililisten.shared.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.media3.common.util.UnstableApi @RunWith(AndroidJUnit4::class)
class MessagesUiTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val app=i.targetContext.applicationContext as ListenApplication
    private fun find(label:String):AccessibilityNodeInfo? {
        fun visit(n:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(n==null||!n.refresh()||!n.isVisibleToUser)return null
            if(n.text?.toString()==label||n.contentDescription?.toString()==label)return n
            for(k in 0 until n.childCount)visit(n.getChild(k))?.let{return it};return null
        };return visit(i.uiAutomation.rootInActiveWindow)
    }
    private fun waitFor(label:String) {val end=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<end){if(find(label)!=null)return;Thread.sleep(50)};error("Missing fixture UI: $label")}
    private fun click(label:String) {
        if(label in listOf("系统","点赞","@我")&&find(label)==null){var tab=find("回复 3") ?: find("@我") ?: find("点赞");while(tab!=null&&!tab.isScrollable)tab=tab.parent;tab?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);i.waitForIdleSync();Thread.sleep(200)}
        waitFor(label);var n=find(label)!!;while(!n.isClickable&&n.parent!=null)n=n.parent
        assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));i.waitForIdleSync();Thread.sleep(200)
    }
    private fun shot(name:String) {
        val folder=File(app.filesDir,"messages-evidence").apply{mkdirs()}
        val bitmap=i.uiAutomation.takeScreenshot() ?: error("No screenshot")
        File(folder,"fixture-$name.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private class Accounts:AccountRepository {
        override val session=MutableStateFlow(AccountSession(SessionStamp("7",1),SessionStatus.AUTHENTICATED,Account(7,"示例账号")))
        override suspend fun verify()=session.value.account
        override suspend fun accept(cookie:String)=error("unused")
        override suspend fun logout(){session.value=AccountSession(SessionStamp("guest",2),SessionStatus.GUEST)}
        override fun requireCurrent(stamp:SessionStamp){check(stamp==session.value.stamp)}
    }
    private class Fixture:MessageRepository {
        val user=MessageSession(8,1,"音乐作者 · 示例",updatedAt=1770000000000000,unread=2,preview=MessageBody("你好，这首歌的现场版已经更新了。"))
        override suspend fun sessions(cursor:MessageCursor?)=MessagePage(listOf(user,MessageSession(9,1,"UP主小助手",updatedAt=1770000000000000,preview=MessageBody("音乐活动通知 · 示例","通知"))))
        override suspend fun conversation(session:MessageSession,cursor:MessageCursor?)=if(cursor==null)MessagePage(listOf(
            PrivateMessage("3","3",8,1770000000000,MessageBody("消息已撤回","撤回")),
            PrivateMessage("2","2",7,1770000000000,MessageBody("谢谢！现场版也很好听。")),
            PrivateMessage("1","1",8,1770000000000,MessageBody("你好，这首歌的现场版已经更新了。"))),MessageCursor("1")) else MessagePage(listOf(PrivateMessage("0","0",8,1769900000000,MessageBody("更早的聊天记录 · 示例"))))
        override suspend fun notices(category:MessageCategory,cursor:MessageCursor?)=MessagePage(listOf(MessageNotice("1",when(category){MessageCategory.REPLY->"示例作者回复了我";MessageCategory.AT->"示例作者@了我";MessageCategory.LIKE->"示例作者赞了我的评论";else->"音乐活动通知 · 示例"},
            if(category==MessageCategory.SYSTEM)"<p>音乐活动已经开始。</p><p>这是用于验证全文显示的示例通知，不是真实账号消息。</p>" else "这首歌很适合夜间收听。\n\n原文：希望下次可以听到现场版。",timeText="2026-10-04 12:00:00",html=category==MessageCategory.SYSTEM)))
        override suspend fun unread()=mapOf(MessageCategory.REPLY to 3)
    }
    private fun fixture(theme:Theme,fontScale:Float=1f,block:(MessagesController,Accounts)->Unit) {
        val activity=i.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);val accounts=Accounts();val repo=Fixture();lateinit var c:MessagesController
        try {
            i.runOnMainSync {
                c=MessagesController(scope,repo,accounts);c.show()
                activity.setContent {ListenTheme(theme){val density=LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density,fontScale)){val view by c.state.collectAsState();Surface{
                        Box((if(fontScale>1f)Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()).windowInsetsPadding(WindowInsets.safeDrawing)) {MessagesPage(view,back={},backConversation=c::back,select=c::select,open=c::open,refresh=c::refresh,refreshNow={c.refresh();c.state.first{!it.loading}},more=c::more,login={},official={})}
                    }}
                }}
            }
            waitFor(repo.user.name);block(c,accounts)
        }finally{i.runOnMainSync{c.hide();scope.cancel();activity.finish()}}
    }
    private fun verify(theme:Theme,name:String,fontScale:Float=1f)=fixture(theme,fontScale){_,_->
        shot("inbox-$name");click("音乐作者 · 示例");waitFor("谢谢！现场版也很好听。");waitFor("消息已撤回")
        assertNull(find("private secret"))
        val last=Rect();val footer=Rect();find("消息已撤回")!!.getBoundsInScreen(last);find("仅查看 · 保留 B站未读状态")!!.getBoundsInScreen(footer)
        assertTrue("Latest message is placed near the bottom",footer.top-last.bottom in 0..200)
        shot("chat-$name");click("加载更早消息");waitFor("更早的聊天记录 · 示例")
        click("返回消息列表");click("回复 3");waitFor("示例作者回复了我");shot("reply-$name");click("示例作者回复了我");waitFor("关闭");shot("detail-$name");click("关闭")
        click("@我");waitFor("示例作者@了我");click("点赞");waitFor("示例作者赞了我的评论");click("系统");waitFor("音乐活动通知 · 示例");assertNull(find("<p>音乐活动已经开始。</p>"));shot("system-$name")
        val rect=Rect();find("仅查看 · 保留 B站未读状态")!!.getBoundsInScreen(rect);assertFalse(rect.isEmpty)
    }
    @Test fun lightInboxConversationAndAllNotificationCategories()=verify(Theme.LIGHT,"light")
    @Test fun darkInboxConversationAndAllNotificationCategories()=verify(Theme.DARK,"dark")
    @Test fun largeFontsKeepRowsConversationAndFullNoticeAccessible()=verify(Theme.LIGHT,"large",1.6f)
    @Test fun accountExitClearsVisibleMessageAndConversation()=fixture(Theme.LIGHT){_,accounts->
        click("音乐作者 · 示例");waitFor("谢谢！现场版也很好听。");i.runOnMainSync{runBlocking{accounts.logout()}}
        waitFor("请登录查看消息");assertNull(find("谢谢！现场版也很好听。"));assertNull(find("音乐作者 · 示例"));shot("guest")
    }
    @Test fun onlineReadOnlyInboxAndNotificationsPreserveUnread()=runBlocking {
        assertNotNull("Existing account needed for read-only verification",app.accounts.verify())
        val repo=BiliMessageRepository(app.api);val before=repo.unread();val sessions=repo.sessions()
        val notificationCounts=linkedMapOf<MessageCategory,Int>();var pages=0
        for(category in MessageCategory.entries.filter{it!=MessageCategory.PRIVATE}) {
            val page=repo.notices(category);notificationCounts[category]=page.items.size
            page.next?.let {cursor->repo.notices(category,cursor);pages++}
        }
        var chatCount=0
        sessions.items.firstOrNull()?.let{session->val chat=repo.conversation(session);chatCount=chat.items.size;chat.next?.let{repo.conversation(session,it);pages++}}
        sessions.next?.let{repo.sessions(it);pages++}
        val after=repo.unread();val finalSessions=repo.sessions()
        assertTrue("Actual user names must be resolved",sessions.items.any{!it.name.startsWith("用户 ")})
        assertEquals("Read-only notifications must preserve unread counts",before,after)
        val sessionUnread=finalSessions.items.associate{it.key to it.unread}
        assertTrue("Read-only private messages must not lower unread counts",sessions.items.all{sessionUnread[it.key]?.let{now->now>=it.unread} ?: true})
        val counts=notificationCounts.entries.joinToString(","){"\"${it.key.name}\":${it.value}"}
        File(app.filesDir,"messages-evidence").apply{mkdirs()}.resolve("online.json").writeText("""{"authenticated":true,"sessions":${sessions.items.size},"conversationMessages":$chatCount,"notifications":{$counts},"additionalPages":$pages,"unreadPreserved":true,"readOnly":true,"privateBodiesExported":false}""")
    }
}
