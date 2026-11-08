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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** Une mise à jour disponible, par Internet ou depuis un voisin. */
data class UpdateInfo(val code: Int, val name: String, val fromPeer: String?, val peerName: String?)

/**
 * Mises à jour de l'appli :
 *  - par Internet : fichier proximo-apk.json et Proximo.apk dans les « Releases » GitHub (tag android) ;
 *  - de téléphone à téléphone : un voisin qui a une version plus récente envoie l'APK par Wi-Fi direct.
 * Dans les deux cas, Android vérifie que l'APK est signée avec la même clé avant d'installer.
 */
class Updater(private val ctx: Context, private val core: MeshCore) {
    private val tag = "ProximoMaj"
    private val settings = Settings.get(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val available = MutableStateFlow<UpdateInfo?>(null)
    /** 0..100 pendant un téléchargement, -1 sinon. */
    val progress = MutableStateFlow(-1)
    val message = MutableStateFlow<String?>(null)

    private val myCode = BuildConfig.VERSION_CODE
    private var receiving: RandomAccessFile? = null
    private var receivingFile: File? = null
    private var receivingTotal = 0
    private var receivedParts = HashSet<Int>()
    private var receivingCode = 0

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
            progress.value = 0
            val f = File(ctx.cacheDir, "Proximo-maj.apk")
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true; c.connectTimeout = 15000; c.readTimeout = 30000
            val total = c.contentLength
            c.inputStream.use { inp -> f.outputStream().use { out ->
                val buf = ByteArray(65536); var n: Int; var done = 0L
                while (inp.read(buf).also { n = it } > 0) { out.write(buf, 0, n); done += n; if (total > 0) progress.value = (done * 100 / total).toInt() }
            } }
            progress.value = -1
            install(f)
        } catch (e: Exception) { progress.value = -1; message.value = "Téléchargement impossible : ${e.message}" }
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
        if (!canInstall()) { message.value = "Autorise Proximo à installer des mises à jour (réglage Android), puis réessaie"; pendingInstall = f; askInstallPermission(); return }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(i)
    }
    private var pendingInstall: File? = null
    fun retryPendingInstall() { pendingInstall?.let { if (fr.proximo.app.MainActivity.isForeground) { pendingInstall = null; install(it) } } }

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
    /** Dès qu'un voisin apparaît, on lui dit quelle version on a (il demandera l'APK s'il est en retard). */
    fun onPeerAppeared(p: Peer) {
        if (!settings.shareUpdates) return
        val j = JSONObject().put("c", "offer").put("v", myCode).put("n", BuildConfig.VERSION_NAME).toString()
        core.send(Frame(Frame.APK, 0, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(p.id), j.toByteArray()))
    }

    /** L'utilisateur accepte de récupérer la version du voisin. */
    fun requestFromPeer(peerId: String) {
        if (!core.hasWifiLink(peerId)) { message.value = "Ce voisin n'est pas joignable en Wi-Fi direct : la mise à jour ne peut pas passer en Bluetooth (trop lent)."; return }
        val j = JSONObject().put("c", "req").put("v", myCode).toString()
        core.send(Frame(Frame.APK, 0, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(peerId), j.toByteArray()))
        progress.value = 0
        lastPartAt = System.currentTimeMillis()
        scope.launch {
            while (progress.value >= 0) {
                kotlinx.coroutines.delay(5000)
                if (progress.value >= 0 && System.currentTimeMillis() - lastPartAt > 60_000) { progress.value = -1; message.value = "La mise à jour n'arrive pas : le voisin n'est plus joignable en Wi-Fi direct."; break }
            }
        }
    }
    @Volatile private var lastPartAt = 0L

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
                    if (v > myCode && !refusedRecently && available.value == null && progress.value < 0) {
                        available.value = UpdateInfo(v, j.optString("n"), f.fromHex, core.peer(f.fromHex)?.name)
                    }
                }
                "req" -> if (j.optInt("v") < myCode && settings.shareUpdates) sendApk(f.fromHex)
                "part" -> onPart(j, p.copyOfRange(2 + len, p.size), f.fromHex)
            }
        } catch (e: Exception) { Log.w(tag, "trame maj", e) }
        return true
    }

    private fun sendApk(peerId: String) = scope.launch {
        if (!core.hasWifiLink(peerId)) { Log.i(tag, "APK : pas de prise Wi-Fi avec $peerId"); return@launch }
        try {
            val src = File(ctx.applicationInfo.sourceDir)
            val data = src.readBytes()
            val total = (data.size + PART - 1) / PART
            for (i in 0 until total) {
                val start = i * PART; val end = minOf(data.size, start + PART)
                val j = JSONObject().put("c", "part").put("i", i).put("n", total).put("v", myCode).put("s", data.size).toString().toByteArray()
                val payload = byteArrayOf((j.size shr 8).toByte(), j.size.toByte()) + j + data.copyOfRange(start, end)
                var tries = 0
                while (!core.sendTo(peerId, Frame(Frame.APK, 0, 0, 0, Frame.newId(), core.myIdBytes(), Hex.dec(peerId), payload).encode()) && tries++ < 20) Thread.sleep(500)
                if (tries >= 20) { Log.w(tag, "envoi APK interrompu"); return@launch }
                if (i % 8 == 7) Thread.sleep(50)
            }
            Log.i(tag, "APK envoyée à $peerId")
        } catch (e: Exception) { Log.w(tag, "envoi APK", e) }
    }

    @Synchronized
    private fun onPart(j: JSONObject, data: ByteArray, from: String) {
        val code = j.optInt("v"); val idx = j.optInt("i"); val total = j.optInt("n"); val size = j.optInt("s")
        if (code <= myCode) return
        if (total <= 0 || idx < 0 || idx >= total || size <= 0 || size > 200_000_000 || idx.toLong() * PART + data.size > size) return
        lastPartAt = System.currentTimeMillis()
        if (receiving == null || receivingCode != code) {
            receivingFile?.delete()
            val f = File(ctx.cacheDir, "Proximo-voisin.apk")
            receiving = RandomAccessFile(f, "rw").also { it.setLength(size.toLong()) }
            receivingFile = f; receivingTotal = total; receivedParts = HashSet(); receivingCode = code
        }
        receiving?.let { it.seek(idx.toLong() * PART); it.write(data) }
        receivedParts.add(idx)
        progress.value = receivedParts.size * 100 / maxOf(1, total)
        if (receivedParts.size == total) {
            receiving?.close(); receiving = null
            progress.value = -1
            receivingFile?.let { install(it) }
        }
    }

    fun dismiss() {
        available.value?.let { settings.lastOfferedVersion = it.code; settings.lastOfferedAt = System.currentTimeMillis() }
        available.value = null; message.value = null
    }

    companion object {
        const val REPO = "ismailersevik4/proximo"
        const val PART = 60_000
    }
}
