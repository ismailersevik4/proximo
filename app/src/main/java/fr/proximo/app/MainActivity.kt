package fr.proximo.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import fr.proximo.app.core.Identity
import fr.proximo.app.mesh.MeshService
import fr.proximo.app.ui.AppRoot
import fr.proximo.app.ui.ProximoTheme

class MainActivity : ComponentActivity() {
    /** Discussion à ouvrir (venant d'une notification). */
    var pendingChat by mutableStateOf<String?>(null)
    var permissionsOk by mutableStateOf(false)

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permissionsOk = requiredPermissions().all { res[it] == true || ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (!permissionsOk) permissionsDeniedOnce = true
        if (Identity.get(this).isReady && permissionsOk) MeshService.start(this)
    }
    var permissionsDeniedOnce by mutableStateOf(false)
    var batteryOk by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La discussion d'une notification n'est ouverte qu'une fois : pas à chaque rotation, ni au retour depuis les applis récentes.
        val fromHistory = ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        pendingChat = if (savedInstanceState == null && !fromHistory) intent?.getStringExtra("chat") else null
        permissionsOk = requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        setContent { ProximoTheme { AppRoot(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingChat = intent.getStringExtra("chat")
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        batteryOk = batteryExempt()
        permissionsOk = requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        if (Identity.get(this).isReady && permissionsOk) MeshService.start(this)
        MeshService.instance?.updater?.retryPendingInstall()
    }

    override fun onPause() { isForeground = false; super.onPause() }

    /** Vrai si Android laisse Proximo tourner en arrière-plan sans le mettre en veille. */
    fun batteryExempt(): Boolean = try { (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName) } catch (e: Exception) { true }

    /** Fenêtre Android « Autoriser Proximo à tourner en arrière-plan ? » ; à défaut, la liste des applis optimisées. */
    @android.annotation.SuppressLint("BatteryLife")
    fun requestBatteryExemption() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    /** Ouvre la fiche de l'appli dans les réglages Android (quand une autorisation a été refusée définitivement). */
    fun openAppSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
    }

    companion object { @Volatile var isForeground = false }

    /** On demande tout d'un coup, mais seules les autorisations indispensables au réseau bloquent l'appli. */
    fun requestPermissions() { askPerms.launch((requiredPermissions() + optionalPermissions()).distinct().toTypedArray()) }

    /** Indispensables : sans elles, aucun téléphone ne peut être trouvé. */
    fun requiredPermissions(): List<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) { l += Manifest.permission.BLUETOOTH_SCAN; l += Manifest.permission.BLUETOOTH_ADVERTISE; l += Manifest.permission.BLUETOOTH_CONNECT }
        else l += Manifest.permission.ACCESS_FINE_LOCATION // exigée par Android pour le scan Bluetooth avant Android 12
        return l
    }

    /** Utiles mais pas bloquantes : Wi-Fi direct, notifications, position, micro. */
    fun optionalPermissions(): List<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) { l += Manifest.permission.NEARBY_WIFI_DEVICES; l += Manifest.permission.POST_NOTIFICATIONS }
        l += Manifest.permission.ACCESS_FINE_LOCATION
        l += Manifest.permission.RECORD_AUDIO
        return l
    }
}
