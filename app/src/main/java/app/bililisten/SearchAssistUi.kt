package app.bililisten

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import app.bililisten.shared.SearchAssistView
import app.bililisten.shared.SearchWord

@Composable internal fun HomeSearchBar(query:String,onSearch:()->Unit,onLink:()->Unit) {
    val colors=MaterialTheme.colorScheme
    val shape=RoundedCornerShape(18.dp)
    val large=LocalDensity.current.fontScale>1.3f
    Surface(Modifier.fillMaxWidth().padding(top=6.dp,bottom=0.dp).semantics{contentDescription="首页搜索栏"},
        shape=shape,color=colors.surface,shadowElevation=0.dp,
        border=BorderStroke(1.dp,colors.primary.copy(alpha=.12f))) {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).background(Brush.horizontalGradient(listOf(colors.surface,colors.primaryContainer.copy(alpha=.12f))))
            .padding(start=2.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onSearch,Modifier.size(48.dp).semantics{contentDescription="搜索视频"}) {
                Image(painterResource(R.drawable.tv_listener),contentDescription=null,modifier=Modifier.size(30.dp))
            }
            Box(Modifier.weight(1f).heightIn(min=48.dp).clickable(onClick=onSearch)
                .semantics{contentDescription="搜索关键词";role=Role.Button}.padding(end=8.dp),contentAlignment=Alignment.CenterStart) {
                Text(query.ifBlank{"搜索视频、UP 主"},fontSize=if(large)12.sp else 14.sp,
                    fontWeight=if(query.isBlank())FontWeight.Normal else FontWeight.Medium,
                    color=if(query.isBlank())colors.onSurfaceVariant else colors.onSurface,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Box(Modifier.width(1.dp).height(20.dp).background(colors.outlineVariant))
            IconButton(onLink,Modifier.size(48.dp).semantics{contentDescription="粘贴链接或 BV 号"}) { Glyph(Mark.LINK,Modifier.size(20.dp),colors.onSurfaceVariant) }
            Box(Modifier.widthIn(min=58.dp).heightIn(min=48.dp).clickable(onClick=onSearch)
                .semantics{contentDescription="首页搜索按钮";role=Role.Button}.padding(horizontal=4.dp),contentAlignment=Alignment.Center) {
                Text("搜索",Modifier.background(colors.primary,RoundedCornerShape(20.dp)).padding(horizontal=14.dp,vertical=7.dp),
                    color=colors.onPrimary,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
            }
        }
    }
}

@Composable internal fun SearchPill(query:String,onQuery:(String)->Unit,onSearch:()->Unit,onLink:()->Unit,
    onFocus:()->Unit={},requestFocus:Boolean=false) {
    val requester=remember{FocusRequester()}
    val keyboard=LocalSoftwareKeyboardController.current
    LaunchedEffect(requestFocus){if(requestFocus){requester.requestFocus();withFrameNanos{};keyboard?.show()}}
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).background(MaterialTheme.colorScheme.surface,RoundedCornerShape(16.dp))
        .border(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.65f),RoundedCornerShape(16.dp)),verticalAlignment=Alignment.CenterVertically) {
        ActionIcon(Mark.SEARCH,"搜索视频",onSearch,color=muted())
        BasicTextField(query,onQuery,Modifier.weight(1f).focusRequester(requester).onFocusChanged{if(it.isFocused)onFocus()}
            .padding(vertical=10.dp).semantics{contentDescription="搜索关键词"},singleLine=true,
            textStyle=LocalTextStyle.current.copy(color=MaterialTheme.colorScheme.onSurface,fontSize=14.sp),
            keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={onSearch()}),
            decorationBox={inner->if(query.isEmpty())Text("搜索视频、UP 主关键词",fontSize=14.sp,color=muted(),maxLines=1,overflow=TextOverflow.Ellipsis);inner()})
        if(query.isNotEmpty())ActionIcon(Mark.CLOSE,"清空关键词",{onQuery("");requester.requestFocus();keyboard?.show()},color=muted())
        ActionIcon(Mark.LINK,"粘贴链接或 BV 号",onLink,color=muted())
    }
}

internal fun LazyListScope.searchAssistItems(view:SearchAssistView,choose:(String)->Unit,retryHot:()->Unit) {
    val suggestions=view.query.isNotBlank()
    item("search-words-title") {
        Row(Modifier.fillMaxWidth().padding(top=4.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(suggestions)"搜索联想" else "热搜推荐",Modifier.weight(1f).semantics{heading()},fontSize=20.sp,fontWeight=FontWeight.Bold)
            if(if(suggestions)view.suggestionsBusy else view.hotBusy)CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
            else if(!suggestions)Text("B 站热搜",color=muted(),fontSize=11.sp)
        }
    }
    val words=if(suggestions)view.suggestions else view.hot
    val error=if(suggestions)view.suggestionsError else view.hotError
    if(error!=null)item("search-words-error") {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(error,Modifier.weight(1f),color=muted(),fontSize=12.sp)
            if(!suggestions)TextButton(retryHot){Text("重新读取")}
        }
    }
    if(words.isEmpty() && error==null && !(if(suggestions)view.suggestionsBusy else view.hotBusy))item("search-words-empty") {
        Text(if(suggestions)"暂无联想词，按搜索键即可搜索" else "暂无热搜词，输入想听的内容试试",color=muted(),fontSize=12.sp)
    }
    items(words,key={"search-word/${it.keyword}"}) { word ->
        SearchWordRow(word,if(suggestions)null else words.indexOf(word)+1){choose(word.keyword)}
    }
}

@Composable internal fun SearchWordRow(word:SearchWord,rank:Int?=null,choose:()->Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface,RoundedCornerShape(16.dp))
        .clickable(onClick=choose).semantics{contentDescription="搜索 ${word.label}"}
        .padding(horizontal=15.dp,vertical=15.dp),verticalAlignment=Alignment.CenterVertically) {
        if(rank==null)Glyph(Mark.SEARCH,Modifier.size(18.dp),muted())
        else Text("$rank",Modifier.width(23.dp),color=if(rank<=3)ListenPink else muted(),fontSize=17.sp,fontWeight=FontWeight.Bold)
        Text(word.label,Modifier.weight(1f).padding(horizontal=12.dp),fontSize=15.sp,lineHeight=22.sp,
            fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
        Glyph(Mark.CHEVRON,Modifier.size(15.dp),muted())
    }
}
