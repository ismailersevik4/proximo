package fr.proximo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    val info by up.available.collectAsState()
    val progress by up.progress.collectAsState()
    val msg by up.message.collectAsState()
    if (info == null && progress < 0 && msg == null) return
    Column(modifier.fillMaxWidth().padding(12.dp).background(Px.Card, RoundedCornerShape(14.dp)).padding(14.dp)) {
        if (progress >= 0) {
            Text("Mise à jour en cours… $progress %", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp)); LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = Px.Accent, trackColor = Px.Card2)
        } else if (info != null) {
            val i = info!!
            Text(if (i.fromPeer != null) "${i.peerName ?: "Un voisin"} a Proximo ${i.name}" else "Mise à jour ${i.name} disponible", color = Color.White, fontWeight = FontWeight.Bold)
            Text(if (i.fromPeer != null) "Récupérer la nouvelle version depuis son téléphone par Wi-Fi direct (environ 15 Mo) ? Tes contacts et messages sont conservés." else "Télécharger et installer la nouvelle version ? Tes contacts et messages sont conservés.", color = Px.Muted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { up.dismiss() }) { Text("Plus tard", color = Px.Muted) }
                TextButton(onClick = { if (i.fromPeer != null) up.requestFromPeer(i.fromPeer) else up.downloadAndInstall(); up.available.value = null }) { Text("Installer", fontWeight = FontWeight.Bold) }
            }
        }
        msg?.let { Text(it, color = Px.Warn, fontSize = 13.sp, modifier = Modifier.clickable { up.dismiss() }) }
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
    Column(Modifier.fillMaxSize().background(Px.Bg).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Spacer(Modifier.height(40.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(s.peerName, 110.dp, color = Px.Avatar)
            Spacer(Modifier.height(18.dp))
            Text(s.peerName, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                when (s.phase) {
                    CallState.RINGING_IN -> "Appel entrant · Wi-Fi direct"
                    CallState.RINGING_OUT -> "Appel en cours…"
                    else -> { val sec = (now - s.since) / 1000; String.format("%02d:%02d", sec / 60, sec % 60) }
                }, color = Px.AccentSoft, fontSize = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text("Appel en direct entre les deux téléphones par Wi-Fi direct (bêta, pas encore chiffré). Si la liaison se dégrade, utilise le talkie-walkie.", color = Px.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
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
