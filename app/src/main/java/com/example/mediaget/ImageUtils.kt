package com.example.mediaget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/** Optional "軽量化" (lightweight) pass applied to downloaded images. */
object ImageUtils {

    private const val MAX_DIMENSION = 1600
    private const val JPEG_QUALITY = 80

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    fun isImage(file: File): Boolean = file.extension.lowercase() in IMAGE_EXTENSIONS

    /**
     * Downscales and re-encodes [file] as JPEG in place if it is larger than
     * [MAX_DIMENSION] on its longest side. Returns the (possibly renamed) file.
     * No-ops safely if decoding fails for any reason.
     */
    fun compressInPlace(file: File): File {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return file

            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sampleSize = 1
            while (longest / (sampleSize * 2) >= MAX_DIMENSION) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return file

            val outFile = if (file.extension.lowercase() == "jpg" || file.extension.lowercase() == "jpeg") {
                file
            } else {
                File(file.parentFile, file.nameWithoutExtension + ".jpg")
            }

            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            bitmap.recycle()

            if (outFile != file) file.delete()
            outFile
        } catch (_: Exception) {
            file
        }
    }
}
