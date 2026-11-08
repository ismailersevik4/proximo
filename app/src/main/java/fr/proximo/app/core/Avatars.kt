package fr.proximo.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/** Photos de profil : la mienne et celles reçues des voisins, en petits JPEG (128 px, ~8 Ko). */
object Avatars {
    const val SIZE = 128
    const val MAX_BYTES = 12_000

    private fun dir(ctx: Context) = File(ctx.filesDir, "avatars").also { it.mkdirs() }
    fun file(ctx: Context, id: String): File = File(dir(ctx), "$id.jpg")
    fun fileIfExists(ctx: Context, id: String): File? = file(ctx, id).takeIf { it.exists() && it.length() > 0 }
    fun mine(ctx: Context): ByteArray? = fileIfExists(ctx, Identity.get(ctx).id)?.readBytes()

    fun save(ctx: Context, id: String, jpeg: ByteArray) { file(ctx, id).writeBytes(jpeg); Db.get(ctx).kv("avatar_$id", jpeg.size.toString()) }
    fun remove(ctx: Context, id: String) { file(ctx, id).delete(); Db.get(ctx).kv("avatar_$id", "0") }

    /** Recadre au carré, réduit à 128 px, compresse jusqu'à tenir dans MAX_BYTES. */
    fun fromUri(ctx: Context, uri: Uri): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / sample > SIZE * 4) sample *= 2
        var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val rot = try { ctx.contentResolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0 } catch (e: Exception) { 0 }
        if (rot != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
        val side = minOf(bmp.width, bmp.height)
        bmp = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        bmp = Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true)
        var q = 80
        var out: ByteArray
        do { val bos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, q, bos); out = bos.toByteArray(); q -= 10 } while (out.size > MAX_BYTES && q >= 30)
        if (out.size > MAX_BYTES) null else out
    } catch (e: Exception) { null }
}
