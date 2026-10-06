package app.bililisten

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun TempoLicenseNotice() {
    var asset by remember { mutableStateOf<String?>(null) }
    TextButton({asset="soundtouch-LGPL-2.1.txt"}){Text("SoundTouch 2.4.1 · 开源许可")}
    TextButton({asset="llvm-NOTICE.txt"}){Text("C++ 运行库 · 开源许可")}
    TextButton({asset="snakeyaml-Apache-2.0.txt"}){Text("SnakeYAML 2.5 · 开源许可")}
    val selected=asset
    if(selected!=null){
        val context=LocalContext.current
        val license=remember(selected){context.assets.open("licenses/$selected").bufferedReader().use{it.readText()}}
        val soundTouch=selected.startsWith("soundtouch")
        val yaml=selected.startsWith("snakeyaml")
        AlertDialog(onDismissRequest={asset=null},title={Text(if(soundTouch)"SoundTouch 2.4.1" else if(yaml)"SnakeYAML 2.5" else "LLVM C++ 运行库")},text={
            Text((if(soundTouch)"© Olli Parviainen\nLGPL 2.1 或更新版本 · 独立动态库\n源码与构建说明随本测试版 APK 提供。\n上游：codeberg.org/soundtouch/soundtouch\n\n" else if(yaml)"Copyright (c) 2008, SnakeYAML\nApache License 2.0\n上游：github.com/snakeyaml/snakeyaml\n\n" else "Android NDK 28.2 · libc++_shared.so\n\n")+license,Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),fontSize=12.sp)
        },confirmButton={TextButton({asset=null}){Text("关闭")}})
    }
}
