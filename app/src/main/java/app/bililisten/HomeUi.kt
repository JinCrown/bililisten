package app.bililisten

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import app.bililisten.shared.*
import kotlinx.coroutines.flow.collect

@Composable internal fun HomeSectionHeading(title:String,action:String,onAction:()->Unit,subtitle:String?=null) {
    Row(Modifier.fillMaxWidth().padding(top=4.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title,Modifier.semantics{heading()},fontSize=20.sp,fontWeight=FontWeight.Bold)
            subtitle?.let { Text(it,Modifier.padding(top=3.dp),fontSize=11.sp,color=muted()) }
        }
        TextButton(onAction,contentPadding=PaddingValues(start=8.dp,end=0.dp)) {
            Text(action,fontSize=12.sp,color=muted())
            Glyph(Mark.CHEVRON,Modifier.padding(start=3.dp).size(13.dp),muted())
        }
    }
}

@Composable internal fun HomeRecommendationHeader(selected:HomeCategory,onSelect:(HomeCategory)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        LazyRow(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            items(HomeCategory.entries) { category ->
                val active=category==selected
                Box(Modifier.heightIn(min=48.dp).semantics{this.selected=active;role=Role.Tab;contentDescription="推荐分类：${category.label}"}
                    .clickable(interactionSource=remember{MutableInteractionSource()},indication=null){onSelect(category)},contentAlignment=Alignment.Center) {
                    Row(Modifier.background(if(active)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,CircleShape)
                        .padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        if(category!=HomeCategory.ALL) {
                            val tint=when(category){HomeCategory.STUDY->Color(0xFF628BE5);HomeCategory.GAME,HomeCategory.AUDIOBOOK->Color(0xFF9A70DB);else->MaterialTheme.colorScheme.primary}
                            if(category==HomeCategory.MUSIC || category==HomeCategory.EMOTION)
                                Text(if(category==HomeCategory.MUSIC)"♪" else "♥︎",fontSize=14.sp,color=if(active)MaterialTheme.colorScheme.onPrimary else tint)
                            else Glyph(when(category){HomeCategory.STUDY->Mark.FOLDER;HomeCategory.AUDIOBOOK->Mark.SUBTITLE;HomeCategory.GAME,HomeCategory.LIVE->Mark.TV;else->Mark.CLOCK},Modifier.size(14.dp),if(active)MaterialTheme.colorScheme.onPrimary else tint)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(category.label,fontSize=12.sp,fontWeight=if(active)FontWeight.Bold else FontWeight.Medium,
                            color=if(active)MaterialTheme.colorScheme.onPrimary else muted())
                    }
                }
            }
        }

    }
}

@Composable internal fun HeroCarousel(rows:List<Recommendation>,category:HomeCategory=HomeCategory.ALL,onClick:(String)->Unit) {
    RecommendationCarousel(rows.map { row->HeroSlide(row.bvid,row.title,row.cover,
        if(category==HomeCategory.ALL)"热榜精选" else "${category.label}精选",
        listOfNotNull(row.author.takeIf{it.isNotBlank()},row.duration.takeIf{it>0}?.let{timeLabel(it*1000)}).joinToString(" · "),
        "收听推荐：${row.title}") },category.sourceLabel){index->onClick(rows[index].bvid)}
}

internal data class HeroSlide(val key:String,val title:String,val cover:String,val badge:String,val detail:String,val description:String)

