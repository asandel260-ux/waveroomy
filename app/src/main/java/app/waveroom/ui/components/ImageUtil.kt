package app.waveroom.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

object ImageUtil {

    private fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        val longest = maxOf(opts.outWidth, opts.outHeight)
        while (longest / sample > maxSide * 2) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        val rot = try {
            ctx.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (e: Exception) {
            0f
        }
        if (rot != 0f) {
            val m = Matrix().apply { postRotate(rot) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        return bmp
    }

    /** Centre-cropped square JPEG for profile photos. */
    fun squareJpeg(ctx: Context, uri: Uri, side: Int = 256): ByteArray? {
        val b = decode(ctx, uri, side) ?: return null
        val s = minOf(b.width, b.height)
        val crop = Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
        val out = Bitmap.createScaledBitmap(crop, side, side, true)
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 86, bos)
        return bos.toByteArray()
    }

    /** JPEG small enough to live on the shared board (<= [maxBytes]); returns bytes + size. */
    fun boardJpeg(ctx: Context, uri: Uri, maxBytes: Int = 450_000): Triple<ByteArray, Int, Int>? {
        var side = 1600
        val b0 = decode(ctx, uri, side) ?: return null
        var q = 85
        var scale = minOf(1f, side.toFloat() / maxOf(b0.width, b0.height))
        while (true) {
            val w = maxOf(1, (b0.width * scale).toInt())
            val h = maxOf(1, (b0.height * scale).toInt())
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(b0, w, h, true) else b0
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
            val bytes = bos.toByteArray()
            if (bytes.size <= maxBytes || (q <= 45 && scale < 0.2f)) return Triple(bytes, w, h)
            if (q > 55) q -= 10 else scale *= 0.8f
        }
    }
}
