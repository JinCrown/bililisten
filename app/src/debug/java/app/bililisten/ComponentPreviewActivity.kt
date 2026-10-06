package app.bililisten

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.bililisten.shared.Theme

class ComponentPreviewActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState)
        val theme=if(intent.getBooleanExtra("dark",false))Theme.DARK else Theme.LIGHT
        val font=intent.getFloatExtra("font",1f).coerceIn(1f,2f)
        setContent{CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,font)){ComponentGallery(theme)}}
    }
}
