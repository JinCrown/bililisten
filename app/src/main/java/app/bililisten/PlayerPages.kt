package app.bililisten

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.first

/** Details sit left of the cover; subtitles sit right. Vertical lists retain their gestures. */
@Composable
internal fun PlayerPages(
    page: String,
    changePage: (String) -> Unit,
    live: Boolean,
    modifier: Modifier = Modifier,
    player: @Composable () -> Unit,
    subtitles: @Composable () -> Unit,
    details: @Composable () -> Unit,
    chrome: (Float) -> Unit = {},
) {
    fun index(name:String)=when(name){"videoDetails"->0;"subtitles"->2;else->1}
    val pages=listOf("videoDetails","player","subtitles")
    val pager = rememberPagerState(initialPage = index(page)) { 3 }
    val latestChange = rememberUpdatedState(changePage)
    val latestPage = rememberUpdatedState(page)
    val dragging by pager.interactionSource.collectIsDraggedAsState()
    var dragged by remember { mutableStateOf(false) }
    var dragOrigin by remember { mutableStateOf(page) }
    val subtitleFraction = (pager.currentPage + pager.currentPageOffsetFraction-1).coerceIn(0f, 1f)
    SideEffect { chrome(subtitleFraction) }
    LaunchedEffect(page) {
        val target = index(page)
        // Also finish/cancel a partial animation when the target is already the nearest page.
        pager.animateScrollToPage(target)
    }
    LaunchedEffect(dragging) {
        if (dragging) {
            dragged = true
            dragOrigin = latestPage.value
        } else if (dragged) {
            // Let the release start its fling before waiting for the user's gesture to settle.
            withFrameNanos { }
            snapshotFlow { pager.isScrollInProgress }.first { !it }
            dragged = false
            if (latestPage.value == dragOrigin) {
                latestChange.value(pages[pager.settledPage])
            }
        }
    }
    HorizontalPager(pager, modifier.fillMaxSize(), userScrollEnabled = !live) { index ->
        when(index){0->details();1->player();else->subtitles()}
    }
}