@Composable internal fun RecommendationCarousel(rows:List<HeroSlide>,sourceLabel:String,actionLabel:String="收听",actionMark:Mark=Mark.PLAY,onClick:(Int)->Unit) {
    val pager=rememberPagerState(pageCount={rows.size})
    val colors=MaterialTheme.colorScheme
    val large=LocalDensity.current.fontScale>1.3f
    Column {
        HorizontalPager(pager,pageSpacing=10.dp,key={rows[it].key}) { index ->
            val row=rows[index]
            Surface(onClick={onClick(index)},shape=RoundedCornerShape(20.dp),color=colors.surface,
                modifier=Modifier.fillMaxWidth().semantics{contentDescription=row.description}) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val heroHeight=maxOf(maxWidth/1.95f,if(large)220.dp else 178.dp)
                    Box(Modifier.fillMaxWidth().height(heroHeight)) {
                        Cover(row.cover,Modifier.matchParentSize())
                        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Transparent,.35f to Color.Transparent,1f to Color.Black.copy(alpha=.82f))))
                        Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.weight(1f).padding(end=8.dp)) {
                                Text(sourceLabel,Modifier.background(Color.Black.copy(alpha=.35f),RoundedCornerShape(7.dp)).padding(horizontal=7.dp,vertical=3.dp),
                                    fontSize=10.sp,color=Color.White,maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                            Text("${index+1} / ${rows.size}",Modifier.background(Color.Black.copy(alpha=.35f),RoundedCornerShape(7.dp))
                                .padding(horizontal=7.dp,vertical=3.dp),fontSize=10.sp,color=Color.White)
                        }
                        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.Bottom) {
                            Column(Modifier.weight(1f).padding(end=10.dp)) {
                                Surface(shape=RoundedCornerShape(6.dp),color=Color(0xFFFFE5EF)) {
                                    Text(row.badge,Modifier.padding(horizontal=6.dp,vertical=3.dp),fontSize=9.sp,color=Color(0xFFB92863),fontWeight=FontWeight.Medium)
                                }
                                Text(row.title,Modifier.padding(top=6.dp),fontWeight=FontWeight.SemiBold,fontSize=15.sp,lineHeight=21.sp,
                                    color=Color.White,maxLines=2,overflow=TextOverflow.Ellipsis)
                                Text(row.detail,
                                    Modifier.padding(top=4.dp),fontSize=11.sp,color=Color.White.copy(alpha=.8f),maxLines=1,overflow=TextOverflow.Ellipsis)
                            }
                            Surface(shape=CircleShape,color=Color.White.copy(alpha=.95f)) {
                                Row(Modifier.padding(horizontal=12.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                                    Glyph(actionMark,Modifier.size(16.dp),colors.primary)
                                    Text(actionLabel,Modifier.padding(start=5.dp),fontSize=12.sp,color=Color(0xFF292330),fontWeight=FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top=9.dp,bottom=2.dp),horizontalArrangement=Arrangement.Center) {
            repeat(rows.size){index->Box(Modifier.padding(horizontal=3.dp).size(if(index==pager.currentPage)14.dp else 4.dp,4.dp)
                .background(if(index==pager.currentPage)colors.primary else colors.onSurfaceVariant.copy(alpha=.2f),CircleShape))}
        }
    }
}

@Composable internal fun HomePopularMusic(s:ScreenState,loadMore:()->Unit={},retryMore:()->Unit=loadMore,open:(PopularMusic)->Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top=6.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("推荐视频",Modifier.weight(1f).semantics{heading()},fontSize=20.sp,fontWeight=FontWeight.Bold)
            Text(if(s.settings.musicRecommendations)"200 万+ · 音乐" else "B站首页推荐",fontSize=11.sp,color=muted())
        }
        if(s.popularMusic.isNotEmpty()) {
            val groups=s.popularMusic.chunked(3)
            val list=key(s.settings.musicRecommendations){rememberLazyListState()}
            val requestMore by rememberUpdatedState(loadMore)
            LaunchedEffect(list,groups.size,s.popularMusicLoaded,s.popularMusicBusy,s.popularMusicMoreBusy,s.popularMusicHasMore,s.popularMusicMoreError) {
                if(!s.popularMusicLoaded || s.popularMusicBusy || s.popularMusicMoreBusy || !s.popularMusicHasMore || s.popularMusicMoreError!=null)return@LaunchedEffect
                snapshotFlow {list.isScrollInProgress && list.layoutInfo.totalItemsCount==groups.size &&
                    (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1)>=maxOf(0,groups.lastIndex-1)}.collect{nearEnd->if(nearEnd)requestMore()}
            }
            val large=LocalDensity.current.fontScale>1.3f
            val measurer=rememberTextMeasurer()
            val style=LocalTextStyle.current
            val density=LocalDensity.current
            // Measure real line boxes, including fallback fonts and system font scaling.
            val titleHeight=with(density){measurer.measure("音乐Ag\n音乐Ag",style.copy(fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold)).size.height.toDp()}+1.dp
            val infoHeight=with(density){measurer.measure("音乐Ag",style.copy(fontSize=11.sp,lineHeight=16.sp)).size.height.toDp()}+1.dp
            val authorHeight=with(density){measurer.measure("音乐Ag",style.copy(fontSize=10.sp,lineHeight=14.sp)).size.height.toDp()}+1.dp
            val rowHeight=maxOf(92.dp,titleHeight+infoHeight*2+authorHeight+10.dp)
            Box(Modifier.height(rowHeight*3+24.dp)) {
                LazyRow(Modifier.fillMaxSize(),state=list,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(groups,key={index,_->index}) { _,group->
                        Column(Modifier.width(if(large)300.dp else 312.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            repeat(3) { index->
                                val row=group.getOrNull(index)
                                if(row==null)Spacer(Modifier.height(rowHeight)) else Row(Modifier.fillMaxWidth().height(rowHeight)
                                    .semantics{contentDescription="打开推荐视频：${row.title}，${row.typeLabel}，${row.playLabel}"}
                                    .clickable{open(row)},verticalAlignment=Alignment.CenterVertically) {
                                    Box(Modifier.size(80.dp).clip(RoundedCornerShape(8.dp))) {
                                        key(row.key){Cover(row.cover,Modifier.matchParentSize())}
                                        Box(Modifier.align(Alignment.BottomEnd).padding(5.dp).size(22.dp)
                                            .background(Color.Black.copy(alpha=.48f),CircleShape),contentAlignment=Alignment.Center) {
                                            Glyph(if(row.collection==null)Mark.PLAY else Mark.FOLDER,Modifier.size(13.dp),Color.White)
                                        }
                                    }
                                    Column(Modifier.weight(1f).padding(start=12.dp)) {
                                        Text(row.title,Modifier.height(titleHeight),fontSize=14.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                                        Text(row.typeLabel,Modifier.padding(top=5.dp).height(infoHeight),fontSize=11.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.primary,maxLines=1,overflow=TextOverflow.Ellipsis)
                                        Text(row.playLabel,Modifier.padding(top=3.dp).height(infoHeight),fontSize=11.sp,lineHeight=16.sp,color=muted(),maxLines=1)
                                        Text(row.author,Modifier.padding(top=2.dp).height(authorHeight),fontSize=10.sp,lineHeight=14.sp,color=muted(),maxLines=1,overflow=TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
                if(s.popularMusicMoreBusy)LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp))
            }
        } else Text(if(s.popularMusicBusy)"正在读取推荐视频…" else if(s.popularMusicError!=null)"推荐视频暂时不可用" else if(s.settings.musicRecommendations)"暂无符合条件的音乐视频或合集" else "暂无可显示的 B站推荐视频",
            Modifier.fillMaxWidth().padding(vertical=12.dp),fontSize=12.sp,color=muted())
        s.popularMusicError?.let{Text(it,Modifier.padding(top=6.dp),fontSize=11.sp,color=muted())}
        s.popularMusicMoreError?.let{message->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(message,Modifier.weight(1f),fontSize=11.sp,color=muted());ActionIcon(Mark.REFRESH,"重新加载更多推荐视频",retryMore)}}
    }
}

@Composable internal fun HomeCreators(s:ScreenState,open:(UpProfile)->Unit) {
    Column {
        if(s.homeUps.isNotEmpty())LazyRow(contentPadding=PaddingValues(vertical=2.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            itemsIndexed(s.homeUps,key={index,_->index}) { _,up ->
                Surface(onClick={open(up)},modifier=Modifier.width(108.dp).semantics{contentDescription="查看推荐 UP：${up.name}"},
                    shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surface,
                    border=BorderStroke(.5.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))) {
                    Column(Modifier.padding(horizontal=9.dp,vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        key(up.mid){Cover(up.avatar,Modifier.size(64.dp).clip(CircleShape),label="${up.name}的头像")}
                        Text(up.name,Modifier.fillMaxWidth().padding(top=8.dp),fontSize=12.sp,fontWeight=FontWeight.SemiBold,
                            maxLines=1,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center)
                        Text(if(s.settings.musicRecommendations)"音乐热榜 UP" else "B站推荐 UP",Modifier.padding(top=4.dp),fontSize=10.sp,color=muted(),maxLines=1)
                        Text("查看投稿",Modifier.fillMaxWidth().padding(top=9.dp).background(blush(),CircleShape).padding(vertical=6.dp),
                            fontSize=11.sp,color=MaterialTheme.colorScheme.primary,textAlign=TextAlign.Center)
                    }
                }
            }
        } else Text(if(s.homeUpsBusy)"正在读取推荐 UP…" else if(s.homeUpsError!=null)"推荐 UP 暂时不可用" else "暂无可推荐的 UP 主",
            Modifier.fillMaxWidth().padding(vertical=14.dp),fontSize=12.sp,color=muted())
        s.homeUpsError?.let{Text(it,Modifier.fillMaxWidth().padding(top=4.dp),fontSize=11.sp,color=muted())}
    }
}

@Composable internal fun HomeRecentCard(vm:MainViewModel,s:ScreenState,row:LocalHistoryEntry,onClick:()->Unit,onMore:()->Unit) {
    LaunchedEffect(row.video.bvid){vm.loadMetadata(row.video.bvid)}
    val info=s.metadata[row.video.bvid]
    val duration=info?.parts?.firstOrNull{it.cid==row.video.cid}?.durationSeconds?.takeIf{it>0}
        ?: info?.takeIf{it.parts.size==1}?.duration?.takeIf{it>0}
    val progress=if(duration!=null)(row.positionMs.toFloat()/(duration*1000)).coerceIn(0f,1f) else 0f
    Surface(onClick,modifier=Modifier.width(144.dp).semantics{contentDescription="继续收听：${row.title}"},shape=RoundedCornerShape(15.dp),color=MaterialTheme.colorScheme.surface) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1.65f)) {
                Cover(info?.cover.orEmpty(),Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.35f)))))
                Glyph(Mark.PLAY,Modifier.align(Alignment.BottomStart).padding(9.dp).size(15.dp),Color.White)
                HomeHistoryMore("最近收听更多：${row.title}",onMore,Modifier.align(Alignment.TopEnd))
            }
            Column(Modifier.padding(horizontal=9.dp,vertical=9.dp)) {
                Text(row.title,fontWeight=FontWeight.Medium,fontSize=12.sp,lineHeight=17.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text("听到 ${timeLabel(row.positionMs)} · P${row.video.part}",Modifier.padding(top=5.dp),fontSize=10.sp,color=muted(),maxLines=1,overflow=TextOverflow.Ellipsis)
                LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().padding(top=7.dp).height(3.dp).clip(CircleShape),
                    color=MaterialTheme.colorScheme.primary,trackColor=MaterialTheme.colorScheme.outlineVariant,gapSize=0.dp,drawStopIndicator={})
            }
        }
    }
}

