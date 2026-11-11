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
        if (tick < 0) return // lecture du compteur : l'écran se rafraîchit à chaque réglage changé
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Section("Mode de connexion") {
                ModeRow("Normal", "Recherche Bluetooth équilibrée, Wi-Fi direct actif. Bon compromis portée / batterie.", settings.mode == Modes.NORMAL) { settings.mode = Modes.NORMAL; svc?.updateMode(Modes.NORMAL) }
                ModeRow("Priorité portée", "Recherche Bluetooth rapide : les voisins apparaissent et se reconnectent plus vite. Consomme plus.", settings.mode == Modes.PORTEE) { settings.mode = Modes.PORTEE; svc?.updateMode(Modes.PORTEE) }
                ModeRow("Priorité batterie", "Recherche Bluetooth lente et entretien Wi-Fi moins fréquent, et (Android 12 et plus) le téléphone peut dormir quand personne n'est à portée. Les voisins mettent plus de temps à apparaître.", settings.mode == Modes.BATTERIE) { settings.mode = Modes.BATTERIE; svc?.updateMode(Modes.BATTERIE) }
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
                SwitchRow("Partager ma version aux voisins", "Un voisin qui a une version plus ancienne peut récupérer l'appli depuis mon téléphone (Wi-Fi direct ou Bluetooth).", settings.shareUpdates) { settings.shareUpdates = it }
                Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Version installée", color = Color.White); Text("Appli ${BuildConfig.VERSION_NAME} · APK ${BuildConfig.VERSION_CODE}", color = Px.Muted, fontSize = 12.sp) }
                    TextButton(onClick = { svc?.messenger?.event("Vérification en cours…"); svc?.updater?.checkOnline(manual = true) }) { Text("Vérifier") }
                }
                Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Chercher une mise à jour chez les voisins", color = Color.White); Text("Demande aux téléphones à portée s'ils ont une version plus récente (Wi-Fi direct ou Bluetooth).", color = Px.Muted, fontSize = 12.sp) }
                    TextButton(onClick = { val n = svc?.updater?.askPeers() ?: 0; svc?.messenger?.event(if (n == 0) "Aucun téléphone connecté pour l'instant" else "Demande envoyée à $n téléphone(s)…") }) { Text("Chercher") }
                }
            }
            Spacer(Modifier.height(14.dp))
            Section("Réseau") {
                val act = ctx as? fr.proximo.app.MainActivity
                val exempt = act?.batteryOk ?: true // relu à chaque retour dans l'appli
                Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (exempt) "Arrière-plan autorisé ✓" else "Arrière-plan : à autoriser", color = if (exempt) Color.White else Px.Warn)
                        Text(if (exempt) "Android ne met pas Proximo en veille. Sur Samsung, vérifie aussi « Applications jamais mises en veille »." else "Sans ça, les messages n'arrivent plus quand l'écran est éteint.", color = Px.Muted, fontSize = 12.sp)
                    }
                    TextButton(onClick = {
                        if (!exempt) act?.requestBatteryExemption()
                        else try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e: Exception) { act?.openAppSettings() }
                    }) { Text(if (exempt) "Réglages" else "Autoriser") }
                }
                val stopped = settings.stoppedManually
                Row(Modifier.fillMaxWidth().padding(16.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (stopped) "Réseau arrêté" else "Arrêter le réseau", color = if (stopped) Px.Warn else Color.White)
                        Text(if (stopped) "Proximo ne cherche plus personne et ne reçoit rien. Appuie sur Démarrer pour reprendre." else "Coupe Bluetooth et Wi-Fi direct de Proximo jusqu'à ce que tu appuies sur Démarrer.", color = Px.Muted, fontSize = 12.sp)
                    }
                    if (stopped) TextButton(onClick = { MeshService.resume(ctx) }) { Text("Démarrer") }
                    else TextButton(onClick = { MeshService.stop(ctx) }) { Text("Arrêter", color = Px.Danger) }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text("Proximo — conçu et développé par Ismail Ersevik", color = Px.Muted, fontSize = 12.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
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
