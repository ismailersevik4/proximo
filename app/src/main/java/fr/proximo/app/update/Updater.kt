package fr.proximo.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.content.FileProvider
import fr.proximo.app.BuildConfig
import fr.proximo.app.core.Hex
import fr.proximo.app.core.Settings
import fr.proximo.app.mesh.Frame
import fr.proximo.app.mesh.MeshCore
import fr.proximo.app.mesh.Peer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Une mise à jour disponible, par Internet ou depuis un voisin. */
data class UpdateInfo(val code: Int, val name: String, val fromPeer: String?, val peerName: String?)

/** Transfert en cours (réception ou envoi) pour l'écran : pourcentage, texte, Bluetooth ou Wi-Fi. */
data class Transfer(val percent: Int, val label: String, val ble: Boolean)

/**
 * Mises à jour de l'appli :
 *  - par Internet : proximo-apk.json et Proximo.apk dans les « Releases » GitHub (tag android) ;
 *  - de téléphone à téléphone : par Wi-Fi direct (rapide), ou en secours par Bluetooth (lent, morceau par morceau avec reprise).
 * Dans les deux cas, Android vérifie que l'APK est signée avec la même clé avant d'installer.
 */
class Updater(private val ctx: Context, private val core: MeshCore) {
    private val tag = "ProximoMaj"
    private val settings = Settings.get(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val available = MutableStateFlow<UpdateInfo?>(null)
    /** Offre d'un voisin, gardée à côté de l'offre Internet (le bandeau propose les deux). */
    val peerOffer = MutableStateFlow<UpdateInfo?>(null)
    private val lastOfferTo = ConcurrentHashMap<String, Long>()
    /** Pourcentage du téléchargement ou de la réception en cours, -1 sinon. */
    val progress = MutableStateFlow(-1)
    /** Détail du transfert en cours (réception ou envoi), null sinon. */
    val transfer = MutableStateFlow<Transfer?>(null)
    val message = MutableStateFlow<String?>(null)
    /** Une APK est prête mais Android n'autorise pas encore Proximo à installer : le bandeau propose « Autoriser ». */
    val needsInstallPermission = MutableStateFlow(false)
    /** Le voisin n'a pas de Wi-Fi direct : demande de confirmation pour passer en Bluetooth (lent). */
    val bleConfirm = MutableStateFlow<UpdateInfo?>(null)

    private val myCode = BuildConfig.VERSION_CODE

    // Réception
    private var receiving: RandomAccessFile? = null
    private var receivingFile: File? = null
    private var receivingTotal = 0
    private var receivedParts = HashSet<Int>()
    private var receivingCode = 0
    private var receivingFrom: String? = null
    private var receivingBle = false
    @Volatile private var lastPartAt = 0L
    private var receiveStart = 0L

    // Envoi : accusés « got » par voisin
    private val gotAcks = ConcurrentHashMap<String, Int>()
    @Volatile private var sendingTo: String? = null

    // ---------------- Internet ----------------
    fun checkOnline() = scope.launch {
        try {
            val rel = JSONObject(get("https://api.github.com/repos/$REPO/releases/tags/android"))
            val assets = rel.getJSONArray("assets")
            var jsonUrl: String? = null; var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                when (a.getString("name")) { "proximo-apk.json" -> jsonUrl = a.getString("browser_download_url"); "Proximo.apk" -> apkUrl = a.getString("browser_download_url") }
            }
            if (jsonUrl == null || apkUrl == null) return@launch
            val v = JSONObject(get(jsonUrl))
            val code = v.getInt("code")
            if (code > myCode) available.value = UpdateInfo(code, v.getString("name"), null, null)
            onlineApkUrl = apkUrl
        } catch (e: Exception) { Log.i(tag, "vérification en ligne impossible : ${e.message}") }
    }
    private var onlineApkUrl: String? = null

