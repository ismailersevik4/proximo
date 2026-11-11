package fr.proximo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.proximo.app.call.CallState
import fr.proximo.app.mesh.MeshService
import kotlinx.coroutines.delay

/** Bandeau « mise à jour disponible » (Internet ou voisin). */
@Composable
fun UpdateBanner(modifier: Modifier) {
    val svc by serviceState()
    val up = svc?.updater ?: return
    val online by up.available.collectAsState()
    val peerInfo by up.peerOffer.collectAsState()
    val info = peerInfo ?: online
    val progress by up.progress.collectAsState()
    val msg by up.message.collectAsState()
    val needPerm by up.needsInstallPermission.collectAsState()
    val bleAsk by up.bleConfirm.collectAsState()
    val tr by up.transfer.collectAsState()
    if (online == null && peerInfo == null && progress < 0 && msg == null && !needPerm && bleAsk == null && tr == null) return
    Column(modifier.fillMaxWidth().padding(12.dp).background(Px.Card, RoundedCornerShape(14.dp)).padding(14.dp)) {
        val t = tr
        if (t != null) {
            Text(if (progress >= 0) "Mise à jour en cours… ${t.percent} %" else "Envoi en cours… ${t.percent} %", color = Color.White, fontWeight = FontWeight.Bold)
            Text(t.label + if (t.ble) " · garde les téléphones côte à côte, écran allumé" else "", color = Px.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { t.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = Px.Accent, trackColor = Px.Card2)
        } else if (bleAsk != null) {
            val b = bleAsk!!
            Text("Pas de Wi-Fi direct avec ${b.peerName ?: "ce voisin"}", color = Color.White, fontWeight = FontWeight.Bold)
            Text("La mise à jour peut passer en Bluetooth, mais c'est lent : environ 10 minutes pour 15 Mo. Garde les deux téléphones côte à côte, écran allumé. Si ça coupe, ça reprendra là où ça s'est arrêté.", color = Px.Muted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { up.bleConfirm.value = null }) { Text("Annuler", color = Px.Muted) }
                TextButton(onClick = { b.fromPeer?.let { up.requestFromPeer(it, allowBle = true) } }) { Text("Continuer en Bluetooth", fontWeight = FontWeight.Bold) }
            }
        } else if (progress >= 0) {
            Text("Mise à jour en cours… $progress %", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = Px.Accent, trackColor = Px.Card2)
        } else if (info != null) {
            val i = info
            val p = peerInfo; val o = online
            Text(when { p != null && o != null -> "Mise à jour ${i.name} disponible"; p != null -> "${p.peerName ?: "Un voisin"} a Proximo ${p.name}"; else -> "Mise à jour ${i.name} disponible" }, color = Color.White, fontWeight = FontWeight.Bold)
            Text(when { p != null && o != null -> "Par Internet, ou depuis le téléphone de ${p.peerName ?: "un voisin"} (Wi-Fi direct ou Bluetooth). Tes contacts et messages sont conservés."; p != null -> "Récupérer la nouvelle version depuis son téléphone (environ 15 Mo, Wi-Fi direct ou Bluetooth) ? Tes contacts et messages sont conservés."; else -> "Télécharger et installer la nouvelle version ? Tes contacts et messages sont conservés." }, color = Px.Muted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { up.dismiss() }) { Text("Plus tard", color = Px.Muted) }
                if (o != null) TextButton(onClick = { up.downloadAndInstall(); up.available.value = null; up.peerOffer.value = null }) { Text(if (p != null) "Par Internet" else "Installer", fontWeight = FontWeight.Bold) }
                if (p != null) TextButton(onClick = { up.requestFromPeer(p.fromPeer!!); up.available.value = null; up.peerOffer.value = null }) { Text(if (o != null) "Depuis ${p.peerName ?: "le voisin"}" else "Installer", fontWeight = FontWeight.Bold) }
            }
        }
        if (needPerm) {
            Text("Mise à jour prête. Android doit autoriser Proximo à installer des applis : appuie sur Autoriser, active l'option, puis reviens dans Proximo.", color = Px.Warn, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { up.needsInstallPermission.value = false }) { Text("Plus tard", color = Px.Muted) }
                TextButton(onClick = { up.requestInstallPermission() }) { Text("Autoriser", fontWeight = FontWeight.Bold) }
            }
        }
        msg?.let { Text(it, color = Px.Warn, fontSize = 13.sp, modifier = Modifier.clickable { up.message.value = null }) } // toucher le texte l'efface, sans refuser la mise à jour pour 24 h
    }
}

