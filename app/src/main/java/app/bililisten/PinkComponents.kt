package app.bililisten

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import app.bililisten.shared.*
import kotlin.math.*

internal val PageSideInset=8.dp

val ListenPink=Color(0xFFD33373)
val ImmersivePink=Color(0xFFFF94BD)
@Composable fun muted()=MaterialTheme.colorScheme.onSurfaceVariant
@Composable fun blush()=MaterialTheme.colorScheme.primaryContainer
@Composable fun ListenTheme(theme:Theme,content:@Composable ()->Unit) {
    val dark=when(theme){Theme.DARK->true;Theme.LIGHT->false;else->isSystemInDarkTheme()}
    val colors=if(dark) darkColorScheme(primary=Color(0xFFFF94BD),onPrimary=Color(0xFF381426),primaryContainer=Color(0xFF402435),onPrimaryContainer=Color(0xFFF6EFF5),background=Color(0xFF16151D),onBackground=Color(0xFFF6EFF5),surface=Color(0xFF24222E),surfaceVariant=Color(0xFF302D3B),onSurface=Color(0xFFF6EFF5),onSurfaceVariant=Color(0xFFB6AFBF),outline=Color(0xFF5F5668),outlineVariant=Color(0xFF393443),secondary=Color(0xFFA9B8ED),secondaryContainer=Color(0xFF402435),onSecondaryContainer=Color(0xFFFF94BD),surfaceContainerLow=Color(0xFF201E28),surfaceContainer=Color(0xFF24222E),surfaceContainerHigh=Color(0xFF282632),surfaceContainerHighest=Color(0xFF302D3B))
    else lightColorScheme(primary=ListenPink,onPrimary=Color.White,primaryContainer=Color(0xFFFFE7F0),onPrimaryContainer=Color(0xFF282332),background=Color(0xFFF8F7FA),onBackground=Color(0xFF282332),surface=Color.White,surfaceVariant=Color(0xFFF0EEF4),onSurface=Color(0xFF282332),onSurfaceVariant=Color(0xFF746E80),outline=Color(0xFFB6AEBD),outlineVariant=Color(0xFFECE7EF),secondary=Color(0xFF667BB3),secondaryContainer=Color(0xFFFFE7F0),onSecondaryContainer=Color(0xFFD33373),surfaceContainerLow=Color.White,surfaceContainer=Color.White,surfaceContainerHigh=Color(0xFFF4F1F6),surfaceContainerHighest=Color(0xFFF0EDF4))
    // Let small captions use their natural line height instead of inheriting
    // Material's 24sp body line height, especially with large system fonts.
    MaterialTheme(colorScheme=colors,shapes=Shapes(extraSmall=RoundedCornerShape(14.dp),small=RoundedCornerShape(16.dp),medium=RoundedCornerShape(20.dp),large=RoundedCornerShape(26.dp),extraLarge=RoundedCornerShape(30.dp)),typography=Typography(
        titleLarge=androidx.compose.ui.text.TextStyle(fontSize=24.sp,lineHeight=32.sp,fontWeight=FontWeight.Bold),
        titleMedium=androidx.compose.ui.text.TextStyle(fontSize=17.sp,lineHeight=24.sp,fontWeight=FontWeight.SemiBold),
        bodyLarge=androidx.compose.ui.text.TextStyle(fontSize=15.sp,lineHeight=TextUnit.Unspecified),
        bodyMedium=androidx.compose.ui.text.TextStyle(fontSize=13.sp,lineHeight=20.sp),
        bodySmall=androidx.compose.ui.text.TextStyle(fontSize=12.sp,lineHeight=18.sp),
        labelLarge=androidx.compose.ui.text.TextStyle(fontSize=13.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold),
        labelSmall=androidx.compose.ui.text.TextStyle(fontSize=11.sp,lineHeight=16.sp,fontWeight=FontWeight.Medium)),content=content)
}

