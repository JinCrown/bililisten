package app.bililisten

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/** Only a user pull shows the refresh indicator; background reads keep content still. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun UserRefreshBox(busy:Boolean,refresh:suspend ()->Unit,modifier:Modifier=Modifier,content:@Composable BoxScope.()->Unit) {
    val scope=rememberCoroutineScope()
    var manualRefresh by remember { mutableStateOf(false) }
    PullToRefreshBox(manualRefresh,{
        if(!busy&&!manualRefresh){manualRefresh=true;scope.launch{try{refresh()}finally{manualRefresh=false}}}
    },modifier,content=content)
}
