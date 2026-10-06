package app.bililisten.storage

import android.app.*
import android.content.Intent
import android.os.IBinder
import app.bililisten.ListenApplication
import app.bililisten.MainActivity
import kotlinx.coroutines.*

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class]) class AudioDownloadService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    override fun onCreate() {
        super.onCreate()
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("audio-downloads","音频下载",NotificationManager.IMPORTANCE_LOW))
        val tap=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(620,Notification.Builder(this,"audio-downloads").setContentTitle("正在处理音频下载")
            .setContentText("可在下载管理中查看进度、暂停或取消").setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(tap).setOngoing(true).build())
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        scope.launch {try{(application as ListenApplication).downloads.drain()}catch(e:CancellationException){throw e}catch(_:Exception){/* Existing files and index are retained on storage failure. */}finally{stopSelf(startId)}}
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int){scope.cancel();stopSelf()}
    override fun onDestroy(){scope.cancel();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
}
