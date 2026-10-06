package app.bililisten.platform

import android.content.Context
import android.content.Intent
import android.net.Uri

enum class OfficialAccountPage(val uri:String){MESSAGES("bilibili://im"),EDIT_PROFILE("bilibili://user_center")}
object OfficialAccountLinks {
    fun intent(page:OfficialAccountPage)=Intent(Intent.ACTION_VIEW,Uri.parse(page.uri)).setPackage("tv.danmaku.bili")
    fun open(context:Context,page:OfficialAccountPage):Boolean {
        val intent=intent(page)
        if(intent.resolveActivity(context.packageManager)==null)return false
        return try{context.startActivity(intent);true}catch(_:android.content.ActivityNotFoundException){false}catch(_:SecurityException){false}
    }
}
