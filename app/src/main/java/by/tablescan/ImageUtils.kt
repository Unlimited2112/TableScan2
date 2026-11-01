package by.tablescan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

object ImageUtils {

    fun load(ctx: Context, uri: Uri, maxSide: Int = 3000): Bitmap {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = cr.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("неподдерживаемый формат изображения")
        val orientation = try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
    }

    fun scaleToWidth(b: Bitmap, w: Int): Bitmap {
        if (b.width == w) return b
        val h = (b.height.toLong() * w / b.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, w, h, true)
    }
}
