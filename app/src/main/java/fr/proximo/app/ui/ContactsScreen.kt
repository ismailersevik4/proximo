package fr.proximo.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import fr.proximo.app.core.Card
import fr.proximo.app.core.Contact
import fr.proximo.app.core.Db

@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().background(Px.Bar).padding(8.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White) }
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun ContactsScreen(onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val ctx = LocalContext.current
    val svc by serviceState()
    val contacts by dbState(emptyList<Contact>()) { it.contacts() }
    val codes by dbState(emptyList<String>()) { it.pendingCodes() }
    val peers by peersState()
    var code by remember { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<Contact?>(null) }

    val scan = rememberLauncherForActivityResult(ScanContract()) { r ->
        val t = r.contents ?: return@rememberLauncherForActivityResult
        val card = Card.fromQr(t)
        if (card != null) svc?.messenger?.addFromCard(card) else svc?.messenger?.event("Ce QR code n'est pas un code Proximo")
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Vise le QR code Proximo de l'autre téléphone").setBeepEnabled(false).setOrientationLocked(true))
    }

    Column(Modifier.fillMaxSize().background(Px.Bg)) {
        ScreenHeader("Contacts", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item {
                Button(onClick = { askCamera.launch(android.Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Icon(Icons.Filled.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scanner un QR code", fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(code, { code = it.uppercase() }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Code PRX-XXXX-XXXX", color = Px.Muted) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { if (svc?.messenger?.addByCode(code) == true) code = "" else svc?.messenger?.event("Code invalide") }) { Text("Ajouter") }
                }
                Text("Le code s'échange par SMS ou WhatsApp ; l'ajout se fait dès que vous êtes à portée l'un de l'autre.", color = Px.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                if (codes.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Codes en attente d'un téléphone à portée :", color = Px.Warn, fontSize = 13.sp)
                    codes.forEach { c -> Row(verticalAlignment = Alignment.CenterVertically) { Text(c, color = Color.White, modifier = Modifier.weight(1f)); IconButton(onClick = { Db.get(ctx).removePendingCode(c) }) { Icon(Icons.Filled.Close, "Retirer", tint = Px.Muted) } } }
                }
                Spacer(Modifier.height(16.dp))
            }
            val incoming = contacts.filter { it.status == 2 }
            if (incoming.isNotEmpty()) {
                item { Text("Demandes reçues", color = Px.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp)) }
                items(incoming, key = { "in" + it.id }) { c ->
                    Row(Modifier.fillMaxWidth().background(Px.Card, RoundedCornerShape(14.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name, 44.dp, id = c.id); Spacer(Modifier.width(12.dp))
                        Text(c.name, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { svc?.messenger?.refuseContact(c.id) }) { Icon(Icons.Filled.Close, "Refuser", tint = Px.Muted) }
                        IconButton(onClick = { svc?.messenger?.acceptContact(c.id) }) { Icon(Icons.Filled.Check, "Accepter", tint = Px.Accent) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
            val outgoing = contacts.filter { it.status == 0 }
            if (outgoing.isNotEmpty()) {
                item { Text("Demandes envoyées", color = Px.Muted, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp)) }
                items(outgoing, key = { "out" + it.id }) { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(c.name, 40.dp, id = c.id); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(c.name, color = Color.White); Text(if (peers.any { it.id == c.id }) "À portée · en attente de sa réponse" else "Partira dès qu'il sera à portée", color = Px.Muted, fontSize = 12.sp) }
                        IconButton(onClick = { toDelete = c }) { Icon(Icons.Filled.Close, "Annuler", tint = Px.Muted) }
                    }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
            val accepted = contacts.filter { it.accepted }
            item { Text("Mes contacts (${accepted.size})", color = Px.Muted, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp)) }
            items(accepted, key = { it.id }) { c ->
                val online = peers.any { it.id == c.id }
                Row(Modifier.fillMaxWidth().clickable { onOpenChat(c.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.name, 46.dp, online = online, id = c.id); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(if (online) "À portée" else if (c.lastSeen > 0) "Vu ${timeStr(c.lastSeen)}" else "Jamais vu à portée", color = if (online) Px.AccentSoft else Px.Muted, fontSize = 12.sp)
                    }
                    IconButton(onClick = { toDelete = c }) { Icon(Icons.Filled.Close, "Supprimer", tint = Px.Muted) }
                }
            }
            if (accepted.isEmpty()) item { Text("Aucun contact pour l'instant. Les téléphones à portée sont sur le radar.", color = Px.Muted, fontSize = 13.sp) }
        }
    }

    toDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { toDelete = null }, containerColor = Px.Card,
            title = { Text("Supprimer ${c.name} ?", color = Color.White) },
            text = { Text("Le contact et vos messages seront effacés de ce téléphone.", color = Px.Muted) },
            confirmButton = { TextButton(onClick = { Db.get(ctx).deleteContact(c.id); toDelete = null }) { Text("Supprimer", color = Px.Danger) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler", color = Px.Muted) } },
        )
    }
}

@Composable
fun NewGroupScreen(onBack: () -> Unit, onCreated: (String) -> Unit) {
    val svc by serviceState()
    val contacts by dbState(emptyList<Contact>()) { it.acceptedContacts() }
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateOf(setOf<String>()) }
    Column(Modifier.fillMaxSize().background(Px.Bg)) {
        ScreenHeader("Nouveau groupe", onBack) {
            TextButton(enabled = name.trim().length >= 2 && selected.value.isNotEmpty(), onClick = { val g = svc?.messenger?.createGroup(name, selected.value.toList()); if (g != null) onCreated(g.id) }) { Text("Créer") }
        }
        OutlinedTextField(name, { if (it.length <= 30) name = it }, label = { Text("Nom du groupe") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp))
        Text("Membres (parmi tes contacts) :", color = Px.Muted, modifier = Modifier.padding(horizontal = 16.dp))
        LazyColumn {
            items(contacts, key = { it.id }) { c ->
                val on = c.id in selected.value
                Row(Modifier.fillMaxWidth().clickable { selected.value = if (on) selected.value - c.id else selected.value + c.id }.padding(16.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { selected.value = if (it) selected.value + c.id else selected.value - c.id })
                    Avatar(c.name, 40.dp, id = c.id); Spacer(Modifier.width(12.dp)); Text(c.name, color = Color.White)
                }
            }
            if (contacts.isEmpty()) item { Text("Ajoute d'abord des contacts.", color = Px.Muted, modifier = Modifier.padding(16.dp)) }
        }
        Text("La clé du groupe est envoyée chiffrée à chaque membre. Les membres ajoutés plus tard reçoivent aussi la clé.", color = Px.Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
    }
}
