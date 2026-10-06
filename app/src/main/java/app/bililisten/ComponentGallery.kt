package app.bililisten

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.bililisten.shared.Theme

/** Runnable in the debug-only preview activity; all content is explicitly demonstration data. */
@Composable fun ComponentGallery(theme:Theme) {
    ListenTheme(theme){Surface{Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)){
        Text("组件预览 · 示例数据",style=MaterialTheme.typography.titleLarge)
        HomeSearchBar("",{},{});SectionTitle("标题与操作",Mark.STAR,"查看全部")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Pill("已选择",true){};Pill("未选择"){};ActionIcon(Mark.SETTINGS,"设置",{})}
        VideoRowCard("很长的视频标题：从收藏夹开始继续收听，验证中文换行、超长名称和按钮在大字体下的布局","","示例作者",1890,725000,"示例收藏夹",{}, {})
        Button({},Modifier.fillMaxWidth()){Text("主要操作")}
        OutlinedButton({},Modifier.fillMaxWidth()){Text("次要操作")}
        EmptyState("暂无内容","这是空状态组件的示例")
        Text("网络暂时不可用 · 请稍后重试",color=MaterialTheme.colorScheme.error)
    }}}
}
