package app.bililisten.platform

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView

/** A missing/disabled WebView must not disable native guest browsing or playback. */
object WebSession {
    fun available(): Boolean = runCatching { WebView.getCurrentWebViewPackage() != null }.getOrDefault(false)

    fun clear(done: (Boolean) -> Unit = {}) {
        if (!available()) { done(false); return }
        try {
            WebStorage.getInstance().deleteAllData()
            val cookies=CookieManager.getInstance()
            cookies.removeAllCookies { done(runCatching { cookies.flush() }.isSuccess) }
        } catch (_: Exception) { done(false) }
    }
}
