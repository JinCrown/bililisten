package app.bililisten.playback

import android.content.Context
import android.net.*
import app.bililisten.shared.*
import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.datasource.HttpDataSource
import java.io.IOException

class PlaybackNetwork(context: Context, private val settings: () -> UserSettings, private val changed: () -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var observedNetwork = manager.activeNetwork
    @Volatile private var lost = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { observedNetwork = network; lost = false; changed() }
        override fun onLost(network: Network) { if (network == observedNetwork) { lost = true; changed() } }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
    }
    fun start() = manager.registerDefaultNetworkCallback(callback)
    fun kind(): NetworkKind {
        if (lost) return NetworkKind.OFFLINE
        val network = manager.activeNetwork ?: return NetworkKind.OFFLINE
        val capabilities = manager.getNetworkCapabilities(network) ?: return NetworkKind.OFFLINE
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkKind.OFFLINE
        return if (manager.isActiveNetworkMetered) NetworkKind.METERED else NetworkKind.UNMETERED
    }
    fun blocked() = PlaybackNetworkPolicy.blocked(kind(), settings().mobilePlayback)
    fun check() { blocked()?.let { throw PlaybackNetworkException(it) } }
    override fun close() { manager.unregisterNetworkCallback(callback) }
}
class PlaybackNetworkException(val issue: PlaybackIssue, val audioRestriction: app.bililisten.shared.AudioRestriction? = null) : IOException(issue.message)

@androidx.media3.common.util.UnstableApi
class BoundedMediaRetry(private val allowed: () -> Boolean) : DefaultLoadErrorHandlingPolicy(1) {
    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val failure = info.exception
        if (failure is PlaybackNetworkException) return C.TIME_UNSET
        val code = (failure as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val transient = failure is java.net.SocketTimeoutException || failure is java.net.ConnectException || failure is HttpDataSource.HttpDataSourceException && code == null
        return StreamRetryPolicy.retryDelayMs(info.errorCount, allowed(), code, transient) ?: C.TIME_UNSET
    }
}
