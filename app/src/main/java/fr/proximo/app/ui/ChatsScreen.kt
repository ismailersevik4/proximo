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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.proximo.app.core.ChatSummary
import fr.proximo.app.core.Db
import fr.proximo.app.core.Message

@Composable
fun ChatsScreen(onOpen: (String) -> Unit, onNewGroup: () -> Unit, onAdd: () -> Unit) {
    val chats by dbState(emptyList<ChatSummary>()) { it.chatSummaries() }
    val peers by peersState()
    val pendingIn by dbState(0) { it.contacts().count { c -> c.status == 2 } }

    Column(Modifier.fillMaxSize().background(Px.Bg)) {
        Row(Modifier.fillMaxWidth().padding(20.dp, 16.dp, 12.dp, 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Discussions", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = onNewGroup) { Icon(Icons.Filled.GroupAdd, "Nouveau groupe", tint = Px.Accent) }
                Box(Modifier.size(44.dp).background(Px.Accent, CircleShape).clickable(onClick = onAdd), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PersonAdd, "Ajouter un contact", tint = Px.Bg)
                    if (pendingIn > 0) Box(Modifier.align(Alignment.TopEnd).size(12.dp).background(Px.Danger, CircleShape))
                }
            }
        }
        if (pendingIn > 0) Text("$pendingIn demande(s) de contact en attente — appuie sur le bouton +", color = Px.Warn, fontSize = 13.sp, modifier = Modifier.padding(20.dp, 4.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(chats, key = { it.chat }) { c ->
                val online = if (c.isGroup) null else peers.any { it.id == c.chat }
                ChatRow(c, online, peers, onClick = { onOpen(c.chat) })
            }
            if (chats.size <= 1) item {
                Text("Ajoute des contacts depuis le radar (téléphones à portée), avec un QR code ou un code PRX.", color = Px.Muted, fontSize = 13.sp, modifier = Modifier.padding(20.dp, 24.dp))
            }
        }
    }
}

@Composable
private fun ChatRow(c: ChatSummary, online: Boolean?, peers: List<fr.proximo.app.mesh.Peer>, onClick: () -> Unit) {
    val isAll = c.chat == Db.ALL
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(if (isAll) Px.AccentDark else Color.Transparent, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(10.dp, 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isAll) Box(Modifier.size(50.dp).background(Color.White.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) { Text("👥", fontSize = 22.sp) }
        else Avatar(c.name, 50.dp, online = online, square = c.isGroup)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(c.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                c.last?.let { Text(timeStr(it.time), color = if (c.unread > 0) Px.Accent else if (isAll) Px.AccentSoft else Px.Muted, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val m = c.last
                val preview = when {
                    m == null -> if (isAll) "Parle à tous les téléphones autour de toi" else "Aucun message"
                    m.status == Message.PENDING && m.mine -> if (peers.isEmpty()) "⏳ En attente · personne à portée" else "⏳ En attente d'envoi"
                    else -> (if (m.mine) "Toi : " else "") + when (m.kind) {
                        Message.PHOTO -> "📷 Photo"; Message.VOICE -> "🎤 Vocal · ${m.body}s"; Message.VIDEO -> "🎬 Vidéo · ${m.body}s"; Message.LOC -> "📍 Position partagée"; Message.SOS -> "🆘 SOS"; else -> m.body
                    }
                }
                Text(preview, color = if (m?.status == Message.PENDING && m.mine) Px.Warn else if (isAll) Px.AccentSoft else if (c.unread > 0) Color.White else Px.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (c.unread > 0) Box(Modifier.background(if (isAll) Color.White else Px.Accent, RoundedCornerShape(999.dp)).padding(7.dp, 1.dp)) { Text("${c.unread}", color = if (isAll) Px.AccentDark else Px.Bg, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
