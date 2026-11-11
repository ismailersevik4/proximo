package fr.proximo.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Réduction d'une photo pour le réseau : au plus 1024 px et environ 200 Ko. */
object Photos {
    fun compress(ctx: Context, uri: Uri): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rotation = try { ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 } catch (e: Exception) { 0 }
        if (rotation != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        val maxSide = 1024f
        val scale = minOf(1f, maxSide / maxOf(bmp.width, bmp.height))
        if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        var quality = 70
        var out: ByteArray
        do {
            val bos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos); out = bos.toByteArray(); quality -= 10
        } while (out.size > 200_000 && quality >= 30)
        out
    } catch (e: Exception) { null }
}

/** Vidéos courtes : lecture du fichier, durée, vignette. Pas de recompression (on filme directement en basse qualité). */
object Videos {
    const val MAX_BYTES = 3_500_000L
    const val MAX_SECONDS = 15

    /** Rend (données, secondes) ou null (trop lourde / illisible), avec le message d'erreur. */
    fun load(ctx: Context, uri: Uri): Pair<Pair<ByteArray, Int>?, String?> {
        return try {
            fun tooBig(size: Long) = "Vidéo trop lourde (%.1f Mo, maximum 3,5 Mo) : filme-la depuis Proximo, 15 s en basse qualité".format(java.util.Locale.FRANCE, size / 1e6)
            // La taille est contrôlée AVANT de lire le fichier : une vidéo de 300 Mo ne passe plus en mémoire.
            val known = try { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L } catch (e: Exception) { -1L }
            if (known > MAX_BYTES) return null to tooBig(known)
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { inp ->
                val bos = java.io.ByteArrayOutputStream(); val buf = ByteArray(65536); var n: Int
                while (inp.read(buf).also { n = it } > 0) { bos.write(buf, 0, n); if (bos.size() > MAX_BYTES) return null to tooBig(bos.size().toLong()) }
                bos.toByteArray()
            } ?: return null to "Vidéo illisible"
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(ctx, uri)
            val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            r.release()
            (bytes to maxOf(1, (ms / 1000).toInt())) to null
        } catch (e: Exception) { null to "Vidéo illisible" }
    }

    private val thumbs = android.util.LruCache<String, Bitmap>(24)
    fun thumbnail(path: String): Bitmap? {
        thumbs.get(path)?.let { return it }
        val b = try {
            if (Build.VERSION.SDK_INT >= 29) android.media.ThumbnailUtils.createVideoThumbnail(File(path), android.util.Size(480, 480), null)
            else android.media.ThumbnailUtils.createVideoThumbnail(path, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
        } catch (e: Exception) { null }
        if (b != null) thumbs.put(path, b)
        return b
    }
}

/** Enregistrement d'un vocal (talkie-walkie) en AAC, petit débit. */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var start = 0L

    fun start(): Boolean = try {
        val f = File(ctx.cacheDir, "vocal.m4a"); f.delete()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(16000); r.setAudioEncodingBitRate(24000); r.setAudioChannels(1)
        r.setMaxDuration(60_000)
        r.setOutputFile(f.absolutePath)
        r.prepare(); r.start()
        rec = r; file = f; start = System.currentTimeMillis()
        true
    } catch (e: Exception) { rec = null; false }

    /** Rend (données, secondes) ou null si trop court. */
    fun stop(): Pair<ByteArray, Int>? {
        val r = rec ?: return null
        rec = null
        val secs = ((System.currentTimeMillis() - start) / 1000).toInt()
        try { r.stop() } catch (e: Exception) { r.release(); return null }
        r.release()
        val f = file ?: return null
        if (secs < 1 || !f.exists()) return null
        return f.readBytes() to maxOf(1, secs)
    }
    fun cancel() { try { rec?.stop() } catch (_: Exception) {}; try { rec?.release() } catch (_: Exception) {}; rec = null }
}

/** Position actuelle, sans Google Play : dernière position connue, sinon une demande rapide. */
object Locations {
    @Suppress("MissingPermission")
    fun allowed(ctx: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Position récente (moins de 2 min), sinon une vraie demande au téléphone — sur toutes les versions d'Android —
     * en attendant au plus maxWaitMs (un SOS ne doit pas rester bloqué à chercher le GPS). À défaut, la dernière connue.
     */
    @Suppress("MissingPermission")
    suspend fun current(ctx: Context, maxWaitMs: Long = 8000): Location? {
        if (!allowed(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val last = providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (e: Exception) { null } }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 120_000) return last
        val provider = try {
            when { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER; lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER; else -> return last }
        } catch (e: Exception) { return last }
        val fresh = try {
            kotlinx.coroutines.withTimeoutOrNull(maxWaitMs) {
                suspendCancellableCoroutine<Location?> { cont ->
                    val l = object : android.location.LocationListener {
                        override fun onLocationChanged(loc: Location) { try { lm.removeUpdates(this) } catch (_: Exception) {}; if (cont.isActive) cont.resume(loc) }
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {}
                        @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    }
                    try { lm.requestLocationUpdates(provider, 0L, 0f, l, android.os.Looper.getMainLooper()) } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
                    cont.invokeOnCancellation { try { lm.removeUpdates(l) } catch (_: Exception) {} }
                }
            }
        } catch (e: Exception) { null }
        return fresh ?: last
    }
}
