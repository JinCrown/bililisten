package app.bililisten.shared

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class MessageCategory(val title:String) { PRIVATE("私信"), REPLY("回复"), AT("@我"), LIKE("点赞"), SYSTEM("系统") }
data class MessageCursor(val id:String,val time:String="")
data class MessageBody(val text:String,val type:String="文字",val withdrawnTarget:String?=null)
data class PrivateMessage(val key:String,val sequence:String,val sender:Long,val sentAt:Long,val body:MessageBody)
data class MessageSession(val talker:Long,val type:Int,val name:String,val avatar:String="",val updatedAt:Long=0,
    val unread:Int=0,val preview:MessageBody=MessageBody("暂无消息")) { val key get()="$type/$talker" }
data class MessageNotice(val id:String,val title:String,val body:String,val actor:String="",val avatar:String="",
    val timeText:String="",val sentAt:Long=0,val html:Boolean=false)
data class MessagePage<T>(val items:List<T>,val next:MessageCursor?=null)
interface MessageRepository {
    suspend fun sessions(cursor:MessageCursor?=null):MessagePage<MessageSession>
    suspend fun conversation(session:MessageSession,cursor:MessageCursor?=null):MessagePage<PrivateMessage>
    suspend fun notices(category:MessageCategory,cursor:MessageCursor?=null):MessagePage<MessageNotice>
    suspend fun unread():Map<MessageCategory,Int>
}
data class MessageFeed<T>(val items:List<T> = emptyList(),val next:MessageCursor?=null,val loading:Boolean=false,
    val loaded:Boolean=false,val error:String?=null)
data class MessagesView(val stamp:SessionStamp?=null,val authenticated:Boolean=false,val category:MessageCategory=MessageCategory.PRIVATE,
    val sessions:MessageFeed<MessageSession> = MessageFeed(),val notifications:Map<MessageCategory,MessageFeed<MessageNotice>> = emptyMap(),
    val selected:MessageSession?=null,val conversation:MessageFeed<PrivateMessage> = MessageFeed(),val unread:Map<MessageCategory,Int> = emptyMap()) {
    val loading get()=if(selected!=null)conversation.loading else if(category==MessageCategory.PRIVATE)sessions.loading else notifications[category]?.loading==true
}

