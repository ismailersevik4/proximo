package fr.proximo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.proximo.app.core.Db
import fr.proximo.app.mesh.MeshService
import fr.proximo.app.mesh.Peer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun initials(name: String?): String {
    val n = (name ?: "?").trim()
    if (n.isEmpty()) return "?"
    val parts = n.split(" ").filter { it.isNotBlank() }
    return if (parts.size >= 2) (parts[0].take(1) + parts[1].take(1)).uppercase() else n.take(2).uppercase()
}

fun timeStr(t: Long): String {
    val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = t }
    val sameDay = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(t))
    now.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)
    if (yesterday) return "Hier"
    return SimpleDateFormat("d MMM", Locale.FRANCE).format(Date(t))
}

fun dayLabel(t: Long): String {
    val now = Calendar.getInstance(); val c = Calendar.getInstance().apply { timeInMillis = t }
    if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) return "Aujourd'hui"
    now.add(Calendar.DAY_OF_YEAR, -1)
    if (now.get(Calendar.YEAR) == c.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == c.get(Calendar.DAY_OF_YEAR)) return "Hier"
    return SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(Date(t)).replaceFirstChar { it.uppercase() }
}

@Composable
fun Avatar(name: String?, size: Dp = 48.dp, online: Boolean? = null, square: Boolean = false, color: Color = Px.Avatar) {
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).background(color, if (square) RoundedCornerShape(size / 4) else CircleShape).then(if (online == true) Modifier.border(2.dp, Px.Accent, if (square) RoundedCornerShape(size / 4) else CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) { Text(initials(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value / 3).sp) }
        if (online != null) Box(
            Modifier.align(Alignment.BottomEnd).size(size / 4).background(Px.Bg, CircleShape).border(2.dp, Px.Bg, CircleShape).background(if (online) Px.Accent else Px.Muted, CircleShape)
        )
    }
}

/** Observe la base : relance `load` à chaque changement. */
@Composable
fun <T> dbState(initial: T, load: suspend (Db) -> T): State<T> {
    val ctx = LocalContext.current
    val db = Db.get(ctx)
    val tick by db.changes.collectAsState()
    return produceState(initial, tick) { value = withContext(Dispatchers.IO) { load(db) } }
}

@Composable
fun serviceState(): State<MeshService?> = MeshService.running.collectAsState()

@Composable
fun peersState(): State<List<Peer>> {
    val svc by serviceState()
    val flow = svc?.core?.peers
    return if (flow != null) flow.collectAsState() else produceState(emptyList(), svc) { value = emptyList() }
}
