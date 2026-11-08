package fr.proximo.app.ui

import android.graphics.Paint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.proximo.app.core.Contact
import fr.proximo.app.core.Settings
import fr.proximo.app.mesh.MeshService
import fr.proximo.app.mesh.Modes
import fr.proximo.app.mesh.Peer
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

@Composable
fun RadarScreen(onOpenChat: (String) -> Unit) {
    val ctx = LocalContext.current
    val peers by peersState()
    val svc by serviceState()
    val statusFlow = svc?.core?.status
    val status = if (statusFlow != null) statusFlow.collectAsState().value else emptyMap()
    val contacts by dbState(emptyList<Contact>()) { it.contacts() }
    val settings = remember { Settings.get(ctx) }
    val settingsTick by settings.changes.collectAsState()
    var selected by remember { mutableStateOf<Peer?>(null) }

    Column(Modifier.fillMaxSize().background(Px.Bg)) {
        Row(Modifier.fillMaxWidth().padding(20.dp, 16.dp, 20.dp, 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(34.dp); Spacer(Modifier.width(10.dp))
                Text("Proximo", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Row(Modifier.background(Px.Card, RoundedCornerShape(999.dp)).padding(12.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(if (peers.isEmpty()) Px.Muted else Px.Accent, CircleShape)); Spacer(Modifier.width(8.dp))
                Text(when (peers.size) { 0 -> "Personne à portée"; 1 -> "1 téléphone à portée"; else -> "${peers.size} téléphones à portée" }, color = Px.AccentSoft, fontSize = 13.sp)
            }
        }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val ble = status[Modes.BLE]; val wifi = status[Modes.WIFI]
            Chip(if (ble?.enabled == true) "Bluetooth ✓" else "Bluetooth ✗", ble?.enabled == true)
            Chip(if (wifi?.enabled == true) "Wi-Fi direct ✓" else "Wi-Fi direct ✗", wifi?.enabled == true)
            key(settingsTick) { Chip("Mode : " + when (settings.mode) { Modes.PORTEE -> "portée"; Modes.BATTERIE -> "batterie"; else -> "normal" }, null) }
        }
        val problem = status.values.firstOrNull { !it.enabled }?.detail
        if (problem != null) Text(problem, color = Px.Warn, fontSize = 12.sp, modifier = Modifier.padding(20.dp, 8.dp, 20.dp, 0.dp))

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Radar(peers, contacts, onTap = { selected = it })
        }
        Text(
            if (peers.isEmpty()) "Les téléphones qui ont Proximo apparaissent ici dès qu'ils sont à portée (Bluetooth ou Wi-Fi direct)."
            else "Appuie sur un téléphone pour l'ajouter, lui écrire ou l'appeler. Les « ? » ne sont pas encore dans tes contacts.",
            color = Px.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(20.dp, 0.dp, 20.dp, 14.dp),
        )
    }

    selected?.let { p ->
        val c = contacts.firstOrNull { it.id == p.id }
        val name = c?.name ?: p.name ?: "Téléphone inconnu"
        val canCall = svc?.calls?.canCall(p.id) == true
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = Px.Card,
            title = { Text(name, color = Color.White) },
            text = {
                Column {
                    Text(buildString {
                        append(if (p.ble) "Bluetooth" else ""); if (p.ble && p.wifi) append(" + "); append(if (p.wifi) "Wi-Fi direct" else "")
                        p.meters?.let { append(" · environ $it m") }
                        if (p.linked) append(" · liaison ouverte")
                    }, color = Px.Muted)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when { c?.accepted == true -> "Dans tes contacts"; c?.status == 0 -> "Demande envoyée, en attente"; c?.status == 2 -> "Il veut t'ajouter en contact"; else -> "Pas encore dans tes contacts" },
                        color = Px.AccentSoft,
                    )
                    if (!canCall) { Spacer(Modifier.height(6.dp)); Text("Trop loin pour un appel en direct (il faut le Wi-Fi direct). Le talkie-walkie marche.", color = Px.Muted, fontSize = 12.sp) }
                }
            },
            confirmButton = {
                when {
                    c?.accepted == true -> {
                        TextButton(onClick = { selected = null; onOpenChat(p.id) }) { Text("Écrire") }
                        if (canCall) TextButton(onClick = { selected = null; svc?.calls?.call(p.id) }) { Text("Appeler") }
                    }
                    c?.status == 2 -> TextButton(onClick = { selected = null; svc?.messenger?.acceptContact(p.id) }) { Text("Accepter") }
                    c?.status == 0 -> TextButton(onClick = { selected = null; svc?.messenger?.requestContact(p.id) }) { Text("Renvoyer la demande") }
                    else -> TextButton(onClick = { selected = null; svc?.messenger?.requestContact(p.id) }) { Text("Ajouter") }
                }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Fermer", color = Px.Muted) } },
        )
    }
}

