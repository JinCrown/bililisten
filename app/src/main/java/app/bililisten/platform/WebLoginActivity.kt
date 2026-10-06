package app.bililisten.platform

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.bililisten.ListenApplication
import app.bililisten.shared.PlatformFailure
import kotlinx.coroutines.*

/** Candidate route only: official HTTPS page, no JS bridge or password interception. */
class WebLoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var message: TextView
    private lateinit var finishButton: Button
    private var clearing = false
    private var verifying = false
    private var pageReady = false
    private val sessionWatch = LoginSessionWatch()
    private val app get() = application as ListenApplication
    private val darkUi get()=intent.getBooleanExtra("darkUi",resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
    private val background get()=Color.parseColor(if(darkUi)"#16151D" else "#F8F7FA")
    private val chromeText get()=Color.parseColor(if(darkUi)"#F6EFF5" else "#282332")
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun chromeButton(label:String,primary:Boolean=false,action:()->Unit)=Button(this).apply{
        text=label;textSize=13f;isAllCaps=false;setPadding(dp(12),dp(8),dp(12),dp(8));minimumHeight=dp(48)
        background=RippleDrawable(ColorStateList.valueOf(Color.parseColor("#22FFFFFF")),GradientDrawable().apply{cornerRadius=dp(16).toFloat();setColor(Color.WHITE)},null)
        setTextColor(if(primary)Color.WHITE else chromeText)
        backgroundTintList=ColorStateList.valueOf(Color.parseColor(if(primary)"#D33373" else if(darkUi)"#302D3B" else "#FFE7F0"))
        setOnClickListener{action()}
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=background;window.navigationBarColor=background
        androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply{isAppearanceLightStatusBars=!darkUi;isAppearanceLightNavigationBars=!darkUi}
        if (!WebSession.available()) { showUnavailable(); return }
        message = TextView(this).apply { text = "在 B 站官方网页完成登录，成功后会自动返回。本应用不读取或保存你的密码。";textSize=12f;setTextColor(chromeText);setPadding(dp(8),dp(8),dp(8),dp(12)) }
        finishButton = chromeButton("未自动返回？重新检查",true){verify()}
        web = try { WebView(this) } catch (_: Exception) { showUnavailable(); return }
        web.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if(request.isForMainFrame)message.text="登录网页未能载入。检查网络后点击刷新，或取消并继续当前账户。"
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, error: WebResourceResponse) {
                    if(request.isForMainFrame)message.text="登录网页暂不可用（${error.statusCode}）。可以刷新或取消后再试。"
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val allowed = isOfficial(request.url)
                    if (!allowed) message.text = "此跳转不在当前验证范围，已停止。若登录需要其他官方验证入口，需补充核验。"
                    return !allowed
                }
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (!request.isForMainFrame || isOfficial(request.url)) return null
                    return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), "Blocked".byteInputStream())
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(this@WebLoginActivity.background)
            addView(TextView(this@WebLoginActivity).apply{text="连接 B 站账户";textSize=22f;setTextColor(chromeText);setTypeface(null,Typeface.BOLD);setPadding(dp(8),dp(16),dp(8),dp(4))})
            addView(message)
            addView(LinearLayout(this@WebLoginActivity).apply{
                setPadding(dp(8),0,dp(8),dp(8))
                addView(chromeButton("刷新登录网页"){if(!verifying){if(pageReady)web.reload() else openCleanPage()}},LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
                addView(chromeButton("取消登录，返回应用"){if(!verifying)finish() else message.text="正在核验并保存会话，请等待结果后返回。"},LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f).apply{marginStart=dp(8)})
            })
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply{setMargins(dp(8),0,dp(8),dp(8))})
            addView(finishButton,LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,LinearLayout.LayoutParams.WRAP_CONTENT).apply{setMargins(dp(8),0,dp(8),dp(12))})
        })
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, false)
        openCleanPage()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    if (pageReady && !verifying && !clearing && !isFinishing && !isDestroyed) {
                        sessionWatch.next(CookieManager.getInstance().getCookie("https://www.bilibili.com/").orEmpty(), android.os.SystemClock.elapsedRealtime())
                            ?.let { verify(it) }
                    }
                    delay(1000)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, object: androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(!verifying)finish() else message.text="正在核验并保存会话，请等待结果后返回。" }
        })
    }

    private fun openCleanPage() {
        finishButton.isEnabled=false
        lifecycleScope.launch {
            val cleared=withTimeoutOrNull(4000) { suspendCancellableCoroutine<Boolean> { continuation -> WebSession.clear { if(continuation.isActive)continuation.resumeWith(Result.success(it)) } } } == true
            if(!isFinishing&&!isDestroyed) {
                pageReady=cleared;finishButton.isEnabled=cleared
                if(cleared)web.loadUrl("https://passport.bilibili.com/login") else message.text="无法清理网页登录会话。可点击刷新重试，或取消返回。"
            }
        }
    }

    private fun showUnavailable() {
        setContentView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;fitsSystemWindows=true;setPadding(dp(8),dp(40),dp(8),dp(24));setBackgroundColor(this@WebLoginActivity.background)
            addView(TextView(this@WebLoginActivity).apply{text="暂时无法连接账户";textSize=22f;setTextColor(chromeText);setTypeface(null,Typeface.BOLD)})
            addView(TextView(this@WebLoginActivity).apply { text="系统 WebView 不可用，暂时无法打开登录网页。请启用或安装系统支持的 WebView 后重试。游客浏览和收听仍可使用。";textSize=14f;setTextColor(chromeText);setPadding(0,dp(20),0,dp(24)) })
            addView(chromeButton("返回继续使用",true){finish()})
        })
    }

    private fun isOfficial(uri: Uri): Boolean = uri.scheme == "https" && uri.port in setOf(-1,443) && uri.userInfo.isNullOrEmpty() && (uri.host == "bilibili.com" || uri.host?.endsWith(".bilibili.com") == true)
    private fun verify(candidate: String? = null) {
        if (verifying || !pageReady || clearing || isFinishing || isDestroyed) return
        val cookie = candidate ?: LoginSessionWatch.selectedCookie(CookieManager.getInstance().getCookie("https://www.bilibili.com/").orEmpty())
        if (cookie == null) { message.text = "尚未取得登录会话，请先完成网页验证。"; return }
        verifying = true; finishButton.isEnabled = false
        message.text = "已检测到登录会话，正在核验并保存…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { app.accounts.accept(cookie) }
                clearWebData { setResult(RESULT_OK); finish() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (error is PlatformFailure && error.code in setOf(403,412,429,-403,-412,-352)) sessionWatch.stop()
                message.text = if (error is PlatformFailure) "${error.category}。请完成网页验证，或点击重新检查。" else "网络或会话保存失败，可点击重新检查。"
                verifying = false; finishButton.isEnabled = true
            }
        }
    }
    private fun clearWebData(done: () -> Unit = {}) {
        if (clearing || !::web.isInitialized) { done(); return }
        clearing = true
        web.stopLoading(); web.clearHistory(); web.clearCache(true); web.clearFormData()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); done() }
    }
    override fun onDestroy() {
        clearWebData()
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }
}
