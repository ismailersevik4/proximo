package fr.proximo.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fr.proximo.app.BuildConfig
import fr.proximo.app.core.Identity
import fr.proximo.app.core.Settings
import fr.proximo.app.mesh.MeshService
import fr.proximo.app.mesh.Modes

fun qrBitmap(text: String, size: Int = 600): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size)
    val dark = 0xFF0B1220.toInt(); val light = 0xFFFFFFFF.toInt()
    for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) dark else light
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
}

@Composable
fun ProfileScreen(onContacts: () -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val me = remember { Identity.get(ctx) }
    var name by remember { mutableStateOf(me.name) }
    var editing by remember { mutableStateOf(false) }
    val qr by androidx.compose.runtime.produceState<Bitmap?>(null, name) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { qrBitmap(me.card().toQr()) } }
    val svc by serviceState()
    val pending by dbState(0) { it.contacts().count { c -> c.status == 2 } }
    val nbContacts by dbState(0) { it.acceptedContacts().size }
    val settings = remember { Settings.get(ctx) }
    val tick by settings.changes.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pickPhoto = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val b = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fr.proximo.app.core.Avatars.fromUri(ctx, uri) }
            if (b != null) { fr.proximo.app.core.Avatars.save(ctx, me.id, b); svc?.messenger?.sendAvatar(null); svc?.messenger?.event("Photo de profil mise à jour") }
            else svc?.messenger?.event("Photo illisible")
        }
    }
    var photoMenu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Px.Bg).verticalScroll(rememberScrollState()).padding(20.dp, 16.dp, 20.dp, 24.dp)) {
        Text("Profil", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().background(Px.Card, RoundedCornerShape(18.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable { photoMenu = true }) {
                Avatar(name, 64.dp, color = Px.Accent, id = me.id)
                Box(Modifier.align(Alignment.BottomEnd).size(22.dp).background(Px.Bg, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) { Text("📷", fontSize = 11.sp) }
                androidx.compose.material3.DropdownMenu(photoMenu, { photoMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Choisir une photo") }, onClick = { photoMenu = false; pickPhoto.launch("image/*") })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Retirer la photo") }, onClick = { photoMenu = false; fr.proximo.app.core.Avatars.remove(ctx, me.id); svc?.messenger?.sendAvatar(null) })
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Mon code : ${me.shortCode}", color = Px.Muted, fontSize = 13.sp)
                Text("Appuie sur la photo pour la changer", color = Px.Muted, fontSize = 11.sp)
            }
            TextButton(onClick = { editing = true }) { Text("Modifier") }
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp)).padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            qr?.let { Image(it.asImageBitmap(), "Mon QR code", Modifier.size(220.dp)) } ?: androidx.compose.foundation.layout.Box(Modifier.size(220.dp))
            Spacer(Modifier.height(8.dp))
            Text("Fais scanner ce code pour être ajouté", color = Color(0xFF334155), fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth().background(Px.Card, RoundedCornerShape(18.dp))) {
            MenuRow("Contacts et demandes", if (pending > 0) "$pending demande(s)" else "$nbContacts contact(s)", onContacts, highlight = pending > 0)
            val modeLabel = remember(tick) { when (settings.mode) { Modes.PORTEE -> "Priorité portée"; Modes.BATTERIE -> "Priorité batterie"; else -> "Normal" } }
            MenuRow("Mode de connexion", modeLabel, onSettings)
            MenuRow("Réglages", "Sons, vocaux, inconnus, mises à jour…", onSettings, last = true)
        }
        Spacer(Modifier.height(14.dp))
        val peersList by peersState()
        val peersCount = peersList.size
        val pendingFlow = svc?.core?.pendingCount
        val pendingOut = if (pendingFlow != null) pendingFlow.collectAsState().value else 0
        Column(Modifier.fillMaxWidth().background(Px.Card, RoundedCornerShape(18.dp)).padding(16.dp)) {
            Text("Réseau", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("$peersCount téléphone(s) à portée · $pendingOut message(s) en attente de relais", color = Px.Muted, fontSize = 13.sp)
            Text("Identifiant du téléphone : ${me.id}", color = Px.Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(28.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(44.dp)
            Spacer(Modifier.height(8.dp))
            Text("Proximo — conçu et développé par Ismail Ersevik", color = Color(0xFFCBD5E1), fontSize = 13.sp, textAlign = TextAlign.Center)
            Text("Appli ${BuildConfig.VERSION_NAME} · APK ${BuildConfig.VERSION_CODE}", color = Px.Muted, fontSize = 12.sp)
        }
    }

    if (editing) {
        var n by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { editing = false }, containerColor = Px.Card,
            title = { Text("Mon pseudo", color = Color.White) },
            text = { OutlinedTextField(n, { if (it.length <= 24) n = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { if (n.trim().length >= 2) { me.name = n.trim(); name = me.name; editing = false } }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Annuler", color = Px.Muted) } },
        )
    }
}

@Composable
fun MenuRow(title: String, value: String, onClick: () -> Unit, highlight: Boolean = false, last: Boolean = false) {
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color.White, fontSize = 15.sp)
            Text("$value  ›", color = if (highlight) Px.Warn else Px.Muted, fontSize = 13.sp)
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(Px.Card2))
    }
}
