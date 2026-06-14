package com.jkbms.gateway.ble

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jkbms.gateway.server.BmsHttpServer
import com.jkbms.gateway.ui.MainActivity

class BmsService : Service() {

    companion object {
        private const val CHANNEL_ID = "jkbms_gw"
        private const val NOTIF_ID = 1
        const val PORT = 8080

        fun start(context: Context) {
            val i = Intent(context, BmsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) = context.stopService(Intent(context, BmsService::class.java))
    }

    inner class LocalBinder : Binder() { fun get() = this@BmsService }

    private val binder = LocalBinder()
    lateinit var bleManager: BleManager
    private var httpServer: BmsHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotif("Uruchamianie..."))

        bleManager = BleManager(applicationContext).apply {
            onStatusChanged = { status ->
                updateNotif(when (status) {
                    "CONNECTED" -> "Połączono z BMS"
                    "CONNECTING" -> "Łączenie..."
                    "DISCONNECTED" -> "Rozłączono — ponawiam..."
                    else -> status
                })
            }
            onDataReceived = { raw ->
                val cur = BmsRepository.getCurrent()
                JkBmsDecoder.decode(raw, cur.deviceName, cur.deviceAddress)?.let { data ->
                    BmsRepository.update(data)
                    updateNotif("SOC: ${data.soc}%  ${data.totalVoltage}V  ${data.current}A")
                }
            }
        }

        httpServer = BmsHttpServer(PORT, bleManager).also { it.start() }
        Log.i("BmsService", "HTTP serwer na porcie $PORT")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        bleManager.disconnect()
        httpServer?.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "JK BMS Gateway", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JK BMS Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pi).setOngoing(true).setSilent(true).build()
    }

    fun updateNotif(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))
}