@Composable internal fun HomeRecentLiveCard(row:LiveHistoryEntry,onClick:()->Unit,onMore:()->Unit) {
    Surface(onClick,modifier=Modifier.width(144.dp),shape=RoundedCornerShape(15.dp),color=MaterialTheme.colorScheme.surface) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(1.65f).background(Brush.linearGradient(listOf(blush(),MaterialTheme.colorScheme.surfaceVariant)))) {
                Glyph(Mark.TV,Modifier.align(Alignment.Center).size(34.dp),MaterialTheme.colorScheme.primary)
                Text("直播",Modifier.align(Alignment.BottomStart).padding(9.dp),fontSize=10.sp,color=muted())
                HomeHistoryMore("删除直播历史：${row.title}",onMore,Modifier.align(Alignment.TopEnd))
            }
            Column(Modifier.padding(9.dp)) {
                Text(row.title,fontSize=12.sp,lineHeight=17.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text("直播历史 · 重新连接",Modifier.padding(top=5.dp),fontSize=10.sp,color=muted())
            }
        }
    }
}

@Composable private fun HomeHistoryMore(label:String,action:()->Unit,modifier:Modifier) {
    IconButton(action,modifier.size(48.dp).semantics{contentDescription=label}) {
        Box(Modifier.size(24.dp).background(Color.White.copy(alpha=.88f),CircleShape),contentAlignment=Alignment.Center) {
            Glyph(Mark.MORE,Modifier.size(15.dp),Color(0xFF746E80))
        }
    }
}