    fun downloadAndInstall() = scope.launch {
        val url = onlineApkUrl ?: return@launch
        try {
            progress.value = 0; transfer.value = Transfer(0, "Téléchargement par Internet…", false)
            val f = File(ctx.cacheDir, "Proximo-maj.apk")
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true; c.connectTimeout = 15000; c.readTimeout = 30000
            val total = c.contentLength
            c.inputStream.use { inp -> f.outputStream().use { out ->
                val buf = ByteArray(65536); var n: Int; var done = 0L
                while (inp.read(buf).also { n = it } > 0) { out.write(buf, 0, n); done += n; if (total > 0) { val pc = (done * 100 / total).toInt(); progress.value = pc; transfer.value = Transfer(pc, "Téléchargement par Internet…", false) } }
            } }
            progress.value = -1; transfer.value = null
            install(f)
        } catch (e: Exception) { progress.value = -1; transfer.value = null; message.value = "Téléchargement impossible : ${e.message}" }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    // ---------------- installation ----------------
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun askInstallPermission() {
        val i = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun install(f: File) {
        if (!sameSignature(f)) { message.value = "Cette APK n'est pas signée avec la clé de Proximo : installation refusée"; f.delete(); return }
        if (!fr.proximo.app.MainActivity.isForeground) {
            // Android refuse d'ouvrir l'installateur depuis l'arrière-plan : on prévient, l'ouverture de l'appli lancera l'installation.
            pendingInstall = f
            notifyReady()
            return
        }
        if (!canInstall()) { pendingInstall = f; needsInstallPermission.value = true; return }
        needsInstallPermission.value = false
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i)
    }
    private var pendingInstall: File? = null
    fun retryPendingInstall() {
        val f = pendingInstall ?: return
        if (!fr.proximo.app.MainActivity.isForeground) return
        if (canInstall()) { pendingInstall = null; needsInstallPermission.value = false; install(f) }
        else needsInstallPermission.value = true
    }
    /** L'utilisateur a appuyé sur « Autoriser » : on ouvre le réglage Android une seule fois, à sa demande. */
    fun requestInstallPermission() { try { askInstallPermission() } catch (e: Exception) { message.value = "Impossible d'ouvrir le réglage : ${e.message}" } }

    private fun notifyReady() {
        try {
            val i = Intent(ctx, fr.proximo.app.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pi = android.app.PendingIntent.getActivity(ctx, 91, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            val n = androidx.core.app.NotificationCompat.Builder(ctx, fr.proximo.app.mesh.MeshService.CH_MESSAGES)
                .setSmallIcon(fr.proximo.app.R.drawable.ic_notif).setContentTitle("Mise à jour de Proximo prête").setContentText("Appuie pour l'installer")
                .setAutoCancel(true).setContentIntent(pi).build()
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(91, n)
        } catch (_: SecurityException) {}
    }

    @Suppress("DEPRECATION")
    private fun sameSignature(apk: File): Boolean {
        return try {
            val pm = ctx.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val info = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
            if (info.packageName != ctx.packageName) return false
            val mine = pm.getPackageInfo(ctx.packageName, flags)
            val a = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            val b = if (Build.VERSION.SDK_INT >= 28) mine.signingInfo?.apkContentsSigners else mine.signatures
            a != null && b != null && a.isNotEmpty() && a.first().toCharsString() == b.first().toCharsString()
        } catch (e: Exception) { Log.w(tag, "signature", e); false }
    }

    // ---------------- de téléphone à téléphone ----------------
    /** Dès qu'une liaison s'ouvre, on dit au voisin quelle version on a (il demandera l'APK s'il est en retard). */
    fun onPeerAppeared(p: Peer) { offerTo(p.id, force = true) }

    private fun offerTo(peerId: String, force: Boolean) {
        if (!settings.shareUpdates) return
        val now = System.currentTimeMillis()
        if (!force && now - (lastOfferTo[peerId] ?: 0L) < 10 * 60_000) return
        val j = JSONObject().put("c", "offer").put("v", myCode).put("n", BuildConfig.VERSION_NAME).toString()
        if (core.sendTo(peerId, Frame(Frame.APK, 0, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(peerId), j.toByteArray()).encode())) lastOfferTo[peerId] = now
    }

    /** Toutes les 10 minutes, on rappelle notre version aux voisins connectés (au cas où l'offre à l'ouverture se soit perdue). */
    fun startPeriodicOffers() = scope.launch {
        while (true) { delay(60_000); for (id in core.connectedPeerIds()) offerTo(id, force = false) }
    }

    /** Bouton « Chercher une mise à jour chez les voisins » : on interroge tout le monde ; les réponses arrivent en « offer ». */
    fun askPeers(): Int {
        val ids = core.connectedPeerIds()
        peerOffer.value = null
        settings.lastOfferedVersion = 0
        var sent = 0
        for (id in ids) if (core.sendTo(id, Frame(Frame.APK, 0, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(id), JSONObject().put("c", "ask").put("v", myCode).toString().toByteArray()).encode())) sent++
        if (sent > 0) scope.launch {
            delay(8000)
            if (peerOffer.value == null && progress.value < 0) message.value = "Aucune version plus récente chez les voisins (ils ont la même version ou une plus ancienne)."
        }
        return sent
    }

    /** L'utilisateur accepte de récupérer la version du voisin. Sans Wi-Fi direct, on demande confirmation pour le Bluetooth. */
    fun requestFromPeer(peerId: String, allowBle: Boolean = false) {
        val wifi = core.hasWifiLink(peerId)
        if (!wifi && !allowBle) { bleConfirm.value = peerOffer.value ?: available.value ?: UpdateInfo(0, "", peerId, core.peer(peerId)?.name); return }
        bleConfirm.value = null
        if (!wifi && !core.connectedPeerIds().contains(peerId)) { message.value = "Ce voisin n'est pas joignable pour l'instant."; return }
        val have = JSONArray(); if (receivingFrom == peerId && receiving != null) receivedParts.forEach { have.put(it) }
        val j = JSONObject().put("c", "req").put("v", myCode).put("ble", !wifi).put("have", have).toString()
        core.send(Frame(Frame.APK, 0, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(peerId), j.toByteArray()))
        receivingBle = !wifi; receivingFrom = peerId
        progress.value = 0; transfer.value = Transfer(0, if (wifi) "Réception par Wi-Fi direct…" else "Réception par Bluetooth (lent)…", !wifi)
        lastPartAt = System.currentTimeMillis(); receiveStart = lastPartAt
        scope.launch {
            val timeout = if (wifi) 60_000 else 180_000
            while (progress.value >= 0) {
                delay(5000)
                if (progress.value >= 0 && System.currentTimeMillis() - lastPartAt > timeout) {
                    progress.value = -1; transfer.value = null
                    message.value = if (receivedParts.isEmpty()) "La mise à jour n'arrive pas : le voisin n'est plus joignable." else "Transfert interrompu à ${receivedParts.size * 100 / maxOf(1, receivingTotal)} % : il reprendra là où il en était quand le voisin sera de nouveau à portée (appuie sur Installer)."
                    break
                }
            }
        }
    }

    /** Rend true si la trame était pour la mise à jour. */
    fun handle(f: Frame, fromPeer: String): Boolean {
        if (f.type != Frame.APK) return false
        try {
            val p = f.payload
            // Les morceaux d'APK : [u16 jsonLen][json][données]
            val len = ((p[0].toInt() and 0xff) shl 8) or (p[1].toInt() and 0xff)
            val isPart = p.size > 2 + len && len > 0 && len < 200 && p[2] == '{'.code.toByte()
            val j = JSONObject(String(if (isPart) p.copyOfRange(2, 2 + len) else p))
            when (j.optString("c")) {
                "offer" -> {
                    val v = j.optInt("v")
                    val refusedRecently = v == settings.lastOfferedVersion && System.currentTimeMillis() - settings.lastOfferedAt < 24 * 3600_000L
                    if (v > myCode && !refusedRecently && progress.value < 0 && (peerOffer.value == null || peerOffer.value!!.code < v)) {
                        peerOffer.value = UpdateInfo(v, j.optString("n"), f.fromHex, core.peer(f.fromHex)?.name)
                    }
                }
                "ask" -> if (j.optInt("v") < myCode) offerTo(f.fromHex, force = true) else core.sendTo(f.fromHex, Frame(Frame.APK, 0, 0, 0, Frame.newId(), core.myIdBytes(), f.from, JSONObject().put("c", "same").put("v", myCode).toString().toByteArray()).encode())
                "same" -> if (peerOffer.value == null) message.value = "${core.peer(f.fromHex)?.name ?: "Le voisin"} a la même version (${j.optInt("v")}) ou une plus ancienne."
                "req" -> if (j.optInt("v") < myCode && settings.shareUpdates) {
                    val have = HashSet<Int>(); j.optJSONArray("have")?.let { a -> for (i in 0 until a.length()) have.add(a.getInt(i)) }
                    sendApk(f.fromHex, j.optBoolean("ble", false), have)
                }
                "part" -> onPart(j, p.copyOfRange(2 + len, p.size), f.fromHex)
                "got" -> gotAcks[f.fromHex] = j.optInt("i")
                "done" -> { if (sendingTo == f.fromHex) { sendingTo = null; transfer.value = null } }
            }
        } catch (e: Exception) { Log.w(tag, "trame maj", e) }
        return true
    }

    /** Envoi de mon APK, morceau par morceau, en attendant l'accusé du voisin pour chacun (les textes passent entre deux). */
    private fun sendApk(peerId: String, ble: Boolean, have: Set<Int>) = scope.launch {
        if (sendingTo != null) { Log.i(tag, "un envoi est déjà en cours"); return@launch }
        if (!ble && !core.hasWifiLink(peerId)) { Log.i(tag, "APK : pas de prise Wi-Fi avec $peerId"); return@launch }
        sendingTo = peerId
        val who = core.peer(peerId)?.name ?: "un voisin"
        try {
            val src = File(ctx.applicationInfo.sourceDir)
            val data = src.readBytes()
            val total = (data.size + PART - 1) / PART
            val flags = if (ble) Frame.FLAG_ALLOW_BLE else 0
            val start = System.currentTimeMillis()
            var sent = have.size
            for (i in 0 until total) {
                if (i in have) continue
                if (sendingTo != peerId) break
                val startB = i * PART; val end = minOf(data.size, startB + PART)
                val j = JSONObject().put("c", "part").put("i", i).put("n", total).put("v", myCode).put("s", data.size).toString().toByteArray()
                val payload = byteArrayOf((j.size shr 8).toByte(), j.size.toByte()) + j + data.copyOfRange(startB, end)
                var ok = false
                for (attempt in 0 until 3) {
                    gotAcks.remove(peerId)
                    var tries = 0
                    while (!core.sendTo(peerId, Frame(Frame.APK, 0, 0, flags, Frame.newId(), core.myIdBytes(), Hex.dec(peerId), payload).encode()) && tries++ < 20) delay(500)
                    if (tries >= 20) break
                    // On attend l'accusé du morceau (Bluetooth : jusqu'à 40 s, Wi-Fi : 10 s).
                    val deadline = System.currentTimeMillis() + if (ble) 40_000 else 10_000
                    while (System.currentTimeMillis() < deadline) { if (gotAcks[peerId] == i) { ok = true; break }; delay(100) }
                    if (ok) break
                }
                if (!ok) { Log.w(tag, "envoi APK interrompu au morceau $i"); transfer.value = null; sendingTo = null; return@launch }
                sent++
                val pc = sent * 100 / total
                val elapsed = System.currentTimeMillis() - start
                val eta = if (sent > have.size) elapsed / (sent - have.size) * (total - sent) / 1000 else 0L
                transfer.value = Transfer(pc, "Envoi de la mise à jour à $who… ${if (ble) "Bluetooth" else "Wi-Fi direct"}" + if (eta > 5) " · ~${eta / 60} min ${eta % 60} s" else "", ble)
            }
            Log.i(tag, "APK envoyée à $peerId")
        } catch (e: Exception) { Log.w(tag, "envoi APK", e) }
        finally { sendingTo = null; transfer.value = null }
    }

    @Synchronized
    private fun onPart(j: JSONObject, data: ByteArray, from: String) {
        val code = j.optInt("v"); val idx = j.optInt("i"); val total = j.optInt("n"); val size = j.optInt("s")
        if (code <= myCode) return
        if (total <= 0 || idx < 0 || idx >= total || size <= 0 || size > 200_000_000 || idx.toLong() * PART + data.size > size) return
        lastPartAt = System.currentTimeMillis()
        if (receiving == null || receivingCode != code) {
            try { receiving?.close() } catch (_: Exception) {}
            receivingFile?.delete()
            val f = File(ctx.cacheDir, "Proximo-voisin.apk")
            receiving = RandomAccessFile(f, "rw").also { it.setLength(size.toLong()) }
            receivingFile = f; receivingTotal = total; receivedParts = HashSet(); receivingCode = code; receivingFrom = from
            if (receiveStart == 0L) receiveStart = System.currentTimeMillis()
        }
        receiving?.let { it.seek(idx.toLong() * PART); it.write(data) }
        receivedParts.add(idx)
        // Accusé au voisin : il peut envoyer le morceau suivant.
        core.send(Frame(Frame.APK, 0, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(from), JSONObject().put("c", "got").put("i", idx).toString().toByteArray()))
        val pc = receivedParts.size * 100 / maxOf(1, total)
        val elapsed = System.currentTimeMillis() - receiveStart
        val eta = if (receivedParts.size > 0) elapsed / receivedParts.size * (total - receivedParts.size) / 1000 else 0L
        progress.value = pc
        transfer.value = Transfer(pc, (if (receivingBle) "Réception par Bluetooth (lent)…" else "Réception par Wi-Fi direct…") + if (eta > 5) " · ~${eta / 60} min ${eta % 60} s" else "", receivingBle)
        if (receivedParts.size == total) {
            receiving?.close(); receiving = null
            progress.value = -1; transfer.value = null; receiveStart = 0L
            core.send(Frame(Frame.APK, 0, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(from), JSONObject().put("c", "done").toString().toByteArray()))
            receivingFile?.let { install(it) }
        }
    }

    fun dismiss() {
        (peerOffer.value ?: available.value)?.let { settings.lastOfferedVersion = it.code; settings.lastOfferedAt = System.currentTimeMillis() }
        available.value = null; peerOffer.value = null; message.value = null; bleConfirm.value = null
    }

    companion object {
        const val REPO = "ismailersevik4/proximo"
        const val PART = 60_000
    }
}