/** Shared visual language for native pages and sheets, with natural height at large font scales. */
@Composable fun PanelCard(modifier:Modifier=Modifier,accent:Boolean=false,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),color=if(accent)blush() else MaterialTheme.colorScheme.surface,
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.7f))) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
@Composable fun IconBadge(mark:Mark,color:Color=MaterialTheme.colorScheme.primary,size:Dp=42.dp) {
    Box(Modifier.size(size).background(color.copy(alpha=.11f),RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){Glyph(mark,Modifier.size(size*.52f),color)}
}
@Composable fun PageIntro(title:String,detail:String,mark:Mark) {
    Row(Modifier.fillMaxWidth().padding(top=8.dp,bottom=12.dp),verticalAlignment=Alignment.CenterVertically){IconBadge(mark);Column(Modifier.weight(1f).padding(start=12.dp)){Text(title,fontSize=18.sp,fontWeight=FontWeight.Bold);Text(detail,fontSize=12.sp,color=muted())}}
}
@Composable fun PanelHeading(title:String,detail:String,mark:Mark) {
    Row(Modifier.fillMaxWidth().padding(bottom=4.dp),verticalAlignment=Alignment.CenterVertically){IconBadge(mark);Column(Modifier.weight(1f).padding(start=12.dp)){Text(title,fontSize=21.sp,fontWeight=FontWeight.Bold);if(detail.isNotBlank())Text(detail,fontSize=12.sp,color=muted())}}
}
@Composable fun InfoCard(text:String,mark:Mark=Mark.INFO) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.65f),RoundedCornerShape(16.dp)).padding(13.dp),verticalAlignment=Alignment.Top){Glyph(mark,Modifier.padding(top=2.dp).size(17.dp),muted());Text(text,Modifier.weight(1f).padding(start=9.dp),fontSize=12.sp,lineHeight=19.sp,color=muted())}
}
@Composable fun SettingToggle(title:String,detail:String,value:Boolean,enabled:Boolean=true,description:String?=null,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f).padding(end=12.dp)){Text(title,fontSize=14.sp,fontWeight=FontWeight.SemiBold);if(detail.isNotBlank())Text(detail,Modifier.padding(top=3.dp),fontSize=12.sp,color=muted())};Switch(value,change,enabled=enabled,modifier=Modifier.semantics{contentDescription=description ?: title})}
}
@Composable fun StatusTag(label:String,mark:Mark?=null) {
    Row(Modifier.background(blush(),CircleShape).padding(horizontal=10.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically){if(mark!=null){Glyph(mark,Modifier.size(13.dp),MaterialTheme.colorScheme.primary);Spacer(Modifier.width(5.dp))};Text(label,fontSize=11.sp,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold)}
}
enum class Mark { HOME,STAR,TV,SEARCH,LINK,BACK,NEXT,PREVIOUS,PLAY,PAUSE,QUEUE,MORE,SETTINGS,CLOCK,FOLDER,REFRESH,DOWNLOAD,CACHE,HISTORY,MESSAGE,CHEVRON,CLOSE,CHECK,SHUFFLE,REPEAT,ADD,SUBTITLE,SHARE,INFO,UP,DOWN,LIKE,COIN }
@Composable internal fun BilibiliTvIcon(modifier:Modifier=Modifier.size(23.dp),color:Color=LocalContentColor.current) {
    Icon(painterResource(R.drawable.ic_bilibili_tv),contentDescription=null,modifier=modifier,tint=color)
}
@Composable fun Glyph(mark:Mark,modifier:Modifier=Modifier.size(24.dp),color:Color=LocalContentColor.current,filled:Boolean=false) {
    if(mark==Mark.TV){BilibiliTvIcon(modifier,color);return}
    Canvas(modifier) {
        val scale=size.minDimension/24f
        withTransform({scale(scale,scale,Offset.Zero)}) {
            val stroke=Stroke(1.7f,cap=StrokeCap.Round,join=StrokeJoin.Round)
            fun line(x:Float,y:Float,a:Float,b:Float)=drawLine(color,Offset(x,y),Offset(a,b),1.8f,StrokeCap.Round)
            fun path(vararg p:Float,fill:Boolean=false){val path=Path();path.moveTo(p[0],p[1]);for(i in 2 until p.size step 2)path.lineTo(p[i],p[i+1]);drawPath(path,color,style=if(fill)Fill else stroke)}
            when(mark){
                Mark.LIKE->{path(8f,10f,12f,3f,15f,3f,15f,9f,21f,9f,21f,14f,18f,21f,8f,21f,8f,10f,fill=filled);path(3f,10f,7f,10f,7f,21f,3f,21f,3f,10f)}
                Mark.COIN->{drawCircle(color,9f,Offset(12f,12f),style=if(filled)Fill else stroke);val ink=if(filled)Color.White else color;drawCircle(ink,6f,Offset(12f,12f),style=stroke);drawLine(ink,Offset(12f,8f),Offset(12f,16f),1.8f,StrokeCap.Round);drawLine(ink,Offset(9f,10f),Offset(15f,10f),1.8f,StrokeCap.Round);drawLine(ink,Offset(9f,14f),Offset(15f,14f),1.8f,StrokeCap.Round)}
                Mark.PLAY->path(7f,4f,20f,12f,7f,20f,7f,4f,fill=true)
                Mark.PAUSE->{drawRoundRect(color,Offset(6f,4f),Size(4f,16f),CornerRadius(2f));drawRoundRect(color,Offset(14f,4f),Size(4f,16f),CornerRadius(2f))}
                Mark.NEXT->{path(5f,5f,17f,12f,5f,19f,5f,5f,fill=true);line(19f,5f,19f,19f)}
                Mark.PREVIOUS->{path(19f,5f,7f,12f,19f,19f,19f,5f,fill=true);line(5f,5f,5f,19f)}
                Mark.STAR->{val p=Path();for(i in 0..10){val a=(-90+i*36)*PI/180;val r=if(i%2==0)10f else 4.8f;val x=12+cos(a).toFloat()*r;val y=12+sin(a).toFloat()*r;if(i==0)p.moveTo(x,y) else p.lineTo(x,y)};p.close();drawPath(p,color,style=if(filled)Fill else stroke)}
                Mark.HOME->{path(3f,10f,12f,2f,21f,10f,21f,21f,15f,21f,15f,14f,9f,14f,9f,21f,3f,21f,3f,10f,fill=filled)}
                Mark.TV->Unit
                Mark.SEARCH->{drawCircle(color,7f,Offset(10f,10f),style=stroke);line(15f,15f,21f,21f)}
                Mark.BACK->path(15f,3f,6f,12f,15f,21f)
                Mark.CHEVRON->path(9f,5f,16f,12f,9f,19f)
                Mark.MORE->for(y in listOf(5f,12f,19f))drawCircle(color,1.7f,Offset(12f,y))
                Mark.CLOCK,Mark.HISTORY->{drawCircle(color,9f,Offset(12f,12f),style=stroke);path(12f,6f,12f,12f,16f,14f)}
                Mark.FOLDER->{path(3f,7f,3f,20f,21f,20f,21f,7f,12f,7f,10f,4f,3f,4f,3f,7f)}
                Mark.QUEUE->{line(3f,5f,15f,5f);line(3f,11f,13f,11f);line(3f,17f,10f,17f);line(18f,8f,18f,20f);line(18f,8f,22f,10f);drawCircle(color,2.5f,Offset(15.5f,20f))}
                Mark.DOWNLOAD->{path(12f,3f,12f,15f);path(7f,10f,12f,15f,17f,10f);path(3f,15f,3f,21f,21f,21f,21f,15f)}
                Mark.CACHE->{drawRoundRect(color,Offset(4f,7f),Size(16f,14f),CornerRadius(3f),style=stroke);path(4f,7f,7f,3f,17f,3f,20f,7f);line(9f,13f,15f,13f)}
                Mark.SETTINGS->{val p=Path();for(i in 0..6){val a=(i*60-30)*PI/180;val x=12+10*cos(a).toFloat();val y=12+10*sin(a).toFloat();if(i==0)p.moveTo(x,y) else p.lineTo(x,y)};drawPath(p,color,style=stroke);drawCircle(color,3.2f,Offset(12f,12f),style=stroke)}
                Mark.MESSAGE,Mark.SUBTITLE->{drawRoundRect(color,Offset(3f,3f),Size(18f,16f),CornerRadius(4f),style=stroke);path(8f,19f,5f,22f,5f,18f);for(x in listOf(7f,12f,17f))drawCircle(color,1f,Offset(x,11f))}
                Mark.CLOSE->{line(5f,5f,19f,19f);line(19f,5f,5f,19f)}
                Mark.CHECK->path(4f,12f,9f,17f,20f,6f)
                Mark.ADD->{line(4f,12f,20f,12f);line(12f,4f,12f,20f)}
                Mark.UP->path(5f,15f,12f,8f,19f,15f)
                Mark.DOWN->path(5f,9f,12f,16f,19f,9f)
                Mark.REPEAT,Mark.REFRESH->{path(4f,10f,4f,6f,19f,6f,16f,3f);path(20f,14f,20f,18f,5f,18f,8f,21f)}
                Mark.SHUFFLE->{path(3f,6f,7f,6f,17f,18f,21f,18f,18f,15f);path(3f,18f,7f,18f,17f,6f,21f,6f,18f,3f)}
                Mark.LINK,Mark.SHARE->{path(9f,5f,4f,5f,4f,21f,20f,21f,20f,15f);path(10f,13f,21f,3f,15f,3f);line(21f,3f,21f,9f)}
                Mark.INFO->{drawCircle(color,9f,Offset(12f,12f),style=stroke);line(12f,11f,12f,18f);drawCircle(color,1f,Offset(12f,7f))}
            }
        }
    }
}
@Composable fun ActionIcon(mark:Mark,label:String,onClick:()->Unit,modifier:Modifier=Modifier,color:Color=LocalContentColor.current,enabled:Boolean=true,filled:Boolean=false) {
    IconButton(onClick,modifier.semantics{contentDescription=label},enabled=enabled){Glyph(mark,color=if(enabled)color else color.copy(alpha=.35f),filled=filled)}
}
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun FavoriteButton(label:String,present:Boolean,enabled:Boolean,onClick:()->Unit,onLongClick:()->Unit) {
    Box(Modifier.size(48.dp).semantics{contentDescription=label;stateDescription=if(present)"已收藏到默认收藏夹，可取消收藏" else "收藏到默认收藏夹"}
        .combinedClickable(enabled=enabled,role=Role.Button,interactionSource=remember{MutableInteractionSource()},indication=null,
            onClickLabel=if(present)"从默认收藏夹取消收藏" else "收藏到默认收藏夹",onLongClickLabel="选择其他收藏夹",onLongClick=onLongClick,onClick=onClick),contentAlignment=Alignment.Center) {
        Glyph(Mark.STAR,Modifier.size(23.dp),MaterialTheme.colorScheme.primary.copy(alpha=if(enabled)1f else .35f),filled=present)
    }
}
@Composable fun Tv(modifier:Modifier=Modifier){BilibiliTvIcon(modifier,MaterialTheme.colorScheme.primary)}
@Composable fun Cover(url:String,modifier:Modifier=Modifier,label:String?=null) {
    val app=LocalContext.current.applicationContext as ListenApplication
    val bitmap by produceState<android.graphics.Bitmap?>(null,url){value=app.covers.load(url)}
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center){
        bitmap?.let{Image(it.asImageBitmap(),label,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)} ?: Glyph(Mark.TV,Modifier.size(30.dp),muted().copy(alpha=.45f))
    }
}
@Composable fun Brand(compact:Boolean=false) {
    Row(verticalAlignment=Alignment.CenterVertically){Tv(Modifier.size(if(compact)34.dp else 46.dp));Spacer(Modifier.width(8.dp));Column{
        Row{Text("哔哩",fontSize=if(compact)17.sp else 23.sp,fontWeight=FontWeight.Bold);Text("听视频",fontSize=if(compact)17.sp else 23.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)}
        if(!compact)Text("把喜欢的视频，听起来",Modifier.padding(top=3.dp),fontSize=11.sp,color=muted())
    }}
}
@Composable fun SectionTitle(title:String,mark:Mark?=null,action:String?=null,onAction:()->Unit={}){
    Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=2.dp).heightIn(min=44.dp),verticalAlignment=Alignment.CenterVertically){
        if(mark!=null){Box(Modifier.size(28.dp).background(blush(),RoundedCornerShape(9.dp)),contentAlignment=Alignment.Center){Glyph(mark,Modifier.size(17.dp),MaterialTheme.colorScheme.primary,true)};Spacer(Modifier.width(9.dp))}
        Text(title,Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.Bold)
        if(action!=null)TextButton(onAction,contentPadding=PaddingValues(horizontal=4.dp)){Text(action,color=muted(),fontSize=12.sp);Glyph(Mark.CHEVRON,Modifier.padding(start=3.dp).size(13.dp),muted())}
    }
}
@Composable fun EmptyState(title:String,body:String="",action:String?=null,onAction:()->Unit={}){
    PanelCard{Column(Modifier.fillMaxWidth().padding(vertical=20.dp,horizontal=8.dp),horizontalAlignment=Alignment.CenterHorizontally){
        Box(Modifier.size(102.dp).background(Brush.radialGradient(listOf(blush(),Color.Transparent)),CircleShape),contentAlignment=Alignment.Center){Tv(Modifier.size(78.dp))}
        Text(title,Modifier.padding(top=8.dp),fontSize=17.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center)
        if(body.isNotBlank())Text(body,Modifier.padding(top=8.dp),fontSize=12.sp,lineHeight=20.sp,color=muted(),textAlign=TextAlign.Center)
        if(action!=null)FilledTonalButton(onAction,Modifier.padding(top=12.dp)){Text(action);Glyph(Mark.CHEVRON,Modifier.padding(start=5.dp).size(14.dp))}
    }}
}
@Composable fun Pill(text:String,selected:Boolean=false,onClick:()->Unit){Surface(onClick,modifier=Modifier.semantics{this.selected=selected},shape=CircleShape,color=if(selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,border=if(selected)null else BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){
    Text(text,Modifier.padding(horizontal=14.dp,vertical=10.dp),fontSize=12.sp,color=if(selected)MaterialTheme.colorScheme.onPrimary else muted(),fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal)
}}

fun timeLabel(ms:Long):String {val s=ms.coerceAtLeast(0)/1000;return if(s>=3600)"${s/3600}:${(s%3600/60).toString().padStart(2,'0')}:${(s%60).toString().padStart(2,'0')}" else "${s/60}:${(s%60).toString().padStart(2,'0')}"}
fun countLabel(n:Long?):String=when{n==null->"—";n>=10000->"%.1f万".format(n/10000.0);else->n.toString()}
@Composable fun VideoRowCard(title:String,cover:String,author:String,duration:Long=0,progress:Long?=null,folder:String?=null,onClick:()->Unit,onMore:()->Unit){
    Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))){
        Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically){
            Box(Modifier.width(if(LocalConfiguration.current.screenWidthDp<360 || LocalDensity.current.fontScale>1.3f)88.dp else 108.dp).aspectRatio(1.5f).clip(RoundedCornerShape(12.dp)).clickable(onClick=onClick)){
                Cover(cover,Modifier.fillMaxSize())
                if(duration>0)Text(timeLabel(duration*1000),Modifier.align(Alignment.BottomEnd).padding(5.dp).background(Color.Black.copy(alpha=.6f),RoundedCornerShape(5.dp)).padding(horizontal=5.dp,vertical=2.dp),color=Color.White,fontSize=10.sp)
            }
            Column(Modifier.weight(1f).clickable(onClick=onClick).padding(start=12.dp)){
                Text(title,maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold,fontSize=13.sp,lineHeight=19.sp)
                Text(author.ifBlank{"视频"},Modifier.padding(top=5.dp),fontSize=11.sp,color=muted(),maxLines=1,overflow=TextOverflow.Ellipsis)
                if(folder!=null){Spacer(Modifier.height(6.dp));Row(Modifier.background(blush(),CircleShape).padding(horizontal=7.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically){Glyph(Mark.FOLDER,Modifier.size(12.dp),MaterialTheme.colorScheme.primary);Text(folder,Modifier.padding(start=4.dp),color=MaterialTheme.colorScheme.primary,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}}
                if(progress!=null){Spacer(Modifier.height(7.dp));LinearProgressIndicator(progress={if(duration>0)(progress/(duration*1000f)).coerceIn(0f,1f) else 0f},modifier=Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),color=MaterialTheme.colorScheme.primary,trackColor=blush(),drawStopIndicator={});Text("已听 ${timeLabel(progress)}",Modifier.padding(top=4.dp),fontSize=10.sp,color=muted())}
            }
            ActionIcon(Mark.MORE,"视频更多操作",onMore,Modifier.size(36.dp),muted())
        }
    }
}
