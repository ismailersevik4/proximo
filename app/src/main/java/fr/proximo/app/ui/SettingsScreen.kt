package fr.proximo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.proximo.app.BuildConfig
import fr.proximo.app.core.Settings
import fr.proximo.app.mesh.MeshService
import fr.proximo.app.mesh.Modes

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings.get(ctx) }
    val tick by settings.changes.collectAsState()
    val svc by serviceState()

    Column(Modifier.fillMaxSize().background(Px.Bg)) {
        ScreenHeader("Réglages", onBack)
        key(tick) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Section("Mode de connexion") {
                ModeRow("Normal", "Bluetooth en continu, Wi-Fi direct par impulsions. Portée maximale, batterie correcte.", settings.mode == Modes.NORMAL) { settings.mode = Modes.NORMAL; svc?.updateMode(Modes.NORMAL) }
                ModeRow("Priorité portée", "Wi-Fi direct en continu : les voisins lointains apparaissent tout de suite. Consomme plus.", settings.mode == Modes.PORTEE) { settings.mode = Modes.PORTEE; svc?.updateMode(Modes.PORTEE) }
                ModeRow("Priorité batterie", "Recherche moins fréquente. La portée baisse quand le téléphone est dans la poche.", settings.mode == Modes.BATTERIE) { settings.mode = Modes.BATTERIE; svc?.updateMode(Modes.BATTERIE) }
            }
            Spacer(Modifier.height(14.dp))
            Section("Messages") {
                SwitchRow("Lire les vocaux à l'arrivée", "Comme un talkie-walkie : le vocal est joué au haut-parleur dès qu'il arrive.", settings.autoPlayVoice) { settings.autoPlayVoice = it }
                SwitchRow("Sons de notification", "Son à chaque nouveau message. Les SOS sonnent toujours.", settings.sounds) { settings.sounds = it }
                SwitchRow("Messages privés des inconnus", "Accepter les messages privés de téléphones qui ne sont pas dans tes contacts.", settings.allowUnknownDm) { settings.allowUnknownDm = it }
            }
            Spacer(Modifier.height(14.dp))
            Section("Réseau et mises à jour") {
                SwitchRow("Démarrer avec le téléphone", "Le réseau Proximo se lance tout seul au démarrage.", settings.autoStart) { settings.autoStart = it }
                SwitchRow("Partager ma version aux voisins", "Un voisin qui a une version plus ancienne peut récupérer l'appli depuis mon téléphone (Wi-Fi direct).", settings.shareUpdates) { settings.shareUpdates = it }
                Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Version installée", color = Color.White); Text("Appli ${BuildConfig.VERSION_NAME} · APK ${BuildConfig.VERSION_CODE}", color = Px.Muted, fontSize = 12.sp) }
                    TextButton(onClick = { svc?.updater?.checkOnline(); svc?.messenger?.events?.value = "Vérification en cours…" }) { Text("Vérifier") }
                }
            }
            Spacer(Modifier.height(14.dp))
            Section("Réseau") {
                Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Arrêter le réseau", color = Color.White); Text("Coupe Bluetooth et Wi-Fi direct de Proximo jusqu'à la prochaine ouverture de l'appli.", color = Px.Muted, fontSize = 12.sp) }
                    TextButton(onClick = { MeshService.stop(ctx) }) { Text("Arrêter", color = Px.Danger) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Proximo — conçu et développé par Ismail Ersevik", color = Px.Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        } }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, color = Px.Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(4.dp, 0.dp, 0.dp, 6.dp))
    Column(Modifier.fillMaxWidth().background(Px.Card, RoundedCornerShape(16.dp))) { content() }
}

@Composable
private fun ModeRow(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(8.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Column { Text(title, color = Color.White); Text(desc, color = Px.Muted, fontSize = 12.sp) }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) { Text(title, color = Color.White); Text(desc, color = Px.Muted, fontSize = 12.sp) }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Px.Bg, checkedTrackColor = Px.Accent))
    }
}
