package fr.proximo.app.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import fr.proximo.app.core.Db
import fr.proximo.app.core.Message
import fr.proximo.app.mesh.MeshService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ChatScreen(chat: String, onBack: (() -> Unit)?, onOpenContacts: () -> Unit) {
    val ctx = LocalContext.current
    val svc by serviceState()
    val db = remember { Db.get(ctx) }
    val isAll = chat == Db.ALL
    val group by dbState<fr.proximo.app.core.Group?>(null) { it.group(chat) }
    val contact by dbState<fr.proximo.app.core.Contact?>(null) { it.contact(chat) }
    val messages by dbState(emptyList<Message>()) { it.messages(chat) }
    val peers by peersState()
    val names by dbState(emptyMap<String, String>()) { d -> d.contacts().associate { c -> c.id to c.name } }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sosDialog by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(ctx) }

    val title = when { isAll -> "Tout le monde"; group != null -> group!!.name; else -> contact?.name ?: "Discussion" }
    val peer = peers.firstOrNull { it.id == chat }
    val subtitle = when {
        isAll -> if (peers.isEmpty()) "Personne à portée pour l'instant" else "${peers.size} téléphone(s) à portée · messages publics"
        group != null -> "${group!!.members.size} membres · ${group!!.members.count { m -> peers.any { it.id == m } }} à portée"
        peer != null -> if (peer.linked) "Joignable" + (peer.meters?.let { " · $it m" } ?: " · Wi-Fi direct") else "À portée · liaison en attente (téléphone en veille ?)"
        else -> "Hors de portée · les messages partiront dès que possible"
    }

    // Marquer comme lu et dire à la messagerie que cette discussion est ouverte
    LaunchedEffect(chat, messages.size) { withContext(Dispatchers.IO) { db.markRead(chat) } }
    DisposableEffect(chat, svc) { svc?.messenger?.openChat = chat; onDispose { if (svc?.messenger?.openChat == chat) svc?.messenger?.openChat = null } }
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { val b = withContext(Dispatchers.IO) { Photos.compress(ctx, uri) }; if (b != null) svc?.messenger?.sendPhoto(chat, b) }
    }
    val photoFile = remember { File(ctx.cacheDir, "photo.jpg") }
    val photoUri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", photoFile) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scope.launch { val b = withContext(Dispatchers.IO) { Photos.compress(ctx, photoUri) }; if (b != null) svc?.messenger?.sendPhoto(chat, b) }
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) takePhoto.launch(photoUri) }
    // Vidéos : filmées directement en basse qualité (15 s), ou choisies dans la galerie si elles sont légères.
    val videoFile = remember { File(ctx.cacheDir, "video.mp4") }
    val videoUri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", videoFile) }
    fun sendVideoFrom(uri: Uri) = scope.launch {
        val (res, err) = withContext(Dispatchers.IO) { Videos.load(ctx, uri) }
        if (res != null) svc?.messenger?.sendVideo(chat, res.first, res.second) else svc?.messenger?.event(err ?: "Vidéo illisible")
    }
    val captureVideo = rememberLauncherForActivityResult(object : ActivityResultContracts.CaptureVideo() {
        override fun createIntent(context: android.content.Context, input: Uri): Intent = super.createIntent(context, input)
            .putExtra(android.provider.MediaStore.EXTRA_DURATION_LIMIT, Videos.MAX_SECONDS)
            .putExtra(android.provider.MediaStore.EXTRA_VIDEO_QUALITY, 0)
            .putExtra(android.provider.MediaStore.EXTRA_SIZE_LIMIT, Videos.MAX_BYTES)
    }) { ok -> if (ok) sendVideoFrom(videoUri) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) sendVideoFrom(uri) }
    val askCameraVideo = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) captureVideo.launch(videoUri) }
    fun videoGuard(action: () -> Unit) {
        if (svc?.messenger?.canSendVideo(chat) == true) action() else svc?.messenger?.event("Vidéo impossible pour l'instant : il faut une liaison Wi-Fi direct (le Bluetooth est trop lent). Envoie une photo ou un vocal.")
    }

    Column(Modifier.fillMaxSize().background(Px.Bg).imePadding()) {
        // ---- en-tête ----
        Row(Modifier.fillMaxWidth().background(Px.Bar).padding(8.dp, 10.dp, 8.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White) } else Spacer(Modifier.width(8.dp))
            if (isAll) Box(Modifier.size(42.dp).background(Px.AccentDark, CircleShape), contentAlignment = Alignment.Center) { Text("👥", fontSize = 20.sp) }
            else Avatar(title, 42.dp, online = if (group != null) null else peer != null, square = group != null)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(subtitle, color = if (peer != null || isAll || group != null) Px.AccentSoft else Px.Muted, fontSize = 12.sp)
            }
            if (!isAll) Icon(Icons.Filled.Lock, "Chiffré", tint = Px.Accent, modifier = Modifier.size(18.dp))
            if (!isAll && group == null) {
                val can = svc?.calls?.canCall(chat) == true
                IconButton(onClick = { if (can) svc?.calls?.call(chat) else svc?.messenger?.event("Trop loin pour un appel (pas de Wi-Fi direct, même via un voisin) : utilise le talkie-walkie") }) {
                    Icon(Icons.Filled.Call, "Appeler", tint = if (can) Px.Accent else Px.Muted)
                }
            }
            if (isAll) TextButton(onClick = { sosDialog = true }) { Text("SOS", color = Px.Danger, fontWeight = FontWeight.Bold) }
            if (group != null) {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu", tint = Color.White) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Membres : " + group!!.members.joinToString { m -> names[m] ?: if (m == svc?.core?.myId()) "moi" else "?" }) }, onClick = { menu = false })
                    DropdownMenuItem(text = { Text("Quitter le groupe", color = Px.Danger) }, onClick = { menu = false; svc?.messenger?.leaveGroup(chat); onBack?.invoke() })
                }
            }
        }

        // ---- messages ----
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 8.dp)) {
            itemsIndexed(messages, key = { _, m -> m.mid }) { i, m ->
                val day = dayLabel(m.time)
                if (i == 0 || dayLabel(messages[i - 1].time) != day) DaySeparator(day)
                Bubble(m, showName = isAll || group != null, contactName = { id -> names[id] ?: peers.firstOrNull { it.id == id }?.name })
            }
            if (messages.isEmpty()) item {
                Text(
                    if (isAll) "Ici, tout le monde à portée lit tes messages, même sans être dans tes contacts. Pratique pour un groupe, un événement ou un SOS."
                    else if (contact?.status == 0) "Demande de contact envoyée. Tu pourras écrire dès que ${contact?.name} aura accepté."
                    else "Messages chiffrés de bout en bout. Ils passent par les téléphones voisins jusqu'à ${title}.",
                    color = Px.Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
                )
            }
        }

        // ---- barre de saisie ----
        if (contact?.status == 0) {
            Text("En attente de l'acceptation de ${contact?.name}…", color = Px.Warn, modifier = Modifier.fillMaxWidth().background(Px.Bar).padding(16.dp), fontSize = 14.sp)
        } else if (contact?.status == 2) {
            Row(Modifier.fillMaxWidth().background(Px.Bar).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${contact?.name} veut t'ajouter", color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = { svc?.messenger?.refuseContact(chat); onBack?.invoke() }) { Text("Refuser", color = Px.Muted) }
                TextButton(onClick = { svc?.messenger?.acceptContact(chat) }) { Text("Accepter") }
            }
        } else Row(Modifier.fillMaxWidth().background(Px.Bar).padding(8.dp, 8.dp, 8.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            var attach by remember { mutableStateOf(false) }
            Box {
                RoundButton(Icons.Filled.Image, "Joindre", Px.Card, Px.Accent) { attach = true }
                DropdownMenu(attach, { attach = false }) {
                    DropdownMenuItem(text = { Text("Prendre une photo") }, leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) }, onClick = { attach = false; askCamera.launch(android.Manifest.permission.CAMERA) })
                    DropdownMenuItem(text = { Text("Photo de la galerie") }, leadingIcon = { Icon(Icons.Filled.Image, null) }, onClick = { attach = false; pickImage.launch("image/*") })
                    DropdownMenuItem(text = { Text("Filmer une vidéo (15 s)") }, leadingIcon = { Icon(Icons.Filled.Videocam, null) }, onClick = { attach = false; videoGuard { askCameraVideo.launch(android.Manifest.permission.CAMERA) } })
                    DropdownMenuItem(text = { Text("Vidéo de la galerie (3,5 Mo max)") }, leadingIcon = { Icon(Icons.Filled.Videocam, null) }, onClick = { attach = false; videoGuard { pickVideo.launch("video/*") } })
                    DropdownMenuItem(text = { Text("Ma position") }, leadingIcon = { Icon(Icons.Filled.Place, null) }, onClick = {
                        attach = false
                        scope.launch { val l = Locations.current(ctx); if (l != null) svc?.messenger?.sendLocation(chat, l.latitude, l.longitude) else svc?.messenger?.event("Position introuvable (GPS éteint ?)") }
                    })
                }
            }
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                text, { text = it }, modifier = Modifier.weight(1f), placeholder = { Text(if (recording) "Enregistrement… relâche pour envoyer" else "Message…", color = Px.Muted) },
                singleLine = false, maxLines = 4, shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Px.Card, unfocusedContainerColor = Px.Card, focusedBorderColor = Px.Accent, unfocusedBorderColor = Px.Card2, cursorColor = Px.Accent, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            )
            Spacer(Modifier.width(6.dp))
            if (text.isBlank()) {
                // Talkie-walkie : maintenir pour parler
                Box(
                    Modifier.size(if (recording) 56.dp else 46.dp).background(if (recording) Px.Danger else Px.Card, CircleShape).pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            if (recorder.start()) {
                                recording = true
                                tryAwaitRelease()
                                recording = false
                                val r = recorder.stop()
                                if (r != null) svc?.messenger?.sendVoice(chat, r.first, r.second) else svc?.messenger?.event("Trop court : maintiens le bouton en parlant")
                            } else svc?.messenger?.event("Micro indisponible")
                        })
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Mic, "Talkie-walkie : maintenir pour parler", tint = if (recording) Color.White else Px.Accent) }
            } else RoundButton(Icons.AutoMirrored.Filled.Send, "Envoyer", Px.Accent, Px.Bg) { svc?.messenger?.sendText(chat, text); text = "" }
        }
    }

    if (sosDialog) {
        var sosText by remember { mutableStateOf("Besoin d'aide !") }
        AlertDialog(
            onDismissRequest = { sosDialog = false }, containerColor = Px.Card,
            title = { Text("Envoyer un SOS", color = Px.Danger, fontWeight = FontWeight.Bold) },
            text = { Column { Text("Tous les téléphones à portée recevront une alerte sonore avec ta position.", color = Px.Muted); Spacer(Modifier.height(10.dp)); OutlinedTextField(sosText, { sosText = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) } },
            confirmButton = { TextButton(onClick = { sosDialog = false; scope.launch { val l = Locations.current(ctx); svc?.messenger?.sendSos(sosText, l?.latitude, l?.longitude) } }) { Text("ENVOYER LE SOS", color = Px.Danger, fontWeight = FontWeight.Bold) } },
            dismissButton = { TextButton(onClick = { sosDialog = false }) { Text("Annuler", color = Px.Muted) } },
        )
    }
}