/** Petit message en bas de l'écran (ex. « Demande envoyée »), disparaît tout seul. */
@Composable
fun EventToast(modifier: Modifier) {
    val svc by serviceState()
    val events = svc?.messenger?.events ?: return
    val ev by events.collectAsState()
    val e = ev ?: return
    LaunchedEffect(e) { delay(3500); if (events.value == e) events.value = null }
    Box(modifier.padding(horizontal = 24.dp).background(Px.Card2, RoundedCornerShape(999.dp)).clickable { events.value = null }.padding(16.dp, 10.dp)) {
        Text(e.text, color = Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

/** Écran d'appel (entrant, sortant, en cours). */
@Composable
fun CallScreen(s: CallState) {
    val svc by serviceState()
    val calls = svc?.calls
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.phase) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    // Posé par-dessus l'appli : il prend tous les touchers (rien ne passe à l'écran du dessous).
    Column(Modifier.fillMaxSize().background(Px.Bg).pointerInput(Unit) { detectTapGestures { } }.systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Spacer(Modifier.height(40.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(s.peerName, 110.dp, color = Px.Avatar, id = s.peerId)
            Spacer(Modifier.height(18.dp))
            Text(s.peerName, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                when (s.phase) {
                    CallState.SEARCHING -> "Recherche d'un chemin via un voisin…"
                    CallState.RINGING_IN -> "Appel entrant"
                    CallState.RINGING_OUT -> "Ça sonne…"
                    else -> { val sec = (now - s.since) / 1000; String.format("%02d:%02d", sec / 60, sec % 60) }
                }, color = Px.AccentSoft, fontSize = 16.sp,
            )
            Spacer(Modifier.height(6.dp))
            if (s.phase != CallState.SEARCHING) Text(if (s.via != null) "🔒 Chiffré · via ${s.viaName ?: "un voisin"} (2 sauts)" else "🔒 Chiffré · Wi-Fi direct", color = Px.Accent, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            Text("Appel chiffré de bout en bout (bêta). Le relais éventuel ne peut pas écouter. Si la liaison se dégrade, utilise le talkie-walkie.", color = Px.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.phase == CallState.ACTIVE) {
                CallButton(if (s.muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (s.muted) "Micro coupé" else "Micro", if (s.muted) Px.Warn else Px.Card2) { calls?.toggleMute() }
                CallButton(Icons.Filled.CallEnd, "Raccrocher", Px.Danger) { calls?.hangup() }
                CallButton(Icons.Filled.VolumeUp, if (s.speaker) "Haut-parleur" else "Écouteur", if (s.speaker) Px.AccentDark else Px.Card2) { calls?.toggleSpeaker() }
            } else if (s.phase == CallState.RINGING_IN) {
                CallButton(Icons.Filled.CallEnd, "Refuser", Px.Danger) { calls?.reject() }
                CallButton(Icons.Filled.Call, "Répondre", Px.Ok) { calls?.accept() }
            } else CallButton(Icons.Filled.CallEnd, "Annuler", Px.Danger) { calls?.hangup() }
        }
    }
}

@Composable
private fun CallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(68.dp).background(bg, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(30.dp)) }
        Spacer(Modifier.height(6.dp)); Text(label, color = Px.Muted, fontSize = 12.sp)
    }
}
