package app.bililisten.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import app.bililisten.shared.*

/** Observes the service's AudioTrack, never infers its route from connected devices. */
class AudioOutputMonitor(context: Context, private val changed: () -> Unit, private val disconnected: () -> Unit) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var closed = false
    private var lastRoute: AudioDeviceInfo? = null
    private var preferredId: Int? = null
    private var preferredType: Int? = null
    private var onlyExternal = false
    private var sinkRate: Int? = null
    private val routeListener = AudioRouting.OnRoutingChangedListener { routing ->
        if (routing === track && !closed) {
            val route = track?.routedDevice
            if (lastRoute?.let { external(it.type) } == true && route?.let { !external(it.type) } == true) disconnected()
            if (onlyExternal && route != null && !external(route.type)) disconnected()
            lastRoute = route
            changed()
        }
    }
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { changed() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { it.id == preferredId || it.id == lastRoute?.id || external(it.type) }) disconnected()
            changed()
        }
    }
    fun start() { manager.registerAudioDeviceCallback(callback,handler) }
    fun attach(value: AudioTrack, rate: Int) { handler.post {
        if (closed) return@post
        track?.removeOnRoutingChangedListener(routeListener)
        track = value; lastRoute = null; sinkRate = rate
        value.addOnRoutingChangedListener(routeListener,handler)
        changed()
    } }
    fun devices(): List<AudioDeviceInfo> = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
    fun select(id: Int?): AudioDeviceInfo? {
        val selected = id?.let { key -> devices().firstOrNull { it.id == key } ?: throw IllegalArgumentException("输出设备已断开") }
        preferredId = selected?.id; preferredType = selected?.type
        return selected
    }
    fun configure(settings: UserSettings): AudioDeviceInfo? {
        onlyExternal = settings.externalOutputOnly
        if (preferredType != settings.preferredOutputType) { preferredId = null; preferredType = settings.preferredOutputType }
        return preferredType?.let { type -> devices().firstOrNull { it.id == preferredId } ?: devices().firstOrNull { it.type == type } }?.also { preferredId=it.id }
    }
    fun allowed(): Boolean = (preferredType == null || devices().any { it.type == preferredType && (preferredId == null || it.id == preferredId) }) &&
        (!onlyExternal || devices().any { external(it.type) })
    fun snapshot(): OutputExperience {
        val route = runCatching { track?.routedDevice }.getOrNull()
        return OutputExperience(devices().map { AudioOutput(it.id,it.type,name(it),it.sampleRates.toList(),it.channelCounts.toList()) },preferredId,
            when { !allowed() -> "所选输出未连接，已暂停；重新选择输出后请手动播放"
                route == null -> "尚未报告实际输出；播放后由系统确认路由"
                else -> "实际输出由系统路由确认；解码能力不代表整条链路无损或空间效果" },onlyExternal,route?.id,route?.let(::name).orEmpty(),sinkRate)
    }
    fun close() { closed=true; manager.unregisterAudioDeviceCallback(callback); track?.removeOnRoutingChangedListener(routeListener);track=null }
    companion object {
        fun external(type: Int) = type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET,AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_USB_DEVICE,AudioDeviceInfo.TYPE_HDMI,AudioDeviceInfo.TYPE_HDMI_ARC,
            AudioDeviceInfo.TYPE_LINE_ANALOG,AudioDeviceInfo.TYPE_LINE_DIGITAL,AudioDeviceInfo.TYPE_HEARING_AID,26,27,29)
        private fun name(device: AudioDeviceInfo) = when(device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "手机扬声器"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "手机听筒"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,AudioDeviceInfo.TYPE_BLUETOOTH_SCO,26,27 -> "蓝牙输出"
            AudioDeviceInfo.TYPE_USB_HEADSET,AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音频输出"
            else -> device.productName.toString().take(80).ifBlank { "系统输出" }
        }
    }
}
