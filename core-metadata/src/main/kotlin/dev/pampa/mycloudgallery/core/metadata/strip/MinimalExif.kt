package dev.pampa.mycloudgallery.core.metadata.strip

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

// Correzioni 5B (R2, docs/review/5b-fase5b.md): l'EXIF di una copia senza posizione **non si pulisce, si riscrive da zero** con i soli valori noti
// e innocui: l'orientamento (senza, la foto si vedrebbe girata) e la data di scatto (con il fuso e i millisecondi, perche' chi riceve la metta al
// suo posto). Fotocamera, obiettivo, numero di serie, nota del produttore, miniatura, XMP dentro l'EXIF e naturalmente il blocco GPS non
// passano: non si toglie cio' che si conosce, si tiene solo cio' che serve. I valori si leggono con `ExifInterface` e si **ricontrollano** (un
// orientamento da 2 a 8, date fatte solo di cifre e separatori): un testo arbitrario non entra mai nell'EXIF nuovo.

/** Cio' che resta dell'EXIF di una foto condivisa senza posizione. */
internal class MinimalExif private constructor(
  val orientation: Int?,
  val dateTimeOriginal: String?,
  val offsetTimeOriginal: String?,
  val subSecTimeOriginal: String?,
) {

  val isEmpty: Boolean get() = orientation == null && dateTimeOriginal == null

  /** Il blocco TIFF (big-endian): IFD0 con l'orientamento e il puntatore all'IFD Exif, l'IFD Exif con la versione e la data. */
  fun tiff(): ByteArray {
    val ifd0 = ArrayList<Entry>()
    val exif = ArrayList<Entry>()
    orientation?.let { ifd0 += Entry(TAG_ORIENTATION, TYPE_SHORT, 1, byteArrayOf((it shr 8).toByte(), it.toByte())) }
    dateTimeOriginal?.let { date ->
      exif += Entry(TAG_EXIF_VERSION, TYPE_UNDEFINED, 4, "0232".toByteArray(Charsets.US_ASCII))
      exif += ascii(TAG_DATETIME_ORIGINAL, date)
      offsetTimeOriginal?.let { exif += ascii(TAG_OFFSET_TIME_ORIGINAL, it) }
      subSecTimeOriginal?.let { exif += ascii(TAG_SUBSEC_TIME_ORIGINAL, it) }
    }
    if (exif.isNotEmpty()) ifd0 += Entry(TAG_EXIF_IFD, TYPE_LONG, 1, ByteArray(4)) // l'indirizzo si scrive sotto
    ifd0.sortBy { it.tag }
    exif.sortBy { it.tag }

    val ifd0At = 8
    val exifAt = ifd0At + ifdSize(ifd0)
    var dataAt = exifAt + if (exif.isEmpty()) 0 else ifdSize(exif)
    val out = java.io.ByteArrayOutputStream()
    val data = java.io.ByteArrayOutputStream()
    out.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, ifd0At.toByte()))
    fun writeIfd(entries: List<Entry>) {
      out.write(u16(entries.size))
      for (e in entries) {
        out.write(u16(e.tag))
        out.write(u16(e.type))
        out.write(u32(e.count.toLong()))
        val value = if (e.tag == TAG_EXIF_IFD) u32(exifAt.toLong()) else e.value
        if (value.size <= 4) {
          out.write(value.copyOf(4))
        } else {
          out.write(u32(dataAt.toLong()))
          data.write(value)
          dataAt += value.size
          if (value.size % 2 == 1) {
            data.write(0)
            dataAt++
          }
        }
      }
      out.write(u32(0)) // nessun IFD dopo (niente miniatura)
    }
    writeIfd(ifd0)
    if (exif.isNotEmpty()) writeIfd(exif)
    out.write(data.toByteArray())
    return out.toByteArray()
  }

  private class Entry(val tag: Int, val type: Int, val count: Int, val value: ByteArray)

  companion object {
    private const val TAG_ORIENTATION = 0x0112
    private const val TAG_EXIF_IFD = 0x8769
    private const val TAG_EXIF_VERSION = 0x9000
    private const val TAG_DATETIME_ORIGINAL = 0x9003
    private const val TAG_OFFSET_TIME_ORIGINAL = 0x9011
    private const val TAG_SUBSEC_TIME_ORIGINAL = 0x9291
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_UNDEFINED = 7

    private val DATE = Regex("^\\d{4}:\\d{2}:\\d{2} \\d{2}:\\d{2}:\\d{2}$")
    private val OFFSET = Regex("^[+-]\\d{2}:\\d{2}$")
    private val SUBSEC = Regex("^\\d{1,9}$")

    /**
     * I valori da tenere di [file], letti con `ExifInterface` e ricontrollati. Null se non c'e' niente da tenere o il file non ha un EXIF che si
     * legga (allora la copia esce senza EXIF: la posizione non c'e', al massimo manca l'orientamento).
     */
    fun of(file: File): MinimalExif? {
      val exif = try {
        ExifInterface(file)
      } catch (_: IOException) {
        return null
      } catch (_: RuntimeException) {
        return null
      }
      val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED).takeIf { it in 2..8 }
      val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.trim()?.takeIf { DATE.matches(it) }
      val offset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.trim()?.takeIf { date != null && OFFSET.matches(it) }
      val subSec = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)?.trim()?.takeIf { date != null && SUBSEC.matches(it) }
      return MinimalExif(orientation, date, offset, subSec).takeIf { !it.isEmpty }
    }

    private fun ascii(tag: Int, text: String): Entry {
      val bytes = text.toByteArray(Charsets.US_ASCII) + 0.toByte()
      return Entry(tag, TYPE_ASCII, bytes.size, bytes)
    }

    private fun ifdSize(entries: List<Entry>): Int = 2 + 12 * entries.size + 4

    private fun u16(v: Int): ByteArray = byteArrayOf((v shr 8).toByte(), v.toByte())

    private fun u32(v: Long): ByteArray = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
  }
}