/** Memory-only inbox, with independent identity and request epochs. No background polling or writes. */
class MessagesController(private val scope:CoroutineScope,private val repository:MessageRepository,private val accounts:AccountRepository) {
    private val mutable=MutableStateFlow(MessagesView())
    val state=mutable.asStateFlow()
    private var visible=false
    private var revision=0L
    private var readJob:Job?=null
    private var unreadJob:Job?=null
    init { scope.launch {accounts.session.collect {identityChanged(it)}} }
    private fun identityChanged(session:AccountSession) {
        val authenticated=session.status==SessionStatus.AUTHENTICATED
        if(state.value.stamp==session.stamp&&state.value.authenticated==authenticated)return
        cancel()
        mutable.value=MessagesView(session.stamp,authenticated)
        if(visible&&authenticated){load();loadUnread()}
    }
    private fun current(ticket:Long,stamp:SessionStamp)=visible&&ticket==revision&&accounts.session.value.stamp==stamp&&accounts.session.value.status==SessionStatus.AUTHENTICATED
    private fun cancel(){revision++;readJob?.cancel();readJob=null;unreadJob?.cancel();unreadJob=null}
    fun show(){val was=visible;visible=true;identityChanged(accounts.session.value);if(!was&&state.value.authenticated){load();loadUnread()}}
    fun hide(){visible=false;cancel();mutable.value=MessagesView(accounts.session.value.stamp,accounts.session.value.status==SessionStatus.AUTHENTICATED)}
    fun select(category:MessageCategory) {
        if(state.value.category==category&&state.value.selected==null)return
        readJob?.cancel();revision++
        mutable.value=mutable.value.copy(category=category,selected=null,conversation=MessageFeed(),sessions=mutable.value.sessions.copy(loading=false),notifications=mutable.value.notifications.mapValues{it.value.copy(loading=false)})
        load()
    }
    fun open(session:MessageSession) {
        if(session !in state.value.sessions.items||!state.value.authenticated)return
        readJob?.cancel();revision++
        mutable.value=mutable.value.copy(selected=session,conversation=MessageFeed())
        load()
    }
    fun back(){readJob?.cancel();revision++;mutable.value=mutable.value.copy(selected=null,conversation=MessageFeed())}
    fun refresh(){if(!state.value.loading){load(force=true);loadUnread()}}
    fun more(){load(more=true)}
    private fun loadUnread() {
        if(!visible||!state.value.authenticated||unreadJob?.isActive==true)return
        val stamp=accounts.session.value.stamp
        unreadJob=scope.launch {
            try {accounts.requireCurrent(stamp);val counts=repository.unread();ensureActive();accounts.requireCurrent(stamp)
                if(visible&&state.value.stamp==stamp&&state.value.authenticated)mutable.value=mutable.value.copy(unread=counts)
            }catch(e:CancellationException){throw e}catch(e:PlatformFailure){if(e.code==-101)expired(stamp)}catch(_:Exception){}
        }
    }
    private fun expired(stamp:SessionStamp) {
        if(accounts.session.value.stamp!=stamp)return
        cancel();mutable.value=MessagesView(stamp,false)
    }
    private fun <T> merge(old:MessageFeed<T>,page:MessagePage<T>,more:Boolean,key:(T)->String):MessageFeed<T> {
        if(more&&page.next==old.next)throw PlatformFailure("分页没有推进，请刷新后再试")
        val keys=page.items.map(key).toSet()
        val items=(if(more)old.items.filter{key(it) !in keys}+page.items else page.items).distinctBy(key)
        if(more&&items.size==old.items.size&&page.next!=null)throw PlatformFailure("分页没有新增内容，请刷新后再试")
        return MessageFeed(items,page.next,loaded=true)
    }
    private fun load(force:Boolean=false,more:Boolean=false) {
        if(!visible||!state.value.authenticated||readJob?.isActive==true)return
        val view=state.value;val stamp=accounts.session.value.stamp;val category=view.category;val selected=view.selected
        val loaded=selected?.let{view.conversation.loaded} ?: if(category==MessageCategory.PRIVATE)view.sessions.loaded else view.notifications[category]?.loaded==true
        val next=if(selected!=null)view.conversation.next else if(category==MessageCategory.PRIVATE)view.sessions.next else view.notifications[category]?.next
        val size=selected?.let{view.conversation.items.size} ?: if(category==MessageCategory.PRIVATE)view.sessions.items.size else view.notifications[category]?.items?.size ?: 0
        if(!force&&!more&&loaded||more&&(next==null||size>=500))return
        val ticket=++revision
        fun change(block:MessagesView.()->MessagesView){if(current(ticket,stamp))mutable.value=mutable.value.block()}
        fun status(loading:Boolean,error:String?=null) {change {
            when {selected!=null->copy(conversation=conversation.copy(loading=loading,error=error))
                category==MessageCategory.PRIVATE->copy(sessions=sessions.copy(loading=loading,error=error))
                else->copy(notifications=notifications+(category to (notifications[category] ?: MessageFeed()).copy(loading=loading,error=error)))}
        }}
        status(true)
        readJob=scope.launch {
            try {
                accounts.requireCurrent(stamp)
                val cursor=if(more)next else null
                when {
                    selected!=null->{val page=repository.conversation(selected,cursor);ensureActive();accounts.requireCurrent(stamp)
                        val feed=merge(view.conversation,page,more){it.key}
                        val withdrawn=feed.items.mapNotNull{it.body.withdrawnTarget}.toSet()
                        change{copy(conversation=feed.copy(items=feed.items.map{if(it.key in withdrawn)it.copy(body=MessageBody("消息已撤回","撤回")) else it}))}}
                    category==MessageCategory.PRIVATE->{val page=repository.sessions(cursor);ensureActive();accounts.requireCurrent(stamp);change{copy(sessions=merge(view.sessions,page,more){it.key})}}
                    else->{val page=repository.notices(category,cursor);ensureActive();accounts.requireCurrent(stamp);change{copy(notifications=notifications+(category to merge(view.notifications[category] ?: MessageFeed(),page,more){it.id}))}}
                }
            }catch(e:CancellationException){throw e}catch(e:Exception) {
                if((e as? PlatformFailure)?.code==-101)expired(stamp) else status(false,(e as? PlatformFailure)?.category ?: "消息暂时无法读取，请稍后重试")
            }
        }
    }
}