fun openSafely(ctx: android.content.Context, intent: Intent) {
    try { ctx.startActivity(intent) } catch (e: Exception) { MeshService.instance?.messenger?.event("Aucune application ne peut ouvrir ceci") }
}

@Composable
fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, bg: Color, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).background(bg, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Icon(icon, desc, tint = tint) }
}

@Composable
private fun DaySeparator(day: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(day, color = Px.Muted, fontSize = 11.sp, modifier = Modifier.background(Px.Card, RoundedCornerShape(999.dp)).padding(12.dp, 4.dp))
    }
}

@Composable
private fun Bubble(m: Message, showName: Boolean, contactName: (String) -> String?) {
    val ctx = LocalContext.current
    if (m.kind == Message.SYS) { Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) { Text(m.body, color = Px.Muted, fontSize = 12.sp) }; return }
    val isSos = m.kind == Message.SOS
    val bg = when { isSos -> Px.Danger.copy(alpha = 0.25f); m.mine -> Px.AccentDark; else -> Px.Card2 }
    val shape = if (m.mine) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(Modifier.widthIn(max = 300.dp).background(bg, shape).padding(12.dp, 8.dp)) {
            if (showName && !m.mine) Text(contactName(m.from) ?: m.senderName.ifEmpty { "Inconnu" }, color = Px.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            when (m.kind) {
                Message.PHOTO -> m.file?.let { AsyncImage(File(it), "Photo", modifier = Modifier.fillMaxWidth().height(200.dp).background(Px.Card, RoundedCornerShape(12.dp)).clickable {
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(it))
                    openSafely(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }) }
                Message.VOICE -> VoiceRow(m)
                Message.VIDEO -> m.file?.let { path ->
                    val thumb by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { Videos.thumbnail(path) } }
                    Box(Modifier.fillMaxWidth().height(200.dp).background(Px.Card, RoundedCornerShape(12.dp)).clickable {
                        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(path))
                        openSafely(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }, contentAlignment = Alignment.Center) {
                        thumb?.let { androidx.compose.foundation.Image(it.asImageBitmap(), "Vidéo", Modifier.fillMaxWidth().height(200.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
                        Box(Modifier.size(54.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Filled.PlayArrow, "Lire", tint = Color.White, modifier = Modifier.size(32.dp)) }
                        Text("🎬 ${m.body} s", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(6.dp, 2.dp))
                    }
                }
                Message.LOC -> {
                    val parts = m.body.split(",")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                        if (parts.size == 2) openSafely(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:${parts[0]},${parts[1]}?q=${parts[0]},${parts[1]}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Icon(Icons.Filled.Place, null, tint = Px.Accent); Spacer(Modifier.width(6.dp)); Text("Position partagée\nOuvrir dans Maps", color = Color.White, fontSize = 14.sp) }
                }
                Message.SOS -> {
                    val o = Db.jsonObj(m.body)
                    Text("🆘 SOS : " + o.optString("t", m.body), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (o.has("lat")) Text("Voir la position", color = Px.Accent, fontSize = 14.sp, modifier = Modifier.clickable { openSafely(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:${o.optDouble("lat")},${o.optDouble("lon")}?q=${o.optDouble("lat")},${o.optDouble("lon")}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
                }
                else -> Text(m.body, color = Color.White, fontSize = 15.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(timeStr(m.time), color = if (m.mine) Px.AccentSoft else Px.Muted, fontSize = 11.sp)
                if (m.mine) {
                    Spacer(Modifier.width(6.dp))
                    val (label, color) = when (m.status) {
                        Message.DELIVERED -> "✓✓ " + (m.route.ifEmpty { "Reçu" }) to Px.AccentSoft
                        Message.RELAYED -> "✓ " + (m.route.ifEmpty { "Relayé" }) to Px.Warn
                        Message.FAILED -> "Échec" to Px.Danger
                        else -> "⏳ En attente" to Px.Warn
                    }
                    Text(label, color = color, fontSize = 11.sp)
                } else if (m.route.isNotEmpty() && m.route != "direct") { Spacer(Modifier.width(6.dp)); Text("· ${m.route}", color = Px.Muted, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun VoiceRow(m: Message) {
    var playing by remember { mutableStateOf(false) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player.value?.release() } }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
        val f = m.file ?: return@clickable
        if (playing) { player.value?.stop(); player.value?.release(); player.value = null; playing = false; return@clickable }
        try {
            val mp = MediaPlayer(); mp.setDataSource(f); mp.setOnCompletionListener { playing = false; it.release(); player.value = null }; mp.prepare(); mp.start()
            player.value = mp; playing = true
        } catch (e: Exception) { playing = false }
    }) {
        Box(Modifier.size(36.dp).background(Color.White.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) { Icon(if (playing) Icons.Filled.Mic else Icons.Filled.PlayArrow, "Écouter", tint = Color.White) }
        Spacer(Modifier.width(10.dp))
        Text("Vocal · ${m.body} s", color = Color.White, fontSize = 14.sp)
    }
}
