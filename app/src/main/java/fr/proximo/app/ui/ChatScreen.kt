package fr.proximo.app.ui

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Vibration
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil.compose.AsyncImage
import fr.proximo.app.MainActivity
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
    var text by rememberSaveable(chat) { mutableStateOf("") } // le brouillon survit à une rotation ou à un appel
    var recording by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sosDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var leaveDialog by remember { mutableStateOf(false) }
    var blockDialog by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(ctx) }
    // L'écran change pendant un enregistrement (appel entrant…) : le micro est toujours libéré.
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    val title = when { isAll -> "Tout le monde"; group != null -> group!!.name; else -> contact?.name ?: "Discussion" }
    val peer = peers.firstOrNull { it.id == chat }
    val subtitle = when {
        isAll -> if (peers.isEmpty()) "Personne à portée pour l'instant" else "${peers.size} téléphone(s) à portée · messages publics"
        group != null -> "${group!!.members.size} membres · ${group!!.members.count { m -> peers.any { it.id == m } }} à portée"
        peer != null -> if (peer.linked) "Joignable" + (peer.meters?.let { " · $it m" } ?: " · Wi-Fi direct") else "À portée · liaison en attente (téléphone en veille ?)"
        else -> "Hors de portée · les messages partiront dès que possible"
    }

    // Marquer comme lu et dire à la messagerie que cette discussion est ouverte
    // (Seulement quand l'écran est vraiment affiché : téléphone en poche avec la discussion restée ouverte, les messages
    //  doivent sonner, notifier et rester « non lus ».)
    val lastMid = messages.lastOrNull()?.mid
    LaunchedEffect(chat, lastMid) { if (MainActivity.isForeground) withContext(Dispatchers.IO) { db.markRead(chat) } }
    LifecycleResumeEffect(chat, svc) {
        val m = svc?.messenger
        m?.openChat = chat
        scope.launch(Dispatchers.IO) { db.markRead(chat) }
        onPauseOrDispose { if (m?.openChat == chat) m.openChat = null }
    }
    // Aller en bas à chaque nouveau dernier message (y compris au-delà de 500 messages) ; sans animation à l'ouverture.
    var firstScroll by remember(chat) { mutableStateOf(true) }
    LaunchedEffect(lastMid) {
        if (lastMid != null) { if (firstScroll) listState.scrollToItem(messages.size - 1) else listState.animateScrollToItem(messages.size - 1); firstScroll = false }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { val b = withContext(Dispatchers.IO) { Photos.compress(ctx, uri) }; if (b != null) svc?.messenger?.sendPhoto(chat, b) }
    }
    val photoFile = remember { File(ctx.cacheDir, "photo.jpg") }
    val photoUri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", photoFile) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) scope.launch { val b = withContext(Dispatchers.IO) { Photos.compress(ctx, photoUri) }; if (b != null) svc?.messenger?.sendPhoto(chat, b) }
    }
    val cameraDenied = "Appareil photo refusé : autorise-le dans les réglages Android de Proximo"
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) takePhoto.launch(photoUri) else svc?.messenger?.event(cameraDenied) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> svc?.messenger?.event(if (ok) "Position autorisée : appuie de nouveau sur « Ma position »" else "Position refusée : autorise-la dans les réglages Android de Proximo") }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) svc?.messenger?.event("Micro refusé : autorise-le dans les réglages Android de Proximo") }
    // Vidéos : filmées directement en basse qualité (15 s), ou choisies dans la galerie si elles sont légères.
    val videoFile = remember { File(ctx.cacheDir, "video.mp4") }
    val videoUri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", videoFile) }
    fun sendVideoFrom(uri: Uri) = scope.launch {
        val (res, err) = withContext(Dispatchers.IO) { Videos.load(ctx, uri) }
        if (res != null) svc?.messenger?.sendVideo(chat, res.first, res.second) else svc?.messenger?.event(err ?: "Vidéo illisible")
    }
    val videoContract = remember {
        object : ActivityResultContracts.CaptureVideo() {
            override fun createIntent(context: android.content.Context, input: Uri): Intent = super.createIntent(context, input)
                .putExtra(android.provider.MediaStore.EXTRA_DURATION_LIMIT, Videos.MAX_SECONDS)
                .putExtra(android.provider.MediaStore.EXTRA_VIDEO_QUALITY, 0)
                .putExtra(android.provider.MediaStore.EXTRA_SIZE_LIMIT, Videos.MAX_BYTES)
        }
    }
    val captureVideo = rememberLauncherForActivityResult(videoContract) { ok -> if (ok) sendVideoFrom(videoUri) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) sendVideoFrom(uri) }
    val askCameraVideo = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) captureVideo.launch(videoUri) else svc?.messenger?.event(cameraDenied) }
    fun videoGuard(action: () -> Unit) {
        if (svc?.messenger?.canSendVideo(chat) == true) action() else svc?.messenger?.event("Vidéo impossible pour l'instant : il faut une liaison Wi-Fi direct (le Bluetooth est trop lent). Envoie une photo ou un vocal.")
    }

    // Wizz (envoyé ou reçu) : la discussion tremble.
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(svc, chat) {
        svc?.messenger?.wizz?.collect { w ->
            if (w != null && w.chat == chat && System.currentTimeMillis() - w.at < 3000) {
                repeat(12) { i -> shake.animateTo(if (i % 2 == 0) 12f else -12f, androidx.compose.animation.core.tween(45)) }
                shake.animateTo(0f, androidx.compose.animation.core.tween(45))
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Px.Bg).imePadding().graphicsLayer { translationX = shake.value.dp.toPx() }) {
        // ---- en-tête ----
        Row(Modifier.fillMaxWidth().background(Px.Bar).padding(8.dp, 10.dp, 8.dp, 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = Color.White) } else Spacer(Modifier.width(8.dp))
            if (isAll) Box(Modifier.size(42.dp).background(Px.AccentDark, CircleShape), contentAlignment = Alignment.Center) { Text("👥", fontSize = 20.sp) }
            else Avatar(title, 42.dp, online = if (group != null) null else peer != null, square = group != null, id = if (group != null) null else chat)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = if (peer != null || isAll || group != null) Px.AccentSoft else Px.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!isAll) Icon(Icons.Filled.Lock, "Chiffré", tint = Px.Accent, modifier = Modifier.size(18.dp))
            if (!isAll && group == null && contact?.blocked != true) {
                // Recalculé toutes les 3 s hors du fil d'affichage (il interroge la base), et vérifié pour de bon au toucher.
                val can by androidx.compose.runtime.produceState(false, svc, chat) {
                    while (true) { value = withContext(Dispatchers.IO) { svc?.calls?.canCall(chat) == true }; kotlinx.coroutines.delay(3000) }
                }
                IconButton(onClick = { if (svc?.calls?.canCall(chat) == true) svc?.calls?.call(chat) else svc?.messenger?.event("Trop loin pour un appel (pas de Wi-Fi direct, même via un voisin) : utilise le talkie-walkie") }) {
                    Icon(Icons.Filled.Call, "Appeler", tint = if (can) Px.Accent else Px.Muted)
                }
            }
            if (isAll) TextButton(onClick = { sosDialog = true }) { Text("SOS", color = Px.Danger, fontWeight = FontWeight.Bold) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu", tint = Color.White) }
                DropdownMenu(menu, { menu = false }) {
                    if (group != null) DropdownMenuItem(text = { Text("Membres : " + group!!.members.joinToString { m -> names[m] ?: if (m == svc?.core?.myId()) "moi" else "?" }) }, onClick = { menu = false })
                    DropdownMenuItem(text = { Text("Effacer la discussion") }, onClick = { menu = false; clearDialog = true })
                    if (!isAll && group == null && contact != null && contact?.blocked != true) DropdownMenuItem(text = { Text("Bloquer ce contact", color = Px.Danger) }, onClick = { menu = false; blockDialog = true })
                    if (group != null) DropdownMenuItem(text = { Text("Quitter le groupe", color = Px.Danger) }, onClick = { menu = false; leaveDialog = true })
                }
            }
        }

        // ---- messages ----
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 8.dp)) {
            itemsIndexed(messages, key = { _, m -> m.mid }) { i, m ->
                val day = dayLabel(m.shownAt)
                if (i == 0 || dayLabel(messages[i - 1].shownAt) != day) DaySeparator(day)
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
        if (contact?.blocked == true) {
            Row(Modifier.fillMaxWidth().background(Px.Bar).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${contact?.name} est bloqué : tu ne reçois plus rien de lui.", color = Px.Warn, modifier = Modifier.weight(1f), fontSize = 14.sp)
                TextButton(onClick = { val m = svc?.messenger; if (m != null) Thread { m.unblockContact(chat) }.start() }) { Text("Débloquer") }
            }
        } else if (contact?.status == 0) {
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
                        if (!Locations.allowed(ctx)) askLocation.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                        else {
                            svc?.messenger?.event("Recherche de la position…")
                            scope.launch { val l = Locations.current(ctx); if (l != null) svc?.messenger?.sendLocation(chat, l.latitude, l.longitude) else svc?.messenger?.event("Position introuvable (GPS éteint ?)") }
                        }
                    })
                }
            }
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                text, { text = it }, modifier = Modifier.weight(1f), placeholder = { Text(if (recording) "Parle… relâche pour envoyer" else "Message…", color = Px.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = false, maxLines = 4, shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Px.Card, unfocusedContainerColor = Px.Card, focusedBorderColor = Px.Accent, unfocusedBorderColor = Px.Card2, cursorColor = Px.Accent, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
            )
            Spacer(Modifier.width(6.dp))
            if (text.isBlank() && !isAll && group == null) {
                // Wizz : fait sonner et vibrer le téléphone du contact, même en silencieux
                RoundButton(Icons.Filled.Vibration, "Wizz : faire sonner et vibrer son téléphone", Px.Card, Px.Warn) { svc?.messenger?.sendWizz(chat) }
                Spacer(Modifier.width(6.dp))
            }
            if (text.isBlank()) {
                // Talkie-walkie : maintenir pour parler
                Box(
                    Modifier.size(if (recording) 56.dp else 46.dp).background(if (recording) Px.Danger else Px.Card, CircleShape).pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            // Micro pas encore autorisé : on le demande (au lieu d'un « Micro indisponible » sans suite).
                            if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { askMic.launch(android.Manifest.permission.RECORD_AUDIO); return@detectTapGestures }
                            if (recorder.start()) {
                                recording = true
                                try { tryAwaitRelease() } finally { recording = false }
                                val r = recorder.stop()
                                if (r != null) svc?.messenger?.sendVoice(chat, r.first, r.second) else svc?.messenger?.event("Trop court : maintiens le bouton en parlant")
                            } else svc?.messenger?.event("Micro indisponible")
                        })
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Mic, "Talkie-walkie : maintenir pour parler", tint = if (recording) Color.White else Px.Accent) }
            } else RoundButton(Icons.AutoMirrored.Filled.Send, "Envoyer", Px.Accent, Px.Bg) {
                // Réseau arrêté : le texte reste dans le champ au lieu de disparaître sans partir.
                val m = svc?.messenger
                if (m != null) { m.sendText(chat, text); text = "" }
            }
        }
    }

    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false }, containerColor = Px.Card,
            title = { Text("Effacer la discussion ?", color = Color.White) },
            text = { Text(if (isAll) "Ton historique de « Tout le monde » sera vidé sur ce téléphone. Le canal reste, les prochains messages arriveront normalement." else "Les messages seront effacés de ce téléphone uniquement ; l'autre garde sa copie.", color = Px.Muted) },
            confirmButton = { TextButton(onClick = { clearDialog = false; scope.launch(Dispatchers.IO) { db.clearChat(chat) } }) { Text("Effacer", color = Px.Danger) } },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Annuler", color = Px.Muted) } },
        )
    }

    if (blockDialog) {
        AlertDialog(
            onDismissRequest = { blockDialog = false }, containerColor = Px.Card,
            title = { Text("Bloquer ${contact?.name ?: "ce contact"} ?", color = Color.White) },
            text = { Text("Tu ne recevras plus rien de lui : ni messages, ni appels, ni wizz, ni demandes de contact. Sa discussion sera cachée (les messages sont gardés). Tu pourras le débloquer dans Contacts.", color = Px.Muted) },
            confirmButton = { TextButton(onClick = { blockDialog = false; val m = svc?.messenger; if (m != null) { Thread { m.blockContact(chat) }.start(); onBack?.invoke() } }) { Text("Bloquer", color = Px.Danger) } },
            dismissButton = { TextButton(onClick = { blockDialog = false }) { Text("Annuler", color = Px.Muted) } },
        )
    }

    if (leaveDialog) {
        AlertDialog(
            onDismissRequest = { leaveDialog = false }, containerColor = Px.Card,
            title = { Text("Quitter le groupe ?", color = Color.White) },
            text = { Text("Ses messages seront effacés de ce téléphone et tu ne pourras plus y revenir sans une nouvelle invitation.", color = Px.Muted) },
            confirmButton = { TextButton(onClick = { leaveDialog = false; val m = svc?.messenger; if (m != null) { Thread { m.leaveGroup(chat) }.start(); onBack?.invoke() } }) { Text("Quitter", color = Px.Danger) } },
            dismissButton = { TextButton(onClick = { leaveDialog = false }) { Text("Annuler", color = Px.Muted) } },
        )
    }

    if (sosDialog) {
        var sosText by remember { mutableStateOf("Besoin d'aide !") }
        AlertDialog(
            onDismissRequest = { sosDialog = false }, containerColor = Px.Card,
            title = { Text("Envoyer un SOS", color = Px.Danger, fontWeight = FontWeight.Bold) },
            text = { Column { Text(if (Locations.allowed(ctx)) "Tous les téléphones à portée recevront une alerte sonore avec ta position." else "Tous les téléphones à portée recevront une alerte sonore. Ta position ne sera pas jointe : elle n'est pas autorisée dans les réglages Android de Proximo.", color = Px.Muted); Spacer(Modifier.height(10.dp)); OutlinedTextField(sosText, { sosText = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) } },
            confirmButton = { TextButton(onClick = { sosDialog = false; scope.launch { val l = Locations.current(ctx, maxWaitMs = 3000); svc?.messenger?.sendSos(sosText, l?.latitude, l?.longitude) } }) { Text("ENVOYER LE SOS", color = Px.Danger, fontWeight = FontWeight.Bold) } },
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: Message, showName: Boolean, contactName: (String) -> String?) {
    val ctx = LocalContext.current
    if (m.kind == Message.SYS) { Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) { Text(m.body, color = Px.Muted, fontSize = 12.sp) }; return }
    val isSos = m.kind == Message.SOS
    val bg = when { isSos -> Px.Danger.copy(alpha = 0.25f); m.mine -> Px.AccentDark; else -> Px.Card2 }
    val shape = if (m.mine) RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    if (details) MessageDetails(m, contactName) { details = false }
    Box(Modifier.fillMaxWidth().padding(vertical = 3.dp), contentAlignment = if (m.mine) Alignment.CenterEnd else Alignment.CenterStart) {
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Détails") }, onClick = { menu = false; details = true })
            if (m.kind == Message.TEXT) DropdownMenuItem(text = { Text("Copier le texte") }, onClick = {
                menu = false
                (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Proximo", m.body))
                MeshService.instance?.messenger?.event("Texte copié")
            })
            if (m.mine && m.status == Message.FAILED) DropdownMenuItem(text = { Text("Renvoyer") }, onClick = { menu = false; MeshService.instance?.messenger?.resend(m.mid) })
            DropdownMenuItem(text = { Text("Supprimer", color = Px.Danger) }, onClick = { menu = false; Thread { Db.get(ctx).deleteMessage(m.mid) }.start() })
        }
        Column(Modifier.widthIn(max = 300.dp).background(bg, shape).combinedClickable(onClick = {}, onLongClick = { menu = true }).padding(12.dp, 8.dp)) {
            if (showName && !m.mine) Text(contactName(m.from) ?: m.senderName.ifEmpty { "Inconnu" }, color = Px.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            when (m.kind) {
                Message.PHOTO -> m.file?.let { AsyncImage(File(it), "Photo", modifier = Modifier.fillMaxWidth().height(200.dp).background(Px.Card, RoundedCornerShape(12.dp)).combinedClickable(onLongClick = { menu = true }) {
                    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(it))
                    openSafely(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }) }
                Message.VOICE -> VoiceRow(m)
                Message.VIDEO -> m.file?.let { path ->
                    val thumb by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, path) { value = withContext(Dispatchers.IO) { Videos.thumbnail(path) } }
                    Box(Modifier.fillMaxWidth().height(200.dp).background(Px.Card, RoundedCornerShape(12.dp)).combinedClickable(onLongClick = { menu = true }) {
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
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.combinedClickable(onLongClick = { menu = true }) {
                        if (parts.size == 2) openSafely(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:${parts[0]},${parts[1]}?q=${parts[0]},${parts[1]}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Icon(Icons.Filled.Place, null, tint = Px.Accent); Spacer(Modifier.width(6.dp)); Text("Position partagée\nOuvrir dans Maps", color = Color.White, fontSize = 14.sp) }
                    val la = parts.getOrNull(0)?.toDoubleOrNull(); val lo = parts.getOrNull(1)?.toDoubleOrNull()
                    if (!m.mine && parts.size == 2 && la != null && lo != null && okPos(la, lo)) PositionInfo(la, lo)
                }
                Message.SOS -> {
                    val o = Db.jsonObj(m.body)
                    Text("🆘 SOS : " + o.optString("t", m.body), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (o.has("lat")) Text("Voir la position", color = Px.Accent, fontSize = 14.sp, modifier = Modifier.clickable { openSafely(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:${o.optDouble("lat")},${o.optDouble("lon")}?q=${o.optDouble("lat")},${o.optDouble("lon")}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
                    if (!m.mine && o.has("lat") && o.has("lon") && okPos(o.optDouble("lat"), o.optDouble("lon"))) PositionInfo(o.optDouble("lat"), o.optDouble("lon"))
                }
                Message.WIZZ -> {
                    Text(if (m.mine) "👋 Wizz envoyé" else "👋 Wizz !", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (!m.mine && m.received - m.time > fr.proximo.app.msg.Messenger.WIZZ_FRESH_MS) Text("Arrivé en retard : n'a pas sonné", color = Px.Warn, fontSize = 11.sp)
                }
                else -> Text(m.body, color = Color.White, fontSize = 15.sp)
            }
            if (m.late) Text("Reçu ${stamp(m.received).lowercase()} · écrit ${stamp(m.time).lowercase()}", color = Px.Warn, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.fillMaxWidth().combinedClickable(onLongClick = { menu = true }) { details = true }, horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text(stamp(m.time), color = if (m.mine) Px.AccentSoft else Px.Muted, fontSize = 11.sp)
                if (m.mine) {
                    Spacer(Modifier.width(6.dp))
                    val (label, color) = when (m.status) {
                        Message.DELIVERED -> "✓✓ " + (m.route.ifEmpty { "Reçu" }) to Px.AccentSoft
                        Message.RELAYED -> "✓ " + (m.route.ifEmpty { "Relayé" }) to Px.Warn
                        Message.FAILED -> "Échec · appui long pour renvoyer" to Px.Danger
                        else -> "⏳ En attente" to Px.Warn
                    }
                    Text(label, color = color, fontSize = 11.sp)
                } else if (m.route.isNotEmpty() && m.route != "direct") { Spacer(Modifier.width(6.dp)); Text("· ${m.route}", color = Px.Muted, fontSize = 11.sp) }
            }
        }
    }
}

/** Coordonnées plausibles (faux pour NaN, l'infini ou hors de la Terre). */
private fun okPos(la: Double, lo: Double) = la in -90.0..90.0 && lo in -180.0..180.0

/**
 * Sous une position reçue : les coordonnées (utiles sans carte ni Internet), puis la distance et la direction
 * depuis la dernière position connue de ce téléphone.
 */
@Composable
private fun PositionInfo(lat: Double, lon: Double) {
    val ctx = LocalContext.current
    val mine by androidx.compose.runtime.produceState<android.location.Location?>(null, lat, lon) { value = withContext(Dispatchers.IO) { Locations.lastKnown(ctx) } }
    Text("%.5f, %.5f".format(java.util.Locale.US, lat, lon), color = Px.Muted, fontSize = 12.sp)
    val me = mine
    if (me == null) Text("Distance inconnue : la position de ce téléphone n'est pas disponible", color = Px.Muted, fontSize = 12.sp)
    else {
        val res = FloatArray(2)
        android.location.Location.distanceBetween(me.latitude, me.longitude, lat, lon, res)
        val ageMin = (System.currentTimeMillis() - me.time) / 60_000
        val age = when { ageMin < 5 -> ""; ageMin < 90 -> " (d'après ta position d'il y a $ageMin min)"; ageMin < 48 * 60 -> " (d'après ta position d'il y a ${ageMin / 60} h)"; else -> " (d'après ta position d'il y a ${ageMin / 1440} jours)" }
        Text("À ${Locations.distanceText(res[0])} · direction ${Locations.compassText(res[1])}$age", color = if (ageMin < 90) Px.AccentSoft else Px.Warn, fontSize = 12.sp)
    }
}

@Composable
private fun MessageDetails(m: Message, contactName: (String) -> String?, onClose: () -> Unit) {
    val lines = buildList {
        add("Écrit : " + fullStamp(m.time))
        if (!m.mine && m.received > 0) add("Reçu : " + fullStamp(m.received) + if (m.late) "  (en retard)" else "")
        if (!m.mine) add("De : " + (contactName(m.from) ?: m.senderName.ifEmpty { "Inconnu" }))
        add("Chemin : " + m.route.ifEmpty { if (m.mine) when (m.status) { Message.DELIVERED -> "reçu"; Message.RELAYED -> "relayé"; Message.FAILED -> "échec"; else -> "en attente" } else "direct" })
        add("Type : " + when (m.kind) { Message.PHOTO -> "photo"; Message.VOICE -> "vocal (${m.body} s)"; Message.VIDEO -> "vidéo (${m.body} s)"; Message.LOC -> "position"; Message.SOS -> "SOS"; Message.WIZZ -> "wizz"; else -> "texte" })
        m.file?.let { f -> val size = File(f).length(); if (size > 0) add("Taille : " + if (size > 1_000_000) "${size / 1_000_000} Mo" else "${size / 1000} Ko") }
    }
    AlertDialog(
        onDismissRequest = onClose, containerColor = Px.Card,
        title = { Text("Détails du message", color = Color.White) },
        text = { Column { lines.forEach { Text(it, color = Px.Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp)) } } },
        confirmButton = { TextButton(onClick = onClose) { Text("Fermer") } },
    )
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
        Box(Modifier.size(36.dp).background(Color.White.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) { Icon(if (playing) Icons.Filled.Stop else Icons.Filled.PlayArrow, "Écouter", tint = Color.White) }
        Spacer(Modifier.width(10.dp))
        Text("Vocal · ${m.body} s", color = Color.White, fontSize = 14.sp)
    }
}
