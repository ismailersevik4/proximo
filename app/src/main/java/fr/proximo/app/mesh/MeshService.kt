package fr.proximo.app.mesh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.aware.WifiAwareManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import fr.proximo.app.MainActivity
import fr.proximo.app.R
import fr.proximo.app.call.CallManager
import fr.proximo.app.core.Identity
import fr.proximo.app.core.Settings
import fr.proximo.app.msg.Messenger
import fr.proximo.app.update.Updater

/** Service de premier plan : fait tourner le réseau même appli fermée. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class MeshService : Service() {
    lateinit var core: MeshCore
    lateinit var messenger: Messenger
    lateinit var updater: Updater
    lateinit var calls: CallManager
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
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
        core.handler = { f, from -> if (calls.handle(f, from) || updater.handle(f, from)) true else messenger.handle(f, from) }
        // Dès qu'une liaison de données est ouverte : échange des cartes (clés) et offre de mise à jour.
        core.onLinkUp = { p -> messenger.onPeerAppeared(p); updater.onPeerAppeared(p) }
        core.setMode(Settings.get(this).mode)
        core.start()
        updater.startPeriodicOffers()
        // Garde le processeur éveillé pour le Bluetooth quand l'écran est éteint (les vieux téléphones coupent sinon).
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Proximo:reseau").also { it.setReferenceCounted(false); it.acquire() }
        } catch (_: Exception) {}
        ContextCompat.registerReceiver(this, radioReceiver, IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        running.value = this
        // Notification vivante : nombre de téléphones à portée et messages en attente.
        scope.launch {
            kotlinx.coroutines.flow.combine(core.peers, core.pendingDetail) { p, d -> p.size to d.first }
                .debounce(1500)
                .collect { (nb, pending) -> try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, serviceNotification(nb, pending)) } catch (_: Exception) {} }
        }
    }

    /** Bluetooth ou Wi-Fi rallumé ou éteint pendant que l'appli tourne : on relance le transport concerné. */
    private val radioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val st = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (st == BluetoothAdapter.STATE_ON || st == BluetoothAdapter.STATE_OFF) core.restart(Modes.BLE)
                }
                WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED -> core.restart(Modes.WIFI)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** L'utilisateur a balayé l'appli des récents : certains téléphones tuent le service, on le relance 3 s plus tard. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (Settings.get(this).stoppedManually) return
        try {
            val pi = PendingIntent.getForegroundService(this, 1, Intent(this, MeshService::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + 3000, pi)
            else am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + 3000, pi)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        scope.cancel()
        try { wakeLock?.release() } catch (_: Exception) {}
        try { unregisterReceiver(radioReceiver) } catch (_: Exception) {}
        try { core.stop() } catch (_: Exception) {}
        calls.hangup()
        running.value = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateMode(mode: Int) { core.setMode(mode) }

    private fun serviceNotification(nb: Int = -1, pending: Int = 0): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when {
            nb < 0 -> getString(R.string.notif_running)
            nb == 0 -> "Personne à portée pour l'instant" + if (pending > 0) " · $pending de tes messages en attente" else ""
            else -> "$nb téléphone${if (nb > 1) "s" else ""} à portée" + if (pending > 0) " · $pending de tes messages en attente" else ""
        }
        return NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Proximo" + if (nb > 0) " · réseau actif" else "")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(false) // balayable (Android 13+) : le service continue quand même
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
            if (!Identity.get(ctx).isReady || Settings.get(ctx).stoppedManually) return
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, MeshService::class.java)) } catch (e: Exception) { android.util.Log.w("Proximo", "démarrage du service", e) }
        }
        fun stop(ctx: Context) { Settings.get(ctx).stoppedManually = true; ctx.stopService(Intent(ctx, MeshService::class.java)) }
        fun resume(ctx: Context) { Settings.get(ctx).stoppedManually = false; start(ctx) }

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
