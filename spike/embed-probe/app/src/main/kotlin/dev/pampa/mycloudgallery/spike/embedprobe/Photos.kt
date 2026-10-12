package dev.pampa.mycloudgallery.spike.embedprobe

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

enum class ConvertFmt(val label: String) {
    JPEG("JPEG q95"),
    TGA("TGA 32 bit (come Gallery)"),
}

/** Risultato della preparazione di una foto: decodifica + conversione in byte, con i tempi. */
class Prepared(
    val position: Int,
    val bytes: ByteArray?,
    val decodeMs: Double,
    val convertMs: Double,
    val width: Int,
    val height: Int,
    val error: String?,
)

object Photos {
    fun uri(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id)

    /** Gli id MediaStore delle foto piu recenti (ordinamento stabile: data di aggiunta decrescente). */
    fun query(ctx: Context, limit: Int): List<Long> {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.MIME_TYPE} LIKE ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("image/%"))
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media._ID),
            )
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val out = ArrayList<Long>(limit)
        ctx.contentResolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.Images.Media._ID),
            args,
            null,
        )?.use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    /**
     * Decodifica gia ridotta (lato lungo = longSide) con ImageDecoder, come Edge Gallery.
     * ImageDecoder applica l'orientamento EXIF e info.size e gia nell'orientamento finale.
     */
    fun decode(ctx: Context, id: Long, longSide: Int): Bitmap {
        val src = ImageDecoder.createSource(ctx.contentResolver, uri(id))
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val long = max(w, h)
            if (long > longSide) {
                val s = longSide.toDouble() / long
                decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
            }
        }
    }

    fun encodeJpeg(bmp: Bitmap, quality: Int = 95): ByteArray {
        val out = ByteArrayOutputStream(256 * 1024)
        check(bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "compress JPEG fallito" }
        return out.toByteArray()
    }

    /** TGA non compresso a 32 bit, origine in alto a sinistra (descrittore 0x28): stesso formato di Gallery/MediaPipe. */
    fun encodeTga(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(18 + w * h * 4)
        out[2] = 2 // true-color non compresso
        out[12] = (w and 0xFF).toByte()
        out[13] = ((w shr 8) and 0xFF).toByte()
        out[14] = (h and 0xFF).toByte()
        out[15] = ((h shr 8) and 0xFF).toByte()
        out[16] = 32
        out[17] = 0x28
        var o = 18
        for (p in px) {
            out[o] = p.toByte() // B
            out[o + 1] = (p shr 8).toByte() // G
            out[o + 2] = (p shr 16).toByte() // R
            out[o + 3] = (p shr 24).toByte() // A
            o += 4
        }
        return out
    }

    fun prepare(ctx: Context, position: Int, id: Long, longSide: Int, fmt: ConvertFmt): Prepared {
        var bmp: Bitmap? = null
        val t0 = nowMs()
        try {
            bmp = decode(ctx, id, longSide)
            val t1 = nowMs()
            val bytes = when (fmt) {
                ConvertFmt.JPEG -> encodeJpeg(bmp)
                ConvertFmt.TGA -> encodeTga(bmp)
            }
            val t2 = nowMs()
            return Prepared(position, bytes, t1 - t0, t2 - t1, bmp.width, bmp.height, null)
        } catch (e: Exception) {
            return Prepared(position, null, nowMs() - t0, 0.0, 0, 0, "${e::class.java.simpleName}: ${e.message}")
        } finally {
            bmp?.recycle()
        }
    }

    fun thumbnail(ctx: Context, id: Long, px: Int): Bitmap? = try {
        ctx.contentResolver.loadThumbnail(uri(id), Size(px, px), null)
    } catch (_: Exception) {
        null
    }
}