@Composable
private fun Chip(text: String, ok: Boolean?) {
    Text(text, color = when (ok) { true -> Px.AccentSoft; false -> Px.Warn; null -> Px.Muted }, fontSize = 12.sp, modifier = Modifier.background(Px.Card, RoundedCornerShape(8.dp)).padding(10.dp, 5.dp))
}

@Composable
private fun Radar(peers: List<Peer>, contacts: List<Contact>, onTap: (Peer) -> Unit) {
    val transition = rememberInfiniteTransition(label = "sweep")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val density = LocalDensity.current
    val accent = Px.Accent; val avatarColor = Px.Avatar; val muted = Px.Muted
    val labelPaint = remember { Paint().apply { color = Px.AccentSoft.toArgb(); textAlign = Paint.Align.CENTER; isAntiAlias = true } }
    val initialsPaint = remember { Paint().apply { color = android.graphics.Color.WHITE; textAlign = Paint.Align.CENTER; isAntiAlias = true; isFakeBoldText = true } }
    val positions = remember { HashMap<String, Offset>() }
    val tapRadius = with(density) { 30.dp.toPx() }

    Canvas(
        Modifier.fillMaxWidth(0.94f).aspectRatio(1f).pointerInput(peers) {
            detectTapGestures { pos ->
                val hit = positions.entries.minByOrNull { hypot(it.value.x - pos.x, it.value.y - pos.y) }
                if (hit != null && hypot(hit.value.x - pos.x, hit.value.y - pos.y) <= tapRadius) peers.firstOrNull { it.id == hit.key }?.let(onTap)
            }
        },
    ) {
        val c = center; val R = size.minDimension / 2 - 8.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.22f), Color.Transparent), c, R), R, c)
        for ((f, a) in listOf(0.33f to 0.35f, 0.66f to 0.5f, 1f to 0.65f)) drawCircle(accent.copy(alpha = a), R * f, c, style = Stroke(if (f == 1f) 2.5.dp.toPx() else 1.5.dp.toPx()))
        labelPaint.textSize = 11.sp.toPx()
        drawContext.canvas.nativeCanvas.drawText("30 m", c.x, c.y - R * 0.33f - 6.dp.toPx(), labelPaint)
        drawContext.canvas.nativeCanvas.drawText("100 m", c.x, c.y - R * 0.66f - 6.dp.toPx(), labelPaint)
        // balayage
        val path = Path().apply { moveTo(c.x, c.y); arcTo(androidx.compose.ui.geometry.Rect(c.x - R, c.y - R, c.x + R, c.y + R), angle - 50f, 50f, false); close() }
        drawPath(path, Brush.sweepGradient(listOf(Color.Transparent, accent.copy(alpha = 0.35f)), c))
        val rad = Math.toRadians(angle.toDouble())
        drawLine(accent, c, Offset(c.x + (R * cos(rad)).toFloat(), c.y + (R * sin(rad)).toFloat()), 2.dp.toPx())

        positions.clear()
        val nodeR = 20.dp.toPx(); val smallR = 14.dp.toPx()
        initialsPaint.textSize = 12.sp.toPx()
        for (p in peers) {
            val contact = contacts.firstOrNull { it.id == p.id }
            val known = contact?.accepted == true
            val a = ((p.id.hashCode().toLong() and 0xffffL) % 360L).toDouble() * Math.PI / 180
            val m = p.meters
            val dist = when { m != null -> (0.1f + 0.56f * (m / 100f).coerceAtMost(1f)); p.wifi -> 0.5f; else -> 0.8f }
            val pos = Offset(c.x + (R * dist * cos(a)).toFloat(), c.y + (R * dist * sin(a)).toFloat())
            positions[p.id] = pos
            if (known) drawLine(accent.copy(alpha = 0.5f), c, pos, 1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 14f)))
            val r = if (known) nodeR else smallR
            drawCircle(avatarColor, r, pos)
            drawCircle(if (known) accent else muted, r, pos, style = Stroke(2.dp.toPx()))
            val label = if (known) initials(contact?.name) else "?"
            drawContext.canvas.nativeCanvas.drawText(label, pos.x, pos.y + 4.dp.toPx(), initialsPaint)
            val name = contact?.name ?: p.name
            val sub = buildString { if (name != null) append(name); if (m != null) append(" · $m m") else if (p.wifi) append(" · Wi-Fi") }
            if (sub.isNotEmpty()) drawContext.canvas.nativeCanvas.drawText(sub, pos.x, pos.y + r + 14.dp.toPx(), labelPaint)
        }
        // moi
        drawCircle(accent, 26.dp.toPx(), c); drawCircle(Px.Bg, 18.dp.toPx(), c)
        drawContext.canvas.nativeCanvas.drawText("Moi", c.x, c.y + 4.dp.toPx(), initialsPaint)
    }
}
