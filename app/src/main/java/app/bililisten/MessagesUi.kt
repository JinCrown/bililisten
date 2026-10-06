package app.bililisten

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.text.HtmlCompat
import app.bililisten.shared.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun messageTime(at:Long)=if(at<=0)"" else DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
private fun noticeText(notice:MessageNotice)=if(notice.html)HtmlCompat.fromHtml(notice.body,HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() else notice.body

@Composable internal fun MessagesPage(view:MessagesView,modifier:Modifier=Modifier,back:()->Unit,backConversation:()->Unit,
    select:(MessageCategory)->Unit,open:(MessageSession)->Unit,refresh:()->Unit,refreshNow:suspend ()->Unit,more:()->Unit,login:()->Unit,official:()->Unit) {
    var detail by remember(view.stamp,view.authenticated){mutableStateOf<MessageNotice?>(null)}
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min=66.dp).padding(horizontal=PageSideInset),verticalAlignment=Alignment.CenterVertically) {
            ActionIcon(Mark.BACK,if(view.selected!=null)"返回消息列表" else "返回",if(view.selected!=null)backConversation else back)
            Text(view.selected?.name ?: "消息",Modifier.weight(1f).padding(start=6.dp),fontWeight=FontWeight.Bold,fontSize=22.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            ActionIcon(Mark.REFRESH,"刷新消息",refresh,enabled=view.authenticated&&!view.loading)
            ActionIcon(Mark.TV,"在 B站查看消息",official)
        }
        if(!view.authenticated)Column(Modifier.fillMaxWidth().weight(1f).padding(PageSideInset),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            Glyph(Mark.MESSAGE,Modifier.size(40.dp),muted());Spacer(Modifier.height(16.dp));Text("请登录查看消息",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
            Text("账号未登录或登录已失效",Modifier.padding(vertical=8.dp),color=muted(),fontSize=13.sp)
            Button(login){Text("登录 B站")}
        } else {
            if(view.selected==null)ScrollableTabRow(selectedTabIndex=view.category.ordinal,edgePadding=PageSideInset,containerColor=MaterialTheme.colorScheme.background) {
                MessageCategory.entries.forEach{category->Tab(selected=category==view.category,onClick={detail=null;select(category)},text={
                    val unread=view.unread[category] ?: 0
                    Text(category.title+if(unread>0)" ${if(unread>99)"99+" else unread}" else "",maxLines=1)
                })}
            }
            UserRefreshBox(view.loading,refreshNow,Modifier.weight(1f)) {
                val conversation=view.selected!=null
                val feed=view.notifications[view.category] ?: MessageFeed()
                val loading=view.loading
                val error=if(conversation)view.conversation.error else if(view.category==MessageCategory.PRIVATE)view.sessions.error else feed.error
                val count=if(conversation)view.conversation.items.size else if(view.category==MessageCategory.PRIVATE)view.sessions.items.size else feed.items.size
                val next=if(conversation)view.conversation.next else if(view.category==MessageCategory.PRIVATE)view.sessions.next else feed.next
                val list=remember(view.stamp,view.category,view.selected?.key){LazyListState()}
                LazyColumn(Modifier.fillMaxSize(),state=list,reverseLayout=conversation,contentPadding=PaddingValues(horizontal=PageSideInset,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(if(conversation)12.dp else 0.dp,if(conversation)Alignment.Bottom else Alignment.Top)) {
                    if(conversation)items(view.conversation.items.sortedWith(compareByDescending<PrivateMessage>{it.sentAt}.thenByDescending{it.sequence.length}.thenByDescending{it.sequence}),key={it.key}){row->
                        val mine=row.sender.toString()==view.stamp?.account
                        Column(Modifier.fillMaxWidth()) {
                            Text(messageTime(row.sentAt),Modifier.align(Alignment.CenterHorizontally).padding(bottom=6.dp),fontSize=11.sp,color=muted())
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=if(mine)Arrangement.End else Arrangement.Start) {
                                Surface(shape=RoundedCornerShape(8.dp),color=if(mine)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,modifier=Modifier.widthIn(max=310.dp)) {
                                    SelectionContainer {Text(row.body.text,Modifier.padding(12.dp),fontSize=15.sp,lineHeight=23.sp)}
                                }
                            }
                        }
                    } else if(view.category==MessageCategory.PRIVATE)items(view.sessions.items,key={it.key}){session->
                        Row(Modifier.fillMaxWidth().clickable{open(session)}.padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically) {
                            Cover(session.avatar,Modifier.size(48.dp).clip(CircleShape))
                            Column(Modifier.weight(1f).padding(horizontal=12.dp)) {
                                Row(verticalAlignment=Alignment.CenterVertically){Text(session.name,Modifier.weight(1f),fontSize=15.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(messageTime(session.updatedAt/1000),fontSize=11.sp,color=muted())}
                                Text(session.preview.text,Modifier.padding(top=5.dp),fontSize=13.sp,color=muted(),maxLines=2,overflow=TextOverflow.Ellipsis)
                            }
                            if(session.unread>0)Badge{Text(if(session.unread>99)"99+" else "${session.unread}")}
                        };HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                    } else items(feed.items,key={it.id}){row->
                        Row(Modifier.fillMaxWidth().clickable{detail=row}.padding(vertical=14.dp),verticalAlignment=Alignment.Top) {
                            if(row.avatar.isNotBlank())Cover(row.avatar,Modifier.size(38.dp).clip(CircleShape)) else Glyph(if(view.category==MessageCategory.LIKE)Mark.LIKE else Mark.MESSAGE,Modifier.size(28.dp).padding(top=3.dp),MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f).padding(start=12.dp)) {
                                Text(row.title,fontSize=15.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                                val text=remember(row){noticeText(row)}
                                if(text.isNotBlank())Text(text,Modifier.padding(top=6.dp),fontSize=13.sp,lineHeight=20.sp,color=muted(),maxLines=3,overflow=TextOverflow.Ellipsis)
                                Text(row.timeText.ifBlank{messageTime(row.sentAt)},Modifier.padding(top=8.dp),fontSize=11.sp,color=muted())
                            }
                            Glyph(Mark.CHEVRON,Modifier.size(16.dp),muted())
                        };HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
                    }
                    if(loading)item{Column(Modifier.fillMaxWidth().padding(16.dp),horizontalAlignment=Alignment.CenterHorizontally){CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp);if(count==0)Text("正在读取消息",Modifier.padding(top=10.dp),fontSize=13.sp,color=muted())}}
                    if(error!=null)item{Column(Modifier.fillMaxWidth().padding(vertical=12.dp)){Text(error,color=MaterialTheme.colorScheme.error,fontSize=13.sp);TextButton(refresh){Text("重试")}}}
                    if(count==0&&!loading&&error==null)item{Text(if(conversation)"暂无聊天记录" else "暂无${view.category.title}消息",Modifier.fillMaxWidth().padding(vertical=32.dp),color=muted())}
                    if(next!=null&&!loading)item{TextButton(if(count<500)more else official,Modifier.fillMaxWidth()){Text(if(count>=500)"在 B站查看更早消息" else if(conversation)"加载更早消息" else "加载更多")}}
                }
            }
            Text("仅查看 · 保留 B站未读状态",Modifier.fillMaxWidth().padding(horizontal=PageSideInset,vertical=8.dp),fontSize=11.sp,color=muted())
        }
    }
    detail?.let{notice->AlertDialog(onDismissRequest={detail=null},title={Text(notice.title,fontSize=18.sp)},text={
        Column(Modifier.heightIn(max=460.dp).verticalScroll(rememberScrollState())){Text(notice.timeText.ifBlank{messageTime(notice.sentAt)},fontSize=12.sp,color=muted());Spacer(Modifier.height(12.dp));SelectionContainer{Text(remember(notice){noticeText(notice)}.ifBlank{"没有附加正文"},fontSize=15.sp,lineHeight=23.sp)}}
    },confirmButton={TextButton({detail=null}){Text("关闭")}})}
}
