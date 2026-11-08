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
        if (Identity.get(this).isReady) MeshService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingChat = intent?.getStringExtra("chat")
        permissionsOk = requiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        setContent { ProximoTheme { AppRoot(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingChat = intent.getStringExtra("chat")
    }

    override fun onResume() {
        super.onResume()
        if (Identity.get(this).isReady && permissionsOk) MeshService.start(this)
        MeshService.instance?.updater?.retryPendingInstall()
    }

    fun requestPermissions() { askPerms.launch(requiredPermissions().toTypedArray()) }

    fun requiredPermissions(): List<String> {
        val l = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) { l += Manifest.permission.BLUETOOTH_SCAN; l += Manifest.permission.BLUETOOTH_ADVERTISE; l += Manifest.permission.BLUETOOTH_CONNECT }
        if (Build.VERSION.SDK_INT >= 33) { l += Manifest.permission.NEARBY_WIFI_DEVICES; l += Manifest.permission.POST_NOTIFICATIONS }
        // La position est exigée par Android pour le Bluetooth (avant Android 12) et le Wi-Fi direct (avant Android 13),
        // et sert aussi au partage de position.
        l += Manifest.permission.ACCESS_FINE_LOCATION
        l += Manifest.permission.RECORD_AUDIO
        return l
    }
}
