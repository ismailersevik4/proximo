package fr.proximo.app.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fr.proximo.app.MainActivity
import fr.proximo.app.R
import fr.proximo.app.call.CallManager
import fr.proximo.app.core.Identity
import fr.proximo.app.core.Settings
import fr.proximo.app.msg.Messenger
import fr.proximo.app.update.Updater

/** Service de premier plan : fait tourner le réseau même appli fermée. */
class MeshService : Service() {
    lateinit var core: MeshCore
    lateinit var messenger: Messenger
    lateinit var updater: Updater
    lateinit var calls: CallManager

    override fun onCreate() {
        super.onCreate()
        running.value = this
        createChannels(this)
        goForeground(NOTIF_ID, serviceNotification())
        val me = Identity.get(this)
        val transports = ArrayList<Transport>()
        val holder = object : TransportListener {
            override fun onPeerSeen(peerId: String, name: String?, transport: String, rssi: Int) = core.onPeerSeen(peerId, name, transport, rssi)
            override fun onPeerLost(peerId: String, transport: String) = core.onPeerLost(peerId, transport)
            override fun onFrame(data: ByteArray, peerId: String, transport: String) = core.onFrame(data, peerId, transport)
            override fun onStatus(transport: String, enabled: Boolean, detail: String) = core.onStatus(transport, enabled, detail)
        }
        transports.add(BleTransport(this, me.id, { me.name }, holder))
        transports.add(AwareTransport(this, me.id, { me.name }, holder))
        core = MeshCore(this, transports)
        messenger = Messenger(this, core)
        updater = Updater(this, core)
        calls = CallManager(this, core)
        core.handler = { f, from -> if (!calls.handle(f, from) && !updater.handle(f, from)) messenger.handle(f, from) }
        core.onPeerChange = { p, appeared -> if (appeared) { messenger.onPeerAppeared(p); updater.onPeerAppeared(p) } }
        core.setMode(Settings.get(this).mode)
        core.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { core.stop() } catch (_: Exception) {}
        calls.hangup()
        running.value = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateMode(mode: Int) { core.setMode(mode) }

    private fun serviceNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Proximo")
            .setContentText(getString(R.string.notif_running))
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun goForeground(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) else startForeground(id, n)
    }

    companion object {
        const val NOTIF_ID = 1
        const val CH_SERVICE = "service"
        const val CH_MESSAGES = "messages"
        const val CH_SOS = "sos"
        const val CH_CALLS = "calls"
        /** Service en cours (null si arrêté) ; observable par l'interface. */
        val running = kotlinx.coroutines.flow.MutableStateFlow<MeshService?>(null)
        val instance: MeshService? get() = running.value

        fun start(ctx: Context) {
            if (!Identity.get(ctx).isReady) return
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, MeshService::class.java)) } catch (e: Exception) { android.util.Log.w("Proximo", "démarrage du service", e) }
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, MeshService::class.java)) }

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_SERVICE, ctx.getString(R.string.notif_channel_service), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, ctx.getString(R.string.notif_channel_messages), NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
            nm.createNotificationChannel(NotificationChannel(CH_SOS, ctx.getString(R.string.notif_channel_sos), NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true); setBypassDnd(true) })
            nm.createNotificationChannel(NotificationChannel(CH_CALLS, ctx.getString(R.string.notif_channel_calls), NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        }
    }
}

/** Relance le réseau au démarrage du téléphone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Settings.get(context).autoStart) MeshService.start(context)
    }
}
