package app.bililisten

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*

/** Short tap toggles like. A completed hold sends one triple; release/drag/navigation cancels it. */
@Composable internal fun RowScope.TripleLikeTile(label:String,count:String,selected:Boolean,enabled:Boolean,target:String,
    like:()->Unit,triple:()->Unit,progress:(Float)->Unit) {
    val animation=remember(target){Animatable(0f)}
    var held by remember(target){mutableStateOf(false)}
    val latestLike=rememberUpdatedState(like)
    val latestTriple=rememberUpdatedState(triple)
    val latestProgress=rememberUpdatedState(progress)
    val gesture=if(enabled)Modifier.pointerInput(target,enabled) {
        detectTapGestures(onTap={if(!held)latestLike.value()},onPress={
            held=false
            coroutineScope {
                val charging=launch {
                    delay(viewConfiguration.longPressTimeoutMillis)
                    held=true
                    animation.animateTo(1f,tween(1500,easing=LinearEasing)){latestProgress.value(value)}
                    ensureActive();latestTriple.value()
                }
                try {tryAwaitRelease()} finally {
                    withContext(NonCancellable){charging.cancelAndJoin();animation.snapTo(0f);latestProgress.value(0f)}
                }
            }
        })
    } else Modifier
    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).then(gesture).semantics {
        contentDescription=label;role=Role.Button;stateDescription=if(selected)"已点赞" else "未点赞"
        if(enabled) {
            onClick("点赞或取消点赞"){latestLike.value();true}
            onLongClick("一键三连"){latestTriple.value();true}
        }else disabled()
    }.padding(vertical=9.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp),contentAlignment=Alignment.Center) {
            if(animation.value>0f)CircularProgressIndicator(progress={animation.value},modifier=Modifier.size(40.dp),strokeWidth=2.dp)
            Glyph(Mark.LIKE,Modifier.size(28.dp),if(selected)MaterialTheme.colorScheme.primary else muted().copy(alpha=if(enabled)1f else .4f),selected)
        }
        Text(label,Modifier.padding(top=5.dp),fontSize=12.sp,color=if(selected)MaterialTheme.colorScheme.primary else muted())
        Text(count,Modifier.padding(top=2.dp),fontSize=11.sp,color=muted())
    }
}
