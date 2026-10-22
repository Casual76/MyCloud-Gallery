package dev.pampa.mycloudgallery.core.ai.cutout

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs

/**
 * Un codificatore PNG che scrive **solo** i tre blocchi obbligatori: `IHDR`, `IDAT` e `IEND` (RGBA a 8 bit, senza interlacciamento).
 * Niente `tEXt`, `eXIf`, `iCCP`, `gAMA`, `pHYs`, `tIME`: un ritaglio fatto per essere condiviso non porta posizione, data, modello del
 * telefono ne' programma che l'ha scritto (docs/fase6-progetto.md D47, "nessuna posizione ne' altro metadato"). Il codificatore di
 * sistema (`Bitmap.compress`) sceglie da solo quali blocchi aggiungere; qui il contenuto del file lo decidiamo noi, e un test lo rilegge.
 *
 * Riga per riga: dalla fonte arriva una riga RGBA alla volta (mai l'immagine intera in un array di byte), il filtro e' scelto per riga
 * fra nessuno, Sub e Up (il piu' piccolo per somma dei valori assoluti), la compressione e' Deflate. Puro: gira sulla JVM.
 */
internal object PngEncoder {

  /** Una fonte di righe RGBA (straight alpha). [row] riempie [into] con `width * 4` byte della riga [y]. */
  fun interface Rows {
    fun row(y: Int, into: ByteArray)
  }

  fun encode(width: Int, height: Int, rows: Rows, level: Int = Deflater.DEFAULT_COMPRESSION): ByteArray {
    val out = ByteArrayOutputStream(max(1 shl 16, minOf(width.toLong() * height, 8L shl 20).toInt()))
    encode(width, height, rows, out, level)
    return out.toByteArray()
  }

  fun encode(width: Int, height: Int, rows: Rows, out: OutputStream, level: Int = Deflater.DEFAULT_COMPRESSION) {
    require(width > 0 && height > 0) { "Immagine vuota." }
    out.write(SIGNATURE)
    val header = ByteArray(13)
    putInt(header, 0, width)
    putInt(header, 4, height)
    header[8] = 8 // profondita'
    header[9] = 6 // RGBA
    header[10] = 0 // compressione
    header[11] = 0 // filtro
    header[12] = 0 // senza interlacciamento
    chunk(out, "IHDR", header, header.size)

    val stride = width * 4
    val current = ByteArray(stride)
    val previous = ByteArray(stride)
    val none = ByteArray(1 + stride)
    val sub = ByteArray(1 + stride)
    val up = ByteArray(1 + stride)
    sub[0] = 1
    up[0] = 2
    val deflater = Deflater(level)
    val buffer = ByteArray(1 shl 16)
    val idat = ByteArrayOutputStream(1 shl 16)
    fun drain(finish: Boolean) {
      while (true) {
        val n = deflater.deflate(buffer)
        if (n > 0) idat.write(buffer, 0, n)
        if (n == 0 && (!finish || deflater.finished())) break
      }
      // Un blocco IDAT per ogni ~64 KiB di dati compressi: niente buffer da centinaia di MB.
      if (idat.size() >= IDAT_FLUSH || finish) {
        if (idat.size() > 0) {
          val bytes = idat.toByteArray()
          chunk(out, "IDAT", bytes, bytes.size)
          idat.reset()
        }
      }
    }
    try {
      for (y in 0 until height) {
        rows.row(y, current)
        var sumNone = 0L
        var sumSub = 0L
        var sumUp = 0L
        none[0] = 0
        for (i in 0 until stride) {
          val raw = current[i]
          val left = if (i >= 4) current[i - 4] else 0
          val above = previous[i]
          val s = (raw - left).toByte()
          val u = (raw - above).toByte()
          none[1 + i] = raw
          sub[1 + i] = s
          up[1 + i] = u
          sumNone += abs(raw.toInt())
          sumSub += abs(s.toInt())
          sumUp += abs(u.toInt())
        }
        val chosen = when {
          sumSub <= sumNone && sumSub <= sumUp -> sub
          sumUp <= sumNone -> up
          else -> none
        }
        deflater.setInput(chosen, 0, chosen.size)
        while (!deflater.needsInput()) drain(finish = false)
        System.arraycopy(current, 0, previous, 0, stride)
      }
      deflater.finish()
      drain(finish = true)
    } finally {
      deflater.end()
    }
    chunk(out, "IEND", ByteArray(0), 0)
  }

  private fun chunk(out: OutputStream, type: String, data: ByteArray, length: Int) {
    val head = ByteArray(8)
    putInt(head, 0, length)
    for (i in 0 until 4) head[4 + i] = type[i].code.toByte()
    out.write(head)
    out.write(data, 0, length)
    val crc = CRC32()
    crc.update(head, 4, 4)
    crc.update(data, 0, length)
    val tail = ByteArray(4)
    putInt(tail, 0, crc.value.toInt())
    out.write(tail)
  }

  private fun putInt(target: ByteArray, at: Int, value: Int) {
    target[at] = (value ushr 24).toByte()
    target[at + 1] = (value ushr 16).toByte()
    target[at + 2] = (value ushr 8).toByte()
    target[at + 3] = value.toByte()
  }

  private fun max(a: Int, b: Int) = if (a > b) a else b

  private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
  private const val IDAT_FLUSH = 1 shl 16
}
